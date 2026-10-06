"""Phase 29: the image degradations a re-shared photo goes through, shared by training and evaluation.

``robustness.py`` measures the forgery score under fixed instances of these (JPEG 50/70/90, a 0.5
rescale, blur, a screenshot, a social-app transcode). ``RandomDegradation`` applies random chains
of the same operations during training to *both* classes, so compression, resampling and blur stop
being evidence of forgery. WebP is deliberately never used in training: it is the held-out
transform that shows whether the robustness generalises beyond the augmentations themselves.
"""

from __future__ import annotations

import io
import random
from collections.abc import Callable

from PIL import Image, ImageEnhance, ImageFilter, features

Perturb = Callable[[Image.Image], Image.Image]

DEFAULT_DEGRADE_P = 0.5


def jpeg(quality: int) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        buf = io.BytesIO()
        img.convert("RGB").save(buf, format="JPEG", quality=quality)
        buf.seek(0)
        return Image.open(buf).convert("RGB")

    return apply


def webp(quality: int) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        buf = io.BytesIO()
        img.convert("RGB").save(buf, format="WEBP", quality=quality)
        buf.seek(0)
        return Image.open(buf).convert("RGB")

    return apply


def rescale(factor: float) -> Perturb:
    """Downscale then upscale back: the detail loss of a thumbnail or a low-resolution re-share."""

    def apply(img: Image.Image) -> Image.Image:
        w, h = img.size
        small = img.resize((max(1, int(w * factor)), max(1, int(h * factor))), Image.BILINEAR)
        return small.resize((w, h), Image.BILINEAR)

    return apply


def center_crop(fraction: float) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        w, h = img.size
        cw, ch = int(w * fraction), int(h * fraction)
        left, top = (w - cw) // 2, (h - ch) // 2
        return img.crop((left, top, left + cw, top + ch))

    return apply


def blur(radius: float) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        return img.filter(ImageFilter.GaussianBlur(radius))

    return apply


def tone(brightness: float, contrast: float) -> Perturb:
    def apply(img: Image.Image) -> Image.Image:
        return ImageEnhance.Contrast(ImageEnhance.Brightness(img).enhance(brightness)).enhance(contrast)

    return apply


def screenshot(img: Image.Image) -> Image.Image:
    """Display-and-capture approximation: resample, slight brightness/contrast shift, PNG-then-JPEG."""
    w, h = img.size
    out = img.resize((int(w * 1.25), int(h * 1.25)), Image.BICUBIC).resize((w, h), Image.BICUBIC)
    return jpeg(85)(tone(1.06, 0.94)(out))


def social_transcode(img: Image.Image) -> Image.Image:
    """Typical platform re-encode: downscale to 75% and JPEG quality 75."""
    return jpeg(75)(rescale(0.75)(img))


def webp_available() -> bool:
    return bool(features.check("webp"))


class RandomDegradation:
    """With probability ``p``, a random re-share chain: resample, blur, tone shift, then (double) JPEG.

    The order follows a real pipeline (resize and filters happen before the final encode). At least
    one JPEG encode is always applied when the chain fires, because that is the artefact the
    unaugmented model mistook for forgery.
    """

    def __init__(self, p: float = DEFAULT_DEGRADE_P, rng: random.Random | None = None) -> None:
        if not 0.0 <= p <= 1.0:
            raise ValueError(f"p must be in [0, 1], got {p}")
        self.p = p
        self.rng = rng or random.Random()

    def chain(self) -> list[Perturb]:
        r = self.rng
        ops: list[Perturb] = []
        if r.random() < 0.5:
            ops.append(rescale(r.uniform(0.4, 1.0)))
        if r.random() < 0.3:
            ops.append(blur(r.uniform(0.3, 2.0)))
        if r.random() < 0.3:
            ops.append(tone(r.uniform(0.9, 1.1), r.uniform(0.9, 1.1)))
        ops.append(jpeg(r.randint(30, 95)))
        if r.random() < 0.3:
            ops.append(jpeg(r.randint(50, 95)))
        return ops

    def __call__(self, img: Image.Image) -> Image.Image:
        if self.p == 0.0 or self.rng.random() >= self.p:
            return img
        out = img.convert("RGB")
        for op in self.chain():
            out = op(out)
        return out
