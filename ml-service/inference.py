"""Inference runtime — models loaded once at startup (Phase 5), optional calibration (Phase 22),
optional CNN + spectral expert ensemble (Phase 29)."""

from __future__ import annotations

import json
import logging
import math
import os
import threading
from pathlib import Path

import numpy as np
import torch

import ensemble as ensemble_lib
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


def load_temperature(path: Path) -> float | None:
    """Temperature from a ``calibration.json`` written by ``ml/calibration.py --promote``; None if absent or invalid."""
    if not path.exists():
        return None
    try:
        t = float(json.loads(path.read_text(encoding="utf-8"))["temperature"])
    except (OSError, ValueError, KeyError, TypeError) as exc:
        logger.warning("Ignoring unreadable calibration file %s: %s", path, exc)
        return None
    if not math.isfinite(t) or not 0.05 <= t <= 20.0:
        logger.warning("Ignoring calibration temperature %s outside [0.05, 20]", t)
        return None
    return t


class InferenceRuntime:
    """Holds cold-started forgery + NSFW models for the process lifetime.

    With an ``ensemble.json`` fitted against this checkpoint, the forgery score is the stacked CNN +
    spectral expert score and the temperature is not applied (the stacker is fitted on raw logits).
    ``FORGERY_ENSEMBLE=off`` serves the CNN alone.
    """

    def __init__(
        self,
        checkpoint: Path | None = None,
        device: str | None = None,
        calibration: Path | None = None,
        ensemble: Path | str | None = None,
    ) -> None:
        requested = device or os.getenv("ML_DEVICE", "auto").strip().lower()
        if requested in ("", "auto"):
            requested = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = torch.device(requested)
        self.checkpoint = Path(checkpoint or DEFAULT_CHECKPOINT)
        self.calibration_path = Path(
            calibration or os.getenv("FORGERY_CALIBRATION") or self.checkpoint.parent / "calibration.json"
        )
        self.temperature = load_temperature(self.calibration_path)
        ensemble_setting = str(ensemble or os.getenv("FORGERY_ENSEMBLE") or self.checkpoint.parent / "ensemble.json")
        self.ensemble = None
        if ensemble_setting.strip().lower() != "off" and self.checkpoint.exists():
            self.ensemble = ensemble_lib.load(Path(ensemble_setting), ensemble_lib.file_sha256(self.checkpoint))
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
            "Runtime ready (device=%s, nsfw_mode=%s, forgery_loads=%s, temperature=%s, ensemble=%s)",
            self.device,
            self.nsfw.mode,
            self._load_count,
            self.temperature,
            self.ensemble.describe()["weights"] if self.ensemble else None,
        )

    @property
    def load_count(self) -> int:
        return self._load_count

    def ensemble_info(self) -> dict:
        return self.ensemble.describe() if self.ensemble else {"active": False, "experts": ["cnn"]}


_runtime: InferenceRuntime | None = None


def init_runtime(
    checkpoint: Path | None = None,
    device: str | None = None,
    calibration: Path | None = None,
    ensemble: Path | str | None = None,
) -> InferenceRuntime:
    global _runtime
    _runtime = InferenceRuntime(checkpoint=checkpoint, device=device, calibration=calibration, ensemble=ensemble)
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


def _sigmoid(z: float) -> float:
    return 1.0 / (1.0 + math.exp(-max(-60.0, min(60.0, z))))


def _assess(rt: InferenceRuntime, rgb: np.ndarray, cnn_logit: float, p_diffusion: float | None) -> dict:
    if rt.ensemble is not None:
        spectral_logit = rt.ensemble.spectral_logit(rgb)
        score = _sigmoid(rt.ensemble.combine(cnn_logit, spectral_logit))
        experts = {"cnn": _sigmoid(cnn_logit), "spectral": _sigmoid(spectral_logit)}
    else:
        score = _sigmoid(cnn_logit / rt.temperature if rt.temperature is not None else cnn_logit)
        experts = {"cnn": score}
    family, confidence = classify_family(score, p_diffusion)
    return {"forgery_score": score, "generation_family": family, "family_confidence": confidence, "experts": experts}


@torch.no_grad()
def run_forgery_assessment(image_bytes: bytes) -> dict:
    """Forgery confidence, per-expert scores, and best-effort generation family (gan / diffusion / unknown)."""
    return run_forgery_assessments([image_bytes])[0]


@torch.no_grad()
def run_forgery_assessments(images: list[bytes]) -> list[dict]:
    """``run_forgery_assessment`` for several images in one forward pass (video frames)."""
    if not images:
        return []
    rt = get_runtime()
    rgbs = [bytes_to_rgb(b) for b in images]
    batch = torch.cat([rgb_to_tensor(rgb, rt.device) for rgb in rgbs])
    forgery_logits, family_logits = rt.forgery_model.forward_all(batch)
    logits = forgery_logits.reshape(-1).tolist()
    diffusion = torch.sigmoid(family_logits).reshape(-1).tolist() if family_logits is not None else [None] * len(logits)
    return [_assess(rt, rgb, z, p) for rgb, z, p in zip(rgbs, logits, diffusion, strict=True)]


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
