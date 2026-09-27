"""Plot ROC curves and confusion matrices for Phase 11 reports."""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


def roc_curve_points(labels: np.ndarray, probs: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    y = labels.astype(np.int64)
    order = np.argsort(-probs)
    y_sorted = y[order]
    n_pos = max(float((y == 1).sum()), 1.0)
    n_neg = max(float((y == 0).sum()), 1.0)
    tps = np.cumsum(y_sorted == 1)
    fps = np.cumsum(y_sorted == 0)
    tpr = np.concatenate([[0.0], tps / n_pos])
    fpr = np.concatenate([[0.0], fps / n_neg])
    return fpr, tpr


def confusion_counts(labels: np.ndarray, probs: np.ndarray, threshold: float = 0.5) -> dict[str, int]:
    preds = (probs >= threshold).astype(np.int64)
    return {
        "tp": int(((preds == 1) & (labels == 1)).sum()),
        "tn": int(((preds == 0) & (labels == 0)).sum()),
        "fp": int(((preds == 1) & (labels == 0)).sum()),
        "fn": int(((preds == 0) & (labels == 1)).sum()),
    }


def save_roc_figure(
    curves: dict[str, tuple[np.ndarray, np.ndarray, float]],
    out_path: Path,
) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(6.5, 5.5))
    ax.plot([0, 1], [0, 1], "--", color="#64748b", linewidth=1, label="chance")
    for name, (fpr, tpr, auc) in curves.items():
        ax.plot(fpr, tpr, linewidth=2, label=f"{name} (AUC={auc:.3f})")
    ax.set_xlabel("False positive rate")
    ax.set_ylabel("True positive rate")
    ax.set_title("Cross-dataset ROC (forgery detector)")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(True, alpha=0.25)
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path


def save_confusion_figure(
    matrices: dict[str, dict[str, int]],
    out_path: Path,
) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    names = list(matrices.keys())
    n = max(len(names), 1)
    fig, axes = plt.subplots(1, n, figsize=(4.2 * n, 4.0), squeeze=False)
    for ax, name in zip(axes[0], names):
        c = matrices[name]
        mat = np.array([[c["tp"], c["fn"]], [c["fp"], c["tn"]]], dtype=float)
        im = ax.imshow(mat, cmap="Blues")
        ax.set_xticks([0, 1], ["Pred fake", "Pred real"])
        ax.set_yticks([0, 1], ["Actual fake", "Actual real"])
        ax.set_title(name)
        for (i, j), val in np.ndenumerate(mat):
            ax.text(j, i, int(val), ha="center", va="center", color="black")
        fig.colorbar(im, ax=ax, fraction=0.046)
    fig.suptitle("Confusion matrices (threshold=0.5)")
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path


GENERALIZATION_METRICS = ("accuracy", "f1", "auc")


def draw_generalization_chart(ax: plt.Axes, datasets: dict[str, dict[str, float]]) -> None:
    """Grouped bars: one group per dataset (in-domain first), one bar per metric."""
    names = list(datasets.keys())
    x = np.arange(len(names))
    width = 0.8 / len(GENERALIZATION_METRICS)
    for i, metric in enumerate(GENERALIZATION_METRICS):
        values = [float(datasets[n].get(metric, 0.0)) for n in names]
        bars = ax.bar(x + (i - 1) * width, values, width, label=metric.upper())
        ax.bar_label(bars, fmt="%.2f", fontsize=7, padding=2)
    labels = [f"{n}\n(in-domain)" if i == 0 else f"{n}\n(held out)" for i, n in enumerate(names)]
    ax.set_xticks(x, labels)
    ax.set_ylim(0, 1.3)
    ax.set_yticks(np.linspace(0, 1, 6))
    ax.set_ylabel("Score")
    ax.set_title("Generalization: trained on FF++, tested cross-dataset")
    ax.legend(loc="upper center", ncol=len(GENERALIZATION_METRICS), fontsize=8)
    ax.grid(True, axis="y", alpha=0.25)


def save_generalization_figure(datasets: dict[str, dict[str, float]], out_path: Path) -> Path:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(7.5, 4.5))
    draw_generalization_chart(ax, datasets)
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path
