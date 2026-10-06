"""Phase 29: score images the way ml-service does (CNN alone, or CNN + spectral expert ensemble).

Temperature scaling is left out on purpose: it never changes a decision at the 0.5 threshold or a
ranking, so robustness and AUC figures are the same with or without it.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
ML_SERVICE = ROOT.parent / "ml-service"
if str(ML_SERVICE) not in sys.path:
    sys.path.append(str(ML_SERVICE))

import ensemble as ensemble_lib  # noqa: E402  (ml-service/ensemble.py: one feature implementation)
from dataset import default_eval_transform  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402


def sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(z, -60.0, 60.0)))


class ServedScorer:
    def __init__(self, model, device: torch.device, checkpoint_sha256: str, ens: ensemble_lib.Ensemble | None):
        self.model = model
        self.device = device
        self.checkpoint_sha256 = checkpoint_sha256
        self.ensemble = ens
        self._transform = default_eval_transform()

    @classmethod
    def load(cls, checkpoint: Path, *, ensemble: Path | None | bool = True, device: str | None = None) -> "ServedScorer":
        """``ensemble``: True uses ``ensemble.json`` beside the checkpoint if it fits, False/None the CNN
        alone, a path that file (which must load, or this raises)."""
        device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
        model, _ = load_checkpoint(checkpoint, device=device_t)
        sha = ensemble_lib.file_sha256(checkpoint)
        ens = None
        if ensemble is True:
            ens = ensemble_lib.load(Path(checkpoint).parent / "ensemble.json", sha)
        elif ensemble:
            ens = ensemble_lib.load(Path(ensemble), sha)
            if ens is None:
                raise ValueError(f"Ensemble {ensemble} is missing or was not fitted against {checkpoint}")
        return cls(model, device_t, sha, ens)

    @torch.no_grad()
    def cnn_logits(self, images: list[Image.Image]) -> np.ndarray:
        if not images:
            return np.zeros(0)
        batch = torch.stack([self._transform(img.convert("RGB")) for img in images]).to(self.device)
        return self.model(batch).view(-1).cpu().numpy().astype(np.float64)

    def score(self, images: list[Image.Image]) -> dict[str, np.ndarray | None]:
        """Per-expert probabilities and the served one (``served``)."""
        z_cnn = self.cnn_logits(images)
        if self.ensemble is None:
            p = sigmoid(z_cnn)
            return {"cnn": p, "spectral": None, "served": p}
        z_spec = np.asarray([self.ensemble.spectral_logit(img) for img in images], dtype=np.float64)
        z = np.asarray([self.ensemble.combine(a, b) for a, b in zip(z_cnn, z_spec, strict=True)])
        return {"cnn": sigmoid(z_cnn), "spectral": sigmoid(z_spec), "served": sigmoid(z)}

    def describe(self) -> dict:
        ens = self.ensemble
        return {
            "experts": ["cnn", "spectral"] if ens else ["cnn"],
            "weights": ens.describe()["weights"] if ens else None,
            "checkpoint_sha256": self.checkpoint_sha256,
            "ensemble_sha256": ens.fingerprint if ens else None,
        }
