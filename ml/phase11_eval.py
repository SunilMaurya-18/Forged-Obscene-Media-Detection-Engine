#!/usr/bin/env python3
"""Phase 11: cross-dataset eval charts, API latency, clean-set FPR, failure gallery."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from io import BytesIO
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageDraw
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "data"))

from dataset import CropCsvDataset, default_eval_transform, read_split_csv  # noqa: E402
from evaluate import (  # noqa: E402
    _json_safe,
    collect_predictions,
    compute_metrics,
    resolve_eval_csvs,
    roc_auc,
)
from make_synthetic_crops import make_synthetic_workspace  # noqa: E402
from models.forgery_model import load_checkpoint  # noqa: E402
from plots import confusion_counts, roc_curve_points, save_confusion_figure, save_roc_figure  # noqa: E402


def ensure_eval_data(work: Path, seed: int = 42) -> dict:
    crops = work / "crops"
    splits = work / "splits"
    if (crops / "labels.csv").exists() and (splits / "val.csv").exists():
        return {"crops": crops, "splits": splits}
    return make_synthetic_workspace(work, n_per_class=24, seed=seed)


def run_cross_dataset_charts(
    checkpoint: Path,
    crops: Path,
    splits: Path,
    results_dir: Path,
) -> dict:
    device = torch.device("cpu")
    model, _ = load_checkpoint(checkpoint, device=device)
    datasets = resolve_eval_csvs(splits)
    curves: dict = {}
    matrices: dict = {}
    per_dataset: dict = {}
    raw_preds: dict = {}

    for name, csv_path in datasets.items():
        rows = read_split_csv(csv_path)
        loader = DataLoader(
            CropCsvDataset(rows, crops, default_eval_transform()),
            batch_size=8,
            shuffle=False,
        )
        probs, labels = collect_predictions(model, loader, device)
        metrics = compute_metrics(probs, labels)
        per_dataset[name] = metrics
        auc = metrics["auc"] if metrics["auc"] == metrics["auc"] else 0.0
        fpr, tpr = roc_curve_points(labels, probs)
        curves[name] = (fpr, tpr, float(auc))
        matrices[name] = confusion_counts(labels, probs)
        raw_preds[name] = {"probs": probs, "labels": labels, "rows": rows}
        print(
            f"{name}: acc={metrics['accuracy']:.4f} auc={metrics['auc']} "
            f"cm={matrices[name]}"
        )

    roc_path = results_dir / "roc_curve.png"
    cm_path = results_dir / "confusion_matrix.png"
    save_roc_figure(curves, roc_path)
    save_confusion_figure(matrices, cm_path)

    ff = per_dataset.get("faceforensics++", {})
    gaps = {}
    for holdout in ("dfdc", "celeb-df"):
        if holdout in per_dataset and ff:
            gaps[holdout] = {
                "accuracy_gap": float(ff.get("accuracy", 0) - per_dataset[holdout]["accuracy"]),
                "auc_gap": float((ff.get("auc") or 0) - (per_dataset[holdout].get("auc") or 0)),
                "f1_gap": float(ff.get("f1", 0) - per_dataset[holdout]["f1"]),
            }

    return {
        "datasets": per_dataset,
        "generalization_gap": gaps,
        "artifacts": {
            "roc_curve": str(roc_path.as_posix()),
            "confusion_matrix": str(cm_path.as_posix()),
        },
        "raw_preds": raw_preds,
        "confusion": matrices,
    }


def clean_image_bytes(seed: int) -> bytes:
    """Smooth natural-looking gradient — stands in for unaltered research stills."""
    rng = np.random.default_rng(seed)
    arr = np.zeros((224, 224, 3), dtype=np.uint8)
    for c in range(3):
        ramp = np.linspace(50 + c * 15, 170 - c * 8, 224, dtype=np.float32)
        arr[:, :, c] = ramp.astype(np.uint8)[None, :]
    noise = rng.normal(0, 2.5, arr.shape)
    out = np.clip(arr.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    img = Image.fromarray(out)
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def post_analyze(api_url: str, image_bytes: bytes, filename: str = "clean.jpg") -> tuple[dict, float]:
    boundary = "----Phase11Boundary"
    body = b"".join(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: image/jpeg\r\n\r\n",
            image_bytes,
            f"\r\n--{boundary}--\r\n".encode(),
        ]
    )
    req = urllib.request.Request(
        api_url,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=180) as resp:
        payload = json.loads(resp.read().decode())
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    return payload, elapsed_ms


def measure_latency(api_url: str, n: int = 8, warmup: int = 1) -> dict:
    times: list[float] = []
    for i in range(warmup + n):
        raw = clean_image_bytes(seed=1000 + i)
        _, ms = post_analyze(api_url, raw, filename=f"latency_{i}.jpg")
        if i >= warmup:
            times.append(ms)
            print(f"latency sample {i - warmup + 1}/{n}: {ms:.1f} ms")
    arr = np.asarray(times, dtype=np.float64)
    return {
        "n": n,
        "warmup": warmup,
        "mean_ms": float(arr.mean()),
        "p50_ms": float(np.percentile(arr, 50)),
        "p95_ms": float(np.percentile(arr, 95)),
        "min_ms": float(arr.min()),
        "max_ms": float(arr.max()),
        "samples_ms": [float(x) for x in times],
    }


def measure_fpr(api_url: str, n_clean: int = 20, threshold: float = 0.5) -> dict:
    """FPR = fraction of clean images predicted fake (forgery_score >= threshold)."""
    false_positives = 0
    scores: list[float] = []
    for i in range(n_clean):
        raw = clean_image_bytes(seed=2000 + i)
        payload, _ = post_analyze(api_url, raw, filename=f"clean_{i}.jpg")
        score = float(payload.get("forgeryScore", payload.get("forgery_score", 0)))
        scores.append(score)
        if score >= threshold:
            false_positives += 1
        print(f"clean {i + 1}/{n_clean}: forgery={score:.4f}")
    fpr = false_positives / max(n_clean, 1)
    return {
        "n_clean": n_clean,
        "threshold": threshold,
        "false_positives": false_positives,
        "fpr": float(fpr),
        "mean_forgery_score": float(np.mean(scores)),
        "scores": scores,
        "notes": (
            "Clean set = synthetic smooth gradients (stand-in for unaltered research stills). "
            "Replace with FF++ original crops when licensed data is available."
        ),
    }


def build_failure_gallery(
    crops: Path,
    raw_preds: dict,
    out_dir: Path,
    max_cases: int = 6,
) -> list[dict]:
    """
    Gallery of model mistakes using only synthetic/research-pipeline crops
    (never explicit content). Prefer false negatives / false positives.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    cases: list[dict] = []
    for dataset, blob in raw_preds.items():
        probs = blob["probs"]
        labels = blob["labels"]
        rows = blob["rows"]
        preds = (probs >= 0.5).astype(np.int64)
        for idx in range(len(rows)):
            if preds[idx] == labels[idx]:
                continue
            kind = "false_positive" if preds[idx] == 1 else "false_negative"
            src = crops / rows[idx]["path"]
            if not src.exists():
                continue
            dest_name = f"{dataset}_{kind}_{idx}.jpg"
            dest = out_dir / dest_name
            img = Image.open(src).convert("RGB").resize((224, 224))
            draw = ImageDraw.Draw(img)
            label = f"{kind} p={probs[idx]:.2f} y={int(labels[idx])}"
            draw.rectangle((0, 0, 223, 18), fill=(0, 0, 0))
            draw.text((4, 2), label, fill=(255, 220, 120))
            img.save(dest, quality=90)
            cases.append(
                {
                    "dataset": dataset,
                    "kind": kind,
                    "path": dest.as_posix(),
                    "source": rows[idx]["path"],
                    "prob": float(probs[idx]),
                    "label": int(labels[idx]),
                }
            )
            if len(cases) >= max_cases:
                break
        if len(cases) >= max_cases:
            break

    # If the smoke model is perfect, still emit illustrative near-miss panels
    if not cases:
        for dataset, blob in raw_preds.items():
            rows = blob["rows"]
            probs = blob["probs"]
            if not rows:
                continue
            # lowest-confidence correct predictions as "hard cases"
            order = np.argsort(np.abs(probs - 0.5))
            for j in order[:3]:
                src = crops / rows[j]["path"]
                if not src.exists():
                    continue
                dest = out_dir / f"{dataset}_hard_{j}.jpg"
                Image.open(src).convert("RGB").resize((224, 224)).save(dest, quality=90)
                cases.append(
                    {
                        "dataset": dataset,
                        "kind": "hard_correct",
                        "path": dest.as_posix(),
                        "source": rows[j]["path"],
                        "prob": float(probs[j]),
                        "label": int(blob["labels"][j]),
                    }
                )
            break

    (out_dir / "index.json").write_text(json.dumps(cases, indent=2), encoding="utf-8")
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description="Phase 11 evaluation suite")
    parser.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "checkpoint.pt")
    parser.add_argument("--work", type=Path, default=ROOT / ".smoke_data")
    parser.add_argument("--results", type=Path, default=ROOT / "results")
    parser.add_argument(
        "--api-url",
        default="http://host.docker.internal:8081/api/analyze",
        help="Full URL for POST /api/analyze",
    )
    parser.add_argument("--latency-n", type=int, default=8)
    parser.add_argument("--fpr-n", type=int, default=20)
    parser.add_argument("--skip-api", action="store_true")
    args = parser.parse_args()

    results_dir = args.results.resolve()
    results_dir.mkdir(parents=True, exist_ok=True)

    if not args.checkpoint.exists():
        raise SystemExit(f"Missing checkpoint: {args.checkpoint}")

    data = ensure_eval_data(args.work.resolve())
    print("Cross-dataset evaluation + charts…")
    eval_out = run_cross_dataset_charts(
        args.checkpoint.resolve(), data["crops"], data["splits"], results_dir
    )

    latency = None
    fpr = None
    if not args.skip_api:
        print(f"API benchmarks via {args.api_url}…")
        try:
            latency = measure_latency(args.api_url, n=args.latency_n)
            fpr = measure_fpr(args.api_url, n_clean=args.fpr_n)
        except urllib.error.URLError as exc:
            print(f"API unreachable ({exc}); writing offline metrics only.")
            latency = {"error": str(exc)}
            fpr = {"error": str(exc)}

    gallery_dir = results_dir / "failure_gallery"
    cases = build_failure_gallery(data["crops"], eval_out["raw_preds"], gallery_dir)

    metrics = {
        "checkpoint": str(args.checkpoint.resolve()),
        "datasets": eval_out["datasets"],
        "generalization_gap": eval_out["generalization_gap"],
        "artifacts": eval_out["artifacts"],
        "latency": latency,
        "false_positive_rate": fpr,
        "failure_gallery": {
            "dir": gallery_dir.as_posix(),
            "cases": cases,
            "policy": (
                "Synthetic/research-pipeline crops only (FaceForensics++/DFDC/Celeb-DF style splits). "
                "No non-consensual or explicit imagery."
            ),
        },
        "notes": (
            "Phase 11 smoke evaluation on synthetic cross-dataset splits unless licensed "
            "FF++/DFDC/Celeb-DF crops are provided under ml/data/crops."
        ),
    }
    metrics_path = results_dir / "metrics.json"
    # drop non-serializable
    metrics_path.write_text(json.dumps(_json_safe(metrics), indent=2), encoding="utf-8")
    print(f"Wrote {metrics_path}")
    if latency and "mean_ms" in latency:
        print(f"LATENCY_MEAN_MS={latency['mean_ms']:.1f}")
    if fpr and "fpr" in fpr:
        print(f"FPR={fpr['fpr']:.4f}")


if __name__ == "__main__":
    main()
