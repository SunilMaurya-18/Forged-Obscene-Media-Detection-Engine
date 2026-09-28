"""Phase 22: calibration, robustness and fairness numerics (model-free)."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML))

from calibration import (  # noqa: E402
    brier_score,
    calibration_report,
    expected_calibration_error,
    fit_temperature,
    is_separable,
    nll,
    sigmoid,
)
from fairness import fairness_report, group_metrics  # noqa: E402
from robustness import TRANSFORMS, robustness_report, stability  # noqa: E402


def _overconfident(n: int = 4000, true_t: float = 3.0, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Labels drawn from sigmoid(z); logits reported as z * true_t, i.e. overconfident by true_t."""
    rng = np.random.default_rng(seed)
    z = rng.normal(0.0, 1.5, n)
    labels = (rng.random(n) < sigmoid(z)).astype(np.int64)
    return z * true_t, labels


def test_fit_temperature_recovers_overconfidence():
    logits, labels = _overconfident()
    t, degenerate = fit_temperature(logits, labels)
    assert not degenerate
    assert t == pytest.approx(3.0, rel=0.15)
    assert nll(logits, labels, t) < nll(logits, labels, 1.0)


def test_calibration_improves_ece_on_held_out():
    fit_logits, fit_labels = _overconfident(seed=1)
    eval_logits, eval_labels = _overconfident(seed=2)
    report = calibration_report(fit_logits, fit_labels, eval_logits, eval_labels)
    assert report["degenerate"] is False
    assert report["ece_after"] < report["ece_before"]
    assert report["brier_after"] <= report["brier_before"]
    assert sum(b["n"] for b in report["reliability_after"]) == len(eval_labels)


def test_separable_fit_is_degenerate_and_not_applied():
    logits = np.array([-9.0, -7.0, -8.0, 6.0, 8.0, 7.5])
    labels = np.array([0, 0, 0, 1, 1, 1])
    assert is_separable(logits, labels)
    report = calibration_report(logits, labels, logits, labels)
    assert report["degenerate"] is True
    assert report["temperature"] == 1.0
    assert report["fitted_temperature"] is None
    assert report["ece_after"] == report["ece_before"]


def test_ece_and_brier_bounds():
    labels = np.array([0, 1, 0, 1])
    perfect = np.array([0.0, 1.0, 0.0, 1.0])
    assert expected_calibration_error(perfect, labels) == pytest.approx(0.0)
    assert brier_score(perfect, labels) == pytest.approx(0.0)
    wrong = 1.0 - perfect
    assert expected_calibration_error(wrong, labels) == pytest.approx(1.0)
    assert brier_score(wrong, labels) == pytest.approx(1.0)


def test_stability_counts_flips_and_shifts():
    labels = np.array([0, 0, 1, 1])
    base = np.array([0.1, 0.45, 0.9, 0.6])
    moved = np.array([0.1, 0.55, 0.9, 0.4])
    s = stability(base, moved, labels, threshold=0.5)
    assert s["flip_rate"] == pytest.approx(0.5)
    assert s["mean_abs_shift"] == pytest.approx(0.075)
    assert s["mean_shift_real"] == pytest.approx(0.05)
    assert s["mean_shift_fake"] == pytest.approx(-0.1)


def test_robustness_report_names_worst_transform():
    labels = np.array([0, 0, 1, 1])
    base = np.array([0.1, 0.2, 0.8, 0.9])
    report = robustness_report(
        base,
        labels,
        {"mild": base + 0.01, "harsh": np.array([0.6, 0.2, 0.3, 0.9])},
    )
    assert report["worst_transform"] == "harsh"
    assert report["transforms"]["mild"]["flip_rate"] == 0.0
    assert report["transforms"]["harsh"]["flip_rate"] == pytest.approx(0.5)


@pytest.mark.parametrize("name", sorted(TRANSFORMS))
def test_transforms_return_rgb_images(name):
    rng = np.random.default_rng(3)
    img = Image.fromarray(rng.integers(0, 255, (224, 224, 3), dtype=np.uint8))
    out = TRANSFORMS[name](img)
    assert out.mode == "RGB"
    assert min(out.size) > 0


def _annotated(n_per_group: int, groups: tuple[str, ...], seed: int = 4):
    rng = np.random.default_rng(seed)
    rows, probs, labels = [], [], []
    for g_i, g in enumerate(groups):
        for i in range(n_per_group):
            y = i % 2
            p = 0.8 if y else 0.2 + 0.15 * g_i
            rows.append({"path": f"{g}/{i}.jpg", "label": "fake" if y else "real", "skin_tone": g})
            probs.append(float(np.clip(p + rng.normal(0, 0.2), 0, 1)))
            labels.append(y)
    return rows, np.array(probs), np.array(labels)


def test_fairness_not_run_without_annotations():
    rows = [{"path": "a.jpg", "label": "real"}, {"path": "b.jpg", "label": "fake"}]
    report = fairness_report(rows, np.array([0.1, 0.9]), np.array([0, 1]))
    assert report["status"] == "not_run"
    assert "no demographic annotations" in report["reason"]
    assert "attributes" not in report


def test_fairness_small_groups_are_insufficient_not_reported():
    rows, probs, labels = _annotated(10, ("A", "B"))
    report = fairness_report(rows, probs, labels, attributes=("skin_tone",), min_n=30, n_boot=50)
    groups = report["attributes"]["skin_tone"]["groups"]
    assert {g["status"] for g in groups.values()} == {"insufficient_data"}
    assert all("fpr" not in g for g in groups.values())
    assert report["attributes"]["skin_tone"]["disparity"] is None


def test_fairness_reports_rates_ci_and_disparity():
    probs_rows = _annotated(80, ("A", "B"))
    result = group_metrics(
        probs_rows[1], probs_rows[2], [r["skin_tone"] for r in probs_rows[0]], min_n=30, n_boot=200
    )
    a, b = result["groups"]["A"], result["groups"]["B"]
    assert a["status"] == b["status"] == "ok"
    for g in (a, b):
        assert 0.0 <= g["fpr"] <= 1.0 and 0.0 <= g["fnr"] <= 1.0
        lo, hi = g["fpr_ci95"]
        assert lo <= g["fpr"] <= hi
    assert b["fpr"] > a["fpr"]
    assert result["disparity"]["fpr"]["worst_group"] == "B"
    assert result["disparity"]["fpr"]["max_minus_min"] == pytest.approx(b["fpr"] - a["fpr"])
