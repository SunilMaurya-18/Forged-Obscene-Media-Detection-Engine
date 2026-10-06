#!/usr/bin/env python3
"""Phase 29: fit the spectral expert and the stacker, write ``ensemble.json`` for ml-service.

* Spectral expert: L2-regularised logistic regression over ``ensemble.spectral_features`` on the
  training split, each image plus ``k`` randomly degraded copies (so it learns not to key on JPEG).
* Stacker: ``sigmoid(w_cnn * z_cnn + w_spectral * z_spectral + b)`` fitted on the validation split
  (plus degraded copies), never on training images, where the CNN's logits are overconfident.

Both fits are ridge-regularised, so they stay finite even when the data is perfectly separable.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import LABEL_TO_IDX, read_split_csv  # noqa: E402
from degradations import RandomDegradation  # noqa: E402
from scoring import ServedScorer, ensemble_lib, sigmoid  # noqa: E402

DEFAULT_K = 3
DEFAULT_LOGIT_CLIP = 15.0


def fit_logistic(x: np.ndarray, y: np.ndarray, l2: float = 1.0, iters: int = 100) -> tuple[np.ndarray, float]:
    """Newton's method on summed BCE + ``l2 * |w|^2 / 2`` (intercept unpenalised)."""
    x = np.asarray(x, dtype=np.float64)
    y = np.asarray(y, dtype=np.float64)
    n, d = x.shape
    xb = np.hstack([x, np.ones((n, 1))])
    penalty = np.diag([l2] * d + [0.0])
    w = np.zeros(d + 1)
    for _ in range(iters):
        p = sigmoid(xb @ w)
        grad = xb.T @ (p - y) + penalty @ w
        hess = xb.T @ (xb * (p * (1 - p))[:, None]) + penalty + 1e-9 * np.eye(d + 1)
        step = np.linalg.solve(hess, grad)
        w -= step
        if np.max(np.abs(step)) < 1e-8:
            break
    return w[:-1], float(w[-1])


def variants(image: Image.Image, k: int, rng: random.Random) -> list[Image.Image]:
    degrade = RandomDegradation(p=1.0, rng=rng)
    return [image] + [degrade(image) for _ in range(k)]


def expand(rows: list[dict], crops: Path, k: int, rng: random.Random) -> tuple[list[Image.Image], np.ndarray]:
    images, labels = [], []
    for row in rows:
        img = Image.open(crops / row["path"]).convert("RGB")
        for v in variants(img, k, rng):
            images.append(v)
            labels.append(LABEL_TO_IDX[row["label"].lower()])
    return images, np.asarray(labels, dtype=np.int64)


def fit_spectral(images: list[Image.Image], labels: np.ndarray, l2: float = 1.0) -> dict:
    feats = np.stack([ensemble_lib.spectral_features(img) for img in images])
    mean = feats.mean(axis=0)
    std = np.maximum(feats.std(axis=0), 1e-6)
    coef, intercept = fit_logistic((feats - mean) / std, labels, l2=l2)
    return {"mean": mean.tolist(), "std": std.tolist(), "coef": coef.tolist(), "intercept": intercept}


def fit_stacker(z_cnn: np.ndarray, z_spectral: np.ndarray, labels: np.ndarray, *,
                l2: float = 1.0, clip: float = DEFAULT_LOGIT_CLIP) -> dict:
    x = np.stack([np.clip(z_cnn, -clip, clip), np.clip(z_spectral, -clip, clip)], axis=1)
    (w_cnn, w_spec), bias = fit_logistic(x, labels, l2=l2)
    return {"cnn": float(w_cnn), "spectral": float(w_spec), "bias": bias, "logit_clip": clip}


def unique_rows(*groups: list[dict]) -> list[dict]:
    seen, out = set(), []
    for group in groups:
        for row in group:
            if row["path"] not in seen:
                seen.add(row["path"])
                out.append(row)
    return out


def split_rows(splits: Path, split: str) -> list[dict]:
    """GAN-era ``{split}.csv`` plus the diffusion split of the same name, when present."""
    groups = [read_split_csv(splits / f"{split}.csv")]
    diffusion = splits / "diffusion" / f"{split}.csv"
    if diffusion.exists():
        groups.append(read_split_csv(diffusion))
    return unique_rows(*groups)


def build_ensemble(checkpoint: Path, crops: Path, splits: Path, *, k: int = DEFAULT_K, seed: int = 29,
                   l2_spectral: float = 1.0, l2_stacker: float = 1.0, device: str | None = None,
                   data: str = "synthetic") -> dict:
    rng = random.Random(seed)
    train_rows, val_rows = split_rows(splits, "train"), split_rows(splits, "val")
    if not train_rows or not val_rows:
        raise FileNotFoundError(f"Need train.csv and val.csv under {splits}")

    train_images, train_labels = expand(train_rows, crops, k, rng)
    spectral = fit_spectral(train_images, train_labels, l2=l2_spectral)

    scorer = ServedScorer.load(checkpoint, ensemble=False, device=device)
    probe = ensemble_lib.from_config({
        "format": ensemble_lib.FORMAT, "feature_version": ensemble_lib.FEATURE_VERSION, "spectral": spectral,
        "stacker": {"cnn": 1.0, "spectral": 0.0, "bias": 0.0, "logit_clip": DEFAULT_LOGIT_CLIP},
    })
    val_images, val_labels = expand(val_rows, crops, k, rng)
    z_cnn = scorer.cnn_logits(val_images)
    z_spec = np.asarray([probe.spectral_logit(img) for img in val_images])
    stacker = fit_stacker(z_cnn, z_spec, val_labels, l2=l2_stacker)

    return {
        "format": ensemble_lib.FORMAT,
        "feature_version": ensemble_lib.FEATURE_VERSION,
        "checkpoint_sha256": scorer.checkpoint_sha256,
        "spectral": spectral,
        "stacker": stacker,
        "fitted": {
            "data": data,
            "spectral_fit": {"images": len(train_rows), "degraded_copies_per_image": k, "samples": len(train_labels)},
            "stacker_fit": {"images": len(val_rows), "degraded_copies_per_image": k, "samples": len(val_labels)},
            "l2": {"spectral": l2_spectral, "stacker": l2_stacker},
            "seed": seed,
        },
    }


def write_ensemble(config: dict, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8", newline="\n")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description="Fit the spectral expert + stacker (ensemble.json).")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "models" / "ensemble.json")
    parser.add_argument("--k", type=int, default=DEFAULT_K, help="degraded copies per image")
    parser.add_argument("--seed", type=int, default=29)
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    config = build_ensemble(args.checkpoint.resolve(), args.crops.resolve(), args.splits.resolve(),
                            k=args.k, seed=args.seed, device=args.device, data="provided")
    print(f"Wrote {write_ensemble(config, args.out.resolve())}: stacker {json.dumps(config['stacker'])}")


if __name__ == "__main__":
    main()
