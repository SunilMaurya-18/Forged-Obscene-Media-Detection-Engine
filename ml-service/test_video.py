"""Phase 23: /infer-video samples frames in memory, aggregates max + top-k mean, writes nothing to disk."""

from __future__ import annotations

import builtins
import io
import os
import tempfile
from pathlib import Path

import av
import numpy as np
import pytest
from fastapi.testclient import TestClient

import video

CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT", Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt")
)


def synthetic_clip(
    seconds: float = 3.0, fps: int = 10, fmt: str = "mp4", codec: str = "mpeg4", noise: bool = False
) -> bytes:
    """A moving-bar test pattern (or incompressible noise, for large files), encoded entirely in memory."""
    buf = io.BytesIO()
    rng = np.random.default_rng(23)
    width, height = (480, 360) if noise else (128, 96)
    with av.open(buf, mode="w", format=fmt) as out:
        stream = out.add_stream(codec, rate=fps)
        stream.width, stream.height, stream.pix_fmt = width, height, "yuv420p"
        if noise:
            stream.bit_rate = 20_000_000
        for i in range(int(seconds * fps)):
            if noise:
                img = rng.integers(0, 256, (height, width, 3), dtype=np.uint8)
            else:
                img = np.full((height, width, 3), 40, np.uint8)
                img[:, :, 2] = (i * 7) % 255
                x = (i * 4) % 112
                img[20:76, x:x + 16] = (230, 200, 60)
            for packet in stream.encode(av.VideoFrame.from_ndarray(img, format="rgb24")):
                out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)
    return buf.getvalue()


# --------------------------------------------------------------------------- pure logic

def test_aggregate_max_and_topk_mean():
    agg = video.aggregate([0.1, 0.9, 0.4, 0.7], top_k=2)
    assert agg == {"max": 0.9, "topk_mean": pytest.approx(0.8), "k": 2}
    assert video.aggregate([0.3], top_k=5)["k"] == 1
    with pytest.raises(ValueError):
        video.aggregate([], top_k=3)


def test_sampling_follows_media_time_and_caps_frames():
    clip = synthetic_clip(seconds=3.0, fps=10)
    info = video.ClipInfo()
    times = [f.time for f in video.sample_frames(clip, fps=2.0, max_frames=64, info=info)]
    assert times == pytest.approx([0.0, 0.5, 1.0, 1.5, 2.0, 2.5], abs=0.01)
    assert info.duration_seconds == pytest.approx(3.0, abs=0.15)
    assert not info.truncated

    # 10 fps over 3 s would need 30 frames: with a cap of 4 the rate drops so the samples span the whole clip
    capped = video.ClipInfo()
    spread = [f.time for f in video.sample_frames(clip, fps=10.0, max_frames=4, info=capped)]
    assert len(spread) == 4
    assert spread[-1] >= 2.0
    assert capped.sample_fps == pytest.approx(4 / capped.duration_seconds)
    assert not capped.truncated


def test_webm_is_decoded_too():
    clip = synthetic_clip(seconds=1.0, fps=10, fmt="webm", codec="libvpx")
    assert len(list(video.sample_frames(clip, fps=5.0, max_frames=64, info=video.ClipInfo()))) == 5


def test_garbage_is_rejected():
    with pytest.raises(video.VideoDecodeError):
        list(video.sample_frames(b"definitely not a video", 1.0, 8, video.ClipInfo()))


def test_analyse_picks_peak_and_aggregates_with_fake_scorers():
    scores = iter([0.2, 0.95, 0.3, 0.6])
    result, peak_png = video.analyse(
        synthetic_clip(seconds=2.0, fps=10),
        fps=2.0,
        top_k=2,
        score_forgery=lambda png: {"forgery_score": next(scores), "generation_family": "gan", "family_confidence": 0.8},
        score_explicit=lambda png: 0.05,
    )
    assert [f["forgery_score"] for f in result["timeline"]] == [0.2, 0.95, 0.3, 0.6]
    assert result["peak"] == {"index": 1, "t": 0.5}
    assert result["aggregate"]["forgery"] == {"max": 0.95, "topk_mean": pytest.approx(0.775), "k": 2}
    assert peak_png.startswith(b"\x89PNG")


def test_batched_scoring_matches_frame_by_frame():
    values = [0.2, 0.95, 0.3, 0.95, 0.6]
    clip = synthetic_clip(seconds=2.5, fps=10)
    single = iter(values)
    by_frame, peak_single = video.analyse(
        clip, fps=2.0, top_k=2,
        score_forgery=lambda png: {"forgery_score": next(single)},
        score_explicit=lambda png: 0.05,
    )
    batched_values = iter(values)
    batch_sizes: list[int] = []

    def score_batch(pngs):
        batch_sizes.append(len(pngs))
        return [{"forgery_score": next(batched_values)} for _ in pngs]

    batched, peak_batched = video.analyse(
        clip, fps=2.0, top_k=2,
        score_forgery=lambda png: pytest.fail("the per-frame scorer must not be used when batching"),
        score_explicit=lambda png: 0.05,
        score_forgery_batch=score_batch, batch_size=2,
    )
    assert batch_sizes == [2, 2, 1]
    assert batched["timeline"] == by_frame["timeline"]
    # Ties keep the earliest frame, across batch boundaries too
    assert batched["peak"] == by_frame["peak"] == {"index": 1, "t": 0.5}
    assert peak_batched == peak_single


# --------------------------------------------------------------------------- HTTP

@pytest.fixture(scope="module")
def client():
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["NSFW_BACKEND"] = "heuristic"
    import inference as inf
    import main

    if inf._runtime is None:
        inf.init_runtime(checkpoint=CHECKPOINT, device="cpu")
    return TestClient(main.app)


def _snapshot(dirs: list[Path]) -> set[str]:
    return {str(p) for d in dirs if d.exists() for p in d.rglob("*")}


class _DiskGuard:
    """Records any file write or temp-file creation while active."""

    def __init__(self, monkeypatch):
        self.monkeypatch = monkeypatch
        self.writes: list[str] = []
        self.watched = [Path(tempfile.gettempdir()), Path.cwd()]

    def __enter__(self):
        real_open, real_os_open = builtins.open, os.open
        writes = self.writes

        def guarded_open(file, mode="r", *args, **kwargs):
            if any(flag in str(mode) for flag in "wax+"):
                writes.append(f"open({file!r}, {mode!r})")
            return real_open(file, mode, *args, **kwargs)

        def guarded_os_open(path, flags, *args, **kwargs):
            if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT):
                writes.append(f"os.open({path!r})")
            return real_os_open(path, flags, *args, **kwargs)

        def no_temp(*_a, **_k):
            writes.append("tempfile")
            raise AssertionError("uploads must not be spooled to disk")

        self.before = _snapshot(self.watched)
        self.monkeypatch.setattr(builtins, "open", guarded_open)
        self.monkeypatch.setattr(os, "open", guarded_os_open)
        # SpooledTemporaryFile.rollover() calls tempfile.TemporaryFile, so a spill to disk is caught here
        for name in ("NamedTemporaryFile", "TemporaryFile", "mkstemp", "mkdtemp"):
            self.monkeypatch.setattr(tempfile, name, no_temp)
        return self

    def __exit__(self, *exc):
        self.monkeypatch.undo()
        self.after = _snapshot(self.watched)
        return False


def test_large_upload_stays_in_memory(client, monkeypatch):
    """A clip well over Starlette's default 1 MB spool threshold is still never written to disk."""
    clip = synthetic_clip(seconds=2.0, fps=10, noise=True)
    if len(clip) < 1_500_000:
        pytest.skip(f"synthetic noise clip only {len(clip)} bytes")
    with _DiskGuard(monkeypatch) as guard:
        r = client.post("/infer-video", files={"file": ("big.mp4", clip, "video/mp4")}, data={"fps": "1"})
    assert r.status_code == 200, r.text
    assert guard.writes == []
    assert guard.after == guard.before


def test_batched_model_scores_match_single_image_scores(client):
    import inference as inf

    pngs = [video.frame_png(frame.rgb) for frame in video.sample_frames(
        synthetic_clip(seconds=2.0, fps=10, noise=True), 2.0, 8, video.ClipInfo())]
    batched = inf.run_forgery_assessments(pngs)
    assert len(batched) == len(pngs) >= 3
    for png, got in zip(pngs, batched):
        want = inf.run_forgery_assessment(png)
        assert got["forgery_score"] == pytest.approx(want["forgery_score"], abs=1e-5)
        assert got["generation_family"] == want["generation_family"]


def test_oversized_video_is_rejected(client, monkeypatch):
    monkeypatch.setattr(video, "MAX_BYTES", 1024)
    r = client.post("/infer-video", files={"file": ("x.mp4", synthetic_clip(1.0), "video/mp4")})
    assert r.status_code == 413


def test_infer_video_returns_timeline_and_one_aggregate_without_touching_disk(client, monkeypatch):
    """DoD: a synthetic clip gets a per-frame timeline and a single aggregate; no frame is written to disk."""
    clip = synthetic_clip(seconds=3.0, fps=10)
    writes: list[str] = []
    real_open, real_os_open = builtins.open, os.open

    def guarded_open(file, mode="r", *args, **kwargs):
        if any(flag in str(mode) for flag in "wax+"):
            writes.append(f"open({file!r}, {mode!r})")
        return real_open(file, mode, *args, **kwargs)

    def guarded_os_open(path, flags, *args, **kwargs):
        if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT):
            writes.append(f"os.open({path!r})")
        return real_os_open(path, flags, *args, **kwargs)

    def no_temp(*_a, **_k):
        writes.append("tempfile")
        raise AssertionError("video frames must not be spooled to disk")

    watched = [Path(tempfile.gettempdir()), Path.cwd()]
    before = _snapshot(watched)
    monkeypatch.setattr(builtins, "open", guarded_open)
    monkeypatch.setattr(os, "open", guarded_os_open)
    for name in ("NamedTemporaryFile", "TemporaryFile", "mkstemp", "mkdtemp"):
        monkeypatch.setattr(tempfile, name, no_temp)

    r = client.post(
        "/infer-video",
        files={"file": ("clip.mp4", clip, "video/mp4")},
        data={"fps": "2", "top_k": "3"},
    )
    monkeypatch.undo()

    assert r.status_code == 200, r.text
    body = r.json()
    assert writes == []
    assert _snapshot(watched) == before

    assert body["media_type"] == "video"
    timeline = body["timeline"]
    assert len(timeline) == body["frames"]["sampled"] == 6
    assert [f["t"] for f in timeline] == pytest.approx([0.0, 0.5, 1.0, 1.5, 2.0, 2.5], abs=0.01)
    forgery = sorted((f["forgery_score"] for f in timeline), reverse=True)
    agg = body["aggregate"]
    assert agg["forgery"]["max"] == pytest.approx(forgery[0])
    assert agg["forgery"]["topk_mean"] == pytest.approx(sum(forgery[:3]) / 3)
    assert agg["explicit"]["max"] == pytest.approx(max(f["explicit_score"] for f in timeline))
    assert body["forgery_score"] == pytest.approx(agg["forgery"]["topk_mean"])
    assert body["explicit_score"] == pytest.approx(agg["explicit"]["max"])
    assert timeline[body["peak"]["index"]]["forgery_score"] == pytest.approx(forgery[0])
    assert body["content_hash"].startswith("sha256:") and len(body["phash"]) >= 8
    assert len(body["heatmap"]) > 100 and body["forensics"]["cam"]["grid"]
    assert body["frames"]["codec"] == "mpeg4"


def test_infer_video_rejects_non_video(client):
    r = client.post("/infer-video", files={"file": ("x.mp4", b"nope" * 100, "video/mp4")})
    assert r.status_code == 422
    r = client.post("/infer-video", files={"file": ("x.mp4", synthetic_clip(1.0), "video/mp4")}, data={"fps": "0"})
    assert r.status_code == 422
