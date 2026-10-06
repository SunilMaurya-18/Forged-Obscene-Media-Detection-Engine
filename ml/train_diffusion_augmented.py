#!/usr/bin/env python3
"""Phase 17: fine-tune the GAN-era checkpoint with diffusion fakes mixed into training.

Training data = Phase 1 FF++ train split (real + GAN-era fakes) + diffusion train split (real +
diffusion fakes). Loss = BCE(forgery) + ``family_weight`` × BCE(family), the family term only on
fakes (gan → 0, diffusion → 1). Keeping the GAN-era data in the mix is what stops the model from
forgetting it; the best epoch is chosen on GAN-era *and* diffusion validation AUC together.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dataset import CropCsvDataset, default_train_transform, read_split_csv  # noqa: E402
from degradations import DEFAULT_DEGRADE_P  # noqa: E402
from evaluate import compute_metrics  # noqa: E402
from evaluate_diffusion import predict  # noqa: E402
from models.forgery_model import load_checkpoint, save_checkpoint  # noqa: E402


def with_family(rows: list[dict], default_fake_family: str) -> list[dict]:
    return [{**r, "family": r.get("family") or (default_fake_family if r["label"] == "fake" else "")} for r in rows]


def merge_unique(*groups: list[dict]) -> list[dict]:
    """De-duplicated by path, with a uniform key set so the default collate can batch the rows."""
    seen, out = set(), []
    for group in groups:
        for row in group:
            if row["path"] not in seen:
                seen.add(row["path"])
                out.append({k: row.get(k, "") for k in ("path", "label", "dataset", "family")})
    return out


def run_training(
    init_checkpoint: Path,
    crops: Path,
    splits: Path,
    out_checkpoint: Path,
    *,
    epochs: int = 6,
    batch_size: int = 8,
    lr: float = 2e-4,
    family_weight: float = 0.5,
    seed: int = 17,
    device: str = "cpu",
    degrade_p: float = DEFAULT_DEGRADE_P,
    extra: dict | None = None,
) -> dict:
    torch.manual_seed(seed)
    device_t = torch.device(device)
    gan_train = with_family(read_split_csv(splits / "train.csv"), "gan")
    gan_val = merge_unique(with_family(read_split_csv(splits / "val.csv"), "gan"))
    dm_train = read_split_csv(splits / "diffusion" / "train.csv")
    dm_val = merge_unique(read_split_csv(splits / "diffusion" / "val.csv"))
    train_rows = merge_unique(gan_train, dm_train)

    model, _ = load_checkpoint(init_checkpoint, device=device_t, family_head=True)
    n_fake = sum(r["label"] == "fake" for r in train_rows)
    n_real = len(train_rows) - n_fake
    forgery_loss = nn.BCEWithLogitsLoss(pos_weight=torch.tensor([n_real / max(n_fake, 1)], device=device_t))
    family_loss = nn.BCEWithLogitsLoss()
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr)
    transform = default_train_transform(degrade_p, random.Random(seed))
    loader = DataLoader(CropCsvDataset(train_rows, crops, transform), batch_size=batch_size, shuffle=True)

    history, best = [], None
    out_checkpoint.parent.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, epochs + 1):
        model.train()
        total, n = 0.0, 0
        for images, labels, meta in loader:
            images = images.to(device_t)
            y = labels.float().unsqueeze(1).to(device_t)
            forgery, family = model.forward_all(images)
            loss = forgery_loss(forgery, y)
            fam_target = torch.tensor([1.0 if f == "diffusion" else 0.0 for f in meta["family"]], device=device_t)
            fam_mask = torch.tensor([f in ("gan", "diffusion") for f in meta["family"]], device=device_t)
            if fam_mask.any():
                loss = loss + family_weight * family_loss(family.view(-1)[fam_mask], fam_target[fam_mask])
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            total += loss.item() * images.size(0)
            n += images.size(0)

        model.eval()
        gan_m = compute_metrics(*predict(model, gan_val, crops, device_t)[:2])
        dm_m = compute_metrics(*predict(model, dm_val, crops, device_t)[:2])
        score = (_auc(gan_m) + _auc(dm_m)) / 2 + 1e-3 * (gan_m["accuracy"] + dm_m["accuracy"])
        record = {"epoch": epoch, "loss": total / max(n, 1), "val_gan_era": gan_m, "val_diffusion_era": dm_m}
        history.append(record)
        print(f"epoch {epoch}/{epochs} loss={record['loss']:.4f} "
              f"val_gan auc={gan_m['auc']:.3f} acc={gan_m['accuracy']:.3f} | "
              f"val_diffusion auc={dm_m['auc']:.3f} acc={dm_m['accuracy']:.3f}")
        if best is None or score >= best:
            best = score
            save_checkpoint(out_checkpoint, model, epoch=epoch,
                            metrics={"val_gan_era": gan_m, "val_diffusion_era": dm_m},
                            extra={"trained_on": ["faceforensics++ (gan-era)", "diffusion"], "init": str(init_checkpoint),
                                   "degrade_p": degrade_p, **(extra or {})})

    out_checkpoint.with_suffix(".train_log.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    return {"history": history, "checkpoint": str(out_checkpoint), "train_counts": {"real": n_real, "fake": n_fake}}


def _auc(m: dict) -> float:
    auc = m.get("auc")
    return float(auc) if isinstance(auc, float) and auc == auc else 0.0


def main() -> None:
    parser = argparse.ArgumentParser(description="Diffusion-augmented fine-tuning.")
    parser.add_argument("--init", type=Path, default=ROOT / "models" / "checkpoint.pt", help="GAN-era checkpoint")
    parser.add_argument("--crops", type=Path, required=True)
    parser.add_argument("--splits", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "models" / "checkpoint.diffusion_aug.pt")
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--family-weight", type=float, default=0.5)
    parser.add_argument("--degrade-p", type=float, default=DEFAULT_DEGRADE_P,
                        help="share of training images re-encoded through a random JPEG/resize/blur chain (0 = off)")
    args = parser.parse_args()
    run_training(args.init.resolve(), args.crops.resolve(), args.splits.resolve(), args.out.resolve(),
                 epochs=args.epochs, lr=args.lr, batch_size=args.batch_size, family_weight=args.family_weight,
                 degrade_p=args.degrade_p)


if __name__ == "__main__":
    main()
