#!/usr/bin/env python3
"""Extract frames from videos at a fixed FPS."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import cv2


VIDEO_EXTS = {".mp4", ".avi", ".mov", ".mkv", ".webm"}


def iter_videos(root: Path) -> list[Path]:
    return sorted(
        p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in VIDEO_EXTS
    )


def relative_label_path(video: Path, input_root: Path) -> Path:
    """Preserve dataset / real-fake hierarchy under the frames output."""
    return video.relative_to(input_root).with_suffix("")


def extract_frames(video_path: Path, out_dir: Path, fps: float) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video: {video_path}")

    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    interval = max(src_fps / fps, 1.0)
    saved: list[Path] = []
    frame_idx = 0
    next_keep = 0.0

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx >= next_keep:
            name = f"frame_{len(saved):06d}.jpg"
            path = out_dir / name
            cv2.imwrite(str(path), frame)
            saved.append(path)
            next_keep += interval
        frame_idx += 1

    cap.release()
    return saved


def run(input_root: Path, output_root: Path, fps: float) -> Path:
    output_root.mkdir(parents=True, exist_ok=True)
    manifest_path = output_root / "frames_manifest.csv"
    videos = iter_videos(input_root)
    if not videos:
        raise FileNotFoundError(f"No videos found under {input_root}")

    rows: list[dict[str, str]] = []
    for video in videos:
        rel = relative_label_path(video, input_root)
        out_dir = output_root / rel
        paths = extract_frames(video, out_dir, fps)
        for p in paths:
            rows.append(
                {
                    "video": str(video.relative_to(input_root).as_posix()),
                    "frame": str(p.relative_to(output_root).as_posix()),
                }
            )

    with manifest_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["video", "frame"])
        writer.writeheader()
        writer.writerows(rows)

    return manifest_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract frames from videos at N fps.")
    parser.add_argument("--input", type=Path, required=True, help="Root with videos")
    parser.add_argument("--output", type=Path, required=True, help="Frames output root")
    parser.add_argument("--fps", type=float, default=1.0, help="Target frames per second")
    args = parser.parse_args()
    manifest = run(args.input.resolve(), args.output.resolve(), args.fps)
    print(f"Wrote {manifest}")


if __name__ == "__main__":
    main()
