#!/usr/bin/env python3
"""Phase 18 fixtures: the synthetic prescreen demo image and pHash parity vectors for the browser port.

Run from the repo root (inside the ml-service image, which has imagehash)::

    python ml-service/tools/phash_vectors.py

Writes ``frontend/public/prescreen-demo.png`` (an abstract test pattern, no people) and
``frontend/src/utils/phash-vectors.json`` (the 32x32 greyscale input imagehash sees + its hash), and
prints the demo image's pHash for ``PRESCREEN_SEED_HASHES``.
"""

from __future__ import annotations

import json
from pathlib import Path

import imagehash
import numpy as np
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[2]


def demo_image() -> Image.Image:
    w, h = 640, 480
    x = np.linspace(0, 1, w)[None, :]
    y = np.linspace(0, 1, h)[:, None]
    base = np.stack([60 + 120 * x + 0 * y, 40 + 90 * y + 0 * x, 140 + 80 * (1 - x) * y], axis=-1)
    img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)
    d.ellipse((70, 60, 300, 290), fill=(250, 196, 70))
    d.rectangle((340, 90, 590, 250), fill=(30, 40, 90))
    d.polygon([(120, 440), (330, 300), (520, 440)], fill=(236, 72, 153))
    d.ellipse((420, 300, 560, 440), outline=(255, 255, 255), width=10)
    d.text((24, 20), "PRESCREEN DEMO PATTERN - SYNTHETIC", fill=(255, 255, 255))
    return img


def gray32(img: Image.Image) -> np.ndarray:
    return np.asarray(img.convert("L").resize((32, 32), Image.Resampling.LANCZOS))


def main() -> None:
    demo = demo_image()
    demo_path = REPO / "frontend" / "public" / "prescreen-demo.png"
    demo.save(demo_path)

    rng = np.random.default_rng(18)
    images = {"prescreen-demo": demo}
    for i in range(5):
        arr = (rng.random((96, 128, 3)) * 255).astype(np.uint8)
        images[f"noise-{i}"] = Image.fromarray(arr, "RGB").resize((320, 240), Image.Resampling.BICUBIC)

    # DCT + median + hex stage, from the exact 32x32 input imagehash sees
    dct_vectors = [{"name": name, "gray32": gray32(img).tolist(), "hash": str(imagehash.phash(img))}
                   for name, img in images.items()]
    # Whole pipeline (RGB → L → Lanczos 32x32 → DCT) on small images, so the resampler is covered too
    small = {
        "demo-80x60": demo.resize((80, 60), Image.Resampling.BILINEAR),
        "noise-57x43": Image.fromarray((rng.random((43, 57, 3)) * 255).astype(np.uint8), "RGB"),
        "noise-40x70": images["noise-1"].resize((40, 70), Image.Resampling.BILINEAR),
    }
    pipeline_vectors = [
        {"name": name, "width": img.width, "height": img.height,
         "rgb": np.asarray(img.convert("RGB")).reshape(-1).tolist(), "hash": str(imagehash.phash(img))}
        for name, img in small.items()
    ]
    out = REPO / "frontend" / "src" / "utils" / "phash-vectors.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"dct": dct_vectors, "pipeline": pipeline_vectors}), encoding="utf-8")
    print(json.dumps({"demo_image": str(demo_path.relative_to(REPO)), "demo_phash": dct_vectors[0]["hash"],
                      "vectors": len(dct_vectors) + len(pipeline_vectors)}, indent=2))


if __name__ == "__main__":
    main()
