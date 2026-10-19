# Model Card — Forged Media Detection

This card covers the two scoring components behind `POST /api/analyze`. Every number here comes from [`ml/results/metrics.json`](../ml/results/metrics.json) or the training logs in `ml/models/`. Re-run `python ml/phase22.py` (calibration, robustness, fairness) and `python ml/phase11_eval.py` (accuracy, latency, false positive rate) to regenerate them.

> **Read this first.** The bundled checkpoint is a **research checkpoint trained on synthetic crops**, not on real faces. Its metrics show that the training and evaluation pipeline works. They are **not** evidence of real-world detection performance. The robustness results below show a concrete way it fails.

## 1. Model details

| | Forgery detector | Explicit-content score |
|---|---|---|
| Architecture | EfficientNet-B0 (torchvision), 1-logit binary head, plus a generation-family head (`gan` / `diffusion`) on the same pooled features | Pretrained open-source classifier `Falconsai/nsfw_image_detection` via `transformers`; falls back to a colour heuristic |
| Input | 224×224 RGB, ImageNet normalisation | Full image |
| Output | `forgery_score` in [0, 1]; `generation_family` + `family_confidence` (only when the image is judged fake and the family head is decisive) | `explicit_score` in [0, 1] |
| Explainability | Grad-CAM on the last conv block; coarse FFT and ELA signatures for reports | None |
| Independence | Separate model, weights and code path from the explicit score | Never reads the forgery score |
| Checkpoint | `ml/models/checkpoint.pt` (Phase 29: Phase 17 model fine-tuned with degradation augmentation, promoted after passing the release gate) | — |
| Second expert (Phase 29) | Spectral expert: logistic regression over the radially averaged log spectrum (32 bins) and 5 noise-residual statistics. No learned convolutional features, so it fails differently from the CNN | — |
| Combination (Phase 29) | Stacker `sigmoid(w_cnn·z_cnn + w_spectral·z_spectral + b)` fitted on the validation split; both experts and the stacker live in `ml/models/ensemble.json` (plain JSON), which is refused unless it was fitted against the loaded checkpoint | — |
| Serving | `ml-service` (FastAPI, CPU), loaded once at startup. `/infer` returns the served `forgery_score` plus `forgery_experts` (per-expert scores); `/health` → `ensemble` | Same service |

**Configuration note.** The Docker image sets `NSFW_BACKEND=heuristic`, so the default explicit score is the heuristic fallback, **not** a trained classifier. Set `NSFW_BACKEND=auto` (with network access for the weights) to use the pretrained model.

## 2. Intended use

- **Triage**, not verdicts. Scores route an upload to `ALLOW`, `REVIEW` (a human decides) or `AUTO_FLAG` using configurable thresholds (defaults: review 0.50, auto-flag 0.85).
- Platforms and trust-and-safety teams screening **face images** for manipulation and explicit content, with a human in the loop for anything uncertain.
- Evidence support: Grad-CAM, FFT and ELA panels in signed reports help a moderator explain a decision. They do not prove manipulation on their own.

## 3. Out-of-scope uses

- **Sole basis for punitive action** against a person (account bans, legal claims) without human review.
- **Non-face manipulation** (objects, documents, scenery): the detector is trained on face crops only.
- **Audio**, and **temporal video forgeries**: videos (Phase 23) are scored frame by frame with this same image model and routed on an aggregate of the frame scores. Nothing models motion, lip sync or audio, and the metrics below are for single images, not clips.
- **Identifying people** or inferring demographics: the system does neither.
- Any use on real non-consensual imagery for training or testing: prohibited by the project rules.

## 4. Training data

| Stage | Data | Size | Source |
|---|---|---|---|
| Phase 2 (GAN-era) | Synthetic crops: smooth gradients labelled *real*, high-frequency 8×8 blocks labelled *fake* (`ml/data/make_synthetic_crops.py`) | 20 per class for the FF++-style train/val split (smoke run) | `checkpoint.train_log.json` |
| Phase 17 (diffusion-era) | Synthetic diffusion-inpainting stand-in over the same crops (`ml/data/build_diffusion_split.py --source synthetic`) | 6 epochs, val n=10 per era | `checkpoint.diffusion_aug.train_log.json` |

The intended training data is **FaceForensics++** (train/val) with **DFDC** and **Celeb-DF** held out for cross-dataset testing. These are consenting-actor research datasets that require access requests (`ml/data/download_instructions.md`). They are not bundled.

## 5. Evaluation results

All on synthetic splits with the demo checkpoint.

| Metric | Value | metrics.json |
|---|---|---|
| Accuracy / AUC, FF++-style in-domain (n=10) | 1.000 / 1.000 | `datasets.faceforensics++` |
| Accuracy / AUC, DFDC-style held out (n=10) | 1.000 / 1.000 | `datasets.dfdc` |
| Accuracy / AUC, Celeb-DF-style held out (n=10) | 1.000 / 1.000 | `datasets.celeb-df` |
| Diffusion-era AUC before → after retraining (n=80) | 0.119 → 1.000 | `diffusion_era` |
| Clean-set false positive rate at 0.5 | 0.0 (0 / 15) | `false_positive_rate` |
| Generation-family accuracy on fakes | 40/40 diffusion, 15/15 GAN-era | `generation_family` |
| End-to-end latency, full Docker stack, CPU | mean 134.1 ms, p50 131.7 ms, p95 188.2 ms (n=5) | `latency` |

Perfect scores on tiny synthetic sets mean the synthetic classes are trivially separable. They say nothing about real deepfakes.

## 6. Calibration (Phase 22)

Method: temperature scaling fitted on the validation split (`ml/calibration.py`). The runtime applies it only when `ml/models/calibration.json` exists, which is written only by `--promote`, and `ml-service /health` reports `calibration.active` and the temperature.

| | Value | metrics.json |
|---|---|---|
| Status | **Degenerate**: the synthetic validation set is perfectly separable, so no finite temperature minimises NLL | `calibration.degenerate` |
| Temperature applied | 1.0 (none); not promoted to the runtime | `calibration.temperature` |
| ECE / Brier on held-out splits (fit n=12, eval n=24) | 0.0040 / 0.0000 (4 d.p.) | `calibration.ece_before`, `calibration.brier_before` |
| Chart | `ml/results/reliability_diagram.png` | `artifacts.reliability_diagram` |

**What this means:** a forgery score of 0.67 from the demo checkpoint is **not** a calibrated 67% probability. Treat scores as a ranking until calibration is fitted on real validation data. The review band (0.50–0.85) exists so that uncertain items reach a human. When `ensemble.json` is active the temperature is not applied: the stacker is itself a logistic fit on the validation split, but on separable synthetic data it is regularised, not calibrated.

## 7. Robustness (Phases 22 and 29)

Score stability of the **served** model (augmented CNN + spectral ensemble) under the transforms real uploads go through (`ml/robustness.py`, threshold 0.5). *Flip rate* is the share of images whose routing changes because of the transform alone. Since Phase 29 it is measured on **held-out test splits only** (DFDC-style 12, Celeb-DF-style 12, diffusion test 80; n=104). `val.csv` selects checkpoints and fits the stacker, so it is excluded.

| Transform | Before (Phase 17 CNN) | After (Phase 29, served) | AUC after |
|---|---|---|---|
| JPEG quality 90 | 0.00 | 0.00 | 1.00 |
| JPEG quality 70 | 0.47 | 0.00 | 1.00 |
| JPEG quality 50 | 0.51 | 0.00 | 1.00 |
| Downscale-upscale 0.5 | 0.50 | 0.00 | 1.00 |
| Gaussian blur r=1.5 | 0.51 | 0.00 | 1.00 |
| Screenshot (resample + tone + JPEG 85) | 0.27 | 0.00 | 1.00 |
| **Social transcode (0.75× + JPEG 75)** | **0.49** | **0.00** | 1.00 |
| Centre crop 80% *(never in training)* | 0.14 | 0.00 | 1.00 |
| WebP quality 60 *(never in training)* | 0.34 | 0.02 | 1.00 |

Sources: `phase29.before`, `robustness.transforms`; charts `ml/results/robustness_before_after.png` and `robustness_chart.png`.

**Finding (Phase 22):** the Phase 17 checkpoint had learned "blocky, high-frequency texture means fake". Ordinary JPEG compression and resampling add exactly that texture, so a genuine photo re-shared through a social app was likely to be scored as forged.

**Fix (Phase 29):** `ml/phase29.py` fine-tunes the checkpoint with `RandomDegradation` (`ml/degradations.py`): half of the training images of *both* classes go through a random resample, blur, tone and (double) JPEG chain, so compression stops being evidence of forgery. It then fits the spectral expert and the stacker on degraded copies too, re-measures, and promotes only if the release gate passes. The augmented CNN alone already reaches 0.00 on every gated transform (0.01 on WebP); the ensemble scores 0.02 on WebP, so on this data its value is a second, independent signal (for example, on a flat-colour image the CNN alone says 0.60 and the ensemble 0.0006), not extra robustness.

**Release gate** (`ml/release_gate.py`, run in CI): social transcode flip rate ≤ 0.05, every other transform ≤ 0.10, clean AUC ≥ 0.90, at least 30 held-out images, and the measured checkpoint and ensemble hashes must match the files in `ml/models/`. A model swapped in without re-running the suite fails CI.

**Remaining signal:** WebP, which training never uses, still raises genuine-image scores by +0.22 on average (fakes −0.08). Only 2% of decisions flip, but codecs outside the augmentation set remain the place to watch. All of this is on synthetic data (section 4).

## 8. Fairness (Phase 22)

`ml/fairness.py` reports per-group FPR / FNR / AUC with bootstrap 95% intervals and the maximum disparity. It refuses to report groups smaller than 30 images or missing a class.

| | Value | metrics.json |
|---|---|---|
| Status | **not_run**: the synthetic splits have no demographic annotations | `fairness.status` |
| Attributes checked | `skin_tone`, `age_band`, `gender_presentation` | `fairness.attributes_requested` |

A false *forged* verdict harms the person pictured, so **FPR disparity is the headline fairness metric**. No fairness claim can be made until the audit runs on an annotated research set. The tool never infers or invents groups.

## 9. Live human-agreement signal

`GET /api/stats` → `moderation` compares moderators' final decisions with the model's original routing. It covers REVIEW items confirmed vs cleared, the AUTO_FLAG overturn rate, allowed items later taken down, and a precision proxy (the share of model-flagged items a human confirmed). Unlike the benchmarks above, this comes from **real moderation traffic** and is the first number to watch after deployment.

## 10. Known limitations and recommendations

1. **Synthetic training data.** Retrain on licensed FF++ / DFDC / Celeb-DF and real diffusion fakes before any real use (Phase 22 task 1).
2. **Compression fragility** (section 7). Addressed on synthetic data in Phase 29 (social transcode flip rate 0.49 → 0.00) and enforced by the release gate in CI. Re-run `python ml/phase29.py --crops <dir> --splits <dir>` on licensed data to confirm, and consider adding codecs outside the augmentation set (WebP, HEIC, AVIF) to the robustness suite.
3. **Uncalibrated scores** (section 6). Fit and promote a temperature on real validation data.
4. **Unknown group performance** (section 8). Annotate an evaluation set and run the audit. Treat any FPR disparity above a few points as a release blocker.
5. **Generalisation.** Detectors trained on one generator family degrade on new ones. Diffusion-era detection remains an open research problem.
6. **Explicit score with the default image is a heuristic** (section 1).
7. **Grad-CAM is an explanation aid**, not proof. Highlighted regions show model attention, not verified tampering.

## 11. Ethical considerations

- Raw media is never persisted. Only perceptual hashes, scores, decisions, coarse forensic signatures and timestamps are stored.
- Uncertain items go to humans, and moderator decisions are auditable and signed into evidentiary reports.
- Training and evaluation use only consenting-actor research datasets and synthetic data. No real non-consensual or explicit imagery is used anywhere in the project.
- Thresholds are configuration, not code, so operators own the trade-off between false positives (wrongly flagging real people) and false negatives (missed abuse).
