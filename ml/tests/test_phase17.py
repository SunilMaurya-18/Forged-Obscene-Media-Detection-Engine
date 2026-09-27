"""Phase 17: diffusion-era split, family head, augmented fine-tune, metrics blocks."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
import torch

ML_ROOT = Path(__file__).resolve().parents[1]
DATA = ML_ROOT / "data"
sys.path.insert(0, str(ML_ROOT))
sys.path.insert(0, str(DATA))

from build_diffusion_split import diffusion_regenerate, run as build_split  # noqa: E402
from evaluate_diffusion import run as evaluate_checkpoint  # noqa: E402
from make_synthetic_crops import _make_crop, make_synthetic_workspace  # noqa: E402
from models.forgery_model import build_model, has_family_head, load_checkpoint, save_checkpoint  # noqa: E402
from phase17 import build_blocks  # noqa: E402
from train_diffusion_augmented import run_training  # noqa: E402


def _rows(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


@pytest.fixture(scope="module")
def workspace(tmp_path_factory):
    root = tmp_path_factory.mktemp("phase17")
    info = make_synthetic_workspace(root, n_per_class=8, seed=5)
    meta = build_split(info["crops"], info["splits"], test_identities=6)
    return {**info, "root": root, "diffusion_meta": meta}


def test_regeneration_changes_pixels_without_a_block_grid():
    real = _make_crop("real", seed=3)
    fake = diffusion_regenerate(real, seed=3)
    assert fake.shape == real.shape and fake.dtype == np.uint8
    diff = np.abs(fake.astype(np.int16) - real.astype(np.int16)).mean(axis=2)
    assert diff.mean() > 1.0
    # Unlike the GAN-era stand-in, there is no 8-px periodic boundary energy
    gray = cv2.cvtColor(fake, cv2.COLOR_BGR2GRAY).astype(np.float32)
    col_steps = np.abs(np.diff(gray, axis=1)).mean(axis=0)
    on_grid = col_steps[7::8].mean()
    off_grid = np.delete(col_steps, np.s_[7::8]).mean()
    assert on_grid < off_grid * 1.5


def test_split_is_identity_disjoint_and_labelled(workspace):
    splits = workspace["splits"] / "diffusion"
    rows = {s: _rows(splits / f"{s}.csv") for s in ("train", "val", "test")}
    ids = {s: {r["identity"] for r in v} for s, v in rows.items()}
    assert not (ids["train"] & ids["test"]) and not (ids["val"] & ids["test"]) and not (ids["train"] & ids["val"])
    assert workspace["diffusion_meta"]["counts"]["test"] == {"real": 6, "fake": 6}
    for split_rows in rows.values():
        assert {r["family"] for r in split_rows if r["label"] == "fake"} == {"diffusion"}
        assert all((workspace["crops"] / r["path"]).exists() for r in split_rows)


def test_import_mode_splits_by_identity(tmp_path):
    src = tmp_path / "import"
    for label in ("real", "fake"):
        (src / label).mkdir(parents=True)
        for person in range(12):
            cv2.imwrite(str(src / label / f"p{person:02d}_{label}.jpg"), _make_crop(label, seed=person))
    crops, splits = tmp_path / "crops", tmp_path / "splits"
    meta = build_split(crops, splits, source="import", import_dir=src)
    assert sum(c["real"] + c["fake"] for c in meta["counts"].values()) == 24
    by_identity: dict[str, set[str]] = {}
    for s in ("train", "val", "test"):
        for r in _rows(splits / "diffusion" / f"{s}.csv"):
            by_identity.setdefault(r["identity"], set()).add(s)
    assert all(len(v) == 1 for v in by_identity.values())


def test_family_head_is_backward_compatible(tmp_path):
    legacy = build_model(pretrained=False, device=torch.device("cpu"))
    path = tmp_path / "legacy.pt"
    save_checkpoint(path, legacy, epoch=1)
    state = torch.load(path, map_location="cpu", weights_only=False)["model_state_dict"]
    assert not has_family_head(state)
    model, _ = load_checkpoint(path, device=torch.device("cpu"))
    assert model.family_head is None
    widened, _ = load_checkpoint(path, device=torch.device("cpu"), family_head=True)
    forgery, family = widened.forward_all(torch.zeros(2, 3, 224, 224))
    assert forgery.shape == (2, 1) and family.shape == (2, 1)
    widened_path = tmp_path / "widened.pt"
    save_checkpoint(widened_path, widened, epoch=2)
    reloaded, _ = load_checkpoint(widened_path, device=torch.device("cpu"))
    assert reloaded.family_head is not None


def test_augmented_training_and_metric_blocks(workspace):
    base = workspace["root"] / "base.pt"
    save_checkpoint(base, build_model(pretrained=False, device=torch.device("cpu")), epoch=0)
    out = workspace["root"] / "aug.pt"
    run_training(base, workspace["crops"], workspace["splits"], out, epochs=1, batch_size=8)
    assert out.exists()
    baseline = evaluate_checkpoint(base, workspace["crops"], workspace["splits"])
    retrained = evaluate_checkpoint(out, workspace["crops"], workspace["splits"])
    assert retrained["diffusion_era"]["family"] is not None
    blocks = build_blocks(baseline, retrained, "synthetic")
    for era in ("gan_era", "diffusion_era"):
        for side in ("baseline", "retrained"):
            assert {"accuracy", "precision", "recall", "auc"} <= set(blocks[era][side])
    assert set(blocks["gan_era"]["regression"]) == set(blocks["gan_era"]["datasets"])
    assert isinstance(blocks["gan_era"]["within_margin"], bool)
