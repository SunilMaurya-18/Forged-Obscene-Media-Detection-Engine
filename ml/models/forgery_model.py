"""EfficientNet-B0 forgery classifier with a binary head."""

from __future__ import annotations

from typing import Any

import torch
import torch.nn as nn
from torchvision import models


class ForgeryModel(nn.Module):
    """Real-vs-fake classifier on 224×224 RGB face crops."""

    def __init__(self, pretrained: bool = True) -> None:
        super().__init__()
        weights = models.EfficientNet_B0_Weights.IMAGENET1K_V1 if pretrained else None
        backbone = models.efficientnet_b0(weights=weights)
        in_features = backbone.classifier[1].in_features
        backbone.classifier = nn.Sequential(
            nn.Dropout(p=0.2, inplace=True),
            nn.Linear(in_features, 1),
        )
        self.backbone = backbone
        # Last conv block used for Grad-CAM
        self.target_layer = self.backbone.features[-1]

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """Return logits of shape (N, 1)."""
        return self.backbone(x)

    def predict_proba(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.forward(x))


def build_model(pretrained: bool = True, device: str | torch.device | None = None) -> ForgeryModel:
    model = ForgeryModel(pretrained=pretrained)
    if device is not None:
        model = model.to(device)
    return model


def save_checkpoint(
    path: str | Any,
    model: ForgeryModel,
    *,
    epoch: int,
    metrics: dict | None = None,
    extra: dict | None = None,
) -> None:
    payload = {
        "arch": "efficientnet_b0",
        "epoch": epoch,
        "model_state_dict": model.state_dict(),
        "metrics": metrics or {},
        **(extra or {}),
    }
    torch.save(payload, path)


def load_checkpoint(
    path: str | Any,
    device: str | torch.device | None = None,
    *,
    pretrained: bool = False,
) -> tuple[ForgeryModel, dict]:
    map_location = device or "cpu"
    payload = torch.load(path, map_location=map_location, weights_only=False)
    model = build_model(pretrained=pretrained, device=device)
    model.load_state_dict(payload["model_state_dict"])
    model.eval()
    return model, payload
