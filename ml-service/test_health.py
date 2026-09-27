"""Phase 0: /health returns ok (runtime may be uninitialized in unit isolation)."""

from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

def _checkpoint_path() -> Path:
    env = os.getenv("FORGERY_CHECKPOINT")
    if env:
        return Path(env)
    return Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"


CHECKPOINT = _checkpoint_path()


@pytest.fixture
def client():
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["FORGERY_CHECKPOINT"] = str(CHECKPOINT)
    os.environ["NSFW_BACKEND"] = "heuristic"
    import main

    with TestClient(main.app) as c:
        yield c


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
