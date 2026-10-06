"""Phase 29: degradation augmentation, spectral ensemble fitting, held-out robustness, release gate."""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

import numpy as np
import pytest
import torch
from PIL import Image

ML = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML))
sys.path.insert(0, str(ML / "data"))

from build_diffusion_split import run as build_diffusion_split  # noqa: E402
from dataset import default_train_transform  # noqa: E402
from degradations import RandomDegradation, social_transcode  # noqa: E402
from make_synthetic_crops import make_synthetic_workspace  # noqa: E402
from models.forgery_model import build_model, save_checkpoint  # noqa: E402
from release_gate import POLICY, run_gate  # noqa: E402
from robustness import HELD_OUT_TRANSFORMS, TRANSFORMS, robustness_csvs, run_robustness  # noqa: E402
from scoring import ServedScorer, ensemble_lib  # noqa: E402
from train_ensemble import build_ensemble, fit_logistic, fit_stacker, write_ensemble  # noqa: E402


def _image(seed: int = 0) -> Image.Image:
    return Image.fromarray(np.random.default_rng(seed).integers(0, 255, (96, 96, 3), dtype=np.uint8))


def test_random_degradation_respects_p_and_keeps_size():
    img = _image()
    assert RandomDegradation(p=0.0)(img) is img
    out = RandomDegradation(p=1.0, rng=random.Random(1))(img)
    assert out.mode == "RGB" and out.size == img.size
    assert not np.array_equal(np.asarray(out), np.asarray(img))
    with pytest.raises(ValueError):
        RandomDegradation(p=1.5)


def test_random_degradation_is_reproducible_with_a_seeded_rng():
    img = _image(3)
    a = RandomDegradation(p=1.0, rng=random.Random(7))(img)
    b = RandomDegradation(p=1.0, rng=random.Random(7))(img)
    assert np.array_equal(np.asarray(a), np.asarray(b))


def test_train_transform_with_degradation_yields_model_input():
    tensor = default_train_transform(degrade_p=1.0, rng=random.Random(0))(_image())
    assert tuple(tensor.shape) == (3, 224, 224)


def test_held_out_transforms_are_measured_but_never_trained_on():
    assert set(HELD_OUT_TRANSFORMS) <= set(TRANSFORMS)
    assert "social_transcode" not in HELD_OUT_TRANSFORMS


def test_fit_logistic_separates_and_stays_finite_when_separable():
    rng = np.random.default_rng(0)
    x = np.vstack([rng.normal(-2, 0.3, (40, 2)), rng.normal(2, 0.3, (40, 2))])
    y = np.array([0] * 40 + [1] * 40)
    coef, intercept = fit_logistic(x, y, l2=1.0)
    assert np.isfinite(coef).all() and np.isfinite(intercept)
    assert ((x @ coef + intercept > 0) == (y == 1)).all()
    weaker, _ = fit_logistic(x, y, l2=100.0)
    assert np.linalg.norm(weaker) < np.linalg.norm(coef)


def test_stacker_prefers_the_informative_expert():
    rng = np.random.default_rng(1)
    y = rng.integers(0, 2, 400)
    informative = np.where(y == 1, 3.0, -3.0) + rng.normal(0, 1, 400)
    noise = rng.normal(0, 3, 400)
    s = fit_stacker(informative, noise, y)
    assert s["cnn"] > 0 and abs(s["cnn"]) > 5 * abs(s["spectral"])


def _block(flips: dict[str, float], auc: float = 1.0, n: int = 100, model: dict | None = None) -> dict:
    return {"n": n, "baseline": {"auc": auc}, "transforms": {k: {"flip_rate": v} for k, v in flips.items()},
            "model": model or {}}


def test_gate_enforces_social_transcode_and_default_limits():
    assert run_gate({"robustness": _block({"social_transcode": 0.03, "jpeg_q50": 0.08})})["passed"]
    assert not run_gate({"robustness": _block({"social_transcode": 0.06})})["passed"]
    assert not run_gate({"robustness": _block({"social_transcode": 0.0, "blur_r1.5": 0.11})})["passed"]
    assert not run_gate({"robustness": _block({"jpeg_q50": 0.0})})["passed"]  # social transcode never measured
    assert not run_gate({"robustness": _block({"social_transcode": 0.0}, auc=0.8)})["passed"]
    assert not run_gate({"robustness": _block({"social_transcode": 0.0}, n=10)})["passed"]
    assert not run_gate({})["passed"]
    assert POLICY["max_flip_rate"]["social_transcode"] == 0.05


def test_gate_fingerprint_must_match_the_shipped_files(tmp_path):
    ckpt = tmp_path / "checkpoint.pt"
    ckpt.write_bytes(b"weights")
    sha = ensemble_lib.file_sha256(ckpt)
    cnn_only = _block({"social_transcode": 0.0}, model={"checkpoint_sha256": sha, "ensemble_sha256": None})
    assert run_gate({"robustness": cnn_only}, tmp_path)["passed"]

    config = {"format": "x", "a": [1, 2]}
    (tmp_path / "ensemble.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
    assert not run_gate({"robustness": cnn_only}, tmp_path)["passed"]  # an ensemble ships that wasn't measured
    with_ens = _block({"social_transcode": 0.0},
                      model={"checkpoint_sha256": sha, "ensemble_sha256": ensemble_lib.canonical_sha256(config)})
    assert run_gate({"robustness": with_ens}, tmp_path)["passed"]

    ckpt.write_bytes(b"swapped weights")
    assert not run_gate({"robustness": with_ens}, tmp_path)["passed"]


@pytest.fixture(scope="module")
def workspace(tmp_path_factory):
    root = tmp_path_factory.mktemp("phase29")
    info = make_synthetic_workspace(root, n_per_class=8, seed=11)
    build_diffusion_split(info["crops"], info["splits"], test_identities=4)
    ckpt = root / "checkpoint.pt"
    torch.manual_seed(0)
    save_checkpoint(ckpt, build_model(pretrained=False, device=torch.device("cpu"), family_head=True), epoch=0)
    return {**info, "root": root, "ckpt": ckpt}


def test_robustness_uses_held_out_splits_only(workspace):
    csvs = robustness_csvs(workspace["splits"])
    assert "diffusion" in csvs and "val" not in {p.stem for p in csvs.values()}
    assert all(p.stem.startswith("test") for p in csvs.values())


def test_ensemble_build_load_and_robustness_report(workspace):
    config = build_ensemble(workspace["ckpt"], workspace["crops"], workspace["splits"], k=1, device="cpu")
    path = write_ensemble(config, workspace["root"] / "ensemble.json")
    assert b"\r\n" not in path.read_bytes()
    loaded = ensemble_lib.load(path, ensemble_lib.file_sha256(workspace["ckpt"]))
    assert loaded is not None

    scorer = ServedScorer.load(workspace["ckpt"], ensemble=path, device="cpu")
    imgs = [_image(i) for i in range(3)]
    scores = scorer.score(imgs)
    assert scores["spectral"] is not None and scores["served"].shape == (3,)
    assert ((scores["served"] >= 0) & (scores["served"] <= 1)).all()

    report = run_robustness(workspace["ckpt"], workspace["crops"], workspace["splits"], device="cpu",
                            transforms={"social_transcode": social_transcode}, ensemble=path)
    assert report["model"]["experts"] == ["cnn", "spectral"]
    assert report["model"]["ensemble_sha256"] == ensemble_lib.canonical_sha256(config)
    assert report["model"]["checkpoint_sha256"] == ensemble_lib.file_sha256(workspace["ckpt"])
    assert report["n"] == sum(report["eval_set"].values())
    assert 0.0 <= report["transforms"]["social_transcode"]["flip_rate"] <= 1.0


def test_explicit_ensemble_for_another_checkpoint_is_refused(workspace, tmp_path):
    config = build_ensemble(workspace["ckpt"], workspace["crops"], workspace["splits"], k=0, device="cpu")
    other = tmp_path / "other.pt"
    save_checkpoint(other, build_model(pretrained=False, device=torch.device("cpu"), family_head=True), epoch=1)
    path = write_ensemble(config, tmp_path / "ensemble.json")
    with pytest.raises(ValueError):
        ServedScorer.load(other, ensemble=path, device="cpu")
    assert ServedScorer.load(other, ensemble=False, device="cpu").describe()["experts"] == ["cnn"]
