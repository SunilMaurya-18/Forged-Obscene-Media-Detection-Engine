#!/usr/bin/env python3
"""Detect faces (MTCNN) and write aligned 224×224 crops.

Use --fallback-center-crop for synthetic smoke tests when no face detector
weights are available or no face is found.
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import cv2
import numpy as np

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
CROP_SIZE = 224


def iter_images(root: Path) -> list[Path]:
    return sorted(
        p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTS
    )


def center_crop_resize(image: np.ndarray, size: int = CROP_SIZE) -> np.ndarray:
    h, w = image.shape[:2]
    side = min(h, w)
    y0 = (h - side) // 2
    x0 = (w - side) // 2
    square = image[y0 : y0 + side, x0 : x0 + side]
    return cv2.resize(square, (size, size), interpolation=cv2.INTER_AREA)


def align_from_box(
    image: np.ndarray, box: tuple[float, float, float, float], size: int = CROP_SIZE
) -> np.ndarray:
    """Expand a detection box slightly and warp to a square crop."""
    h, w = image.shape[:2]
    x1, y1, x2, y2 = box
    bw, bh = x2 - x1, y2 - y1
    cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    side = max(bw, bh) * 1.3
    nx1 = int(max(cx - side / 2, 0))
    ny1 = int(max(cy - side / 2, 0))
    nx2 = int(min(cx + side / 2, w))
    ny2 = int(min(cy + side / 2, h))
    crop = image[ny1:ny2, nx1:nx2]
    if crop.size == 0:
        return center_crop_resize(image, size)
    return cv2.resize(crop, (size, size), interpolation=cv2.INTER_AREA)


class FaceDetector:
    """MTCNN when available; optional center-crop fallback for CI/smoke tests."""

    def __init__(self, fallback_center_crop: bool = False) -> None:
        self.fallback_center_crop = fallback_center_crop
        self._mtcnn = None
        try:
            from mtcnn import MTCNN  # type: ignore

            self._mtcnn = MTCNN()
        except Exception:
            if not fallback_center_crop:
                raise RuntimeError(
                    "MTCNN unavailable. Install `mtcnn`/`tensorflow` or pass "
                    "--fallback-center-crop for synthetic tests."
                )

    def detect_largest(self, bgr: np.ndarray) -> np.ndarray | None:
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        if self._mtcnn is not None:
            detections = self._mtcnn.detect_faces(rgb)
            if detections:
                best = max(detections, key=lambda d: d.get("confidence", 0))
                x, y, w, h = best["box"]
                # mtcnn may return negative origins
                x, y = max(x, 0), max(y, 0)
                return align_from_box(bgr, (x, y, x + w, y + h))

        if self.fallback_center_crop:
            return center_crop_resize(bgr)
        return None


def infer_label(relative_path: Path) -> str:
    """Map path segments to real/fake labels."""
    parts = {p.lower() for p in relative_path.parts}
    fake_tokens = {
        "fake",
        "manipulated",
        "deepfakes",
        "face2face",
        "faceswap",
        "neuraltextures",
        "celeb-synthesis",
        "synthesis",
    }
    real_tokens = {"real", "original", "originals", "celeb-real", "pristine"}
    if parts & fake_tokens:
        return "fake"
    if parts & real_tokens:
        return "real"
    # default: parent folder name
    parent = relative_path.parent.name.lower()
    if parent in fake_tokens:
        return "fake"
    return "real"


def infer_dataset(relative_path: Path) -> str:
    parts_lower = [p.lower() for p in relative_path.parts]
    for name in ("faceforensics++", "ff++", "faceforensics", "dfdc", "celeb-df", "celebdf"):
        if name in parts_lower:
            if name in ("ff++", "faceforensics"):
                return "faceforensics++"
            if name == "celebdf":
                return "celeb-df"
            return name
    # top-level folder under frames root
    return relative_path.parts[0] if relative_path.parts else "unknown"


def run(input_root: Path, output_root: Path, fallback_center_crop: bool) -> Path:
    output_root.mkdir(parents=True, exist_ok=True)
    detector = FaceDetector(fallback_center_crop=fallback_center_crop)
    labels_path = output_root / "labels.csv"
    images = iter_images(input_root)
    if not images:
        raise FileNotFoundError(f"No images found under {input_root}")

    rows: list[dict[str, str]] = []
    crop_idx = 0
    for image_path in images:
        rel = image_path.relative_to(input_root)
        bgr = cv2.imread(str(image_path))
        if bgr is None:
            continue
        crop = detector.detect_largest(bgr)
        if crop is None:
            continue

        dataset = infer_dataset(rel)
        label = infer_label(rel)
        out_rel = Path(dataset) / label / f"crop_{crop_idx:06d}.jpg"
        out_path = output_root / out_rel
        out_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(out_path), crop)
        rows.append(
            {
                "path": out_rel.as_posix(),
                "label": label,
                "dataset": dataset,
                "source_frame": rel.as_posix(),
            }
        )
        crop_idx += 1

    with labels_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f, fieldnames=["path", "label", "dataset", "source_frame"]
        )
        writer.writeheader()
        writer.writerows(rows)

    return labels_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Detect + align faces to 224×224.")
    parser.add_argument("--input", type=Path, required=True, help="Frames root")
    parser.add_argument("--output", type=Path, required=True, help="Crops output root")
    parser.add_argument(
        "--fallback-center-crop",
        action="store_true",
        help="Center-crop when MTCNN missing or no face found (smoke tests only)",
    )
    args = parser.parse_args()
    labels = run(args.input.resolve(), args.output.resolve(), args.fallback_center_crop)
    print(f"Wrote {labels}")


if __name__ == "__main__":
    main()
