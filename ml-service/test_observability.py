"""Phase 24: request IDs, JSON logs, /metrics and the production-secrets startup guard."""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import observability
import startup_guard

CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT")
    or Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"
)


@pytest.fixture
def client():
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["FORGERY_CHECKPOINT"] = str(CHECKPOINT)
    os.environ["NSFW_BACKEND"] = "heuristic"
    import main

    with TestClient(main.app) as c:
        yield c


def test_request_id_is_echoed_or_generated(client):
    echoed = client.get("/health", headers={"X-Request-ID": "backend-req-42"})
    assert echoed.headers["X-Request-ID"] == "backend-req-42"

    generated = client.get("/health")
    assert len(generated.headers["X-Request-ID"]) == 32

    # Header text that could inject into logs is replaced, never echoed
    hostile = client.get("/health", headers={"X-Request-ID": "x\" injected=1 " + "a" * 80})
    assert hostile.headers["X-Request-ID"] != "x\" injected=1 " + "a" * 80
    assert len(hostile.headers["X-Request-ID"]) == 32


def test_metrics_expose_latency_and_errors(client):
    client.get("/health")
    client.post("/infer", files={"file": ("empty.jpg", b"", "image/jpeg")})  # 400, not an error
    body = client.get("/metrics").text
    assert 'fmd_ml_requests_total{endpoint="/health",method="GET",status="200"}' in body
    assert 'fmd_ml_requests_total{endpoint="/infer",method="POST",status="400"}' in body
    assert "fmd_ml_request_duration_seconds_bucket" in body
    assert "fmd_ml_errors_total" in body
    assert "fmd_ml_models_loaded 1.0" in body
    # Route templates only, so label cardinality stays bounded
    client.get("/does-not-exist")
    assert 'endpoint="unmatched"' in client.get("/metrics").text


def test_undecodable_upload_is_a_client_error_not_a_5xx(client):
    res = client.post("/infer", files={"file": ("x.png", b"<svg xmlns='http://www.w3.org/2000/svg'/>", "image/png")})
    assert res.status_code == 400
    assert 'fmd_ml_errors_total{endpoint="/infer"}' not in client.get("/metrics").text


def test_json_formatter_carries_request_id():
    token = observability.request_id_var.set("abc123")
    try:
        record = logging.LogRecord("test", logging.INFO, __file__, 1, "scored %s", ("clip",), None)
        line = json.loads(observability.JsonFormatter().format(record))
    finally:
        observability.request_id_var.reset(token)
    assert line["message"] == "scored clip"
    assert line["request_id"] == "abc123"
    assert line["log.level"] == "INFO"
    assert line["service.name"] == "ml-service"
    assert "@timestamp" in line


@pytest.mark.parametrize("profile", ["test", "dev"])
def test_guard_allows_demo_key_in_relaxed_profiles(monkeypatch, profile):
    monkeypatch.setenv("APP_PROFILE", profile)
    monkeypatch.setenv("PROVENANCE_SIGNING_KEY", "dev-only-provenance-signing-key")
    startup_guard.enforce()


@pytest.mark.parametrize("key", [None, "dev-only-provenance-signing-key", "short-secret"])
def test_guard_refuses_weak_key_outside_relaxed(monkeypatch, key):
    monkeypatch.setenv("APP_PROFILE", "prod")
    if key is None:
        monkeypatch.delenv("PROVENANCE_SIGNING_KEY", raising=False)
    else:
        monkeypatch.setenv("PROVENANCE_SIGNING_KEY", key)
    with pytest.raises(RuntimeError, match="Refusing to start with APP_PROFILE=prod"):
        startup_guard.enforce()


def test_guard_accepts_real_key_outside_dev(monkeypatch):
    monkeypatch.setenv("APP_PROFILE", "prod")
    monkeypatch.setenv("PROVENANCE_SIGNING_KEY", "k" * 48)
    startup_guard.enforce()
