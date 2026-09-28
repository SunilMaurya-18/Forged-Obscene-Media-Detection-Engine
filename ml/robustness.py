#!/usr/bin/env python3
"""Phase 22: forgery-score stability under the transforms real uploads go through.

Each transform is applied to the image before the standard eval transform. For every transform we
report AUC and accuracy, and against the untouched baseline: the mean absolute score shift and the
decision flip rate at the routing threshold (how often re-encoding alone would change the outcome).
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from collections.abc import Callable
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageEnhance, ImageFilter
from torch.utils.data import DataLoader, Dataset

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import LABEL_TO_IDX, default_eval_transform, read_split_csv  # noqa: E402
from evaluate import collect_predictions, compute_metrics, resolve_eval_csvs  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402

Perturb = Callable[[Image.Image], Image.Image]


def jpeg(quality: int) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        buf = io.BytesIO()
        img.convert("RGB").save(buf, format="JPEG", quality=quality)
        buf.seek(0)
        return Image.open(buf).convert("RGB")

    return apply


def rescale(factor: float) -> Perturb:
    """Downscale then upscale back: the detail loss of a thumbnail or a low-resolution re-share."""

    def apply(img: Image.Image) -> Image.Image:
        w, h = img.size
        small = img.resize((max(1, int(w * factor)), max(1, int(h * factor))), Image.BILINEAR)
        return small.resize((w, h), Image.BILINEAR)

    return apply


def center_crop(fraction: float) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        w, h = img.size
        cw, ch = int(w * fraction), int(h * fraction)
        left, top = (w - cw) // 2, (h - ch) // 2
        return img.crop((left, top, left + cw, top + ch))

    return apply


def blur(radius: float) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        return img.filter(ImageFilter.GaussianBlur(radius))

    return apply


def screenshot(img: Image.Image) -> Image.Image:
    """Display-and-capture approximation: resample, slight brightness/contrast shift, PNG-then-JPEG."""
    w, h = img.size
    out = img.resize((int(w * 1.25), int(h * 1.25)), Image.BICUBIC).resize((w, h), Image.BICUBIC)
    out = ImageEnhance.Brightness(out).enhance(1.06)
    out = ImageEnhance.Contrast(out).enhance(0.94)
    return jpeg(85)(out)


def social_transcode(img: Image.Image) -> Image.Image:
    """Typical platform re-encode: downscale to 75% and JPEG quality 75."""
    return jpeg(75)(rescale(0.75)(img))


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


class PerturbedDataset(Dataset):
    def __init__(self, rows: list[dict[str, str]], crops_root: Path, perturb: Perturb | None) -> None:
        self.rows = rows
        self.crops_root = Path(crops_root)
        self.perturb = perturb
        self.transform = default_eval_transform()

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int):
        row = self.rows[index]
        image = Image.open(self.crops_root / row["path"]).convert("RGB")
        if self.perturb is not None:
            image = self.perturb(image)
        return self.transform(image), LABEL_TO_IDX[row["label"].lower()], row


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


def run_robustness(
    checkpoint: Path,
    crops_root: Path,
    splits_dir: Path,
    *,
    threshold: float = 0.5,
    batch_size: int = 16,
    device: str | None = None,
    transforms: dict[str, Perturb] | None = None,
) -> dict:
    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    model, _ = load_checkpoint(checkpoint, device=device_t)
    rows: list[dict[str, str]] = []
    for csv_path in resolve_eval_csvs(splits_dir).values():
        rows.extend(read_split_csv(csv_path))
    if not rows:
        raise FileNotFoundError(f"No evaluation CSVs found under {splits_dir}")

    def predict(perturb: Perturb | None) -> tuple[np.ndarray, np.ndarray]:
        loader = DataLoader(PerturbedDataset(rows, crops_root, perturb), batch_size=batch_size)
        return collect_predictions(model, loader, device_t)

    baseline, labels = predict(None)
    perturbed = {}
    for name, fn in (transforms or TRANSFORMS).items():
        perturbed[name], _ = predict(fn)
        print(f"{name}: flip_rate={np.mean((baseline >= threshold) != (perturbed[name] >= threshold)):.3f}")
    return robustness_report(baseline, labels, perturbed, threshold)


def main() -> None:
    parser = argparse.ArgumentParser(description="Forgery-score robustness under real-world transforms.")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    report = run_robustness(
        args.checkpoint.resolve(),
        args.crops.resolve(),
        args.splits.resolve(),
        threshold=args.threshold,
        batch_size=args.batch_size,
        device=args.device,
    )
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
