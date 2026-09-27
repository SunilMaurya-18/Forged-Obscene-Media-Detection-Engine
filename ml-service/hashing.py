"""Perceptual hashing via imagehash."""

from __future__ import annotations

import io

from PIL import Image
import imagehash


def compute_phash(image_bytes: bytes) -> str:
    """Return a perceptual hash hex string for the image bytes."""
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    return str(imagehash.phash(image))
