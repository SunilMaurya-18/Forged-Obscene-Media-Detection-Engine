#!/usr/bin/env python3
"""Phase 12: results poster + generalization chart from ml/results/metrics.json."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

from plots import draw_generalization_chart, save_generalization_figure

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
IN_DOMAIN = "faceforensics++"


def headline_stats(metrics: dict) -> list[tuple[str, str, str]]:
    """(label, value, caption) tiles for the poster header."""
    datasets = metrics.get("datasets", {})
    in_domain = datasets.get(IN_DOMAIN, {})
    held_out = [m for name, m in datasets.items() if name != IN_DOMAIN]
    held_out_acc = sum(m["accuracy"] for m in held_out) / len(held_out) if held_out else None
    latency = metrics.get("latency", {})
    fpr = metrics.get("false_positive_rate", {})

    def pct(v: float | None) -> str:
        return "n/a" if v is None else f"{v * 100:.1f}%"

    return [
        ("In-domain accuracy", pct(in_domain.get("accuracy")), f"FF++ test, n={in_domain.get('n', '?')}"),
        ("Cross-dataset accuracy", pct(held_out_acc), "mean of DFDC + Celeb-DF (held out)"),
        (
            "API latency",
            f"{latency.get('p50_ms', 'n/a')} ms",
            f"p50 · p95 {latency.get('p95_ms', 'n/a')} ms · POST /api/analyze",
        ),
        (
            "Clean-set FPR",
            pct(fpr.get("fpr")),
            f"{fpr.get('false_positives', '?')}/{fpr.get('n_clean', '?')} at threshold {fpr.get('threshold', '?')}",
        ),
    ]


def _image_panel(ax: plt.Axes, path: Path, title: str) -> None:
    ax.axis("off")
    ax.set_title(title, fontsize=11)
    if path.exists():
        ax.imshow(plt.imread(path))
    else:
        ax.text(0.5, 0.5, f"{path.name} not found", ha="center", va="center", color="#64748b")


def save_poster(metrics: dict, results_dir: Path, out_path: Path) -> Path:
    fig = plt.figure(figsize=(16, 12), facecolor="white")
    grid = fig.add_gridspec(3, 4, height_ratios=[0.5, 1.6, 1.3], hspace=0.35, wspace=0.25)

    fig.suptitle("Forged / Obscene Media Detection Engine — Results", fontsize=22, fontweight="bold", y=0.98)
    fig.text(
        0.5,
        0.935,
        "EfficientNet-B0 forgery detector · independent NSFW classifier · Spring Boot decision engine",
        ha="center",
        fontsize=12,
        color="#475569",
    )

    for col, (label, value, caption) in enumerate(headline_stats(metrics)):
        ax = fig.add_subplot(grid[0, col])
        ax.axis("off")
        ax.add_patch(plt.Rectangle((0, 0), 1, 1, transform=ax.transAxes, color="#eff6ff", ec="#bfdbfe"))
        ax.text(0.5, 0.72, label, ha="center", fontsize=12, color="#1e3a8a", transform=ax.transAxes)
        ax.text(0.5, 0.38, value, ha="center", fontsize=24, fontweight="bold", transform=ax.transAxes)
        ax.text(0.5, 0.1, caption, ha="center", fontsize=8.5, color="#475569", transform=ax.transAxes)

    draw_generalization_chart(fig.add_subplot(grid[1, :2]), metrics.get("datasets", {}))
    _image_panel(fig.add_subplot(grid[1, 2:]), results_dir / "roc_curve.png", "")
    _image_panel(fig.add_subplot(grid[2, :]), results_dir / "confusion_matrix.png", "")

    notes = metrics.get("notes", "")
    if notes:
        fig.text(0.5, 0.015, notes, ha="center", fontsize=9, color="#64748b", style="italic")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=120)
    plt.close(fig)
    return out_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Phase 12 results poster")
    parser.add_argument("--metrics", type=Path, default=RESULTS / "metrics.json")
    parser.add_argument("--out-dir", type=Path, default=RESULTS)
    args = parser.parse_args()

    metrics = json.loads(args.metrics.read_text(encoding="utf-8"))
    chart = save_generalization_figure(metrics.get("datasets", {}), args.out_dir / "generalization_chart.png")
    poster = save_poster(metrics, args.metrics.parent, args.out_dir / "poster.png")
    print(f"generalization chart: {chart}")
    print(f"poster: {poster}")


if __name__ == "__main__":
    main()
