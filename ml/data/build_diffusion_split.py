#!/usr/bin/env python3
"""Phase 17: build a diffusion-era forgery split (train / val / held-out test, no identity leakage).

Two sources:

* ``synthetic`` (default, runs offline): every FaceForensics++ *real* crop from the Phase 1 splits
  gets a diffusion-style counterpart. A feathered region is pushed through a forward-noise /
  denoise pass (the way SD-inpainting regenerates a masked area), which removes the sensor noise
  there and leaves band-limited "decoder" texture instead of the 8x8 block grid GAN-era fakes
  carry. Pairs keep the Phase 1 split of their source identity, and the test split uses fresh
  identities that never appear in train or val.
* ``import``: a folder of crops produced by a real diffusion pipeline, laid out as
  ``<dir>/real/**`` and ``<dir>/fake/**`` with an optional ``identities.csv`` (path,identity).
  Identities are split 70/15/15 deterministically. Per the project rules, only use diffusion fakes
  generated from consenting-actor sources (e.g. inpainting over FF++ originals).

Output (next to the Phase 1 crops, so one crops root serves every split)::

    <crops>/diffusion/{real,fake}/*.jpg
    <splits>/diffusion/{train,val,test}.csv   columns: path,label,dataset,split,family,identity
    <splits>/diffusion/meta.json
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

import cv2
import numpy as np

from make_synthetic_crops import _make_crop

DATASET = "diffusion"
FIELDS = ["path", "label", "dataset", "split", "family", "identity"]
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}


def _feathered_ellipse(h: int, w: int, rng: np.random.Generator) -> np.ndarray:
    """Soft mask over a face-sized region, roughly where an inpainting mask would sit."""
    mask = np.zeros((h, w), dtype=np.float32)
    cx = int(w * rng.uniform(0.4, 0.6))
    cy = int(h * rng.uniform(0.4, 0.6))
    ax = int(w * rng.uniform(0.22, 0.34))
    ay = int(h * rng.uniform(0.26, 0.38))
    cv2.ellipse(mask, (cx, cy), (ax, ay), float(rng.uniform(-20, 20)), 0, 360, 1.0, -1)
    return cv2.GaussianBlur(mask, (0, 0), sigmaX=w * 0.04)[..., None]


def diffusion_regenerate(img: np.ndarray, seed: int, strength: float | None = None) -> np.ndarray:
    """Simulated diffusion inpainting of one region of a uint8 RGB/BGR crop.

    Forward-noise to ``strength`` (as in img2img/inpaint), then "denoise" with an edge-preserving
    filter: the regenerated area loses the camera's white sensor noise, gains low-amplitude
    mid-frequency texture from the decoder, and drifts slightly in colour. No periodic grid.
    """
    rng = np.random.default_rng(seed)
    s = float(strength if strength is not None else rng.uniform(0.45, 0.75))
    x = img.astype(np.float32)
    h, w = x.shape[:2]

    noised = np.sqrt(1 - s) * x + np.sqrt(s) * rng.normal(0, 40, x.shape).astype(np.float32)
    denoised = cv2.bilateralFilter(np.clip(noised, 0, 255).astype(np.uint8), 9, 60, 7).astype(np.float32)
    denoised = cv2.GaussianBlur(denoised, (0, 0), sigmaX=1.6)
    # Rescale toward the source's low-frequency content (the denoiser's x0 estimate)
    base = cv2.GaussianBlur(x, (0, 0), sigmaX=6)
    regenerated = base + (denoised - cv2.GaussianBlur(denoised, (0, 0), sigmaX=6)) * 0.35

    texture = cv2.GaussianBlur(rng.normal(0, 1, (h, w)).astype(np.float32), (0, 0), sigmaX=1.3)[..., None]
    regenerated += texture * rng.uniform(9, 14)
    regenerated += rng.uniform(-5, 5, size=(1, 1, x.shape[2])).astype(np.float32)

    mask = _feathered_ellipse(h, w, rng)
    out = mask * regenerated + (1 - mask) * x
    return np.clip(out, 0, 255).astype(np.uint8)


def _write_csv(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in FIELDS})


def _read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _pair(crops: Path, real_rel: str, identity: str, split: str, seed: int, write_real: np.ndarray | None) -> list[dict]:
    """Write the diffusion fake for one real crop (and the real itself if it is new)."""
    stem = Path(real_rel).stem
    if write_real is not None:
        real_rel = f"{DATASET}/real/{stem}.jpg"
        (crops / real_rel).parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(crops / real_rel), write_real)
    source = cv2.imread(str(crops / real_rel), cv2.IMREAD_COLOR)
    if source is None:
        raise FileNotFoundError(crops / real_rel)
    fake_rel = f"{DATASET}/fake/{stem}_dm.jpg"
    (crops / fake_rel).parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(crops / fake_rel), diffusion_regenerate(source, seed))
    return [
        {"path": real_rel, "label": "real", "dataset": DATASET, "split": split, "family": "", "identity": identity},
        {"path": fake_rel, "label": "fake", "dataset": DATASET, "split": split, "family": "diffusion", "identity": identity},
    ]


def build_synthetic(crops: Path, splits: Path, *, test_identities: int = 40, seed: int = 17) -> dict:
    out: dict[str, list[dict]] = {"train": [], "val": [], "test": []}
    for split in ("train", "val"):
        for i, row in enumerate(r for r in _read_csv(splits / f"{split}.csv") if r["label"] == "real"):
            out[split] += _pair(crops, row["path"], row["path"], split, seed * 1000 + len(out[split]) + i, None)
    for i in range(test_identities):
        real = _make_crop("real", seed=900_000 + seed * 1000 + i)
        out["test"] += _pair(crops, f"heldout_{i:04d}.jpg", f"heldout_{i:04d}", "test", seed * 7919 + i, real)
    return out


def _identity_split(identity: str, seed: int) -> str:
    bucket = int(hashlib.sha256(f"{seed}:{identity}".encode()).hexdigest(), 16) % 100
    return "train" if bucket < 70 else "val" if bucket < 85 else "test"


def build_import(crops: Path, source: Path, *, seed: int = 17) -> dict:
    ids: dict[str, str] = {}
    id_csv = source / "identities.csv"
    if id_csv.exists():
        ids = {r["path"].replace("\\", "/"): r["identity"] for r in _read_csv(id_csv)}
    out: dict[str, list[dict]] = {"train": [], "val": [], "test": []}
    for label in ("real", "fake"):
        for path in sorted(p for p in (source / label).rglob("*") if p.suffix.lower() in IMAGE_EXT):
            rel_src = path.relative_to(source).as_posix()
            identity = ids.get(rel_src, path.stem.split("_")[0])
            dest_rel = f"{DATASET}-import/{rel_src}"
            (crops / dest_rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, crops / dest_rel)
            split = _identity_split(identity, seed)
            out[split].append({
                "path": dest_rel, "label": label, "dataset": DATASET, "split": split,
                "family": "diffusion" if label == "fake" else "", "identity": identity,
            })
    return out


def run(crops: Path, splits: Path, *, source: str = "synthetic", import_dir: Path | None = None,
        test_identities: int = 40, seed: int = 17) -> dict:
    if source == "import":
        if import_dir is None:
            raise ValueError("--import-dir is required for --source import")
        rows = build_import(crops, import_dir, seed=seed)
    else:
        rows = build_synthetic(crops, splits, test_identities=test_identities, seed=seed)

    ids = {k: {r["identity"] for r in v} for k, v in rows.items()}
    leaks = (ids["train"] & ids["test"]) | (ids["val"] & ids["test"]) | (ids["train"] & ids["val"])
    if leaks:
        raise AssertionError(f"identity leakage across splits: {sorted(leaks)[:5]}")

    out_dir = splits / DATASET
    for split, split_rows in rows.items():
        _write_csv(out_dir / f"{split}.csv", split_rows)
    meta = {
        "source": source,
        "seed": seed,
        "counts": {k: {"real": sum(r["label"] == "real" for r in v), "fake": sum(r["label"] == "fake" for r in v)}
                   for k, v in rows.items()},
        "identity_disjoint": True,
        "notes": (
            "Synthetic diffusion-style inpainting over the Phase 1 real crops (stand-in until a real diffusion "
            "pipeline is run over licensed FF++ originals)." if source == "synthetic"
            else f"Imported from {import_dir}"
        ),
    }
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the Phase 17 diffusion-era split.")
    parser.add_argument("--crops", type=Path, required=True, help="Phase 1 crops root (labels.csv lives here)")
    parser.add_argument("--splits", type=Path, required=True, help="Phase 1 splits dir (train.csv / val.csv)")
    parser.add_argument("--source", choices=["synthetic", "import"], default="synthetic")
    parser.add_argument("--import-dir", type=Path, default=None)
    parser.add_argument("--test-identities", type=int, default=40)
    parser.add_argument("--seed", type=int, default=17)
    args = parser.parse_args()
    meta = run(args.crops.resolve(), args.splits.resolve(), source=args.source,
               import_dir=args.import_dir.resolve() if args.import_dir else None,
               test_identities=args.test_identities, seed=args.seed)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
