"""Phase 17: generation_family (gan / diffusion / unknown) in /infer."""

from __future__ import annotations

import io
import os

import numpy as np
import pytest
import torch
from PIL import Image

from forgery_runtime import ForgeryModel, load_forgery_model
from inference import classify_family


def test_family_is_unknown_when_not_forged_or_ambiguous():
    assert classify_family(0.2, 0.99) == ("unknown", None)
    assert classify_family(0.9, None) == ("unknown", None)
    family, confidence = classify_family(0.9, 0.55)
    assert family == "unknown" and confidence == pytest.approx(0.55)


def test_family_attribution_above_threshold():
    assert classify_family(0.9, 0.95)[0] == "diffusion"
    family, confidence = classify_family(0.9, 0.1)
    assert family == "gan" and confidence == pytest.approx(0.9)


def test_legacy_checkpoint_without_family_head_still_loads(tmp_path):
    path = tmp_path / "legacy.pt"
    torch.save({"model_state_dict": ForgeryModel(family_head=False).state_dict()}, path)
    model = load_forgery_model(path, torch.device("cpu"))
    assert model.family_head is None
    forgery, family = model.forward_all(torch.zeros(1, 3, 224, 224))
    assert forgery.shape == (1, 1) and family is None


def _gan_era_crop() -> bytes:
    """Same recipe as the synthetic GAN-era fakes in ml/data/make_synthetic_crops.py."""
    rng = np.random.default_rng(11)
    blocks = rng.integers(0, 255, size=(28, 28, 3), dtype=np.uint8)
    img = np.kron(blocks, np.ones((8, 8, 1), dtype=np.uint8)).astype(np.float32)
    img = np.clip(img + rng.normal(0, 12, img.shape), 0, 255).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(img).save(buf, format="JPEG", quality=95)
    return buf.getvalue()


def test_infer_reports_generation_family():
    from test_infer import CHECKPOINT

    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["NSFW_BACKEND"] = "heuristic"
    import inference as inf
    import main as main_mod
    from fastapi.testclient import TestClient

    inf._runtime = None
    with TestClient(main_mod.app) as client:
        body = client.post("/infer", files={"file": ("f.jpg", _gan_era_crop(), "image/jpeg")}).json()
        assert body["generation_family"] in {"gan", "diffusion", "unknown"}
        if inf.get_runtime().forgery_model.family_head is not None:
            assert body["forgery_score"] >= 0.5
            assert body["generation_family"] == "gan"
            assert body["family_confidence"] > 0.5
