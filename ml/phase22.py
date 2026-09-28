#!/usr/bin/env python3
"""Phase 22: calibration, robustness and fairness in one run, merged into results/metrics.json.

    python phase22.py                           # synthetic workspace (ml/.smoke_data)
    python phase22.py --crops <dir> --splits <dir> --promote-calibration   # licensed data

Existing blocks in metrics.json are kept; only ``calibration``, ``robustness`` and ``fairness`` are
replaced.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "data"))

from calibration import run_calibration  # noqa: E402
from evaluate import _json_safe  # noqa: E402
from fairness import DEFAULT_ATTRIBUTES, DEFAULT_MIN_N, run_fairness  # noqa: E402
from phase11_eval import ensure_eval_data  # noqa: E402
from robustness import run_robustness  # noqa: E402

SYNTHETIC_NOTE = (
    "Synthetic smoke splits and the demo checkpoint: these blocks show the evaluation tooling works "
    "end to end, not real-world calibration or robustness. Re-run with --crops/--splits on licensed "
    "FF++/DFDC/Celeb-DF crops for report-quality figures."
)


def save_reliability_diagram(calibration: dict, out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(6.0, 5.5))
    ax.plot([0, 1], [0, 1], "--", color="#64748b", linewidth=1, label="perfect calibration")
    for key, label, marker in (
        ("reliability_before", f"before (ECE={calibration['ece_before']:.3f})", "o"),
        ("reliability_after", f"after T={calibration['temperature']:.2f} (ECE={calibration['ece_after']:.3f})", "s"),
    ):
        bins = [b for b in calibration[key] if b["n"]]
        ax.plot(
            [b["mean_prob"] for b in bins],
            [b["frac_fake"] for b in bins],
            marker=marker,
            linewidth=1.5,
            label=label,
        )
    title = "Reliability diagram (forgery score)"
    if calibration.get("degenerate"):
        title += "\nfit set separable: temperature not applied"
    ax.set_title(title, fontsize=10)
    ax.set_xlabel("Mean predicted P(fake)")
    ax.set_ylabel("Observed fraction fake")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.grid(True, alpha=0.25)
    ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path


def save_robustness_chart(robustness: dict, out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    names = list(robustness["transforms"])
    flips = [robustness["transforms"][n]["flip_rate"] for n in names]
    shifts = [robustness["transforms"][n]["mean_abs_shift"] for n in names]
    fig, ax = plt.subplots(figsize=(8.0, 4.5))
    x = range(len(names))
    width = 0.4
    b1 = ax.bar([i - width / 2 for i in x], flips, width, label="decision flip rate", color="#f0563c")
    b2 = ax.bar([i + width / 2 for i in x], shifts, width, label="mean |score shift|", color="#34d1bf")
    ax.bar_label(b1, fmt="%.2f", fontsize=7, padding=2)
    ax.bar_label(b2, fmt="%.2f", fontsize=7, padding=2)
    ax.set_xticks(list(x), names, rotation=25, ha="right", fontsize=8)
    ax.set_ylim(0, max(1.0, max(flips + shifts, default=0) * 1.2))
    ax.set_ylabel("Fraction / score units")
    ax.set_title(f"Robustness vs. untouched baseline (threshold {robustness['threshold']})")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, axis="y", alpha=0.25)
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path


def merge_metrics(metrics_path: Path, blocks: dict) -> dict:
    existing = json.loads(metrics_path.read_text(encoding="utf-8")) if metrics_path.exists() else {}
    existing.update(blocks)
    artifacts = existing.setdefault("artifacts", {})
    artifacts["reliability_diagram"] = "ml/results/reliability_diagram.png"
    artifacts["robustness_chart"] = "ml/results/robustness_chart.png"
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    metrics_path.write_text(json.dumps(_json_safe(existing), indent=2), encoding="utf-8")
    return existing


def main() -> None:
    parser = argparse.ArgumentParser(description="Phase 22 credibility suite")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--crops", type=Path, default=None)
    parser.add_argument("--splits", type=Path, default=None)
    parser.add_argument("--work", type=Path, default=ROOT / ".smoke_data")
    parser.add_argument("--results", type=Path, default=ROOT / "results")
    parser.add_argument("--attributes", type=str, default=",".join(DEFAULT_ATTRIBUTES))
    parser.add_argument("--min-group-n", type=int, default=DEFAULT_MIN_N)
    parser.add_argument("--promote-calibration", action="store_true")
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()

    if (args.crops is None) != (args.splits is None):
        parser.error("--crops and --splits go together")
    synthetic = args.crops is None
    if synthetic:
        data = ensure_eval_data(args.work.resolve())
        crops, splits = Path(data["crops"]), Path(data["splits"])
    else:
        crops, splits = args.crops.resolve(), args.splits.resolve()
    checkpoint = args.checkpoint.resolve()
    results = args.results.resolve()

    print("Calibration…")
    calibration = run_calibration(checkpoint, crops, splits, device=args.device, promote=args.promote_calibration)
    print(f"  T={calibration['temperature']} degenerate={calibration['degenerate']} "
          f"ECE {calibration['ece_before']} -> {calibration['ece_after']}")
    print("Robustness…")
    robustness = run_robustness(checkpoint, crops, splits, device=args.device)
    print("Fairness…")
    fairness = run_fairness(
        checkpoint,
        crops,
        splits,
        attributes=tuple(a.strip() for a in args.attributes.split(",") if a.strip()),
        min_n=args.min_group_n,
        device=args.device,
    )
    print(f"  status={fairness['status']}")

    for block in (calibration, robustness, fairness):
        block["data"] = "synthetic" if synthetic else "provided"
        if synthetic:
            block["notes"] = SYNTHETIC_NOTE

    save_reliability_diagram(calibration, results / "reliability_diagram.png")
    save_robustness_chart(robustness, results / "robustness_chart.png")
    merge_metrics(
        results / "metrics.json",
        {"calibration": calibration, "robustness": robustness, "fairness": fairness},
    )
    print(f"Wrote {results / 'metrics.json'}, reliability_diagram.png, robustness_chart.png")


if __name__ == "__main__":
    main()
