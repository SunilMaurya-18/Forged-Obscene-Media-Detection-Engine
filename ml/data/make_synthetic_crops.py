"""Create a tiny synthetic crop dataset + splits for smoke training (no licensed data)."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import cv2
import numpy as np

from build_splits import run as build_splits


def _make_crop(label: str, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    img = np.zeros((224, 224, 3), dtype=np.uint8)
    if label == "real":
        # Smooth low-frequency gradient (more "natural")
        for c in range(3):
            ramp = np.linspace(40 + c * 20, 180 - c * 10, 224, dtype=np.float32)
            img[:, :, c] = ramp.astype(np.uint8)[None, :]
        noise = rng.normal(0, 3, img.shape).astype(np.float32)
    else:
        # High-frequency checker / JPEG-like blocks (more "forged")
        tile = 8
        for y in range(0, 224, tile):
            for x in range(0, 224, tile):
                color = rng.integers(0, 255, size=3)
                img[y : y + tile, x : x + tile] = color
        noise = rng.normal(0, 12, img.shape).astype(np.float32)
    out = np.clip(img.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    return out


def make_synthetic_workspace(root: Path, n_per_class: int = 24, seed: int = 42) -> dict:
    """
    Layout:
      root/crops/{dataset}/{label}/crop_XXXXXX.jpg
      root/crops/labels.csv
      root/splits/{train,val,test_dfdc,test_celeb-df}.csv
    """
    crops = root / "crops"
    labels_rows: list[dict[str, str]] = []
    idx = 0
    specs = [
        ("faceforensics++", "real", n_per_class),
        ("faceforensics++", "fake", n_per_class),
        ("dfdc", "real", max(4, n_per_class // 4)),
        ("dfdc", "fake", max(4, n_per_class // 4)),
        ("celeb-df", "real", max(4, n_per_class // 4)),
        ("celeb-df", "fake", max(4, n_per_class // 4)),
    ]
    for dataset, label, n in specs:
        for i in range(n):
            rel = Path(dataset) / label / f"crop_{idx:06d}.jpg"
            path = crops / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            img = _make_crop(label, seed=seed + idx)
            cv2.imwrite(str(path), img)
            labels_rows.append(
                {
                    "path": rel.as_posix(),
                    "label": label,
                    "dataset": dataset,
                    "source_frame": f"synth_{idx}.jpg",
                }
            )
            idx += 1

    labels_csv = crops / "labels.csv"
    with labels_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["path", "label", "dataset", "source_frame"])
        w.writeheader()
        w.writerows(labels_rows)

    splits = root / "splits"
    meta = build_splits(crops, splits, seed=seed, val_ratio=0.25)
    return {"crops": crops, "splits": splits, "meta": meta, "labels": labels_csv}


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--n", type=int, default=24)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    info = make_synthetic_workspace(args.out.resolve(), n_per_class=args.n, seed=args.seed)
    print(json.dumps({"crops": str(info["crops"]), "splits": str(info["splits"]), "meta": info["meta"]}, indent=2))
