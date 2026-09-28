#!/usr/bin/env python3
"""Phase 22: per-group error rates for the forgery detector.

Reads optional annotation columns from the split CSVs (e.g. ``skin_tone``, ``age_band``,
``gender_presentation``) and reports FPR / FNR / AUC per group with bootstrap confidence intervals.
A false "forged" verdict harms the person pictured, so FPR disparity is the headline number.

Groups smaller than ``min_n`` (or missing one class) are reported as ``insufficient_data`` rather
than as a noisy rate. Splits without annotations produce ``status: not_run`` with the reason; the
tool never invents groups.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import CropCsvDataset, default_eval_transform, read_split_csv  # noqa: E402
from evaluate import collect_predictions, resolve_eval_csvs, roc_auc  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402

DEFAULT_ATTRIBUTES = ("skin_tone", "age_band", "gender_presentation")
DEFAULT_MIN_N = 30


def error_rates(probs: np.ndarray, labels: np.ndarray, threshold: float) -> tuple[float | None, float | None]:
    preds = probs >= threshold
    real, fake = labels == 0, labels == 1
    fpr = float(np.mean(preds[real])) if real.any() else None
    fnr = float(np.mean(~preds[fake])) if fake.any() else None
    return fpr, fnr


def bootstrap_ci(
    probs: np.ndarray, labels: np.ndarray, threshold: float, n_boot: int, rng: np.random.Generator
) -> dict[str, list[float] | None]:
    """95% percentile intervals for FPR and FNR, resampling within each class."""
    real_idx, fake_idx = np.flatnonzero(labels == 0), np.flatnonzero(labels == 1)
    fprs, fnrs = [], []
    for _ in range(n_boot):
        idx = np.concatenate(
            [rng.choice(real_idx, len(real_idx), replace=True), rng.choice(fake_idx, len(fake_idx), replace=True)]
        )
        fpr, fnr = error_rates(probs[idx], labels[idx], threshold)
        fprs.append(fpr)
        fnrs.append(fnr)
    return {
        "fpr_ci95": [float(np.percentile(fprs, 2.5)), float(np.percentile(fprs, 97.5))],
        "fnr_ci95": [float(np.percentile(fnrs, 2.5)), float(np.percentile(fnrs, 97.5))],
    }


def group_metrics(
    probs: np.ndarray,
    labels: np.ndarray,
    groups: list[str],
    *,
    threshold: float = 0.5,
    min_n: int = DEFAULT_MIN_N,
    n_boot: int = 500,
    seed: int = 42,
) -> dict:
    rng = np.random.default_rng(seed)
    groups_arr = np.asarray(groups, dtype=object)
    out: dict[str, dict] = {}
    for g in sorted({x for x in groups if x}):
        mask = groups_arr == g
        p, y = probs[mask], labels[mask]
        n_real, n_fake = int((y == 0).sum()), int((y == 1).sum())
        entry: dict = {"n": int(mask.sum()), "n_real": n_real, "n_fake": n_fake}
        if entry["n"] < min_n or n_real == 0 or n_fake == 0:
            entry["status"] = "insufficient_data"
        else:
            fpr, fnr = error_rates(p, y, threshold)
            auc = roc_auc(y, p)
            entry.update(
                {
                    "status": "ok",
                    "fpr": fpr,
                    "fnr": fnr,
                    "auc": auc if auc == auc else None,
                    **bootstrap_ci(p, y, threshold, n_boot, rng),
                }
            )
        out[g] = entry

    ok = {g: e for g, e in out.items() if e["status"] == "ok"}
    disparity = None
    if len(ok) >= 2:
        disparity = {
            metric: {
                "max_minus_min": float(max(e[metric] for e in ok.values()) - min(e[metric] for e in ok.values())),
                "worst_group": max(ok, key=lambda k: ok[k][metric]),
            }
            for metric in ("fpr", "fnr")
        }
    return {"groups": out, "disparity": disparity}


def fairness_report(
    rows: list[dict[str, str]],
    probs: np.ndarray,
    labels: np.ndarray,
    *,
    attributes: tuple[str, ...] = DEFAULT_ATTRIBUTES,
    threshold: float = 0.5,
    min_n: int = DEFAULT_MIN_N,
    n_boot: int = 500,
) -> dict:
    present = [a for a in attributes if any((r.get(a) or "").strip() for r in rows)]
    base = {"threshold": threshold, "min_group_size": min_n, "attributes_requested": list(attributes)}
    if not present:
        return {
            **base,
            "status": "not_run",
            "reason": (
                "The evaluation split has no demographic annotations (columns: "
                + ", ".join(attributes)
                + "). Add them to the split CSVs from an annotated research set; no groups are inferred."
            ),
        }
    by_attribute = {}
    for attr in present:
        groups = [(r.get(attr) or "").strip() for r in rows]
        by_attribute[attr] = {
            "n_annotated": sum(1 for g in groups if g),
            **group_metrics(probs, labels, groups, threshold=threshold, min_n=min_n, n_boot=n_boot),
        }
    return {**base, "status": "ok", "attributes": by_attribute}


def run_fairness(
    checkpoint: Path,
    crops_root: Path,
    splits_dir: Path,
    *,
    attributes: tuple[str, ...] = DEFAULT_ATTRIBUTES,
    threshold: float = 0.5,
    min_n: int = DEFAULT_MIN_N,
    batch_size: int = 16,
    device: str | None = None,
) -> dict:
    rows: list[dict[str, str]] = []
    for csv_path in resolve_eval_csvs(splits_dir).values():
        rows.extend(read_split_csv(csv_path))
    if not any((r.get(a) or "").strip() for r in rows for a in attributes):
        return fairness_report(rows, np.zeros(len(rows)), np.zeros(len(rows), dtype=np.int64), attributes=attributes)

    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    model, _ = load_checkpoint(checkpoint, device=device_t)
    loader = DataLoader(CropCsvDataset(rows, crops_root, default_eval_transform()), batch_size=batch_size)
    probs, labels = collect_predictions(model, loader, device_t)
    return fairness_report(rows, probs, labels, attributes=attributes, threshold=threshold, min_n=min_n)


def main() -> None:
    parser = argparse.ArgumentParser(description="Per-group error rates for the forgery detector.")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--attributes", type=str, default=",".join(DEFAULT_ATTRIBUTES))
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--min-n", type=int, default=DEFAULT_MIN_N)
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    report = run_fairness(
        args.checkpoint.resolve(),
        args.crops.resolve(),
        args.splits.resolve(),
        attributes=tuple(a.strip() for a in args.attributes.split(",") if a.strip()),
        threshold=args.threshold,
        min_n=args.min_n,
        device=args.device,
    )
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
