# 0002. Two independent models for forgery and explicit content

- **Status:** Accepted
- **Date:** 2026-09-28 (recorded in Phase 25; the split dates from Phase 2)

## Context

The harms this service targets do not line up on one axis:

| Media | Forged? | Explicit? | Example harm |
|-------|:-------:|:---------:|--------------|
| Deepfake nude of a real person | Yes | Yes | Synthetic NCII |
| Real intimate photo shared without consent | No | Yes | Classic NCII (no manipulation at all) |
| Face-swapped "confession" or fake news photo | Yes | No | Defamation, election disinformation |
| Holiday photo | No | No | None |

A single "harmful" score would have to learn all four quadrants from one label. It would also be
unable to say *why* something was flagged, and it would blur two questions that have different
evidence, different thresholds and often different laws. A real intimate image is not "forged", but
it may still have to come down within hours.

## Decision

Run two separately trained models on every image and on the sampled frames of every video, and route
on either score:

- **Forgery model:** an EfficientNet-B0 classifier with temperature-scaled probabilities, plus a
  generation-family head (GAN or diffusion) on the same features, so the report can say what kind of
  generator likely produced the image.
- **Explicit model:** `Falconsai/nsfw_image_detection` from Hugging Face, with a colour-heuristic
  fallback for machines without the weights (`NSFW_BACKEND=heuristic`, the compose default).

`DecisionEngineService.route` treats the scores independently:

- `AUTO_FLAG` if **either** score reaches its own auto-flag threshold (default 0.85 each)
- `REVIEW` if **either** reaches its own review threshold (default 0.50 each)
- `ALLOW` otherwise

Each threshold is configured on its own (`DECISION_FORGERY_AUTO_FLAG`, `DECISION_EXPLICIT_AUTO_FLAG`,
`DECISION_FORGERY_REVIEW`, `DECISION_EXPLICIT_REVIEW`). Video aggregates each model's frame scores
separately before routing.

## Consequences

**Easier**
- Every decision says which model fired, and reports show both scores. Moderators and appellants can
  see whether an item was held for manipulation, for nudity, or both.
- Thresholds can be tuned per harm. A platform can be strict on explicit content and cautious on
  forgery, where false positives can silence legitimate satire or journalism.
- Each model is evaluated, calibrated and audited on its own: reliability diagrams, robustness and
  fairness slices (`ml/calibration.py`, `ml/robustness.py`, `ml/fairness.py`, `docs/MODEL_CARD.md`).
- Either model can be swapped (a better detector, a vendor API) without retraining the other.

**Harder, and accepted**
- Two inferences per image or frame: roughly double the compute of a single model.
- OR-routing means the flag rate is roughly the union of both models' false positives. The review band
  and human confirmation exist to absorb that.
- Neither model knows consent. "Real, explicit, consensual" and "real, explicit, non-consensual" look
  identical to both. Consent comes only from the complaint flow and human review, never from a score.
- The heuristic explicit backend is much weaker than the real model. It is labelled as such
  in the model card and must not be used in production.

## Enforced by

- `ml-service/inference.py` and `ml-service/forgery_runtime.py` (forgery model, calibration and family
  head), `ml-service/nsfw_classifier.py` (explicit model and heuristic fallback), `docs/MODEL_CARD.md`.
- `backend/src/main/java/com/forgeddetect/service/DecisionEngineService.java` and its tests.
- `AnalysisResult` stores `forgeryScore` and `explicitScore` as separate columns. Queue, reports and
  transparency output show them separately.

## Revisit when

- A single multi-label model beats both on every evaluation slice, including fairness, *and* still
  outputs two calibrated scores. The routing contract (two scores, two thresholds) should survive
  even then.
- A third harm class (for example CSAM hash matching or violent extremism) is added. It should arrive
  as another independent signal, not be folded into an existing score.
