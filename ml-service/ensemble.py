"""Phase 29: a second, independent forgery expert and the stacker that combines it with the CNN.

The spectral expert looks only at the frequency spectrum and the noise residual of the image (no
learned convolutional features), so it fails differently from the EfficientNet. It is a logistic
regression over hand-built features, stored with the stacker as plain JSON in ``ensemble.json``
(no pickle, no extra dependencies). ``ml/train_ensemble.py`` fits it with this same module, so
training and serving compute identical features.

The stacker is tied to the CNN checkpoint it was fitted against (its logit scale): a file whose
``checkpoint_sha256`` doesn't match the loaded checkpoint is refused and the CNN serves alone.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

FORMAT = "fmd-forgery-ensemble/v1"
FEATURE_VERSION = 1
SIZE = 256
N_BINS = 32
N_RESIDUAL = 5
N_FEATURES = N_BINS + N_RESIDUAL


def _radial_bins() -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    yy, xx = np.indices((SIZE, SIZE))
    r = np.hypot(yy - SIZE / 2, xx - SIZE / 2) / (SIZE / 2)
    inside = r < 1.0
    idx = np.minimum((r * N_BINS).astype(np.int64), N_BINS - 1)
    counts = np.bincount(idx[inside], minlength=N_BINS).astype(np.float64)
    return idx, inside, counts


_BIN_IDX, _INSIDE, _BIN_COUNTS = _radial_bins()


def _laplacian(x: np.ndarray) -> np.ndarray:
    return 4.0 * x[1:-1, 1:-1] - x[:-2, 1:-1] - x[2:, 1:-1] - x[1:-1, :-2] - x[1:-1, 2:]


def spectral_features(image: Image.Image | np.ndarray) -> np.ndarray:
    """37 numbers: the shape of the radially averaged log spectrum (32 bins, mean removed, so overall
    brightness and contrast drop out) and five noise-residual statistics (luma residual spread, mean
    magnitude and log kurtosis; chroma residual spread for Cb and Cr)."""
    pil = image if isinstance(image, Image.Image) else Image.fromarray(np.asarray(image, dtype=np.uint8))
    ycc = np.asarray(pil.convert("RGB").resize((SIZE, SIZE), Image.BICUBIC).convert("YCbCr"), dtype=np.float64)
    y, cb, cr = ycc[..., 0], ycc[..., 1], ycc[..., 2]

    power = np.log1p(np.abs(np.fft.fftshift(np.fft.fft2(y - y.mean()))))
    profile = np.bincount(_BIN_IDX[_INSIDE], weights=power[_INSIDE], minlength=N_BINS) / _BIN_COUNTS
    profile -= profile.mean()

    res = _laplacian(y)
    m2 = float(np.mean(res**2))
    m4 = float(np.mean(res**4))
    residual = [
        math.log1p(float(res.std())),
        math.log1p(float(np.abs(res).mean())),
        math.log1p(m4 / (m2 * m2 + 1e-6)),
        math.log1p(float(_laplacian(cb).std())),
        math.log1p(float(_laplacian(cr).std())),
    ]
    return np.concatenate([profile, np.asarray(residual)])


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def canonical_sha256(config: dict) -> str:
    """Fingerprint of an ensemble independent of whitespace and key order (and of git's line endings)."""
    return hashlib.sha256(json.dumps(config, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


@dataclass(frozen=True)
class Ensemble:
    mean: np.ndarray
    std: np.ndarray
    coef: np.ndarray
    intercept: float
    w_cnn: float
    w_spectral: float
    bias: float
    logit_clip: float
    fingerprint: str

    def spectral_logit(self, image: Image.Image | np.ndarray) -> float:
        x = (spectral_features(image) - self.mean) / self.std
        return float(x @ self.coef + self.intercept)

    def combine(self, cnn_logit: float, spectral_logit: float) -> float:
        c = self.logit_clip
        return (
            self.w_cnn * float(np.clip(cnn_logit, -c, c))
            + self.w_spectral * float(np.clip(spectral_logit, -c, c))
            + self.bias
        )

    def describe(self) -> dict:
        return {
            "active": True,
            "experts": ["cnn", "spectral"],
            "weights": {"cnn": round(self.w_cnn, 4), "spectral": round(self.w_spectral, 4)},
            "fingerprint": self.fingerprint[:16],
        }


def from_config(config: dict, checkpoint_sha256: str | None = None) -> Ensemble:
    """Validate a parsed ``ensemble.json``; raises ValueError on anything that doesn't fit."""
    if config.get("format") != FORMAT or config.get("feature_version") != FEATURE_VERSION:
        raise ValueError(f"unsupported ensemble format {config.get('format')!r} v{config.get('feature_version')!r}")
    expected = config.get("checkpoint_sha256")
    if checkpoint_sha256 is not None and expected != checkpoint_sha256:
        raise ValueError("fitted against a different checkpoint")
    spectral, stacker = config["spectral"], config["stacker"]
    arrays = [np.asarray(spectral[k], dtype=np.float64) for k in ("mean", "std", "coef")]
    if any(a.shape != (N_FEATURES,) for a in arrays):
        raise ValueError(f"spectral expert needs {N_FEATURES} features")
    scalars = [float(spectral["intercept"]), float(stacker["cnn"]), float(stacker["spectral"]),
               float(stacker["bias"]), float(stacker["logit_clip"])]
    if not all(np.isfinite(a).all() for a in arrays) or not all(math.isfinite(s) for s in scalars):
        raise ValueError("non-finite ensemble parameters")
    if scalars[4] <= 0:
        raise ValueError("logit_clip must be positive")
    mean, std, coef = arrays
    return Ensemble(mean, np.maximum(std, 1e-6), coef, *scalars, fingerprint=canonical_sha256(config))


def load(path: Path, checkpoint_sha256: str | None = None) -> Ensemble | None:
    """The ensemble at ``path``, or None (CNN alone) when it is absent, unreadable or doesn't fit."""
    path = Path(path)
    if not path.exists():
        return None
    try:
        return from_config(json.loads(path.read_text(encoding="utf-8")), checkpoint_sha256)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        logger.warning("Ignoring forgery ensemble %s: %s", path, exc)
        return None
