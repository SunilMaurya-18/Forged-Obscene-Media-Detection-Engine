"""Refuse to start outside the test profile while insecure provenance secrets are configured.

``APP_PROFILE`` (default ``prod``) mirrors the backend Spring profile; docker compose sets both
from one variable.
"""

from __future__ import annotations

import logging
import os

import content_credentials
import provenance

logger = logging.getLogger(__name__)

# Local compose uses APP_PROFILE=dev; only prod (and similar) enforce strong secrets.
RELAXED_PROFILES = frozenset({"test", "dev"})
TEST_PROFILES = RELAXED_PROFILES  # backwards-compatible alias
MIN_KEY_LENGTH = 32


def profile() -> str:
    return (os.getenv("APP_PROFILE") or "prod").strip().lower()


def insecure_settings() -> list[str]:
    problems = []
    key = os.getenv("PROVENANCE_SIGNING_KEY", "")
    if not key or key == provenance.DEFAULT_SIGNING_KEY or len(key) < MIN_KEY_LENGTH:
        problems.append(
            f"PROVENANCE_SIGNING_KEY is unset, the published default or shorter than {MIN_KEY_LENGTH} characters"
        )
    return problems


def enforce() -> None:
    """Raises ``RuntimeError`` (aborting startup) when a non-dev/test profile runs with weak secrets."""
    current = profile()
    if current in RELAXED_PROFILES:
        return
    problems = insecure_settings()
    if problems:
        raise RuntimeError(
            f"Refusing to start with APP_PROFILE={current}: " + "; ".join(problems)
            + ". Set real secrets (see .env.production.example), or APP_PROFILE=dev for local compose."
        )
    if content_credentials.write_enabled() and not os.getenv("C2PA_SIGNING_CERT"):
        logger.warning(
            "C2PA manifests are signed with a generated development certificate (validators report "
            "VALID_UNTRUSTED). Set C2PA_SIGNING_CERT / C2PA_SIGNING_KEY to a trust-list certificate."
        )
