#!/usr/bin/env python3
"""Phase 29: the release gate a forgery model must pass before it is promoted or shipped.

    python ml/release_gate.py                      # check ml/results/metrics.json against ml/models
    python ml/release_gate.py --no-fingerprint     # thresholds only (e.g. for a candidate model)

Two kinds of check, all of which must pass (exit code 1 otherwise):

* **Robustness.** On the held-out ``robustness`` block: social-app transcoding may flip at most 5%
  of decisions (the model card's promotion rule), every other transform at most 10%, the clean AUC
  must be at least 0.90, and the evaluation set must have at least 30 images.
* **Fingerprint.** The block must have been measured on the exact files that ship: the checkpoint's
  SHA-256 and the ensemble's canonical hash (or the absence of an ensemble) must match ``ml/models``.
  A checkpoint swapped in without re-running the robustness suite fails here.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ML_SERVICE = ROOT.parent / "ml-service"
if str(ML_SERVICE) not in sys.path:
    sys.path.append(str(ML_SERVICE))

import ensemble as ensemble_lib  # noqa: E402

POLICY = {
    "max_flip_rate": {"social_transcode": 0.05},
    "default_max_flip_rate": 0.10,
    "min_baseline_auc": 0.90,
    "min_n": 30,
}


def _check(name: str, value, op: str, limit) -> dict:
    ok = value is not None and (value <= limit if op == "<=" else value >= limit if op == ">=" else value == limit)
    return {"name": name, "value": value, "op": op, "limit": limit, "passed": bool(ok)}


def check_robustness(block: dict | None, policy: dict = POLICY) -> list[dict]:
    if not block or not block.get("transforms"):
        return [_check("robustness block present", None, "==", True)]
    checks = [
        _check("held-out images", block.get("n"), ">=", policy["min_n"]),
        _check("clean AUC", (block.get("baseline") or {}).get("auc"), ">=", policy["min_baseline_auc"]),
    ]
    transforms = block["transforms"]
    for name in policy["max_flip_rate"]:
        if name not in transforms:
            checks.append(_check(f"flip rate · {name} (not measured)", None, "<=", policy["max_flip_rate"][name]))
    for name, t in transforms.items():
        limit = policy["max_flip_rate"].get(name, policy["default_max_flip_rate"])
        checks.append(_check(f"flip rate · {name}", t.get("flip_rate"), "<=", limit))
    return checks


def check_fingerprint(block: dict | None, models_dir: Path) -> list[dict]:
    model = (block or {}).get("model") or {}
    checkpoint = models_dir / "checkpoint.pt"
    actual_ckpt = ensemble_lib.file_sha256(checkpoint) if checkpoint.exists() else None
    checks = [_check("measured checkpoint is the shipped checkpoint",
                     model.get("checkpoint_sha256") == actual_ckpt and actual_ckpt is not None, "==", True)]
    ens_path = models_dir / "ensemble.json"
    actual_ens = ensemble_lib.canonical_sha256(json.loads(ens_path.read_text(encoding="utf-8"))) if ens_path.exists() else None
    checks.append(_check("measured ensemble is the shipped ensemble",
                         model.get("ensemble_sha256") == actual_ens, "==", True))
    return checks


def run_gate(metrics: dict, models_dir: Path | None = None, policy: dict = POLICY) -> dict:
    block = metrics.get("robustness")
    checks = check_robustness(block, policy)
    if models_dir is not None:
        checks += check_fingerprint(block, models_dir)
    return {"passed": all(c["passed"] for c in checks), "policy": policy, "checks": checks}


def format_report(result: dict) -> str:
    lines = []
    for c in result["checks"]:
        value = f"{c['value']:.3f}" if isinstance(c["value"], float) else str(c["value"])
        lines.append(f"  {'PASS' if c['passed'] else 'FAIL'}  {c['name']}: {value} (needs {c['op']} {c['limit']})")
    lines.append("Release gate: " + ("PASSED" if result["passed"] else "FAILED"))
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Forgery-model release gate")
    parser.add_argument("--metrics", type=Path, default=ROOT / "results" / "metrics.json")
    parser.add_argument("--models-dir", type=Path, default=ROOT / "models")
    parser.add_argument("--no-fingerprint", action="store_true")
    args = parser.parse_args()
    metrics = json.loads(args.metrics.read_text(encoding="utf-8"))
    result = run_gate(metrics, None if args.no_fingerprint else args.models_dir.resolve())
    print(format_report(result))
    sys.exit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
