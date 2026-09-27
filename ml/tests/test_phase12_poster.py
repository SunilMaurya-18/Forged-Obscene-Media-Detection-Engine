"""Phase 12 poster + generalization chart."""

from __future__ import annotations

from poster import headline_stats, save_poster
from plots import save_generalization_figure

METRICS = {
    "datasets": {
        "faceforensics++": {"accuracy": 0.95, "f1": 0.94, "auc": 0.98, "n": 100},
        "dfdc": {"accuracy": 0.70, "f1": 0.68, "auc": 0.75, "n": 50},
        "celeb-df": {"accuracy": 0.60, "f1": 0.55, "auc": 0.65, "n": 50},
    },
    "latency": {"p50_ms": 120.0, "p95_ms": 180.0},
    "false_positive_rate": {"fpr": 0.1, "false_positives": 1, "n_clean": 10, "threshold": 0.5},
}


def test_headline_stats_average_held_out_accuracy():
    tiles = {label: value for label, value, _ in headline_stats(METRICS)}
    assert tiles["In-domain accuracy"] == "95.0%"
    assert tiles["Cross-dataset accuracy"] == "65.0%"
    assert tiles["Clean-set FPR"] == "10.0%"


def test_poster_and_chart_written(tmp_path):
    chart = save_generalization_figure(METRICS["datasets"], tmp_path / "chart.png")
    poster = save_poster(METRICS, tmp_path, tmp_path / "poster.png")
    assert chart.stat().st_size > 0
    assert poster.stat().st_size > 0
