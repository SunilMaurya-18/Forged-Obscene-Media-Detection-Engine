"""Phase 24: refuse to start outside the dev profile while demo secrets are configured.

``APP_PROFILE`` (default ``dev``) mirrors the backend's Spring profile; docker compose sets both
from one variable. In any other profile the service will not boot with the published demo
provenance key, because anyone holding it could forge "verified" labels.
"""

from __future__ import annotations

import logging
import os

import content_credentials
import provenance

logger = logging.getLogger(__name__)

DEV_PROFILES = frozenset({"dev", "test"})
MIN_KEY_LENGTH = 32


def profile() -> str:
    return (os.getenv("APP_PROFILE") or "dev").strip().lower()


def insecure_settings() -> list[str]:
    problems = []
    key = os.getenv("PROVENANCE_SIGNING_KEY", "")
    if not key or key == provenance.DEFAULT_SIGNING_KEY or len(key) < MIN_KEY_LENGTH:
        problems.append(
            f"PROVENANCE_SIGNING_KEY is unset, the demo default or shorter than {MIN_KEY_LENGTH} characters"
        )
    return problems


def enforce() -> None:
    """Raises ``RuntimeError`` (aborting startup) when a non-dev profile runs with demo secrets."""
    current = profile()
    if current in DEV_PROFILES:
        return
    problems = insecure_settings()
    if problems:
        raise RuntimeError(
            f"Refusing to start with APP_PROFILE={current}: " + "; ".join(problems)
            + ". Set real secrets, or APP_PROFILE=dev for a local demo."
        )
    if content_credentials.write_enabled() and not os.getenv("C2PA_SIGNING_CERT"):
        logger.warning(
            "C2PA manifests are signed with a generated development certificate (validators report "
            "VALID_UNTRUSTED). Set C2PA_SIGNING_CERT / C2PA_SIGNING_KEY to a trust-list certificate."
        )
