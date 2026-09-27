"""EfficientNet-B0 forgery classifier with a binary head (+ optional generation-family head, Phase 17)."""

from __future__ import annotations

from typing import Any

import torch
import torch.nn as nn
from torchvision import models

FAMILIES = ("gan", "diffusion")


class ForgeryModel(nn.Module):
    """Real-vs-fake classifier on 224×224 RGB face crops.

    With ``family_head=True`` a second logit on the same pooled features estimates which
    generation family a fake came from (sigmoid → P(diffusion); low → GAN). It is trained only on
    fake samples and is a best-effort signal, not a verdict.
    """

    def __init__(self, pretrained: bool = True, family_head: bool = False) -> None:
        super().__init__()
        weights = models.EfficientNet_B0_Weights.IMAGENET1K_V1 if pretrained else None
        backbone = models.efficientnet_b0(weights=weights)
        in_features = backbone.classifier[1].in_features
        backbone.classifier = nn.Sequential(
            nn.Dropout(p=0.2, inplace=True),
            nn.Linear(in_features, 1),
        )
        self.backbone = backbone
        self.family_head = nn.Linear(in_features, 1) if family_head else None
        # Last conv block used for Grad-CAM
        self.target_layer = self.backbone.features[-1]

    def pooled(self, x: torch.Tensor) -> torch.Tensor:
        return torch.flatten(self.backbone.avgpool(self.backbone.features(x)), 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """Return forgery logits of shape (N, 1)."""
        return self.backbone(x)

    def forward_all(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor | None]:
        """Forgery logits and generation-family logits (None without a family head)."""
        p = self.pooled(x)
        forgery = self.backbone.classifier(p)
        family = self.family_head(p) if self.family_head is not None else None
        return forgery, family

    def predict_proba(self, x: torch.Tensor) -> torch.Tensor:
        return torch.sigmoid(self.forward(x))


def build_model(
    pretrained: bool = True, device: str | torch.device | None = None, *, family_head: bool = False
) -> ForgeryModel:
    model = ForgeryModel(pretrained=pretrained, family_head=family_head)
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
        "heads": ["forgery", "family"] if model.family_head is not None else ["forgery"],
        **(extra or {}),
    }
    torch.save(payload, path)


def has_family_head(state_dict: dict) -> bool:
    return any(k.startswith("family_head.") for k in state_dict)


def load_checkpoint(
    path: str | Any,
    device: str | torch.device | None = None,
    *,
    pretrained: bool = False,
    family_head: bool | None = None,
) -> tuple[ForgeryModel, dict]:
    """Load either checkpoint generation. ``family_head=True`` on a pre-Phase-17 checkpoint adds a
    freshly initialised family head (used when fine-tuning from the GAN-era model)."""
    map_location = device or "cpu"
    payload = torch.load(path, map_location=map_location, weights_only=False)
    state = payload["model_state_dict"]
    stored = has_family_head(state)
    model = build_model(pretrained=pretrained, device=device, family_head=stored if family_head is None else family_head)
    model.load_state_dict(state, strict=(model.family_head is not None) == stored)
    model.eval()
    return model, payload
