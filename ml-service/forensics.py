"""Forensic signatures and panels for evidentiary reports (Phase 15).

Raw media is never stored, so at analysis time we keep only *coarse numeric signatures* that the
backend can render into report panels later:

- ``cam``: 14x14 Grad-CAM attention grid (where the forgery model looked)
- ``ela``: 16x16 grid of mean JPEG error-level residual per block, plus summary stats
- ``fft``: 32x32 centred log-magnitude spectrum (no phase) and a 32-bin radial profile

None of these can be inverted back into a recognisable image. When a moderator re-supplies the
original at report time, :func:`render_panels` produces full-resolution panels instead; those are
returned to the caller and never persisted.
"""

from __future__ import annotations

import base64
import io

import cv2
import numpy as np
from PIL import Image

CAM_GRID = 14
ELA_GRID = 16
FFT_GRID = 32
FFT_BINS = 32
ANALYSIS_SIZE = 224
ELA_QUALITY = 90
ELA_SCALE = 15


def _rgb(image_bytes: bytes) -> np.ndarray:
    with Image.open(io.BytesIO(image_bytes)) as img:
        return np.asarray(img.convert("RGB"))


def _grid(values: np.ndarray, size: int) -> list[list[float]]:
    small = cv2.resize(values.astype(np.float32), (size, size), interpolation=cv2.INTER_AREA)
    return np.round(small.astype(np.float64), 2).tolist()


def _normalise(values: np.ndarray) -> np.ndarray:
    return (values - values.min()) / (np.ptp(values) + 1e-8)


def ela_residual(rgb: np.ndarray) -> np.ndarray:
    """Per-pixel absolute difference after a JPEG re-save (float32, RGB)."""
    buf = io.BytesIO()
    Image.fromarray(rgb).save(buf, format="JPEG", quality=ELA_QUALITY)
    buf.seek(0)
    recompressed = np.asarray(Image.open(buf).convert("RGB"), dtype=np.float32)
    return np.abs(rgb.astype(np.float32) - recompressed)


def log_spectrum(rgb: np.ndarray) -> np.ndarray:
    """Centred log-magnitude FFT of the luminance, normalised to [0, 1]."""
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    magnitude = np.log1p(np.abs(np.fft.fftshift(np.fft.fft2(gray))))
    return _normalise(magnitude)


def high_frequency_ratio(rgb: np.ndarray) -> float:
    """Share of spectral power (DC excluded) beyond half the maximum radius. Upsampling and
    generator artefacts shift this; heavily smoothed images score low."""
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    power = np.abs(np.fft.fftshift(np.fft.fft2(gray - gray.mean()))) ** 2
    h, w = power.shape
    y, x = np.indices((h, w))
    r = np.hypot(x - w / 2, y - h / 2)
    return float(power[r > r.max() / 2].sum() / (power.sum() + 1e-8))


def radial_profile(spectrum: np.ndarray, bins: int = FFT_BINS) -> np.ndarray:
    h, w = spectrum.shape
    y, x = np.indices((h, w))
    r = np.hypot(x - w / 2, y - h / 2)
    r_norm = np.clip(r / r.max(), 0, 1 - 1e-9)
    idx = (r_norm * bins).astype(int)
    sums = np.bincount(idx.ravel(), weights=spectrum.ravel(), minlength=bins)
    counts = np.bincount(idx.ravel(), minlength=bins)
    return sums / np.maximum(counts, 1)


def signature(image_bytes: bytes, cam: np.ndarray) -> dict:
    """Coarse, non-invertible forensic signature for storage alongside scores."""
    rgb = cv2.resize(_rgb(image_bytes), (ANALYSIS_SIZE, ANALYSIS_SIZE), interpolation=cv2.INTER_AREA)

    ela = ela_residual(rgb).mean(axis=2)
    spectrum = log_spectrum(rgb)
    profile = radial_profile(spectrum)
    high = high_frequency_ratio(rgb)

    return {
        "version": 1,
        "cam": {"grid": _grid(cam, CAM_GRID), "peak": _peak(cam)},
        "ela": {
            "grid": _grid(ela / 255.0 * ELA_SCALE, ELA_GRID),
            "mean": round(float(ela.mean()), 3),
            "p95": round(float(np.percentile(ela, 95)), 3),
            "max": round(float(ela.max()), 3),
            "quality": ELA_QUALITY,
        },
        "fft": {
            "grid": _grid(spectrum, FFT_GRID),
            "profile": np.round(profile.astype(np.float64), 3).tolist(),
            "high_freq_ratio": round(high, 4),
        },
    }


def _peak(cam: np.ndarray) -> dict:
    """Location of the strongest attention as fractions of width/height."""
    y, x = np.unravel_index(int(np.argmax(cam)), cam.shape)
    return {"x": round(x / cam.shape[1], 3), "y": round(y / cam.shape[0], 3)}


def _png_b64(rgb: np.ndarray) -> str:
    ok, buf = cv2.imencode(".png", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    if not ok:
        raise RuntimeError("PNG encode failed")
    return base64.b64encode(buf.tobytes()).decode("ascii")


def render_panels(image_bytes: bytes) -> dict[str, str]:
    """Full-resolution FFT and ELA panels (base64 PNG) from an original re-supplied at report time."""
    rgb = cv2.resize(_rgb(image_bytes), (ANALYSIS_SIZE * 2, ANALYSIS_SIZE * 2), interpolation=cv2.INTER_AREA)
    spectrum = (log_spectrum(rgb) * 255).astype(np.uint8)
    fft_rgb = cv2.cvtColor(cv2.applyColorMap(spectrum, cv2.COLORMAP_INFERNO), cv2.COLOR_BGR2RGB)
    ela_rgb = np.clip(ela_residual(rgb) * ELA_SCALE, 0, 255).astype(np.uint8)
    return {"fft": _png_b64(fft_rgb), "ela": _png_b64(ela_rgb)}
