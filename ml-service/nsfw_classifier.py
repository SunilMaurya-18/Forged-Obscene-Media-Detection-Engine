"""Independent explicit/NSFW content scorer (Phase 4).

Completely separate from the forgery detection model — no shared weights,
no imports from inference forgery paths.
"""

from __future__ import annotations

import io
import logging
import os
from typing import Any

import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

# Env: force heuristic-only (tests / offline). Default tries open-source weights.
NSFW_BACKEND = os.getenv("NSFW_BACKEND", "auto")  # auto | transformers | heuristic


def _pil_from_bytes(image_bytes: bytes) -> Image.Image:
    return Image.open(io.BytesIO(image_bytes)).convert("RGB")


def _heuristic_explicit_score(image: Image.Image) -> float:
    """Dev/offline fallback: skin-tone pixel ratio in YCrCb.

    Not a production NSFW model — used only when pretrained weights are
    unavailable. Still independent of any forgery network.
    """
    rgb = np.asarray(image)
    if rgb.size == 0:
        return 0.0
    import cv2

    ycrcb = cv2.cvtColor(rgb, cv2.COLOR_RGB2YCrCb)
    # Common Cr/Cb skin band (coarse)
    mask = (
        (ycrcb[:, :, 1] > 133)
        & (ycrcb[:, :, 1] < 173)
        & (ycrcb[:, :, 2] > 77)
        & (ycrcb[:, :, 2] < 127)
    )
    ratio = float(mask.mean())
    # Map ratio into a soft [0, 1] score without hard thresholds in callers
    return float(np.clip(ratio * 1.8, 0.0, 1.0))


class NSFWClassifier:
    """Pretrained open-source NSFW classifier with optional heuristic fallback."""

    def __init__(self, backend: str | None = None) -> None:
        self.backend = (backend or NSFW_BACKEND).lower()
        self._pipeline: Any = None
        self._mode = "heuristic"
        self._load()

    def _load(self) -> None:
        if self.backend == "heuristic":
            self._mode = "heuristic"
            logger.info("NSFWClassifier using heuristic backend (explicit request)")
            return

        if self.backend in ("auto", "transformers"):
            try:
                from transformers import pipeline  # type: ignore

                self._pipeline = pipeline(
                    "image-classification",
                    model="Falconsai/nsfw_image_detection",
                    device=-1,
                )
                self._mode = "transformers"
                logger.info("NSFWClassifier loaded Falconsai/nsfw_image_detection")
                return
            except Exception as exc:  # noqa: BLE001
                if self.backend == "transformers":
                    raise RuntimeError(
                        f"Failed to load NSFW transformers model: {exc}"
                    ) from exc
                logger.warning(
                    "NSFW transformers model unavailable (%s); using heuristic fallback",
                    exc,
                )

        self._mode = "heuristic"

    @property
    def mode(self) -> str:
        return self._mode

    def explicit_score(self, image_bytes: bytes) -> float:
        """Return explicit-content confidence in [0, 1]."""
        image = _pil_from_bytes(image_bytes)
        if self._mode == "transformers" and self._pipeline is not None:
            results = self._pipeline(image)
            # Expect labels like "nsfw" / "normal"
            scores = {str(r["label"]).lower(): float(r["score"]) for r in results}
            for key in ("nsfw", "porn", "explicit", "sexy"):
                if key in scores:
                    return float(np.clip(scores[key], 0.0, 1.0))
            # If only "normal" present, invert
            if "normal" in scores:
                return float(np.clip(1.0 - scores["normal"], 0.0, 1.0))
            # Highest non-normal score
            best = max(scores.values()) if scores else 0.0
            return float(np.clip(best, 0.0, 1.0))

        return _heuristic_explicit_score(image)


def score_explicit(image_bytes: bytes, classifier: NSFWClassifier | None = None) -> dict[str, float]:
    """Public Phase-4 API: `{explicit_score: float}` with no forgery dependency."""
    clf = classifier or NSFWClassifier()
    return {"explicit_score": clf.explicit_score(image_bytes)}
