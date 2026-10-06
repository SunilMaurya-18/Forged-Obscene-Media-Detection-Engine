"""Dataset helpers for face-crop CSV manifests."""

from __future__ import annotations

import csv
import random
from pathlib import Path

import torch
from PIL import Image
from torch.utils.data import Dataset
from torchvision import transforms

from degradations import RandomDegradation

LABEL_TO_IDX = {"real": 0, "fake": 1}
IDX_TO_LABEL = {0: "real", 1: "fake"}

IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)


def default_train_transform(degrade_p: float = 0.0, rng: random.Random | None = None) -> transforms.Compose:
    """``degrade_p`` > 0 re-encodes that share of training images through a random re-share chain
    (resample, blur, JPEG) at their native size, before the resize to 224."""
    degrade = [RandomDegradation(degrade_p, rng)] if degrade_p > 0 else []
    return transforms.Compose(
        [
            *degrade,
            transforms.Resize((224, 224)),
            transforms.RandomHorizontalFlip(),
            transforms.ToTensor(),
            transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
        ]
    )


def default_eval_transform() -> transforms.Compose:
    return transforms.Compose(
        [
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize(IMAGENET_MEAN, IMAGENET_STD),
        ]
    )


def read_split_csv(csv_path: Path) -> list[dict[str, str]]:
    with csv_path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


class CropCsvDataset(Dataset):
    """Loads rows with columns: path, label[, dataset, split]."""

    def __init__(
        self,
        rows: list[dict[str, str]],
        crops_root: Path,
        transform: transforms.Compose | None = None,
    ) -> None:
        self.rows = rows
        self.crops_root = Path(crops_root)
        self.transform = transform or default_eval_transform()

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, int, dict[str, str]]:
        row = self.rows[index]
        path = self.crops_root / row["path"]
        image = Image.open(path).convert("RGB")
        tensor = self.transform(image)
        label = LABEL_TO_IDX[row["label"].lower()]
        return tensor, label, row
