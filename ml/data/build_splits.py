#!/usr/bin/env python3
"""Build reproducible train/val splits from FaceForensics++ crops only.

DFDC and Celeb-DF are written to held-out test manifests and never enter train/val.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

import numpy as np

TRAIN_DATASETS = {"faceforensics++", "ff++", "faceforensics"}
HOLD_OUT = {"dfdc", "celeb-df", "celebdf"}


def normalize_dataset(name: str) -> str:
    n = name.lower().strip()
    if n in {"ff++", "faceforensics"}:
        return "faceforensics++"
    if n == "celebdf":
        return "celeb-df"
    return n


def load_labels(labels_csv: Path) -> list[dict[str, str]]:
    with labels_csv.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def stratified_split(
    rows: list[dict[str, str]], val_ratio: float, seed: int
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    rng = np.random.default_rng(seed)
    by_label: dict[str, list[dict[str, str]]] = {"real": [], "fake": []}
    for row in rows:
        by_label.setdefault(row["label"], []).append(row)

    train: list[dict[str, str]] = []
    val: list[dict[str, str]] = []
    for label, group in by_label.items():
        idx = np.arange(len(group))
        rng.shuffle(idx)
        n_val = int(round(len(group) * val_ratio)) if len(group) > 1 else 0
        val_idx = set(idx[:n_val].tolist())
        for i, row in enumerate(group):
            (val if i in val_idx else train).append(row)
    return train, val


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["path", "label", "dataset", "split"]
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fields})


def run(crops_root: Path, output_root: Path, seed: int, val_ratio: float) -> dict:
    labels_csv = crops_root / "labels.csv"
    if not labels_csv.exists():
        raise FileNotFoundError(f"Missing {labels_csv}")

    rows = load_labels(labels_csv)
    ff_rows: list[dict[str, str]] = []
    holdout: dict[str, list[dict[str, str]]] = {}

    for row in rows:
        ds = normalize_dataset(row.get("dataset", ""))
        row = {**row, "dataset": ds}
        if ds in TRAIN_DATASETS or ds == "faceforensics++":
            ff_rows.append(row)
        elif ds in HOLD_OUT:
            holdout.setdefault(ds, []).append(row)
        else:
            # Unknown datasets treated as hold-out to avoid train leakage
            holdout.setdefault(ds, []).append(row)

    train, val = stratified_split(ff_rows, val_ratio=val_ratio, seed=seed)
    for r in train:
        r["split"] = "train"
    for r in val:
        r["split"] = "val"

    output_root.mkdir(parents=True, exist_ok=True)
    write_csv(output_root / "train.csv", train)
    write_csv(output_root / "val.csv", val)

    for ds, ds_rows in holdout.items():
        for r in ds_rows:
            r["split"] = "test"
        write_csv(output_root / f"test_{ds}.csv", ds_rows)

    # Combined labels for convenience
    all_rows = train + val
    for ds_rows in holdout.values():
        all_rows.extend(ds_rows)
    write_csv(output_root / "all_splits.csv", all_rows)

    meta = {
        "seed": seed,
        "val_ratio": val_ratio,
        "train_count": len(train),
        "val_count": len(val),
        "holdout_counts": {k: len(v) for k, v in holdout.items()},
        "train_datasets": ["faceforensics++"],
        "holdout_datasets": sorted(holdout.keys()),
    }
    meta_path = output_root / "split_meta.json"
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description="Build FF++ train/val; hold out DFDC/Celeb-DF.")
    parser.add_argument("--crops", type=Path, required=True, help="Crops root with labels.csv")
    parser.add_argument("--output", type=Path, required=True, help="Splits output directory")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--val-ratio", type=float, default=0.2)
    args = parser.parse_args()
    meta = run(args.crops.resolve(), args.output.resolve(), args.seed, args.val_ratio)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
