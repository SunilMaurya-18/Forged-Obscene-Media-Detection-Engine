"""Phase 29: spectral expert features, ensemble.json validation, and the stacked score at inference."""

from __future__ import annotations

import io
import json
import math
import os
from pathlib import Path

import numpy as np
import pytest
import torch
from PIL import Image

import ensemble as ens
import inference as inf

CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT")
    or Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"
)


def _config(checkpoint_sha256: str | None = None, **stacker) -> dict:
    rng = np.random.default_rng(0)
    return {
        "format": ens.FORMAT,
        "feature_version": ens.FEATURE_VERSION,
        "checkpoint_sha256": checkpoint_sha256,
        "spectral": {
            "mean": rng.normal(0, 1, ens.N_FEATURES).tolist(),
            "std": np.abs(rng.normal(1, 0.1, ens.N_FEATURES)).tolist(),
            "coef": rng.normal(0, 0.3, ens.N_FEATURES).tolist(),
            "intercept": 0.1,
        },
        "stacker": {"cnn": 0.6, "spectral": 0.4, "bias": -0.2, "logit_clip": 15.0, **stacker},
    }


def _image(seed: int = 1, size: int = 200) -> Image.Image:
    rng = np.random.default_rng(seed)
    return Image.fromarray(rng.integers(0, 255, (size, size, 3), dtype=np.uint8))


def _jpeg(color=(120, 90, 60)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (224, 224), color).save(buf, format="JPEG")
    return buf.getvalue()


def test_features_are_finite_deterministic_and_size_independent():
    img = _image()
    f = ens.spectral_features(img)
    assert f.shape == (ens.N_FEATURES,) and np.isfinite(f).all()
    assert np.array_equal(f, ens.spectral_features(np.asarray(img)))
    flat = ens.spectral_features(Image.new("RGB", (64, 48), (90, 90, 90)))
    assert np.isfinite(flat).all()
    assert abs(float(f[: ens.N_BINS].sum())) < 1e-9  # spectrum shape only: mean removed


def test_features_separate_smooth_from_blocky_texture():
    smooth = np.tile(np.linspace(40, 200, 224, dtype=np.uint8)[None, :, None], (224, 1, 3))
    blocky = np.kron(np.random.default_rng(2).integers(0, 255, (28, 28, 3)), np.ones((8, 8, 1))).astype(np.uint8)
    # Residual spread (log std of the Laplacian) is far higher for the high-frequency texture
    assert ens.spectral_features(blocky)[ens.N_BINS] > ens.spectral_features(smooth)[ens.N_BINS] + 1.0


def test_from_config_validates():
    good = ens.from_config(_config())
    assert good.describe()["weights"] == {"cnn": 0.6, "spectral": 0.4}
    bad_cases = [
        {**_config(), "format": "other"},
        {**_config(), "feature_version": 99},
        {**_config(), "spectral": {**_config()["spectral"], "coef": [0.0] * 3}},
        {**_config(), "stacker": {**_config()["stacker"], "cnn": float("nan")}},
        {**_config(), "stacker": {**_config()["stacker"], "logit_clip": 0}},
    ]
    for cfg in bad_cases:
        with pytest.raises((ValueError, KeyError)):
            ens.from_config(cfg)
    with pytest.raises(ValueError, match="different checkpoint"):
        ens.from_config(_config("aaa"), checkpoint_sha256="bbb")


def test_load_returns_none_for_missing_garbage_or_mismatch(tmp_path):
    assert ens.load(tmp_path / "absent.json") is None
    garbage = tmp_path / "garbage.json"
    garbage.write_text("{nope", encoding="utf-8")
    assert ens.load(garbage) is None
    mismatched = tmp_path / "e.json"
    mismatched.write_text(json.dumps(_config("aaa")), encoding="utf-8")
    assert ens.load(mismatched, checkpoint_sha256="bbb") is None
    assert ens.load(mismatched, checkpoint_sha256="aaa") is not None


def test_combine_clips_each_expert():
    e = ens.from_config(_config(logit_clip=5.0))
    assert e.combine(100.0, -100.0) == pytest.approx(0.6 * 5.0 + 0.4 * -5.0 - 0.2)
    assert e.combine(1.0, 2.0) == pytest.approx(0.6 + 0.8 - 0.2)


def test_canonical_hash_ignores_formatting():
    cfg = _config()
    assert ens.canonical_sha256(cfg) == ens.canonical_sha256(json.loads(json.dumps(cfg, indent=4)))
    assert ens.canonical_sha256(cfg) != ens.canonical_sha256(_config(bias=0.3))


@pytest.mark.skipif(not CHECKPOINT.exists(), reason=f"Missing checkpoint: {CHECKPOINT}")
def test_runtime_serves_the_stacked_score(tmp_path):
    raw = _jpeg()
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu", calibration=tmp_path / "none.json", ensemble="off")
    alone = inf.run_forgery_assessment(raw)
    assert set(alone["experts"]) == {"cnn"}
    assert inf.get_runtime().ensemble_info()["active"] is False

    path = tmp_path / "ensemble.json"
    path.write_text(json.dumps(_config(ens.file_sha256(CHECKPOINT))), encoding="utf-8")
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu", calibration=tmp_path / "none.json", ensemble=path)
    rt = inf.get_runtime()
    assert rt.ensemble_info()["active"] is True
    stacked = inf.run_forgery_assessment(raw)
    with torch.no_grad():
        z_cnn = float(rt.forgery_model(inf.rgb_to_tensor(inf.bytes_to_rgb(raw), rt.device)).item())
    z_spec = rt.ensemble.spectral_logit(inf.bytes_to_rgb(raw))
    assert stacked["experts"]["spectral"] == pytest.approx(1 / (1 + math.exp(-z_spec)), abs=1e-9)
    expected = 1 / (1 + math.exp(-rt.ensemble.combine(z_cnn, z_spec)))
    assert stacked["forgery_score"] == pytest.approx(expected, abs=1e-6)

    # Video frames get the same score as a single image
    assert inf.run_forgery_assessments([raw, raw])[1]["forgery_score"] == pytest.approx(stacked["forgery_score"], abs=1e-6)

    # An ensemble fitted against another checkpoint is refused; the CNN serves alone
    path.write_text(json.dumps(_config("0" * 64)), encoding="utf-8")
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu", calibration=tmp_path / "none.json", ensemble=path)
    assert inf.get_runtime().ensemble is None
    inf._runtime = None
