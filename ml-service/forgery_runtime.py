"""Forgery model load + Grad-CAM helpers for the inference service."""

from __future__ import annotations

import base64
import io
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from torchvision import models, transforms

IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)


class ForgeryModel(nn.Module):
    def __init__(self, pretrained: bool = False) -> None:
        super().__init__()
        weights = models.EfficientNet_B0_Weights.IMAGENET1K_V1 if pretrained else None
        backbone = models.efficientnet_b0(weights=weights)
        in_features = backbone.classifier[1].in_features
        backbone.classifier = nn.Sequential(
            nn.Dropout(p=0.2, inplace=True),
            nn.Linear(in_features, 1),
        )
        self.backbone = backbone
        self.target_layer = self.backbone.features[-1]

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.backbone(x)


EVAL_TRANSFORM = transforms.Compose(
    [
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
    ]
)


def load_forgery_model(checkpoint: Path, device: torch.device) -> ForgeryModel:
    payload = torch.load(checkpoint, map_location=device, weights_only=False)
    model = ForgeryModel(pretrained=False).to(device)
    model.load_state_dict(payload["model_state_dict"])
    model.eval()
    return model


def bytes_to_rgb(image_bytes: bytes) -> np.ndarray:
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    return np.asarray(image)


def rgb_to_tensor(rgb: np.ndarray, device: torch.device) -> torch.Tensor:
    pil = Image.fromarray(rgb).convert("RGB")
    return EVAL_TRANSFORM(pil).unsqueeze(0).to(device)


class GradCAM:
    def __init__(self, model: ForgeryModel) -> None:
        self.model = model
        self.activations: torch.Tensor | None = None
        self.gradients: torch.Tensor | None = None
        layer = model.target_layer
        self._fwd = layer.register_forward_hook(self._on_fwd)
        self._bwd = layer.register_full_backward_hook(self._on_bwd)

    def _on_fwd(self, _m: Any, _i: Any, output: torch.Tensor) -> None:
        self.activations = output.detach()

    def _on_bwd(self, _m: Any, _gi: Any, gout: tuple[torch.Tensor, ...]) -> None:
        self.gradients = gout[0].detach()

    def close(self) -> None:
        self._fwd.remove()
        self._bwd.remove()

    def __call__(self, tensor: torch.Tensor) -> np.ndarray:
        self.model.zero_grad(set_to_none=True)
        logits = self.model(tensor)
        logits[:, 0].sum().backward()
        assert self.activations is not None and self.gradients is not None
        weights = self.gradients.mean(dim=(2, 3), keepdim=True)
        cam = F.relu((weights * self.activations).sum(dim=1, keepdim=True))
        cam = F.interpolate(cam, size=(224, 224), mode="bilinear", align_corners=False)
        cam_np = cam.squeeze().detach().cpu().numpy()
        cam_np = (cam_np - cam_np.min()) / (np.ptp(cam_np) + 1e-8)
        return cam_np.astype(np.float32)


def overlay_heatmap_b64(rgb: np.ndarray, cam: np.ndarray, alpha: float = 0.45) -> str:
    base = cv2.resize(rgb, (224, 224))
    heat = cv2.applyColorMap((cam * 255).astype(np.uint8), cv2.COLORMAP_JET)
    heat = cv2.cvtColor(heat, cv2.COLOR_BGR2RGB)
    blended = (alpha * heat + (1.0 - alpha) * base).astype(np.uint8)
    bgr = cv2.cvtColor(blended, cv2.COLOR_RGB2BGR)
    ok, buf = cv2.imencode(".png", bgr)
    if not ok:
        raise RuntimeError("PNG encode failed")
    return base64.b64encode(buf.tobytes()).decode("ascii")
