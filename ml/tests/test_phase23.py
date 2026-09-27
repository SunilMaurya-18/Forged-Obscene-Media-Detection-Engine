"""Phase 2 + 3: train/eval checkpoint + Grad-CAM/FFT/ELA."""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

ML_ROOT = Path(__file__).resolve().parents[1]
DATA = ML_ROOT / "data"
sys.path.insert(0, str(ML_ROOT))
sys.path.insert(0, str(DATA))

from make_synthetic_crops import make_synthetic_workspace  # noqa: E402
from evaluate import run_evaluation  # noqa: E402
from explain import explain_image, error_level_analysis, fft_spectrum  # noqa: E402
from train import run_training  # noqa: E402


@pytest.fixture(scope="module")
def trained_workspace(tmp_path_factory):
    root = tmp_path_factory.mktemp("phase23")
    info = make_synthetic_workspace(root, n_per_class=16, seed=7)
    ckpt = root / "checkpoint.pt"
    run_training(
        info["crops"],
        info["splits"],
        ckpt,
        epochs=2,
        batch_size=8,
        lr=1e-3,
        pretrained=False,
        device="cpu",
    )
    assert ckpt.exists()
    return {"root": root, "crops": info["crops"], "splits": info["splits"], "ckpt": ckpt}


def test_train_produces_checkpoint(trained_workspace):
    assert trained_workspace["ckpt"].stat().st_size > 0


def test_evaluate_writes_metrics(trained_workspace, tmp_path):
    results_path = tmp_path / "metrics.json"
    results = run_evaluation(
        trained_workspace["ckpt"],
        trained_workspace["crops"],
        trained_workspace["splits"],
        results_path,
        batch_size=8,
        device="cpu",
    )
    assert results_path.exists()
    data = json.loads(results_path.read_text(encoding="utf-8"))
    assert "faceforensics++" in data["datasets"]
    assert "dfdc" in data["datasets"]
    assert "celeb-df" in data["datasets"]
    for name, m in data["datasets"].items():
        for key in ("accuracy", "precision", "recall", "f1", "auc"):
            assert key in m, f"{name} missing {key}"
    assert "generalization_gap" in data
    assert results["datasets"]


def test_fft_and_ela_shapes():
    img = np.zeros((224, 224, 3), dtype=np.uint8)
    img[:, :112] = (30, 90, 180)
    img[:, 112:] = (200, 40, 40)
    fft = fft_spectrum(img)
    ela = error_level_analysis(img)
    assert fft.shape == (224, 224, 3)
    assert ela.shape == (224, 224, 3)


def test_explain_returns_base64_pngs(trained_workspace, tmp_path):
    sample = next(trained_workspace["crops"].rglob("*.jpg"))
    out = explain_image(sample, trained_workspace["ckpt"], device="cpu")
    assert set(out) == {"heatmap", "fft", "ela"}
    for key, b64 in out.items():
        raw = base64.b64decode(b64)
        assert raw[:8] == b"\x89PNG\r\n\x1a\n", f"{key} is not a PNG"
        arr = np.frombuffer(raw, dtype=np.uint8)
        decoded = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        assert decoded is not None and decoded.size > 0
