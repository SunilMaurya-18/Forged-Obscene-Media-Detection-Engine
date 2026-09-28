#!/usr/bin/env python3
"""Phase 22: temperature-scaling calibration for the forgery score.

Fits a single temperature T on the validation split so that ``sigmoid(logit / T)`` is a calibrated
probability, and reports expected calibration error (ECE) and Brier score before and after on the
held-out test splits. T only rescales confidence: rankings, AUC and Grad-CAM are unchanged.

A perfectly separable fit set has no finite NLL minimum (the optimum drives T towards 0), so the
fit is reported as degenerate and never promoted to the runtime.
"""

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

from dataset import CropCsvDataset, default_eval_transform, read_split_csv  # noqa: E402
from evaluate import resolve_eval_csvs  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402

T_BOUNDS = (0.05, 20.0)
RUNTIME_CALIBRATION = ROOT / "models" / "calibration.json"


def sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(z, -60.0, 60.0)))


def nll(logits: np.ndarray, labels: np.ndarray, temperature: float) -> float:
    """Mean binary cross-entropy of ``sigmoid(logits / T)``, computed stably from logits."""
    z = logits / temperature
    y = labels.astype(np.float64)
    return float(np.mean(np.maximum(z, 0.0) - z * y + np.log1p(np.exp(-np.abs(z)))))


def is_separable(logits: np.ndarray, labels: np.ndarray) -> bool:
    pos = logits[labels == 1]
    neg = logits[labels == 0]
    if len(pos) == 0 or len(neg) == 0:
        return True
    return bool(neg.max() < pos.min())


def fit_temperature(
    logits: np.ndarray, labels: np.ndarray, bounds: tuple[float, float] = T_BOUNDS
) -> tuple[float, bool]:
    """Minimise NLL over log T (coarse grid, then golden-section refine).

    Returns ``(temperature, degenerate)``; degenerate when the fit set is separable or the optimum
    sits on a bound, in which case the temperature is not meaningful.
    """
    logits = np.asarray(logits, dtype=np.float64)
    labels = np.asarray(labels, dtype=np.int64)
    if is_separable(logits, labels):
        return 1.0, True

    lo, hi = np.log(bounds[0]), np.log(bounds[1])
    grid = np.linspace(lo, hi, 200)
    losses = [nll(logits, labels, float(np.exp(g))) for g in grid]
    i = int(np.argmin(losses))
    a, b = grid[max(i - 1, 0)], grid[min(i + 1, len(grid) - 1)]
    ratio = (np.sqrt(5.0) - 1.0) / 2.0
    for _ in range(60):
        c = b - ratio * (b - a)
        d = a + ratio * (b - a)
        if nll(logits, labels, float(np.exp(c))) < nll(logits, labels, float(np.exp(d))):
            b = d
        else:
            a = c
    t = float(np.exp((a + b) / 2.0))
    on_bound = i in (0, len(grid) - 1)
    return t, on_bound


def reliability_bins(probs: np.ndarray, labels: np.ndarray, n_bins: int = 10) -> list[dict]:
    """Equal-width bins over P(fake): mean predicted probability vs observed fraction of fakes."""
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    idx = np.clip(np.digitize(probs, edges[1:-1], right=False), 0, n_bins - 1)
    bins = []
    for b in range(n_bins):
        mask = idx == b
        n = int(mask.sum())
        bins.append(
            {
                "lo": float(edges[b]),
                "hi": float(edges[b + 1]),
                "n": n,
                "mean_prob": float(probs[mask].mean()) if n else None,
                "frac_fake": float(labels[mask].mean()) if n else None,
            }
        )
    return bins


def expected_calibration_error(probs: np.ndarray, labels: np.ndarray, n_bins: int = 10) -> float:
    total = len(probs)
    if total == 0:
        return 0.0
    return float(
        sum(
            b["n"] / total * abs(b["mean_prob"] - b["frac_fake"])
            for b in reliability_bins(probs, labels, n_bins)
            if b["n"]
        )
    )


def brier_score(probs: np.ndarray, labels: np.ndarray) -> float:
    return float(np.mean((probs - labels.astype(np.float64)) ** 2)) if len(probs) else 0.0


def calibration_report(
    fit_logits: np.ndarray,
    fit_labels: np.ndarray,
    eval_logits: np.ndarray,
    eval_labels: np.ndarray,
    n_bins: int = 10,
) -> dict:
    temperature, degenerate = fit_temperature(fit_logits, fit_labels)
    applied = 1.0 if degenerate else temperature
    before = sigmoid(eval_logits)
    after = sigmoid(eval_logits / applied)
    return {
        "method": "temperature scaling (NLL on validation split)",
        "temperature": round(applied, 4),
        "fitted_temperature": None if degenerate else round(temperature, 4),
        "degenerate": degenerate,
        "degenerate_reason": (
            "Fit set is perfectly separable (or the optimum hits a bound), so no finite temperature "
            "minimises NLL. Calibration needs a validation set with overlapping scores, e.g. licensed "
            "FF++/DFDC/Celeb-DF crops."
            if degenerate
            else None
        ),
        "n_fit": int(len(fit_labels)),
        "n_eval": int(len(eval_labels)),
        "n_bins": n_bins,
        "ece_before": round(expected_calibration_error(before, eval_labels, n_bins), 4),
        "ece_after": round(expected_calibration_error(after, eval_labels, n_bins), 4),
        "brier_before": round(brier_score(before, eval_labels), 4),
        "brier_after": round(brier_score(after, eval_labels), 4),
        "reliability_before": reliability_bins(before, eval_labels, n_bins),
        "reliability_after": reliability_bins(after, eval_labels, n_bins),
    }


@torch.no_grad()
def collect_logits(model: nn.Module, loader: DataLoader, device: torch.device) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    logits: list[float] = []
    labels: list[int] = []
    for images, y, _ in loader:
        logits.extend(model(images.to(device)).cpu().view(-1).tolist())
        labels.extend(y.view(-1).tolist())
    return np.asarray(logits, dtype=np.float64), np.asarray(labels, dtype=np.int64)


def _logits_for(model, rows, crops_root: Path, device, batch_size: int):
    loader = DataLoader(CropCsvDataset(rows, crops_root, default_eval_transform()), batch_size=batch_size)
    return collect_logits(model, loader, device)


def run_calibration(
    checkpoint: Path,
    crops_root: Path,
    splits_dir: Path,
    *,
    batch_size: int = 16,
    device: str | None = None,
    promote: bool = False,
) -> dict:
    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    model, _ = load_checkpoint(checkpoint, device=device_t)

    fit_csv = splits_dir / "val.csv"
    if not fit_csv.exists():
        raise FileNotFoundError(f"Calibration needs a validation split: {fit_csv}")
    fit_logits, fit_labels = _logits_for(model, read_split_csv(fit_csv), crops_root, device_t, batch_size)

    eval_rows = []
    for csv_path in resolve_eval_csvs(splits_dir).values():
        if csv_path != fit_csv:
            eval_rows.extend(read_split_csv(csv_path))
    if not eval_rows:
        eval_rows = read_split_csv(fit_csv)
    eval_logits, eval_labels = _logits_for(model, eval_rows, crops_root, device_t, batch_size)

    report = calibration_report(fit_logits, fit_labels, eval_logits, eval_labels)
    report["fit_split"] = "val.csv"
    report["promoted"] = False
    if promote:
        if report["degenerate"]:
            print("Calibration is degenerate; not promoting to the runtime.")
        else:
            RUNTIME_CALIBRATION.write_text(
                json.dumps(
                    {
                        "temperature": report["temperature"],
                        "checkpoint": checkpoint.name,
                        "ece_before": report["ece_before"],
                        "ece_after": report["ece_after"],
                        "n_fit": report["n_fit"],
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
            report["promoted"] = True
            print(f"Wrote runtime calibration {RUNTIME_CALIBRATION}")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Temperature-scaling calibration for the forgery score.")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--promote", action="store_true", help="write models/calibration.json for ml-service")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    report = run_calibration(
        args.checkpoint.resolve(),
        args.crops.resolve(),
        args.splits.resolve(),
        batch_size=args.batch_size,
        device=args.device,
        promote=args.promote,
    )
    summary = {k: v for k, v in report.items() if not k.startswith("reliability")}
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
