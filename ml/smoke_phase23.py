#!/usr/bin/env python3
"""Smoke: synthetic crops → train → evaluate → explain (produces DoD artifacts)."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "data"))

from make_synthetic_crops import make_synthetic_workspace
from evaluate import run_evaluation
from explain import explain_image
from train import run_training


def main() -> None:
    work = ROOT / ".smoke_data"
    work.mkdir(exist_ok=True)
    info = make_synthetic_workspace(work, n_per_class=20, seed=42)
    ckpt = ROOT / "models" / "checkpoint.pt"
    results = ROOT / "results" / "metrics.json"

    print("Training…")
    try:
        run_training(
            info["crops"],
            info["splits"],
            ckpt,
            epochs=3,
            batch_size=8,
            lr=1e-3,
            pretrained=True,
            device="cpu",
        )
    except Exception as exc:  # noqa: BLE001 — offline / no weights mirror
        print(f"Pretrained weights unavailable ({exc}); retrying without ImageNet weights.")
        run_training(
            info["crops"],
            info["splits"],
            ckpt,
            epochs=3,
            batch_size=8,
            lr=1e-3,
            pretrained=False,
            device="cpu",
        )

    print("Evaluating…")
    metrics = run_evaluation(ckpt, info["crops"], info["splits"], results, batch_size=8, device="cpu")
    print(json.dumps(metrics["generalization_gap"], indent=2))

    sample = next(info["crops"].rglob("*.jpg"))
    print(f"Explaining {sample}…")
    explained = explain_image(sample, ckpt, device="cpu")
    preview = {k: f"{v[:40]}… ({len(v)} chars)" for k, v in explained.items()}
    print(json.dumps(preview, indent=2))
    print(f"checkpoint: {ckpt}")
    print(f"metrics: {results}")


if __name__ == "__main__":
    main()
