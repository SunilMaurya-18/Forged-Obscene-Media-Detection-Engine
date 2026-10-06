#!/usr/bin/env python3
"""Phase 29 end to end: robustness baseline → degradation-augmented fine-tune → spectral ensemble →
held-out robustness → release gate → promote only if the gate passes.

    python phase29.py                                   # synthetic workspace (ml/.smoke_data)
    python phase29.py --crops <dir> --splits <dir>      # licensed data (needs splits/diffusion/)

The served model before and after is measured on the same held-out images (GAN-era test splits plus
the diffusion test split; never ``val.csv``, which selects the checkpoint and fits the stacker).
Writes ``robustness`` (the served model), a ``phase29`` block with the before/after comparison,
per-expert clean metrics and the gate result, and refreshes ``datasets`` for the served model.
Promotion copies the checkpoint to ``models/checkpoint.pt`` (the previous one is kept locally as
``checkpoint.pre_phase29.pt``) and the ensemble to ``models/ensemble.json``. Exit code 1 when the
gate fails; nothing is promoted then.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "data"))

from build_diffusion_split import run as build_diffusion_split  # noqa: E402
from dataset import LABEL_TO_IDX, read_split_csv  # noqa: E402
from degradations import DEFAULT_DEGRADE_P  # noqa: E402
from evaluate import _json_safe, compute_metrics, resolve_eval_csvs  # noqa: E402
from phase11_eval import ensure_eval_data  # noqa: E402
from phase22 import save_robustness_chart  # noqa: E402
from release_gate import format_report, run_gate  # noqa: E402
from robustness import robustness_csvs, run_robustness  # noqa: E402
from scoring import ServedScorer  # noqa: E402
from train_diffusion_augmented import run_training  # noqa: E402
from train_ensemble import build_ensemble, write_ensemble  # noqa: E402

SYNTHETIC_NOTE = (
    "Synthetic smoke splits: these numbers show that degradation-augmented training, the spectral "
    "ensemble and the release gate work end to end. They are not real-world robustness. Re-run with "
    "--crops/--splits on licensed FF++/DFDC/Celeb-DF crops (and real diffusion fakes) for report-quality figures."
)


def expert_metrics(scorer: ServedScorer, csvs: dict[str, Path], crops: Path) -> dict:
    """Clean metrics per dataset for each expert and the served score."""
    out = {}
    for name, csv_path in csvs.items():
        rows = read_split_csv(csv_path)
        images = [Image.open(crops / r["path"]).convert("RGB") for r in rows]
        labels = np.asarray([LABEL_TO_IDX[r["label"].lower()] for r in rows], dtype=np.int64)
        scores = scorer.score(images)
        out[name] = {k: compute_metrics(v, labels) for k, v in scores.items() if v is not None}
    return out


def summary(block: dict) -> dict:
    return {
        "n": block["n"],
        "baseline_auc": block["baseline"]["auc"],
        "flip_rate": {k: round(v["flip_rate"], 4) for k, v in block["transforms"].items()},
        "worst_transform": block["worst_transform"],
        "experts": block["model"]["experts"],
    }


def save_before_after_chart(before: dict, after: dict, out_path: Path) -> Path:
    """``before`` / ``after`` are ``summary()`` dicts (as stored in the ``phase29`` block)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    names = list(after["flip_rate"])
    b = [before["flip_rate"].get(n, 0.0) for n in names]
    a = [after["flip_rate"][n] for n in names]
    fig, ax = plt.subplots(figsize=(8.5, 4.6), dpi=140)
    x = range(len(names))
    bars_b = ax.bar([i - 0.2 for i in x], b, width=0.4, label="Before (Phase 17 CNN)", color="#94a3b8")
    bars_a = ax.bar([i + 0.2 for i in x], a, width=0.4, label="After (augmented CNN + spectral ensemble)", color="#10b981")
    ax.bar_label(bars_b, fmt="%.2f", fontsize=7, padding=2)
    ax.bar_label(bars_a, fmt="%.2f", fontsize=7, padding=2)
    ax.axhline(0.05, color="#f0563c", linestyle="--", linewidth=1, label="Release gate: social transcode ≤ 0.05")
    ax.set_xticks(list(x), names, rotation=25, ha="right", fontsize=8)
    ax.set_ylim(0, max(0.8, max(a + b, default=0) * 1.5))
    ax.set_ylabel("Decision flip rate (held-out)")
    ax.set_title(f"Phase 29: robustness before vs after (n={after['n']} held-out images, synthetic data)", fontsize=10)
    ax.legend(loc="upper center", ncol=2, fontsize=8, frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path)
    plt.close(fig)
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Phase 29 robustness pipeline")
    parser.add_argument("--work", type=Path, default=ROOT / ".smoke_data")
    parser.add_argument("--crops", type=Path, default=None)
    parser.add_argument("--splits", type=Path, default=None)
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--degrade-p", type=float, default=DEFAULT_DEGRADE_P)
    parser.add_argument("--k", type=int, default=3, help="degraded copies per image for the ensemble fits")
    parser.add_argument("--seed", type=int, default=29)
    parser.add_argument("--no-promote", action="store_true")
    parser.add_argument("--chart-only", action="store_true", help="Redraw the before/after chart from metrics.json")
    args = parser.parse_args()

    if args.chart_only:
        block = json.loads((ROOT / "results" / "metrics.json").read_text(encoding="utf-8"))["phase29"]
        chart = save_before_after_chart(block["before"], block["after"], ROOT / "results" / "robustness_before_after.png")
        public = ROOT.parent / "frontend" / "public"
        if public.exists():
            shutil.copy2(chart, public / chart.name)
        return

    if (args.crops is None) != (args.splits is None):
        parser.error("--crops and --splits go together")
    synthetic = args.crops is None
    if synthetic:
        data = ensure_eval_data(args.work.resolve())
        crops, splits = Path(data["crops"]), Path(data["splits"])
        if not (splits / "diffusion" / "test.csv").exists():
            build_diffusion_split(crops, splits)
    else:
        crops, splits = args.crops.resolve(), args.splits.resolve()
        if not (splits / "diffusion" / "train.csv").exists():
            raise SystemExit(f"{splits / 'diffusion'} is missing: run phase17.py --source import first")

    models_dir = ROOT / "models"
    results = ROOT / "results"
    current = models_dir / "checkpoint.pt"

    print(f"Before: served model ({current.name}) on held-out images…")
    before = run_robustness(current, crops, splits)

    candidate = models_dir / "checkpoint.robust.pt"
    print(f"Degradation-augmented fine-tune (p={args.degrade_p}, {args.epochs} epochs)…")
    run_training(current, crops, splits, candidate, epochs=args.epochs, seed=args.seed, degrade_p=args.degrade_p,
                 extra={"phase": 29})

    print("Fitting the spectral expert and stacker…")
    ensemble_config = build_ensemble(candidate, crops, splits, k=args.k, seed=args.seed,
                                     data="synthetic" if synthetic else "provided")
    candidate_ensemble = write_ensemble(ensemble_config, args.work.resolve() / "ensemble.candidate.json")
    print(f"  stacker: {json.dumps(ensemble_config['stacker'])}")

    print("After: augmented CNN alone…")
    after_cnn = run_robustness(candidate, crops, splits, ensemble=False)
    print("After: augmented CNN + spectral ensemble…")
    after = run_robustness(candidate, crops, splits, ensemble=candidate_ensemble)

    scorer = ServedScorer.load(candidate, ensemble=candidate_ensemble)
    clean = expert_metrics(scorer, robustness_csvs(splits), crops)

    gate = run_gate({"robustness": after})
    print(format_report(gate))

    promoted = False
    if gate["passed"] and not args.no_promote:
        backup = models_dir / "checkpoint.pre_phase29.pt"
        if not backup.exists():
            shutil.copy2(current, backup)
        shutil.copy2(candidate, current)
        shutil.copy2(candidate_ensemble, models_dir / "ensemble.json")
        promoted = True
        print("Promoted the augmented checkpoint and ensemble to ml/models/")

    metrics_path = results / "metrics.json"
    metrics = json.loads(metrics_path.read_text(encoding="utf-8")) if metrics_path.exists() else {}
    note = SYNTHETIC_NOTE if synthetic else None
    if promoted:
        after["data"] = "synthetic" if synthetic else "provided"
        if note:
            after["notes"] = note
        metrics["robustness"] = after
        served = expert_metrics(scorer, resolve_eval_csvs(splits), crops)
        metrics["datasets"] = {k: v["served"] for k, v in served.items()}
        ff = metrics["datasets"].get("faceforensics++")
        metrics["generalization_gap"] = {
            h: {"accuracy_gap": round(ff["accuracy"] - m["accuracy"], 4),
                "auc_gap": round(ff["auc"] - m["auc"], 4),
                "f1_gap": round(ff["f1"] - m["f1"], 4)}
            for h, m in metrics["datasets"].items() if h != "faceforensics++" and ff
        }
        save_robustness_chart(after, results / "robustness_chart.png")
    metrics["phase29"] = {
        "data": "synthetic" if synthetic else "provided",
        "training": {"degrade_p": args.degrade_p, "epochs": args.epochs, "seed": args.seed,
                     "init": "Phase 17 checkpoint", "held_out_transforms": after["held_out_transforms"]},
        "ensemble": {"stacker": ensemble_config["stacker"], "fitted": ensemble_config["fitted"]},
        "before": summary(before),
        "after_cnn_only": summary(after_cnn),
        "after": summary(after),
        "clean_held_out": clean,
        "gate": gate,
        "promoted": promoted,
        "notes": note,
    }
    artifacts = metrics.setdefault("artifacts", {})
    artifacts["robustness_before_after"] = "ml/results/robustness_before_after.png"
    chart = save_before_after_chart(summary(before), summary(after), results / "robustness_before_after.png")
    metrics_path.write_text(json.dumps(_json_safe(metrics), indent=2), encoding="utf-8")

    public = ROOT.parent / "frontend" / "public"
    if (public / "metrics.json").exists():
        shutil.copy2(metrics_path, public / "metrics.json")
        for name in (chart.name, "robustness_chart.png"):
            shutil.copy2(results / name, public / name)
    print(f"Wrote {metrics_path} and {chart}")
    sys.exit(0 if gate["passed"] else 1)


if __name__ == "__main__":
    main()
