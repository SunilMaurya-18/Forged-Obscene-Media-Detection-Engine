"""Shared pytest defaults for the ml-service suite.

``startup_guard`` defaults ``APP_PROFILE`` to ``prod`` (compose posture). Unit tests that
start the FastAPI lifespan via ``TestClient`` must use a relaxed profile so they do not
require real secrets. Tests that assert prod refusal override via ``monkeypatch``.
"""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def _relaxed_app_profile(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_PROFILE", "test")
