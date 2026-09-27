#!/usr/bin/env python3
"""Explainability: Grad-CAM heatmap, FFT spectrum, Error Level Analysis."""

from __future__ import annotations

import argparse
import base64
import io
import sys
from pathlib import Path

import cv2
import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import IMAGENET_MEAN, IMAGENET_STD, default_eval_transform
from models.forgery_model import ForgeryModel, load_checkpoint


def _to_base64_png(image_bgr_or_rgb: np.ndarray, *, rgb: bool = False) -> str:
    if rgb:
        image = cv2.cvtColor(image_bgr_or_rgb, cv2.COLOR_RGB2BGR)
    else:
        image = image_bgr_or_rgb
    ok, buf = cv2.imencode(".png", image)
    if not ok:
        raise RuntimeError("Failed to encode PNG")
    return base64.b64encode(buf.tobytes()).decode("ascii")


def fft_spectrum(image_rgb: np.ndarray) -> np.ndarray:
    """Return a visualization of the log magnitude FFT spectrum (RGB uint8)."""
    gray = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    f = np.fft.fftshift(np.fft.fft2(gray))
    magnitude = np.log1p(np.abs(f))
    magnitude = (magnitude - magnitude.min()) / (np.ptp(magnitude) + 1e-8)
    mag_u8 = (magnitude * 255).astype(np.uint8)
    colored = cv2.applyColorMap(mag_u8, cv2.COLORMAP_INFERNO)
    return cv2.cvtColor(colored, cv2.COLOR_BGR2RGB)


def error_level_analysis(image_rgb: np.ndarray, quality: int = 90, scale: int = 15) -> np.ndarray:
    """ELA: resave as JPEG and amplify absolute residual (RGB uint8)."""
    pil = Image.fromarray(image_rgb)
    buf = io.BytesIO()
    pil.save(buf, format="JPEG", quality=quality)
    buf.seek(0)
    recompressed = np.array(Image.open(buf).convert("RGB"))
    diff = np.abs(image_rgb.astype(np.float32) - recompressed.astype(np.float32))
    amplified = np.clip(diff * scale, 0, 255).astype(np.uint8)
    return amplified


class GradCAM:
    """Grad-CAM over the EfficientNet features block."""

    def __init__(self, model: ForgeryModel) -> None:
        self.model = model
        self.activations: torch.Tensor | None = None
        self.gradients: torch.Tensor | None = None
        layer = model.target_layer
        self._fwd = layer.register_forward_hook(self._save_activation)
        self._bwd = layer.register_full_backward_hook(self._save_gradient)

    def _save_activation(self, _module, _inp, output) -> None:
        self.activations = output.detach()

    def _save_gradient(self, _module, _gin, gout) -> None:
        self.gradients = gout[0].detach()

    def close(self) -> None:
        self._fwd.remove()
        self._bwd.remove()

    def __call__(self, input_tensor: torch.Tensor) -> np.ndarray:
        self.model.zero_grad(set_to_none=True)
        logits = self.model(input_tensor)
        score = logits[:, 0].sum()
        score.backward()
        assert self.activations is not None and self.gradients is not None
        weights = self.gradients.mean(dim=(2, 3), keepdim=True)
        cam = (weights * self.activations).sum(dim=1, keepdim=True)
        cam = F.relu(cam)
        cam = F.interpolate(cam, size=(224, 224), mode="bilinear", align_corners=False)
        cam = cam.squeeze().cpu().numpy()
        cam = (cam - cam.min()) / (np.ptp(cam) + 1e-8)
        return cam.astype(np.float32)


def overlay_heatmap(image_rgb: np.ndarray, cam: np.ndarray, alpha: float = 0.45) -> np.ndarray:
    heat = cv2.applyColorMap((cam * 255).astype(np.uint8), cv2.COLORMAP_JET)
    heat = cv2.cvtColor(heat, cv2.COLOR_BGR2RGB)
    base = cv2.resize(image_rgb, (224, 224))
    blended = (alpha * heat + (1 - alpha) * base).astype(np.uint8)
    return blended


def prepare_tensor(image_rgb: np.ndarray, device: torch.device) -> torch.Tensor:
    transform = default_eval_transform()
    pil = Image.fromarray(image_rgb).convert("RGB")
    return transform(pil).unsqueeze(0).to(device)


def explain_image(
    image_path: Path,
    checkpoint: Path,
    *,
    device: str | None = None,
) -> dict[str, str]:
    """Return base64 PNG strings for heatmap, fft, and ela."""
    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    model, _ = load_checkpoint(checkpoint, device=device_t)
    model.eval()

    bgr = cv2.imread(str(image_path))
    if bgr is None:
        raise FileNotFoundError(f"Cannot read image: {image_path}")
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    rgb_224 = cv2.resize(rgb, (224, 224))

    tensor = prepare_tensor(rgb_224, device_t)
    cam_engine = GradCAM(model)
    try:
        cam = cam_engine(tensor)
    finally:
        cam_engine.close()

    heatmap = overlay_heatmap(rgb_224, cam)
    fft_img = fft_spectrum(rgb_224)
    ela_img = error_level_analysis(rgb_224)

    return {
        "heatmap": _to_base64_png(heatmap, rgb=True),
        "fft": _to_base64_png(fft_img, rgb=True),
        "ela": _to_base64_png(ela_img, rgb=True),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Grad-CAM + FFT + ELA explainability.")
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=ROOT / "models" / "checkpoint.pt",
    )
    parser.add_argument("--device", type=str, default=None)
    parser.add_argument(
        "--out-json",
        type=Path,
        default=None,
        help="Optional path to write base64 JSON payload",
    )
    args = parser.parse_args()
    result = explain_image(args.image.resolve(), args.checkpoint.resolve(), device=args.device)
    for key, value in result.items():
        print(f"{key}: data:image/png;base64,{value[:48]}... ({len(value)} chars)")
    if args.out_json:
        import json

        args.out_json.write_text(json.dumps({k: v[:80] + "..." for k, v in result.items()}, indent=2))
        # Write full payload alongside truncated preview
        full = args.out_json.with_suffix(".full.json")
        full.write_text(json.dumps(result), encoding="utf-8")
        print(f"Wrote {full}")


if __name__ == "__main__":
    main()
