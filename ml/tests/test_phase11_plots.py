"""Phase 11 plot helpers."""

from __future__ import annotations

import numpy as np

from plots import confusion_counts, roc_curve_points


def test_roc_and_confusion_shapes():
    labels = np.array([0, 0, 1, 1, 1, 0])
    probs = np.array([0.1, 0.4, 0.6, 0.9, 0.55, 0.2])
    fpr, tpr = roc_curve_points(labels, probs)
    assert len(fpr) == len(tpr) >= 2
    assert fpr[0] == 0 and tpr[0] == 0
    cm = confusion_counts(labels, probs, threshold=0.5)
    assert cm["tp"] + cm["fn"] == 3
    assert cm["tn"] + cm["fp"] == 3
