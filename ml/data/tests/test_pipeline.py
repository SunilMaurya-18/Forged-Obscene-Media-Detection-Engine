"""Phase 1 smoke test: synthetic videos → crops + reproducible splits."""

from __future__ import annotations

import csv
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
DATA = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DATA))

from build_splits import run as build_splits  # noqa: E402
from detect_align_faces import run as detect_align  # noqa: E402
from extract_frames import run as extract_frames  # noqa: E402


def _write_dummy_video(path: Path, color: tuple[int, int, int], frames: int = 8) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(path), fourcc, 4.0, (128, 128))
    assert writer.isOpened(), f"Failed to open VideoWriter for {path}"
    for i in range(frames):
        img = np.zeros((128, 128, 3), dtype=np.uint8)
        img[:] = color
        # Moving square so frames differ
        cv2.rectangle(img, (10 + i * 5, 40), (40 + i * 5, 70), (255, 255, 255), -1)
        writer.write(img)
    writer.release()


def _make_sample_tree(raw: Path) -> None:
    _write_dummy_video(
        raw / "faceforensics++" / "original" / "id000.mp4", (40, 120, 40)
    )
    _write_dummy_video(
        raw / "faceforensics++" / "manipulated" / "id000_fake.mp4", (40, 40, 160)
    )
    _write_dummy_video(raw / "dfdc" / "real" / "dfdc_r0.mp4", (80, 80, 80))
    _write_dummy_video(raw / "dfdc" / "fake" / "dfdc_f0.mp4", (20, 20, 20))
    _write_dummy_video(raw / "celeb-df" / "Celeb-real" / "c_r0.mp4", (90, 60, 30))
    _write_dummy_video(
        raw / "celeb-df" / "Celeb-synthesis" / "c_f0.mp4", (30, 60, 90)
    )


def test_pipeline_produces_labels_and_reproducible_splits():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        raw = root / "raw"
        frames = root / "frames"
        crops = root / "crops"
        splits_a = root / "splits_a"
        splits_b = root / "splits_b"

        _make_sample_tree(raw)

        manifest = extract_frames(raw, frames, fps=2.0)
        assert manifest.exists()
        assert any(frames.rglob("*.jpg"))

        labels = detect_align(frames, crops, fallback_center_crop=True)
        assert labels.exists()
        with labels.open(newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        assert len(rows) > 0
        assert {"real", "fake"} <= {r["label"] for r in rows}
        assert all((crops / r["path"]).exists() for r in rows)

        # Crops are 224×224
        sample = cv2.imread(str(crops / rows[0]["path"]))
        assert sample is not None
        assert sample.shape[:2] == (224, 224)

        meta_a = build_splits(crops, splits_a, seed=42, val_ratio=0.25)
        meta_b = build_splits(crops, splits_b, seed=42, val_ratio=0.25)
        assert meta_a == meta_b

        train_a = (splits_a / "train.csv").read_text(encoding="utf-8")
        train_b = (splits_b / "train.csv").read_text(encoding="utf-8")
        assert train_a == train_b

        # FF++ only in train/val; DFDC + Celeb-DF held out
        with (splits_a / "train.csv").open(newline="", encoding="utf-8") as f:
            train_rows = list(csv.DictReader(f))
        assert all(r["dataset"] == "faceforensics++" for r in train_rows)
        assert (splits_a / "test_dfdc.csv").exists()
        assert (splits_a / "test_celeb-df.csv").exists()

        # Different seed changes assignment when enough samples
        meta_other = build_splits(crops, root / "splits_other", seed=99, val_ratio=0.25)
        assert meta_other["seed"] == 99


def test_split_seed_changes_membership_when_possible():
    """With multiple FF++ crops, seeds 1 and 2 should not always match."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        crops = root / "crops"
        labels = crops / "labels.csv"
        crops.mkdir(parents=True)
        # Enough FF++ rows for a non-trivial stratified split
        rows = []
        for i in range(10):
            label = "real" if i < 5 else "fake"
            rel = Path("faceforensics++") / label / f"crop_{i:03d}.jpg"
            path = crops / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            cv2.imwrite(str(path), np.zeros((224, 224, 3), dtype=np.uint8))
            rows.append(
                {
                    "path": rel.as_posix(),
                    "label": label,
                    "dataset": "faceforensics++",
                    "source_frame": f"f{i}.jpg",
                }
            )
        with labels.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(
                f, fieldnames=["path", "label", "dataset", "source_frame"]
            )
            w.writeheader()
            w.writerows(rows)

        build_splits(crops, root / "s1", seed=1, val_ratio=0.3)
        build_splits(crops, root / "s2", seed=2, val_ratio=0.3)
        t1 = (root / "s1" / "train.csv").read_text(encoding="utf-8")
        t2 = (root / "s2" / "train.csv").read_text(encoding="utf-8")
        # Extremely unlikely to be identical with different seeds
        assert t1 != t2 or t1.count("\n") < 3
