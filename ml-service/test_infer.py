"""Phase 5: /infer wires forgery + explicit + heatmap + phash; models load once."""

from __future__ import annotations

import io
import os
from pathlib import Path

import pytest
from PIL import Image
from fastapi.testclient import TestClient

def _checkpoint_path() -> Path:
    env = os.getenv("FORGERY_CHECKPOINT")
    if env:
        return Path(env)
    return Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"


CHECKPOINT = _checkpoint_path()


def _jpeg_bytes(color=(80, 120, 160), size=224) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (size, size), color).save(buf, format="JPEG")
    return buf.getvalue()


@pytest.fixture(scope="module")
def client():
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["FORGERY_CHECKPOINT"] = str(CHECKPOINT)
    os.environ["NSFW_BACKEND"] = "heuristic"

    # Import after env is set
    import inference as inf
    import main as main_mod

    inf._runtime = None
    inf.init_runtime(checkpoint=CHECKPOINT, device="cpu")
    # Bypass FastAPI lifespan for TestClient by using already-inited runtime
    with TestClient(main_mod.app) as c:
        # lifespan will init again — ensure same checkpoint
        yield c


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["models_loaded"] is True
    assert body["forgery_loads"] == 1


def test_infer_json_shape(client):
    files = {"file": ("sample.jpg", _jpeg_bytes(), "image/jpeg")}
    r = client.post("/infer", files=files)
    assert r.status_code == 200, r.text
    data = r.json()
    assert set(data) >= {"forgery_score", "explicit_score", "heatmap", "phash"}
    assert isinstance(data["forgery_score"], float)
    assert isinstance(data["explicit_score"], float)
    assert 0.0 <= data["forgery_score"] <= 1.0
    assert 0.0 <= data["explicit_score"] <= 1.0
    assert isinstance(data["heatmap"], str) and len(data["heatmap"]) > 100
    assert isinstance(data["phash"], str) and len(data["phash"]) >= 8


def test_models_not_reloaded_per_request(client):
    import inference as inf

    before = inf.get_runtime().load_count
    for _ in range(3):
        files = {"file": ("s.jpg", _jpeg_bytes((30, 40, 50)), "image/jpeg")}
        assert client.post("/infer", files=files).status_code == 200
    assert inf.get_runtime().load_count == before == 1


def test_phash_stable():
    from hashing import compute_phash

    raw = _jpeg_bytes()
    assert compute_phash(raw) == compute_phash(raw)
