#!/usr/bin/env python3
"""Phase 17 end to end: diffusion split → GAN-era baseline → augmented fine-tune → full re-evaluation.

Writes ``gan_era`` and ``diffusion_era`` blocks (baseline vs retrained, side by side) into
``ml/results/metrics.json`` and a comparison chart, then promotes the retrained checkpoint to
``ml/models/checkpoint.pt`` (the GAN-era one is kept as ``checkpoint.gan_era.pt``, local only).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "data"))

from build_diffusion_split import run as build_diffusion_split  # noqa: E402
from evaluate import _json_safe  # noqa: E402
from evaluate_diffusion import run as evaluate_checkpoint  # noqa: E402
from make_synthetic_crops import make_synthetic_workspace  # noqa: E402
from models.forgery_model import has_family_head  # noqa: E402
from train_diffusion_augmented import run_training  # noqa: E402

METRIC_KEYS = ("accuracy", "precision", "recall", "f1", "auc", "n")
ALLOWED_GAN_REGRESSION = 0.02


def _pick(m: dict) -> dict:
    return {k: m.get(k) for k in METRIC_KEYS}


def _delta(after: dict, before: dict) -> dict:
    return {k: round(float(after[k]) - float(before[k]), 4) for k in ("accuracy", "auc", "f1")
            if isinstance(after.get(k), float) and isinstance(before.get(k), float)}


def gan_era_checkpoint(models_dir: Path) -> Path:
    """The Phase 2 (GAN-era) checkpoint: the backup if a retrained one was already promoted."""
    import torch

    backup = models_dir / "checkpoint.gan_era.pt"
    if backup.exists():
        return backup
    current = models_dir / "checkpoint.pt"
    state = torch.load(current, map_location="cpu", weights_only=False)["model_state_dict"]
    if has_family_head(state):
        raise SystemExit(
            "checkpoint.pt is already diffusion-augmented and no checkpoint.gan_era.pt backup exists. "
            "Recreate the GAN-era model with `python ml/smoke_phase23.py` first."
        )
    shutil.copy2(current, backup)
    return backup


def build_blocks(baseline: dict, retrained: dict, source: str) -> dict:
    gan_before, gan_after = baseline["gan_era"], retrained["gan_era"]
    regression = {name: _delta(gan_after["per_dataset"][name], m) for name, m in gan_before["per_dataset"].items()}
    worst = min([d.get("accuracy", 0.0) for d in regression.values()] + [d.get("auc", 0.0) for d in regression.values()])
    dm_before, dm_after = baseline["diffusion_era"], retrained["diffusion_era"]
    return {
        "gan_era": {
            "datasets": list(gan_before["per_dataset"]),
            "baseline": {**_pick(gan_before["pooled"]), "per_dataset": {k: _pick(v) for k, v in gan_before["per_dataset"].items()}},
            "retrained": {**_pick(gan_after["pooled"]), "per_dataset": {k: _pick(v) for k, v in gan_after["per_dataset"].items()}},
            "regression": regression,
            "allowed_regression": ALLOWED_GAN_REGRESSION,
            "within_margin": worst >= -ALLOWED_GAN_REGRESSION,
        },
        "diffusion_era": {
            "dataset": "diffusion (synthetic inpainting stand-in)" if source == "synthetic" else "diffusion (imported)",
            "split": "held-out test identities",
            "baseline": _pick(dm_before),
            "retrained": _pick(dm_after),
            "auc_gain": _delta(dm_after, dm_before).get("auc"),
        },
        "generation_family": {
            "head": "sigmoid → P(diffusion); 'unknown' below the forgery threshold or when 0.35 < p < 0.65",
            "diffusion_test_fakes": dm_after.get("family"),
            "gan_era_fakes": gan_after["pooled"].get("family"),
        },
    }


def save_chart(blocks: dict, path: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    names = blocks["gan_era"]["datasets"] + ["diffusion"]
    before = [blocks["gan_era"]["baseline"]["per_dataset"][n]["auc"] for n in names[:-1]] + [blocks["diffusion_era"]["baseline"]["auc"]]
    after = [blocks["gan_era"]["retrained"]["per_dataset"][n]["auc"] for n in names[:-1]] + [blocks["diffusion_era"]["retrained"]["auc"]]
    fig, ax = plt.subplots(figsize=(8, 4.6), dpi=140)
    x = range(len(names))
    ax.bar([i - 0.2 for i in x], before, width=0.4, label="GAN-era checkpoint (baseline)", color="#94a3b8")
    ax.bar([i + 0.2 for i in x], after, width=0.4, label="Diffusion-augmented (retrained)", color="#6366f1")
    for i, (b, a) in enumerate(zip(before, after)):
        ax.text(i - 0.2, b + 0.015, f"{b:.2f}", ha="center", fontsize=8)
        ax.text(i + 0.2, a + 0.015, f"{a:.2f}", ha="center", fontsize=8)
    ax.axvline(len(names) - 1.5, color="#cbd5e1", linestyle="--", linewidth=1)
    ax.set_xticks(list(x), ["FF++", "DFDC", "Celeb-DF", "Diffusion (held out)"])
    ax.set_ylim(0, 1.12)
    ax.set_ylabel("ROC-AUC")
    ax.set_title("Phase 17: GAN-era vs diffusion-era detection (synthetic stand-in data)", fontsize=10)
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.1), ncol=2, fontsize=8, frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="Phase 17 diffusion-era robustness pipeline")
    parser.add_argument("--work", type=Path, default=ROOT / ".smoke_data")
    parser.add_argument("--source", choices=["synthetic", "import"], default="synthetic")
    parser.add_argument("--import-dir", type=Path, default=None)
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--no-promote", action="store_true")
    parser.add_argument("--chart-only", action="store_true", help="Redraw the chart from metrics.json")
    args = parser.parse_args()

    if args.chart_only:
        chart = ROOT / "results" / "diffusion_robustness.png"
        save_chart(json.loads((ROOT / "results" / "metrics.json").read_text(encoding="utf-8")), chart)
        public = ROOT.parent / "frontend" / "public"
        if public.exists():
            shutil.copy2(chart, public / chart.name)
        return

    work = args.work.resolve()
    crops, splits = work / "crops", work / "splits"
    if not (crops / "labels.csv").exists() or not (splits / "train.csv").exists():
        make_synthetic_workspace(work, n_per_class=20, seed=42)
    meta = build_diffusion_split(crops, splits, source=args.source, import_dir=args.import_dir)
    print("diffusion split:", json.dumps(meta["counts"]))

    models_dir = ROOT / "models"
    base_ckpt = gan_era_checkpoint(models_dir)
    print(f"Baseline (GAN-era) evaluation of {base_ckpt.name}…")
    baseline = evaluate_checkpoint(base_ckpt, crops, splits)
    print(f"  diffusion AUC={baseline['diffusion_era']['auc']:.3f} acc={baseline['diffusion_era']['accuracy']:.3f}")

    out_ckpt = models_dir / "checkpoint.diffusion_aug.pt"
    print("Diffusion-augmented fine-tune…")
    run_training(base_ckpt, crops, splits, out_ckpt, epochs=args.epochs)
    retrained = evaluate_checkpoint(out_ckpt, crops, splits)
    print(f"  diffusion AUC={retrained['diffusion_era']['auc']:.3f} acc={retrained['diffusion_era']['accuracy']:.3f}")

    blocks = build_blocks(baseline, retrained, args.source)
    results_dir = ROOT / "results"
    chart = results_dir / "diffusion_robustness.png"
    save_chart(blocks, chart)

    metrics_path = results_dir / "metrics.json"
    metrics = json.loads(metrics_path.read_text(encoding="utf-8")) if metrics_path.exists() else {}
    per = blocks["gan_era"]["retrained"]["per_dataset"]
    metrics["datasets"] = {k: {kk: v[kk] for kk in METRIC_KEYS} for k, v in per.items()}
    ff = per.get("faceforensics++", {})
    metrics["generalization_gap"] = {
        h: {"accuracy_gap": round(ff["accuracy"] - per[h]["accuracy"], 4),
            "auc_gap": round(ff["auc"] - per[h]["auc"], 4),
            "f1_gap": round(ff["f1"] - per[h]["f1"], 4)}
        for h in ("dfdc", "celeb-df") if h in per and ff
    }
    metrics.update(blocks)
    metrics.setdefault("artifacts", {})["diffusion_robustness"] = "ml/results/diffusion_robustness.png"
    metrics["phase17_notes"] = (
        "Diffusion-era detection is an active research area, not a solved problem. These numbers come from a "
        "synthetic diffusion-inpainting stand-in over synthetic crops, so they show the retraining mechanism, "
        "not real-world coverage. Re-run with --source import on diffusion fakes generated from licensed FF++ "
        "originals for report-quality figures."
    )
    metrics_path.write_text(json.dumps(_json_safe(metrics), indent=2), encoding="utf-8")
    public = ROOT.parent / "frontend" / "public"
    if (public / "metrics.json").exists():
        shutil.copy2(metrics_path, public / "metrics.json")
        shutil.copy2(chart, public / chart.name)

    if not args.no_promote:
        shutil.copy2(out_ckpt, models_dir / "checkpoint.pt")
        print("Promoted the diffusion-augmented checkpoint to ml/models/checkpoint.pt")
    print(json.dumps(_json_safe({k: blocks[k] for k in ("diffusion_era", "generation_family")}), indent=2))
    print("GAN-era regression:", json.dumps(blocks["gan_era"]["regression"]), "within margin:",
          blocks["gan_era"]["within_margin"])


if __name__ == "__main__":
    main()
