"""FastAPI ML inference microservice — Phases 4–5, provenance (14), forensic signatures (15),
generation family (17), video and C2PA Content Credentials (23), observability (24), forgery
ensemble (29)."""

from __future__ import annotations

import base64
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from PIL import UnidentifiedImageError
from starlette.formparsers import MultiPartParser

import content_credentials
import forensics
import observability
import provenance
import startup_guard
import video
from hashing import compute_phash
from inference import (
    get_runtime,
    init_runtime,
    run_explain,
    run_explicit_check,
    run_forgery_assessment,
    run_forgery_assessments,
    run_forgery_check,
)

observability.configure_logging()
logger = logging.getLogger(__name__)

IMAGE_MAX_BYTES = int(os.getenv("IMAGE_MAX_BYTES", str(25 * 1024 * 1024)))

# Starlette spools multipart files over 1 MB to a temp file on disk. Keep every accepted upload in
# memory instead, so media bytes (including every frame of a video) are never written to disk.
MultiPartParser.spool_max_size = max(video.MAX_BYTES, IMAGE_MAX_BYTES) + 1024 * 1024


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Cold-start: load forgery + NSFW models once per process."""
    startup_guard.enforce()
    logger.info("Starting model cold-load…")
    init_runtime()
    rt = get_runtime()
    observability.MODELS_LOADED.set(1 if rt.forgery_model is not None else 0)
    logger.info("Cold-load complete (forgery_loads=%s)", rt.load_count)
    yield


app = FastAPI(
    title="Forged Media ML Service",
    version="0.29.0",
    lifespan=lifespan,
)
observability.install(app)


@app.get("/health")
def health() -> dict:
    ready = False
    loads = 0
    temperature = None
    ensemble = {"active": False, "experts": ["cnn"]}
    try:
        rt = get_runtime()
        ready = rt.forgery_model is not None
        loads = rt.load_count
        temperature = rt.temperature
        ensemble = rt.ensemble_info()
    except RuntimeError:
        pass
    return {
        "status": "ok" if ready else "starting",
        "models_loaded": ready,
        "forgery_loads": loads,
        "calibration": {"active": temperature is not None and not ensemble["active"], "temperature": temperature},
        "ensemble": ensemble,
        "c2pa": content_credentials.signer_info(),
    }


@app.post("/infer")
async def infer(file: UploadFile = File(...)) -> dict:
    """Run forgery + explicit checks, Grad-CAM heatmap, and pHash.

    Also issues a fresh ``provenance_id`` for this analysis and reads back any manifest the
    upload already carries (``provenance`` is null for unlabelled images).
    """
    raw = await _read_upload(file)
    try:
        assessment = run_forgery_assessment(raw)
        explicit_score = run_explicit_check(raw)
        heatmap, cam = run_explain(raw)
        signature = forensics.signature(raw, cam)
        phash = compute_phash(raw)
        content_hash = provenance.content_hash(raw)
        embedded = provenance.verify(raw)
        credentials = content_credentials.read(raw)
    except UnidentifiedImageError as exc:
        raise HTTPException(status_code=400, detail="Unsupported or corrupt image") from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("infer failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    observability.observe_scores(assessment["forgery_score"], explicit_score)
    return {
        "media_type": "image",
        "forgery_score": assessment["forgery_score"],
        "forgery_experts": assessment["experts"],
        "generation_family": assessment["generation_family"],
        "family_confidence": assessment["family_confidence"],
        "explicit_score": explicit_score,
        "heatmap": heatmap,
        "phash": phash,
        "content_hash": content_hash,
        "provenance_id": provenance.new_provenance_id(),
        "provenance": embedded,
        "c2pa": credentials,
        "forensics": signature,
    }


@app.post("/infer-video")
def infer_video(
    file: UploadFile = File(...),
    fps: float = Form(1.0, gt=0.0, le=10.0),
    top_k: int = Form(3, ge=1, le=32),
    max_frames: int | None = Form(None, ge=1, le=video.MAX_FRAMES),
) -> dict:
    """Score a video frame by frame: sample at ``fps``, aggregate as max and top-k mean.

    Frames are decoded from memory and discarded after scoring; only the peak (highest forgery
    score) frame is kept long enough to draw its heatmap, perceptual hash and forensic signature.
    Top-level ``forgery_score`` is the top-k mean and ``explicit_score`` the max (one explicit
    frame is enough); callers may route on any value in ``aggregate``.
    """
    raw = file.file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty file")
    if len(raw) > video.MAX_BYTES:
        raise HTTPException(status_code=413, detail=f"Video is larger than {video.MAX_BYTES // (1024 * 1024)} MB")
    try:
        result, peak_png = video.analyse(
            raw, fps, top_k, run_forgery_assessment, run_explicit_check, max_frames=max_frames,
            score_forgery_batch=run_forgery_assessments,
        )
    except video.VideoDecodeError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("infer-video failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    try:
        heatmap, cam = run_explain(peak_png)
        signature = forensics.signature(peak_png, cam)
        phash = compute_phash(peak_png)
    finally:
        del peak_png
    observability.observe_scores(
        result["aggregate"]["forgery"]["topk_mean"], result["aggregate"]["explicit"]["max"]
    )
    observability.VIDEO_FRAMES.observe(len(result.get("timeline") or []))
    return {
        **result,
        "forgery_score": result["aggregate"]["forgery"]["topk_mean"],
        "explicit_score": result["aggregate"]["explicit"]["max"],
        "heatmap": heatmap,
        "phash": phash,
        "content_hash": video.file_hash(raw),
        "provenance_id": provenance.new_provenance_id(),
        "provenance": None,
        "c2pa": content_credentials.read(raw),
        "forensics": signature,
    }


@app.post("/forensics/panels")
async def forensic_panels(file: UploadFile = File(...)) -> dict:
    """Full-resolution Grad-CAM / FFT / ELA panels for a report. Nothing is kept."""
    raw = await _read_upload(file)
    try:
        heatmap, _ = run_explain(raw)
        panels = forensics.render_panels(raw)
        return {
            "heatmap": heatmap,
            "fft": panels["fft"],
            "ela": panels["ela"],
            "phash": compute_phash(raw),
            "content_hash": provenance.content_hash(raw),
        }
    except Exception as exc:  # noqa: BLE001
        logger.exception("forensic panels failed")
        raise HTTPException(status_code=422, detail=f"Could not render panels: {exc}") from exc


@app.post("/provenance/embed")
async def provenance_embed(
    file: UploadFile = File(...),
    provenance_id: str = Form(..., min_length=1, max_length=64),
    analyzed_at: str = Form(..., min_length=1, max_length=64),
    forgery_score: float = Form(..., ge=0.0, le=1.0),
    explicit_score: float = Form(..., ge=0.0, le=1.0),
    decision: str = Form(..., min_length=1, max_length=32),
    derived_from: str | None = Form(None, max_length=64),
) -> dict:
    """Return a labelled copy of the image: HMAC-signed XMP manifest plus, when available, a C2PA
    manifest (written after the XMP so its hard binding covers the final bytes)."""
    raw = await _read_upload(file)
    try:
        manifest = provenance.build_manifest(
            raw, provenance_id, analyzed_at, forgery_score, explicit_score, decision, derived_from
        )
        labelled, mime_type = provenance.embed(raw, manifest)
    except Exception as exc:  # noqa: BLE001
        logger.exception("provenance embed failed")
        raise HTTPException(status_code=422, detail=f"Could not label image: {exc}") from exc

    c2pa_error = None
    if content_credentials.write_enabled():
        try:
            labelled = content_credentials.sign(labelled, raw, {
                "provenance_id": provenance_id,
                "analyzed_at": analyzed_at,
                "forgery_score": round(forgery_score, 4),
                "explicit_score": round(explicit_score, 4),
                "decision": decision,
                "derived_from": derived_from,
                "xmp_manifest": provenance.SPEC,
            })
        except Exception as exc:  # noqa: BLE001 - the XMP label alone is still a valid result
            logger.warning("C2PA signing failed for %s: %s", provenance_id, exc)
            c2pa_error = str(exc)[:240]

    return {
        "image": base64.b64encode(labelled).decode("ascii"),
        "mime_type": mime_type,
        "manifest": provenance.verify(labelled),
        "c2pa": content_credentials.read(labelled),
        "c2pa_error": c2pa_error,
    }


@app.post("/provenance/read")
async def provenance_read(file: UploadFile = File(...)) -> dict:
    """Read back an embedded manifest without running the models."""
    raw = await _read_upload(file)
    try:
        return {
            "phash": compute_phash(raw),
            "content_hash": provenance.content_hash(raw),
            "provenance": provenance.verify(raw),
            "c2pa": content_credentials.read(raw),
        }
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=422, detail=f"Not a readable image: {exc}") from exc


async def _read_upload(file: UploadFile) -> bytes:
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty file")
    if len(raw) > IMAGE_MAX_BYTES:
        raise HTTPException(status_code=413, detail=f"Image is larger than {IMAGE_MAX_BYTES // (1024 * 1024)} MB")
    return raw


__all__ = ["app", "run_forgery_check", "run_explicit_check", "compute_phash"]
