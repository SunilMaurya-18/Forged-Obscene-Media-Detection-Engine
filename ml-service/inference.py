"""Inference runtime — models loaded once at startup (Phase 5)."""

from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

import numpy as np
import torch

from forgery_runtime import (
    GradCAM,
    bytes_to_rgb,
    load_forgery_model,
    overlay_heatmap_b64,
    rgb_to_tensor,
)
from nsfw_classifier import NSFWClassifier

logger = logging.getLogger(__name__)

DEFAULT_CHECKPOINT = Path(
    os.getenv(
        "FORGERY_CHECKPOINT",
        str(Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt"),
    )
)


class InferenceRuntime:
    """Holds cold-started forgery + NSFW models for the process lifetime."""

    def __init__(self, checkpoint: Path | None = None, device: str | None = None) -> None:
        self.device = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
        self.checkpoint = Path(checkpoint or DEFAULT_CHECKPOINT)
        self.forgery_model = None
        self.nsfw = NSFWClassifier()
        self._lock = threading.Lock()
        self._load_count = 0

        if not self.checkpoint.exists():
            raise FileNotFoundError(
                f"Forgery checkpoint not found: {self.checkpoint}. "
                "Run ml/smoke_phase23.py or set FORGERY_CHECKPOINT."
            )
        logger.info("Loading forgery checkpoint once from %s", self.checkpoint)
        self.forgery_model = load_forgery_model(self.checkpoint, self.device)
        self._load_count += 1
        logger.info(
            "Runtime ready (device=%s, nsfw_mode=%s, forgery_loads=%s)",
            self.device,
            self.nsfw.mode,
            self._load_count,
        )

    @property
    def load_count(self) -> int:
        return self._load_count


_runtime: InferenceRuntime | None = None


def init_runtime(
    checkpoint: Path | None = None, device: str | None = None
) -> InferenceRuntime:
    global _runtime
    _runtime = InferenceRuntime(checkpoint=checkpoint, device=device)
    return _runtime


def get_runtime() -> InferenceRuntime:
    if _runtime is None:
        raise RuntimeError("Inference runtime not initialized; call init_runtime() at startup")
    return _runtime


FAMILY_MIN_FORGERY = float(os.getenv("FAMILY_MIN_FORGERY", "0.5"))
FAMILY_MARGIN = float(os.getenv("FAMILY_MARGIN", "0.15"))


def classify_family(forgery_score: float, p_diffusion: float | None) -> tuple[str, float | None]:
    """Map P(diffusion) to gan / diffusion / unknown.

    ``unknown`` whenever the image isn't judged fake, the checkpoint has no family head, or the
    head sits within ``FAMILY_MARGIN`` of 0.5 — attribution is only offered when it is meaningful.
    """
    if p_diffusion is None or forgery_score < FAMILY_MIN_FORGERY:
        return "unknown", None
    confidence = max(p_diffusion, 1.0 - p_diffusion)
    if abs(p_diffusion - 0.5) < FAMILY_MARGIN:
        return "unknown", confidence
    return ("diffusion" if p_diffusion >= 0.5 else "gan"), confidence


@torch.no_grad()
def run_forgery_assessment(image_bytes: bytes) -> dict:
    """Forgery confidence plus best-effort generation family (gan / diffusion / unknown)."""
    rt = get_runtime()
    tensor = rgb_to_tensor(bytes_to_rgb(image_bytes), rt.device)
    forgery_logits, family_logits = rt.forgery_model.forward_all(tensor)
    score = float(torch.sigmoid(forgery_logits).item())
    p_diffusion = float(torch.sigmoid(family_logits).item()) if family_logits is not None else None
    family, confidence = classify_family(score, p_diffusion)
    return {"forgery_score": score, "generation_family": family, "family_confidence": confidence}


def run_forgery_check(image_bytes: bytes) -> float:
    """Return forgery confidence in [0, 1] using the cold-started model."""
    return run_forgery_assessment(image_bytes)["forgery_score"]


def run_explicit_check(image_bytes: bytes) -> float:
    """Return explicit-content confidence in [0, 1] via independent NSFW classifier."""
    return get_runtime().nsfw.explicit_score(image_bytes)


def run_heatmap(image_bytes: bytes) -> str:
    """Grad-CAM overlay as base64 PNG (uses forgery backbone only)."""
    return run_explain(image_bytes)[0]


def run_explain(image_bytes: bytes) -> tuple[str, np.ndarray]:
    """Grad-CAM overlay (base64 PNG) and the raw 224x224 CAM it was drawn from."""
    rt = get_runtime()
    rgb = bytes_to_rgb(image_bytes)
    tensor = rgb_to_tensor(rgb, rt.device)
    # Grad-CAM needs gradients — temporarily enable
    with rt._lock:
        was_training = rt.forgery_model.training
        rt.forgery_model.eval()
        cam_engine = GradCAM(rt.forgery_model)
        try:
            cam = cam_engine(tensor)
        finally:
            cam_engine.close()
            rt.forgery_model.train(was_training)
    return overlay_heatmap_b64(rgb, cam), cam
