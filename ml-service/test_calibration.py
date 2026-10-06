"""Phase 22: optional temperature-scaling calibration at inference time."""

from __future__ import annotations

import io
import json
import math
import os
from pathlib import Path

import pytest
from PIL import Image

import inference as inf

CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT")
    or Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"
)


def _jpeg(color=(120, 90, 60)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (224, 224), color).save(buf, format="JPEG")
    return buf.getvalue()


def test_load_temperature_validates(tmp_path):
    missing = tmp_path / "none.json"
    assert inf.load_temperature(missing) is None

    good = tmp_path / "good.json"
    good.write_text(json.dumps({"temperature": 1.7}), encoding="utf-8")
    assert inf.load_temperature(good) == pytest.approx(1.7)

    for bad in ({"temperature": 0.0}, {"temperature": 1e6}, {"temperature": "x"}, {}):
        path = tmp_path / "bad.json"
        path.write_text(json.dumps(bad), encoding="utf-8")
        assert inf.load_temperature(path) is None

    garbage = tmp_path / "garbage.json"
    garbage.write_text("{not json", encoding="utf-8")
    assert inf.load_temperature(garbage) is None


def _raw_logit(raw: bytes) -> float:
    import torch

    from forgery_runtime import bytes_to_rgb, rgb_to_tensor

    rt = inf.get_runtime()
    with torch.no_grad():
        logits, _ = rt.forgery_model.forward_all(rgb_to_tensor(bytes_to_rgb(raw), rt.device))
    return float(logits.item())


@pytest.mark.skipif(not CHECKPOINT.exists(), reason=f"Missing checkpoint: {CHECKPOINT}")
def test_temperature_rescales_logit(tmp_path):
    raw = _jpeg()
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu", calibration=tmp_path / "absent.json", ensemble="off")
    assert inf.get_runtime().temperature is None
    z = _raw_logit(raw)
    assert inf.run_forgery_assessment(raw)["forgery_score"] == pytest.approx(1 / (1 + math.exp(-z)), abs=1e-6)

    calib = tmp_path / "calibration.json"
    calib.write_text(json.dumps({"temperature": 8.0}), encoding="utf-8")
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu", calibration=calib, ensemble="off")
    assert inf.get_runtime().temperature == pytest.approx(8.0)
    scaled = inf.run_forgery_assessment(raw)["forgery_score"]

    assert scaled == pytest.approx(1 / (1 + math.exp(-z / 8.0)), abs=1e-6)
    inf._runtime = None
