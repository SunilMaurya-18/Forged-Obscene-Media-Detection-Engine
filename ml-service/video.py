"""Video analysis (Phase 23).

Frames are decoded straight from the uploaded bytes (PyAV over an in-memory buffer), sampled at a
fixed rate, scored in batches of ``VIDEO_BATCH_SIZE`` and dropped. Only per-frame scores, the frames
of the batch being scored and the single highest-scoring frame (needed for the heatmap and forensic
signature) are held until the response is built; nothing is ever written to disk.
"""

from __future__ import annotations

import hashlib
import io
import os
from collections.abc import Callable, Iterator
from dataclasses import dataclass

import av
import numpy as np
from PIL import Image

MAX_FRAMES = int(os.getenv("VIDEO_MAX_FRAMES", "64"))
MAX_BYTES = int(os.getenv("VIDEO_MAX_BYTES", str(50 * 1024 * 1024)))
MAX_SIDE = 640
"""Frames are downscaled to this long side before scoring; the models work at 224 px anyway."""
BATCH_SIZE = max(1, int(os.getenv("VIDEO_BATCH_SIZE", "8")))
"""Frames per forgery-model forward pass. 1 scores frame by frame; larger batches use the CPU/GPU better."""

VIDEO_MIME_BY_SIGNATURE = (
    (b"\x1aE\xdf\xa3", "video/webm"),
    (b"RIFF", "video/x-msvideo"),
    (b"GIF8", "image/gif"),
)


class VideoDecodeError(ValueError):
    """The upload is not a video we can decode, or has no video stream."""


@dataclass(frozen=True)
class SampledFrame:
    index: int
    time: float
    rgb: np.ndarray


@dataclass
class ClipInfo:
    duration_seconds: float | None = None
    width: int | None = None
    height: int | None = None
    codec: str | None = None
    source_fps: float | None = None
    truncated: bool = False
    sample_fps: float | None = None
    """Rate actually used: the requested fps, lowered when that would exceed max_frames over the clip."""


def sniff_mime(data: bytes) -> str:
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "video/quicktime" if data[8:12] == b"qt  " else "video/mp4"
    for signature, mime in VIDEO_MIME_BY_SIGNATURE:
        if data.startswith(signature):
            return mime
    return "application/octet-stream"


def file_hash(data: bytes) -> str:
    """SHA-256 of the container bytes. Videos have no single pixel buffer, so this hashes the file."""
    return "sha256:" + hashlib.sha256(data).hexdigest()


def sample_frames(data: bytes, fps: float, max_frames: int, info: ClipInfo) -> Iterator[SampledFrame]:
    """Yield frames at roughly ``fps`` per second of media time, at most ``max_frames``. Fills ``info``.

    When the container reports a duration and ``fps`` would need more than ``max_frames`` frames, the
    rate is lowered so the samples still span the whole clip: a benign intro can't hide a later
    manipulated segment behind the frame cap.
    """
    if len(data) > MAX_BYTES:
        raise VideoDecodeError(f"Video is larger than {MAX_BYTES // (1024 * 1024)} MB")
    try:
        container = av.open(io.BytesIO(data), mode="r")
    except (av.FFmpegError, ValueError) as exc:
        raise VideoDecodeError(f"Not a readable video: {exc}") from exc

    with container:
        if not container.streams.video:
            raise VideoDecodeError("The file has no video stream")
        stream = container.streams.video[0]
        stream.thread_type = "AUTO"
        info.codec = stream.codec_context.name
        info.width = stream.codec_context.width or None
        info.height = stream.codec_context.height or None
        info.source_fps = float(stream.average_rate) if stream.average_rate else None
        if stream.duration and stream.time_base:
            info.duration_seconds = float(stream.duration * stream.time_base)
        elif container.duration:
            info.duration_seconds = container.duration / av.time_base

        rate = fps
        if info.duration_seconds and info.duration_seconds > 0 and fps * info.duration_seconds > max_frames:
            rate = max_frames / info.duration_seconds
        info.sample_fps = rate
        interval = 1.0 / rate
        next_time = 0.0
        emitted = 0
        try:
            for decoded, frame in enumerate(container.decode(stream)):
                t = frame.time
                if t is None:
                    t = decoded / info.source_fps if info.source_fps else float(decoded)
                if t + 1e-6 < next_time:
                    continue
                if emitted >= max_frames:
                    info.truncated = True
                    break
                yield SampledFrame(emitted, float(t), frame.to_ndarray(format="rgb24"))
                emitted += 1
                while next_time <= t + 1e-6:
                    next_time += interval
        except av.FFmpegError as exc:
            if emitted == 0:
                raise VideoDecodeError(f"Could not decode video frames: {exc}") from exc
            info.truncated = True
        if emitted == 0:
            raise VideoDecodeError("No decodable frames in the video")


def frame_png(rgb: np.ndarray) -> bytes:
    """In-memory PNG of a frame, downscaled to ``MAX_SIDE``, so the image scorers can be reused as-is."""
    img = Image.fromarray(rgb)
    if max(img.size) > MAX_SIDE:
        img.thumbnail((MAX_SIDE, MAX_SIDE))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def aggregate(scores: list[float], top_k: int) -> dict:
    """Max and mean of the ``top_k`` highest scores (k capped at the number of frames)."""
    if not scores:
        raise ValueError("no scores to aggregate")
    k = max(1, min(top_k, len(scores)))
    top = sorted(scores, reverse=True)[:k]
    return {"max": float(max(scores)), "topk_mean": float(sum(top) / k), "k": k}


def analyse(
    data: bytes,
    fps: float,
    top_k: int,
    score_forgery: Callable[[bytes], dict],
    score_explicit: Callable[[bytes], float],
    max_frames: int | None = None,
    score_forgery_batch: Callable[[list[bytes]], list[dict]] | None = None,
    batch_size: int | None = None,
) -> tuple[dict, bytes]:
    """Score every sampled frame. Returns (result without image-derived fields, PNG of the peak frame).

    With ``score_forgery_batch`` the forgery model sees ``batch_size`` frames per call; otherwise
    ``score_forgery`` is called per frame. The peak frame is the one with the highest forgery score
    (earliest on ties); callers use it for the heatmap, perceptual hash and forensic signature, then
    discard it.
    """
    info = ClipInfo()
    timeline: list[dict] = []
    peak_png: bytes | None = None
    peak: dict | None = None
    size = max(1, batch_size or BATCH_SIZE) if score_forgery_batch else 1
    pending: list[tuple[int, float, bytes]] = []

    def flush() -> None:
        nonlocal peak, peak_png
        pngs = [png for _, _, png in pending]
        assessments = score_forgery_batch(pngs) if score_forgery_batch else [score_forgery(pngs[0])]
        for (index, time, png), assessment in zip(pending, assessments, strict=True):
            entry = {
                "index": index,
                "t": round(time, 3),
                "forgery_score": float(assessment["forgery_score"]),
                "explicit_score": float(score_explicit(png)),
            }
            timeline.append(entry)
            if peak is None or entry["forgery_score"] > peak["forgery_score"]:
                peak = {**entry, "generation_family": assessment.get("generation_family"),
                        "family_confidence": assessment.get("family_confidence")}
                peak_png = png
        pending.clear()

    for frame in sample_frames(data, fps, max_frames or MAX_FRAMES, info):
        pending.append((frame.index, frame.time, frame_png(frame.rgb)))
        del frame
        if len(pending) >= size:
            flush()
    if pending:
        flush()

    forgery = aggregate([f["forgery_score"] for f in timeline], top_k)
    explicit = aggregate([f["explicit_score"] for f in timeline], top_k)
    result = {
        "media_type": "video",
        "timeline": timeline,
        "aggregate": {"forgery": forgery, "explicit": explicit, "k": forgery["k"]},
        "peak": {"index": peak["index"], "t": peak["t"]},
        "generation_family": peak["generation_family"],
        "family_confidence": peak["family_confidence"],
        "frames": {
            "sampled": len(timeline),
            "fps": round(info.sample_fps or fps, 6),
            "requested_fps": fps,
            "truncated": info.truncated,
            "max_frames": max_frames or MAX_FRAMES,
            "duration_seconds": info.duration_seconds,
            "width": info.width,
            "height": info.height,
            "codec": info.codec,
            "source_fps": info.source_fps,
        },
    }
    return result, peak_png
