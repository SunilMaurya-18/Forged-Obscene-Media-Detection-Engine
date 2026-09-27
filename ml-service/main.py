"""FastAPI ML inference microservice — Phases 4–5, provenance (14), forensic signatures (15), generation family (17)."""

from __future__ import annotations

import base64
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

import forensics
import provenance
from hashing import compute_phash
from inference import (
    get_runtime,
    init_runtime,
    run_explain,
    run_explicit_check,
    run_forgery_assessment,
    run_forgery_check,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Cold-start: load forgery + NSFW models once per process."""
    logger.info("Starting model cold-load…")
    init_runtime()
    rt = get_runtime()
    logger.info("Cold-load complete (forgery_loads=%s)", rt.load_count)
    yield


app = FastAPI(
    title="Forged Media ML Service",
    version="0.17.0",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict:
    ready = False
    loads = 0
    try:
        rt = get_runtime()
        ready = rt.forgery_model is not None
        loads = rt.load_count
    except RuntimeError:
        pass
    return {"status": "ok" if ready else "starting", "models_loaded": ready, "forgery_loads": loads}


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
    except Exception as exc:  # noqa: BLE001
        logger.exception("infer failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return {
        "forgery_score": assessment["forgery_score"],
        "generation_family": assessment["generation_family"],
        "family_confidence": assessment["family_confidence"],
        "explicit_score": explicit_score,
        "heatmap": heatmap,
        "phash": phash,
        "content_hash": content_hash,
        "provenance_id": provenance.new_provenance_id(),
        "provenance": embedded,
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
    """Return a labelled copy of the image with a signed provenance manifest embedded as XMP."""
    raw = await _read_upload(file)
    try:
        manifest = provenance.build_manifest(
            raw, provenance_id, analyzed_at, forgery_score, explicit_score, decision, derived_from
        )
        labelled, mime_type = provenance.embed(raw, manifest)
    except Exception as exc:  # noqa: BLE001
        logger.exception("provenance embed failed")
        raise HTTPException(status_code=422, detail=f"Could not label image: {exc}") from exc

    return {
        "image": base64.b64encode(labelled).decode("ascii"),
        "mime_type": mime_type,
        "manifest": provenance.verify(labelled),
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
        }
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=422, detail=f"Not a readable image: {exc}") from exc


async def _read_upload(file: UploadFile) -> bytes:
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty file")
    return raw


__all__ = ["app", "run_forgery_check", "run_explicit_check", "compute_phash"]
