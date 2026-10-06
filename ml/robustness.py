#!/usr/bin/env python3
"""Phase 22: forgery-score stability under the transforms real uploads go through.

Each transform is applied to the image before scoring. For every transform we report AUC and
accuracy, and against the untouched baseline: the mean absolute score shift and the decision flip
rate at the routing threshold (how often re-encoding alone would change the outcome).

Since Phase 29 the score is the one ml-service serves (the CNN, or the CNN + spectral ensemble when
``models/ensemble.json`` is next to the checkpoint), and only held-out test splits are used:
``val.csv`` selects checkpoints and fits the ensemble, so it cannot back a robustness claim.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import LABEL_TO_IDX, read_split_csv  # noqa: E402
from degradations import (  # noqa: E402
    Perturb,
    blur,
    center_crop,
    jpeg,
    rescale,
    screenshot,
    social_transcode,
    webp,
    webp_available,
)
from evaluate import compute_metrics, resolve_eval_csvs  # noqa: E402
from scoring import ServedScorer  # noqa: E402

TRANSFORMS: dict[str, Perturb] = {
    "jpeg_q90": jpeg(90),
    "jpeg_q70": jpeg(70),
    "jpeg_q50": jpeg(50),
    "rescale_0.5": rescale(0.5),
    "center_crop_0.8": center_crop(0.8),
    "blur_r1.5": blur(1.5),
    "screenshot": screenshot,
    "social_transcode": social_transcode,
}
if webp_available():
    TRANSFORMS["webp_q60"] = webp(60)

# Never applied by RandomDegradation during training
HELD_OUT_TRANSFORMS = tuple(name for name in ("webp_q60", "center_crop_0.8") if name in TRANSFORMS)


def robustness_csvs(splits_dir: Path) -> dict[str, Path]:
    """Held-out test splits (GAN-era ``test_*.csv`` and ``diffusion/test.csv``); val only as a last resort."""
    held_out = {p.stem.removeprefix("test_"): p for p in sorted(splits_dir.glob("test_*.csv"))}
    diffusion = splits_dir / "diffusion" / "test.csv"
    if diffusion.exists():
        held_out["diffusion"] = diffusion
    return held_out or resolve_eval_csvs(splits_dir)


def stability(baseline: np.ndarray, perturbed: np.ndarray, labels: np.ndarray, threshold: float) -> dict:
    flips = (baseline >= threshold) != (perturbed >= threshold)
    shift = perturbed - baseline
    out = {
        "mean_abs_shift": float(np.mean(np.abs(shift))) if len(shift) else 0.0,
        "flip_rate": float(np.mean(flips)) if len(flips) else 0.0,
    }
    for name, idx in (("real", 0), ("fake", 1)):
        mask = labels == idx
        out[f"mean_shift_{name}"] = float(np.mean(shift[mask])) if mask.any() else None
    return out


def robustness_report(
    baseline_probs: np.ndarray,
    labels: np.ndarray,
    perturbed: dict[str, np.ndarray],
    threshold: float = 0.5,
) -> dict:
    base_metrics = compute_metrics(baseline_probs, labels, threshold)
    per_transform = {}
    for name, probs in perturbed.items():
        m = compute_metrics(probs, labels, threshold)
        per_transform[name] = {
            "auc": m["auc"],
            "accuracy": m["accuracy"],
            "auc_drop": (
                float(base_metrics["auc"] - m["auc"])
                if base_metrics["auc"] == base_metrics["auc"] and m["auc"] == m["auc"]
                else None
            ),
            **stability(baseline_probs, probs, labels, threshold),
        }
    worst = max(per_transform, key=lambda k: per_transform[k]["flip_rate"]) if per_transform else None
    return {
        "threshold": threshold,
        "n": int(len(labels)),
        "baseline": {"auc": base_metrics["auc"], "accuracy": base_metrics["accuracy"]},
        "transforms": per_transform,
        "worst_transform": worst,
    }


def score_rows(
    scorer: ServedScorer,
    rows: list[dict[str, str]],
    crops_root: Path,
    perturb: Perturb | None,
    batch_size: int = 16,
) -> tuple[np.ndarray, np.ndarray]:
    probs: list[np.ndarray] = []
    for start in range(0, len(rows), batch_size):
        images = []
        for row in rows[start : start + batch_size]:
            image = Image.open(Path(crops_root) / row["path"]).convert("RGB")
            images.append(perturb(image) if perturb is not None else image)
        probs.append(scorer.score(images)["served"])
    labels = np.asarray([LABEL_TO_IDX[r["label"].lower()] for r in rows], dtype=np.int64)
    return (np.concatenate(probs) if probs else np.zeros(0)), labels


def run_robustness(
    checkpoint: Path,
    crops_root: Path,
    splits_dir: Path,
    *,
    threshold: float = 0.5,
    batch_size: int = 16,
    device: str | None = None,
    transforms: dict[str, Perturb] | None = None,
    ensemble: Path | None | bool = True,
) -> dict:
    """``ensemble``: True picks up ``ensemble.json`` next to the checkpoint, False scores the CNN alone,
    or a path to a specific ensemble file."""
    scorer = ServedScorer.load(checkpoint, ensemble=ensemble, device=device)
    csvs = robustness_csvs(splits_dir)
    rows: list[dict[str, str]] = []
    for csv_path in csvs.values():
        rows.extend(read_split_csv(csv_path))
    if not rows:
        raise FileNotFoundError(f"No evaluation CSVs found under {splits_dir}")

    baseline, labels = score_rows(scorer, rows, crops_root, None, batch_size)
    perturbed = {}
    for name, fn in (transforms or TRANSFORMS).items():
        perturbed[name], _ = score_rows(scorer, rows, crops_root, fn, batch_size)
        print(f"{name}: flip_rate={np.mean((baseline >= threshold) != (perturbed[name] >= threshold)):.3f}")
    report = robustness_report(baseline, labels, perturbed, threshold)
    report["eval_set"] = {name: len(read_split_csv(p)) for name, p in csvs.items()}
    report["held_out_transforms"] = [t for t in HELD_OUT_TRANSFORMS if t in perturbed]
    report["model"] = scorer.describe()
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Forgery-score robustness under real-world transforms.")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", type=str, default=None)
    parser.add_argument("--cnn-only", action="store_true", help="ignore ensemble.json and score the CNN alone")
    args = parser.parse_args()
    report = run_robustness(
        args.checkpoint.resolve(),
        args.crops.resolve(),
        args.splits.resolve(),
        threshold=args.threshold,
        batch_size=args.batch_size,
        device=args.device,
        ensemble=not args.cnn_only,
    )
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
