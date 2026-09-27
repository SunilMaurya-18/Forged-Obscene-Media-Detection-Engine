"""Phase 15: coarse forensic signatures (stored) and full-resolution panels (report time only)."""

from __future__ import annotations

import base64
import io
import json
import os
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

import forensics
import provenance


def _gradient(size=224) -> np.ndarray:
    x = np.linspace(0, 255, size, dtype=np.float32)
    return np.stack([np.tile(x, (size, 1)), np.tile(x[:, None], (1, size)), np.full((size, size), 90.0)], axis=2).astype(np.uint8)


def _jpeg(rgb: np.ndarray, quality=90) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def _png(rgb: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, format="PNG")
    return buf.getvalue()


def _flat_cam(size=224) -> np.ndarray:
    cam = np.zeros((size, size), dtype=np.float32)
    cam[40:80, 150:190] = 1.0
    return cam


def test_signature_is_coarse_and_small():
    sig = forensics.signature(_jpeg(_gradient()), _flat_cam())
    assert len(sig["cam"]["grid"]) == 14 and len(sig["cam"]["grid"][0]) == 14
    assert len(sig["ela"]["grid"]) == 16 and len(sig["ela"]["grid"][0]) == 16
    assert len(sig["fft"]["grid"]) == 32 and len(sig["fft"]["profile"]) == 32
    encoded = json.dumps(sig)
    assert len(encoded) < 16000  # fits the backend column with room to spare
    assert all(np.isfinite(v) for row in sig["fft"]["grid"] for v in row)


def test_cam_peak_tracks_attention():
    peak = forensics.signature(_jpeg(_gradient()), _flat_cam())["cam"]["peak"]
    assert 0.6 < peak["x"] < 0.9 and 0.15 < peak["y"] < 0.4


def test_ela_grid_highlights_a_pasted_region():
    """A block pasted into a JPEG after compression shows a higher error level than the rest."""
    base = np.asarray(Image.open(io.BytesIO(_jpeg(_gradient(), quality=70))).convert("RGB")).copy()
    rng = np.random.default_rng(7)
    base[0:56, 0:56] = rng.integers(0, 256, (56, 56, 3), dtype=np.uint8)  # top-left quarter-ish, never JPEG'd
    grid = np.array(forensics.signature(_png(base), _flat_cam())["ela"]["grid"])
    pasted = grid[:4, :4].mean()
    rest = grid[8:, 8:].mean()
    assert pasted > 3 * rest


def test_fft_high_frequency_ratio_separates_noise_from_smooth():
    smooth = forensics.signature(_png(_gradient()), _flat_cam())["fft"]["high_freq_ratio"]
    noise = np.random.default_rng(1).integers(0, 256, (224, 224, 3), dtype=np.uint8)
    noisy = forensics.signature(_png(noise), _flat_cam())["fft"]["high_freq_ratio"]
    assert noisy > smooth


def test_render_panels_returns_pngs():
    panels = forensics.render_panels(_jpeg(_gradient()))
    for key in ("fft", "ela"):
        img = Image.open(io.BytesIO(base64.b64decode(panels[key])))
        assert img.format == "PNG" and img.size == (448, 448)


CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT", Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt")
)


@pytest.fixture(scope="module")
def api():
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["NSFW_BACKEND"] = "heuristic"
    import inference as inf
    import main

    if inf._runtime is None:
        inf.init_runtime(checkpoint=CHECKPOINT, device="cpu")
    return TestClient(main.app)


def test_infer_returns_forensic_signature(api):
    raw = _jpeg(_gradient())
    body = api.post("/infer", files={"file": ("g.jpg", raw, "image/jpeg")}).json()
    sig = body["forensics"]
    assert sig["version"] == 1
    assert len(sig["cam"]["grid"]) == 14
    assert 0.0 <= sig["fft"]["high_freq_ratio"] <= 1.0


def test_panels_endpoint_renders_all_three_and_hashes(api):
    raw = _jpeg(_gradient())
    r = api.post("/forensics/panels", files={"file": ("g.jpg", raw, "image/jpeg")})
    assert r.status_code == 200, r.text
    body = r.json()
    for key in ("heatmap", "fft", "ela"):
        assert Image.open(io.BytesIO(base64.b64decode(body[key]))).format == "PNG"
    assert body["content_hash"] == provenance.content_hash(raw)
    assert len(body["phash"]) == 16


def test_panels_endpoint_rejects_non_image(api):
    r = api.post("/forensics/panels", files={"file": ("x.jpg", b"not an image", "image/jpeg")})
    assert r.status_code == 422
