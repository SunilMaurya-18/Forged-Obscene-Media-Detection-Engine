#!/usr/bin/env python3
"""Train EfficientNet-B0 real-vs-fake classifier on FF++ crop splits."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from tqdm import tqdm

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import CropCsvDataset, default_train_transform, default_eval_transform, read_split_csv
from models.forgery_model import build_model, save_checkpoint


def binary_metrics(logits: torch.Tensor, labels: torch.Tensor, threshold: float = 0.5) -> dict:
    probs = torch.sigmoid(logits).detach().cpu().view(-1)
    y = labels.detach().cpu().view(-1).float()
    preds = (probs >= threshold).float()
    tp = ((preds == 1) & (y == 1)).sum().item()
    tn = ((preds == 0) & (y == 0)).sum().item()
    fp = ((preds == 1) & (y == 0)).sum().item()
    fn = ((preds == 0) & (y == 1)).sum().item()
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = (
        2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    )
    acc = (tp + tn) / max(tp + tn + fp + fn, 1)
    return {
        "accuracy": acc,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "loss": None,
    }


@torch.no_grad()
def evaluate_loader(model: nn.Module, loader: DataLoader, criterion: nn.Module, device: torch.device) -> dict:
    model.eval()
    total_loss = 0.0
    all_logits: list[torch.Tensor] = []
    all_labels: list[torch.Tensor] = []
    n = 0
    for images, labels, _ in loader:
        images = images.to(device)
        labels = labels.float().unsqueeze(1).to(device)
        logits = model(images)
        loss = criterion(logits, labels)
        total_loss += loss.item() * images.size(0)
        n += images.size(0)
        all_logits.append(logits.cpu())
        all_labels.append(labels.cpu())
    logits_cat = torch.cat(all_logits, dim=0)
    labels_cat = torch.cat(all_labels, dim=0)
    metrics = binary_metrics(logits_cat, labels_cat)
    metrics["loss"] = total_loss / max(n, 1)
    return metrics


def train_one_epoch(
    model: nn.Module,
    loader: DataLoader,
    criterion: nn.Module,
    optimizer: torch.optim.Optimizer,
    device: torch.device,
) -> dict:
    model.train()
    total_loss = 0.0
    all_logits: list[torch.Tensor] = []
    all_labels: list[torch.Tensor] = []
    n = 0
    for images, labels, _ in tqdm(loader, desc="train", leave=False):
        images = images.to(device)
        labels = labels.float().unsqueeze(1).to(device)
        optimizer.zero_grad(set_to_none=True)
        logits = model(images)
        loss = criterion(logits, labels)
        loss.backward()
        optimizer.step()
        total_loss += loss.item() * images.size(0)
        n += images.size(0)
        all_logits.append(logits.detach().cpu())
        all_labels.append(labels.detach().cpu())
    metrics = binary_metrics(torch.cat(all_logits), torch.cat(all_labels))
    metrics["loss"] = total_loss / max(n, 1)
    return metrics


def run_training(
    crops_root: Path,
    splits_dir: Path,
    checkpoint_path: Path,
    *,
    epochs: int = 5,
    batch_size: int = 16,
    lr: float = 1e-4,
    pretrained: bool = True,
    device: str | None = None,
    num_workers: int = 0,
) -> dict:
    device_t = torch.device(device or ("cuda" if torch.cuda.is_available() else "cpu"))
    train_rows = read_split_csv(splits_dir / "train.csv")
    val_rows = read_split_csv(splits_dir / "val.csv")
    if not train_rows:
        raise FileNotFoundError(f"No train rows in {splits_dir / 'train.csv'}")

    train_ds = CropCsvDataset(train_rows, crops_root, default_train_transform())
    val_ds = CropCsvDataset(val_rows or train_rows[:1], crops_root, default_eval_transform())
    train_loader = DataLoader(
        train_ds, batch_size=batch_size, shuffle=True, num_workers=num_workers
    )
    val_loader = DataLoader(
        val_ds, batch_size=batch_size, shuffle=False, num_workers=num_workers
    )

    model = build_model(pretrained=pretrained, device=device_t)
    criterion = nn.BCEWithLogitsLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)

    history: list[dict] = []
    best_val_f1 = -1.0
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)

    for epoch in range(1, epochs + 1):
        train_m = train_one_epoch(model, train_loader, criterion, optimizer, device_t)
        val_m = evaluate_loader(model, val_loader, criterion, device_t)
        record = {"epoch": epoch, "train": train_m, "val": val_m}
        history.append(record)
        print(
            f"epoch {epoch}/{epochs}  "
            f"train_loss={train_m['loss']:.4f} val_loss={val_m['loss']:.4f} "
            f"val_acc={val_m['accuracy']:.4f} val_f1={val_m['f1']:.4f}"
        )
        if val_m["f1"] >= best_val_f1:
            best_val_f1 = val_m["f1"]
            save_checkpoint(
                checkpoint_path,
                model,
                epoch=epoch,
                metrics={"val": val_m, "train": train_m},
            )

    log_path = checkpoint_path.with_suffix(".train_log.json")
    log_path.write_text(json.dumps(history, indent=2), encoding="utf-8")
    return {"history": history, "checkpoint": str(checkpoint_path)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Train forgery detection model.")
    parser.add_argument("--crops", type=Path, required=True, help="Crops root (labels paths relative)")
    parser.add_argument("--splits", type=Path, required=True, help="Directory with train.csv / val.csv")
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=ROOT / "models" / "checkpoint.pt",
    )
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--no-pretrained", action="store_true")
    parser.add_argument("--device", type=str, default=None)
    args = parser.parse_args()
    run_training(
        args.crops.resolve(),
        args.splits.resolve(),
        args.checkpoint.resolve(),
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        pretrained=not args.no_pretrained,
        device=args.device,
    )


if __name__ == "__main__":
    main()
