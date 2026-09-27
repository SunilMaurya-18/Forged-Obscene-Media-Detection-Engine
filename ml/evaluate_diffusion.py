#!/usr/bin/env python3
"""Phase 17: score a checkpoint on the diffusion-era test split (and optionally the GAN-era sets).

Run it on the Phase 2 checkpoint first: that is the pre-retraining baseline, and it is expected to
be weak on diffusion fakes because the GAN-era model keys on artefacts diffusion models don't leave.
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
from evaluate import _json_safe, compute_metrics, resolve_eval_csvs  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402

GAN_ERA = ("faceforensics++", "dfdc", "celeb-df")


@torch.no_grad()
def predict(model, rows: list[dict], crops: Path, device: torch.device, batch_size: int = 16):
    """Forgery probabilities, labels, and P(diffusion) per row (None without a family head)."""
    loader = DataLoader(CropCsvDataset(rows, crops, default_eval_transform()), batch_size=batch_size, shuffle=False)
    probs, labels, family = [], [], []
    for images, y, _ in loader:
        forgery, fam = model.forward_all(images.to(device))
        probs.extend(torch.sigmoid(forgery).view(-1).cpu().tolist())
        labels.extend(y.view(-1).tolist())
        if fam is not None:
            family.extend(torch.sigmoid(fam).view(-1).cpu().tolist())
    return (np.asarray(probs, dtype=np.float64), np.asarray(labels, dtype=np.int64),
            np.asarray(family, dtype=np.float64) if family else None)


def family_accuracy(rows: list[dict], p_diffusion: np.ndarray | None) -> dict | None:
    """Accuracy of the family head on fakes whose family is known (gan vs diffusion)."""
    if p_diffusion is None:
        return None
    known = [(i, r.get("family") or ("gan" if r["label"] == "fake" else "")) for i, r in enumerate(rows)]
    known = [(i, f) for i, f in known if rows[i]["label"] == "fake" and f in ("gan", "diffusion")]
    if not known:
        return None
    correct = sum((p_diffusion[i] >= 0.5) == (f == "diffusion") for i, f in known)
    return {"accuracy": correct / len(known), "n": len(known)}


def evaluate_rows(model, rows, crops, device) -> dict:
    probs, labels, fam = predict(model, rows, crops, device)
    metrics = compute_metrics(probs, labels)
    metrics["family"] = family_accuracy(rows, fam)
    return metrics


def gan_era_block(model, crops: Path, splits: Path, device) -> dict:
    """Per-dataset GAN-era metrics plus a pooled summary over all three benchmarks."""
    per, pooled_p, pooled_y, pooled_rows, pooled_f = {}, [], [], [], []
    for name, csv_path in resolve_eval_csvs(splits).items():
        rows = read_split_csv(csv_path)
        probs, labels, fam = predict(model, rows, crops, device)
        per[name] = compute_metrics(probs, labels)
        pooled_p.append(probs)
        pooled_y.append(labels)
        pooled_rows += rows
        if fam is not None:
            pooled_f.append(fam)
    summary = compute_metrics(np.concatenate(pooled_p), np.concatenate(pooled_y))
    summary["family"] = family_accuracy(pooled_rows, np.concatenate(pooled_f) if pooled_f else None)
    return {"per_dataset": per, "pooled": summary}


def diffusion_block(model, crops: Path, splits: Path, device, split: str = "test") -> dict:
    rows = read_split_csv(splits / "diffusion" / f"{split}.csv")
    return evaluate_rows(model, rows, crops, device)


def run(checkpoint: Path, crops: Path, splits: Path, *, include_gan_era: bool = True, device: str = "cpu") -> dict:
    device_t = torch.device(device)
    model, _ = load_checkpoint(checkpoint, device=device_t)
    out = {"checkpoint": str(checkpoint), "diffusion_era": diffusion_block(model, crops, splits, device_t)}
    if include_gan_era:
        out["gan_era"] = gan_era_block(model, crops, splits, device_t)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate a checkpoint on the diffusion-era split.")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=None, help="Optional JSON output path")
    parser.add_argument("--diffusion-only", action="store_true")
    args = parser.parse_args()
    result = run(args.checkpoint.resolve(), args.crops.resolve(), args.splits.resolve(),
                 include_gan_era=not args.diffusion_only)
    text = json.dumps(_json_safe(result), indent=2)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
