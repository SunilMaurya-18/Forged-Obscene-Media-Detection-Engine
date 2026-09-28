# Model Card — Forged Media Detection

This card covers the two scoring components behind `POST /api/analyze`. Every number here comes from [`ml/results/metrics.json`](../ml/results/metrics.json) or the training logs in `ml/models/`. Re-run `python ml/phase22.py` (calibration, robustness, fairness) and `python ml/phase11_eval.py` (accuracy, latency, false positive rate) to regenerate them.

> **Read this first.** The bundled checkpoint is a **demo checkpoint trained on synthetic crops**, not on real faces. Its metrics show that the training and evaluation pipeline works. They are **not** evidence of real-world detection performance. The robustness results below show a concrete way it fails.

## 1. Model details

| | Forgery detector | Explicit-content score |
|---|---|---|
| Architecture | EfficientNet-B0 (torchvision), 1-logit binary head, plus a generation-family head (`gan` / `diffusion`) on the same pooled features | Pretrained open-source classifier `Falconsai/nsfw_image_detection` via `transformers`; falls back to a colour heuristic |
| Input | 224×224 RGB, ImageNet normalisation | Full image |
| Output | `forgery_score` in [0, 1]; `generation_family` + `family_confidence` (only when the image is judged fake and the family head is decisive) | `explicit_score` in [0, 1] |
| Explainability | Grad-CAM on the last conv block; coarse FFT and ELA signatures for reports | None |
| Independence | Separate model, weights and code path from the explicit score | Never reads the forgery score |
| Checkpoint | `ml/models/checkpoint.pt` (Phase 17 diffusion-augmented, promoted) | — |
| Serving | `ml-service` (FastAPI, CPU), loaded once at startup | Same service |

**Demo configuration note.** The Docker image sets `NSFW_BACKEND=heuristic`, so the demo stack's explicit score is the heuristic fallback, **not** a trained classifier. Set `NSFW_BACKEND=auto` (with network access for the weights) to use the pretrained model.

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
| ECE / Brier on held-out splits (fit n=12, eval n=24) | 0.0022 / 0.0000 (4 d.p.) | `calibration.ece_before`, `calibration.brier_before` |
| Chart | `ml/results/reliability_diagram.png` | `artifacts.reliability_diagram` |

**What this means:** a forgery score of 0.67 from the demo checkpoint is **not** a calibrated 67% probability. Treat scores as a ranking until calibration is fitted on real validation data. The review band (0.50–0.85) exists so that uncertain items reach a human.

## 7. Robustness (Phase 22)

Score stability under the transforms real uploads go through (`ml/robustness.py`, n=36 held-out images, threshold 0.5). *Flip rate* is the share of images whose routing changes because of the transform alone.

| Transform | Flip rate | AUC | Direction |
|---|---|---|---|
| JPEG quality 90 | 0.00 | 1.00 | stable |
| Centre crop 80% | 0.00 | 1.00 | stable |
| Screenshot (resample + tone + JPEG 85) | 0.14 | 1.00 | real images pushed up |
| JPEG quality 70 | 0.44 | 0.11 | real images pushed to *fake* |
| JPEG quality 50 | 0.50 | 0.00 | real images pushed to *fake* |
| Downscale-upscale 0.5 | 0.50 | 0.00 | real images pushed to *fake* |
| Gaussian blur r=1.5 | 0.50 | 0.00 | real images pushed to *fake* |
| Social transcode (0.75× + JPEG 75) | 0.50 | 0.00 | real images pushed to *fake* |

Source: `robustness.transforms`; chart `ml/results/robustness_chart.png`.

**Finding:** the demo checkpoint learned "blocky, high-frequency texture means fake". Ordinary JPEG compression and resampling add exactly that texture, so **a genuine photo re-shared through a social app is likely to be scored as forged**. Real-image scores shift by +0.89 to +1.00 under these transforms, while fakes barely move. This is the most important known failure mode. The mitigations are in section 10.

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
2. **Compression fragility** (section 7). Add JPEG, blur, rescale and transcode augmentation to training, then re-run `phase22.py` and require flip rate ≤ 0.05 on `social_transcode` before promoting a checkpoint.
3. **Uncalibrated scores** (section 6). Fit and promote a temperature on real validation data.
4. **Unknown group performance** (section 8). Annotate an evaluation set and run the audit. Treat any FPR disparity above a few points as a release blocker.
5. **Generalisation.** Detectors trained on one generator family degrade on new ones. Diffusion-era detection remains an open research problem.
6. **Explicit score in the demo is a heuristic** (section 1).
7. **Grad-CAM is an explanation aid**, not proof. Highlighted regions show model attention, not verified tampering.

## 11. Ethical considerations

- Raw media is never persisted. Only perceptual hashes, scores, decisions, coarse forensic signatures and timestamps are stored.
- Uncertain items go to humans, and moderator decisions are auditable and signed into evidentiary reports.
- Training and evaluation use only consenting-actor research datasets and synthetic data. No real non-consensual or explicit imagery is used anywhere in the project.
- Thresholds are configuration, not code, so operators own the trade-off between false positives (wrongly flagging real people) and false negatives (missed abuse).
