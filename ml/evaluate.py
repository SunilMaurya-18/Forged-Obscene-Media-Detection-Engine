#!/usr/bin/env python3
"""Evaluate forgery model on FF++ + held-out DFDC / Celeb-DF splits."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import CropCsvDataset, default_eval_transform, read_split_csv
from models.forgery_model import load_checkpoint


def _json_safe(obj):
    if isinstance(obj, float) and (obj != obj):  # NaN
        return None
    if isinstance(obj, dict):
        return {k: _json_safe(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_json_safe(v) for v in obj]
    return obj


@torch.no_grad()
def collect_predictions(
    model: nn.Module, loader: DataLoader, device: torch.device
) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    probs: list[float] = []
    labels: list[int] = []
    for images, y, _ in loader:
        images = images.to(device)
        p = torch.sigmoid(model(images)).cpu().view(-1).numpy()
        probs.extend(p.tolist())
        labels.extend(y.view(-1).tolist())
    return np.asarray(probs, dtype=np.float64), np.asarray(labels, dtype=np.int64)


def roc_auc(labels: np.ndarray, probs: np.ndarray) -> float:
    """Trapezoidal ROC-AUC without sklearn."""
    y = labels.astype(np.int64)
    if len(np.unique(y)) < 2:
        return float("nan")
    order = np.argsort(-probs)
    y_sorted = y[order]
    n_pos = float((y == 1).sum())
    n_neg = float((y == 0).sum())
    tps = np.cumsum(y_sorted == 1)
    fps = np.cumsum(y_sorted == 0)
    tpr = np.concatenate([[0.0], tps / n_pos])
    fpr = np.concatenate([[0.0], fps / n_neg])
    return float(np.trapezoid(tpr, fpr) if hasattr(np, "trapezoid") else np.trapz(tpr, fpr))


def compute_metrics(probs: np.ndarray, labels: np.ndarray, threshold: float = 0.5) -> dict:
    if len(labels) == 0:
        return {
            "accuracy": 0.0,
            "precision": 0.0,
            "recall": 0.0,
            "f1": 0.0,
            "auc": 0.0,
            "n": 0,
        }
    preds = (probs >= threshold).astype(np.int64)
    tp = int(((preds == 1) & (labels == 1)).sum())
    tn = int(((preds == 0) & (labels == 0)).sum())
    fp = int(((preds == 1) & (labels == 0)).sum())
    fn = int(((preds == 0) & (labels == 1)).sum())
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    acc = (tp + tn) / max(tp + tn + fp + fn, 1)
    return {
        "accuracy": float(acc),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "auc": roc_auc(labels, probs),
        "n": int(len(labels)),
    }


def resolve_eval_csvs(splits_dir: Path) -> dict[str, Path]:
    """Map dataset name → CSV used for evaluation."""
    mapping: dict[str, Path] = {}
    ff_test = splits_dir / "test_faceforensics++.csv"
    if ff_test.exists():
        mapping["faceforensics++"] = ff_test
    else:
        val = splits_dir / "val.csv"
        if val.exists():
            mapping["faceforensics++"] = val
    for name in ("dfdc", "celeb-df"):
        path = splits_dir / f"test_{name}.csv"
        if path.exists():
            mapping[name] = path
    return mapping


def run_evaluation(
    checkpoint: Path,
    crops_root: Path,
    splits_dir: Path,
    results_path: Path,
    *,
    batch_size: int = 16,
    device: str | None = None,
) -> dict:
    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    model, _payload = load_checkpoint(checkpoint, device=device_t)
    datasets = resolve_eval_csvs(splits_dir)
    if not datasets:
        raise FileNotFoundError(f"No evaluation CSVs found under {splits_dir}")

    per_dataset: dict[str, dict] = {}
    for name, csv_path in datasets.items():
        rows = read_split_csv(csv_path)
        loader = DataLoader(
            CropCsvDataset(rows, crops_root, default_eval_transform()),
            batch_size=batch_size,
            shuffle=False,
        )
        probs, labels = collect_predictions(model, loader, device_t)
        metrics = compute_metrics(probs, labels)
        per_dataset[name] = metrics
        print(
            f"{name}: acc={metrics['accuracy']:.4f} prec={metrics['precision']:.4f} "
            f"rec={metrics['recall']:.4f} f1={metrics['f1']:.4f} "
            f"auc={metrics['auc']} n={metrics['n']}"
        )

    ff = per_dataset.get("faceforensics++", {})
    gaps = {}
    for holdout in ("dfdc", "celeb-df"):
        if holdout in per_dataset and ff:
            ff_auc = ff.get("auc")
            ho_auc = per_dataset[holdout].get("auc")
            auc_gap = (
                float(ff_auc - ho_auc)
                if isinstance(ff_auc, float)
                and isinstance(ho_auc, float)
                and ff_auc == ff_auc
                and ho_auc == ho_auc
                else float("nan")
            )
            gaps[holdout] = {
                "accuracy_gap": float(ff.get("accuracy", 0) - per_dataset[holdout]["accuracy"]),
                "auc_gap": auc_gap,
                "f1_gap": float(ff.get("f1", 0) - per_dataset[holdout]["f1"]),
            }

    results = {
        "checkpoint": str(checkpoint),
        "datasets": per_dataset,
        "generalization_gap": gaps,
        "notes": (
            "Train/val from FaceForensics++ only; DFDC and Celeb-DF are held-out "
            "cross-dataset tests. Gaps are (FF++ − holdout)."
        ),
    }
    results_path.parent.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(_json_safe(results), indent=2), encoding="utf-8")
    print(f"Wrote {results_path}")
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description="Cross-dataset forgery evaluation.")
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=ROOT / "models" / "checkpoint.pt",
    )
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument(
        "--results",
        type=Path,
        default=ROOT / "results" / "metrics.json",
    )
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    run_evaluation(
        args.checkpoint.resolve(),
        args.crops.resolve(),
        args.splits.resolve(),
        args.results.resolve(),
        batch_size=args.batch_size,
        device=args.device,
    )


if __name__ == "__main__":
    main()
