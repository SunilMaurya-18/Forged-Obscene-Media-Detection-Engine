# IMPLEMENTATION_PLAN.md

### Forged/Obscene Media Detection Engine — Agent Build Plan

> Drop this file in the project root. Work through the phases **in order**. Do not start a phase until the previous phase's "Definition of Done" is fully satisfied. After finishing a phase, stop, summarize what changed, and wait for confirmation before starting the next one.

---

## 0. Rules for the agent (read before starting)

- **Tech stack is locked** — do not substitute frameworks:
  - Backend/API: **Spring Boot (Java)** — Web, Security, Data JPA, PostgreSQL driver
  - ML inference microservice: **Python + FastAPI**, internal-only, called by Spring Boot via `WebClient`
  - Frontend: **React + Tailwind**
  - DB: **PostgreSQL**
  - Orchestration: **Docker Compose**
- Keep the repo **always runnable**: `docker-compose up` must not crash even mid-phase — stub incomplete endpoints rather than leaving broken imports.
- **Never** include, download, generate, or reference real non-consensual/explicit imagery anywhere in code, fixtures, tests, or sample data. All model training/testing uses only public research benchmarks: **FaceForensics++, DFDC, Celeb-DF** (consenting-actor datasets). If a task seems to require real explicit content, stop and flag it instead of proceeding.
- Store only perceptual hashes + scores + metadata in the DB — never raw flagged media.
- Write a minimal test for each phase's core logic before moving to the next phase.
- One phase = one focused set of commits. Don't jump ahead to later phases' files.

---

## 1. Target Folder Structure (create in Phase 0)

```
forged-media-detector/
├── ml/                          # offline training code, not a running service
│   ├── data/
│   ├── models/
│   ├── train.py
│   ├── evaluate.py
│   └── explain.py
├── ml-service/                  # FastAPI — inference only
│   ├── main.py
│   ├── inference.py
│   ├── hashing.py
│   └── requirements.txt
├── backend/                     # Spring Boot
│   └── src/main/java/com/forgeddetect/
│       ├── controller/
│       ├── service/
│       ├── repository/
│       ├── model/
│       ├── security/
│       └── ForgedMediaApplication.java
├── frontend/
│   └── src/pages/
├── mock-social-feed/
├── docker-compose.yml
└── README.md
```

---

## Phase 0 — Project Scaffolding

**Goal:** empty-but-runnable skeleton for all four services.

Tasks:

- [ ] Generate Spring Boot project (Spring Initializr deps: Web, Security, Data JPA, PostgreSQL, Actuator)
- [ ] Generate FastAPI service with a `/health` route
- [ ] Generate React app (Vite) with Tailwind, one placeholder page
- [ ] Write `docker-compose.yml` wiring backend, ml-service, frontend, postgres
- [ ] Add `.env.example` with `ML_SERVICE_URL`, `SPRING_DATASOURCE_URL`, `POSTGRES_PASSWORD`

Definition of Done:

- [ ] `docker-compose up` starts all 4 containers without error
- [ ] Spring Boot: `GET /actuator/health` → 200
- [ ] FastAPI: `GET /health` → 200
- [ ] React dev server renders a placeholder page
- [ ] Postgres container reports healthy

---

## Phase 1 — Data Pipeline (offline, in `ml/`)

**Goal:** reproducible scripts turning raw datasets into training-ready face crops.

Tasks:

- [ ] `ml/data/download_instructions.md` — document manual access steps for FaceForensics++/DFDC/Celeb-DF (these require request forms, not direct download)
- [ ] `ml/data/extract_frames.py` — video → frames at N fps
- [ ] `ml/data/detect_align_faces.py` — MTCNN/RetinaFace detection + alignment → 224×224 crops
- [ ] `ml/data/build_splits.py` — train/val split from FF++ only; keep DFDC + Celeb-DF entirely held out for cross-dataset testing

Definition of Done:

- [ ] Running the pipeline on a small local sample (few videos) produces a folder of aligned face crops with a `labels.csv` (real/fake)
- [ ] Splits are reproducible given a fixed seed

---

## Phase 2 — Forgery Detection Model (offline, in `ml/`)

**Goal:** trained checkpoint for real-vs-fake classification.

Tasks:

- [ ] `ml/models/forgery_model.py` — EfficientNet-B0 or XceptionNet backbone, binary head, load ImageNet pretrained weights
- [ ] `ml/train.py` — training loop, checkpointing, per-epoch metric logging
- [ ] `ml/evaluate.py` — evaluate on FF++ test split, then on DFDC + Celeb-DF (generalization test)

Definition of Done:

- [ ] A saved checkpoint exists (`ml/models/checkpoint.pt`)
- [ ] `evaluate.py` prints accuracy/precision/recall/F1/AUC for all three datasets
- [ ] Results (including the generalization gap) are saved to `ml/results/metrics.json`

---

## Phase 3 — Explainability

**Goal:** heatmap + forensic overlays for any prediction.

Tasks:

- [ ] `ml/explain.py`: Grad-CAM hook on the trained backbone → base64 heatmap overlay
- [ ] Add FFT spectrum visualization function
- [ ] Add Error Level Analysis (ELA) visualization function

Definition of Done:

- [ ] Given one image, `explain.py` returns a heatmap + FFT + ELA image, each as base64 PNG

---

## Phase 4 — Explicit Content Classifier

**Goal:** independent explicit/NSFW confidence score.

Tasks:

- [ ] `ml-service/nsfw_classifier.py` — load a pretrained open-source NSFW classifier
- [ ] Keep its output completely independent from the forgery score (separate function, separate field)

Definition of Done:

- [ ] Given an image, returns `{explicit_score: float}` with no dependency on the forgery model

---

## Phase 5 — ML Microservice Inference Endpoint (`ml-service/`)

**Goal:** wire Phases 2–4 into one FastAPI endpoint.

Tasks:

- [ ] `ml-service/inference.py` — loads the Phase 2 checkpoint once at startup, exposes `run_forgery_check(image_bytes)` and `run_explicit_check(image_bytes)`
- [ ] `ml-service/hashing.py` — `compute_phash(image_bytes)` using `imagehash`
- [ ] `ml-service/main.py` — `POST /infer` returning:

  ```json
  {"forgery_score": 0.0, "explicit_score": 0.0, "heatmap": "base64...", "phash": "..."}
  ```

Definition of Done:

- [ ] `curl -F file=@sample.jpg localhost:8000/infer` returns a valid JSON response
- [ ] Cold-start model load happens once, not per-request

---

## Phase 6 — Decision Engine (Spring Boot, pure logic first)

**Goal:** routing rule, unit-testable independent of any HTTP layer.

Tasks:

- [ ] `service/DecisionEngineService.java` — `route(forgeryScore, explicitScore) -> Decision{AUTO_FLAG, REVIEW, ALLOW}`
- [ ] Thresholds read from `application.yml` (configurable, not hardcoded)
- [ ] Unit tests covering all three routing outcomes + boundary values

Definition of Done:

- [ ] `mvn test` passes for `DecisionEngineServiceTest`

---

## Phase 7 — Spring Boot API Layer

**Goal:** the real, exposed API.

Tasks:

- [ ] `model/AnalysisResult.java` — JPA entity: id, phash, forgeryScore, explicitScore, decision, createdAt
- [ ] `repository/AnalysisResultRepository.java` — Spring Data JPA repo
- [ ] `service/MlClientService.java` — `WebClient` call to `ML_SERVICE_URL/infer`
- [ ] `controller/AnalyzeController.java` — `POST /api/analyze`: call ML service → decision engine → persist → return
- [ ] `controller/QueueController.java` — `GET /api/queue`, `POST /api/queue/{id}/decision`
- [ ] `controller/StatsController.java` — `GET /api/stats`

Definition of Done:

- [ ] `POST /api/analyze` with a sample image returns scores + decision and creates a DB row
- [ ] `GET /api/queue` returns items with `decision = REVIEW`

---

## Phase 8 — Auth (Spring Security)

**Goal:** moderator-only access to queue/decision endpoints.

Tasks:

- [ ] `security/SecurityConfig.java` — JWT or session auth, `MODERATOR` role
- [ ] `@PreAuthorize("hasRole('MODERATOR')")` on `QueueController` write endpoints
- [ ] Seed one moderator user (dev-only)

Definition of Done:

- [ ] Unauthenticated `POST /api/queue/{id}/decision` → 401/403
- [ ] Authenticated moderator request succeeds

---

## Phase 9 — Frontend (React)

**Goal:** demo + moderation UI.

Tasks:

- [ ] `pages/Demo.jsx` — drag-drop upload → calls `/api/analyze` → shows scores, heatmap, decision
- [ ] `pages/ModerationQueue.jsx` — lists `REVIEW` items with **blurred** thumbnails, approve/reject buttons
- [ ] `pages/Dashboard.jsx` — stats: accuracy per dataset, ROC curve, confusion matrix (from Phase 2 `metrics.json`)
- [ ] Login screen wired to Spring Security auth

Definition of Done:

- [ ] Full flow works end-to-end from the browser: upload → see result → (if flagged) appears in moderator queue → moderator can act on it

---

## Phase 10 — Mock Social Feed Integration

**Goal:** demonstrate real-time platform integration.

Tasks:

- [ ] `mock-social-feed/` — a tiny app with a few dummy posts, each "upload" calls `POST /api/analyze`
- [ ] Show the routing outcome inline next to each mock post

Definition of Done:

- [ ] Posting a manipulated sample image via the mock feed results in it appearing in the moderation queue

---

## Phase 11 — Testing & Evaluation

**Goal:** the numbers for your report/poster.

Tasks:

- [ ] Run full cross-dataset evaluation (FF++ → DFDC/Celeb-DF), save charts
- [ ] Measure `/api/analyze` end-to-end latency (image upload → response)
- [ ] Measure false positive rate on a clean/unaltered image set
- [ ] Compile a short "failure case" gallery using research-dataset images only

Definition of Done:

- [ ] `ml/results/` contains metrics.json, ROC curve image, confusion matrix image
- [ ] A latency number and FPR number are documented in `README.md`

---

## Phase 12 — Deployment & Exhibition Prep

**Goal:** demo-ready package.

Tasks:

- [x] Finalize `docker-compose.yml` for one-command full-stack startup
- [x] Write `README.md`: architecture diagram, setup steps, demo script
- [x] Prepare a results poster/summary (accuracy + generalization chart)

Definition of Done:

- [x] A fresh clone + `docker-compose up` gives a fully working demo with no manual steps beyond `.env` setup

---

# MARKET-READINESS EXTENSION (Phases 13–16)

> Added after Phase 0–12 completion. Purpose: turn the working demo into something that maps directly to India's IT (Intermediary Guidelines and Digital Media Ethics Code) Amendment Rules, 2026 (effective 20 Feb 2026) — mandatory 2-hour takedown for non-consensual deepfake complaints, 3-hour takedown for government orders, traceability/labelling of AI content, and proactive detection duties. These four phases are the flagship differentiator — build them in order, same phase-gate rules as above apply.
>
> **Deliberately deferred, not forgotten:** audio/voice deepfake detection. It requires a new dataset (e.g., ASVspoof) and a separate model architecture (spectrogram-based CNN or wav2vec-based classifier) — treat it as a standalone future phase (17+), not part of this batch.

---

## Phase 13 — Compliance SLA Engine (Spring Boot)

**Goal:** automated 2-hour / 3-hour takedown clocks with escalation, matching the IT Rules 2026 deadlines.

Tasks:

- [x] Extend `AnalysisResult` entity: add `complaintType` (`USER_COMPLAINT` / `GOVERNMENT_ORDER`), `slaDeadline` (computed: +2h for user NCII complaints, +3h for government orders), `slaStatus` (`ON_TRACK` / `AT_RISK` / `BREACHED`)
- [x] `service/SlaClockService.java` — `@Scheduled` job recalculating `slaStatus` for all open items on a short interval
- [x] `controller/ComplianceController.java`:
  - `GET /api/compliance/queue` — open items sorted by nearest deadline first
  - `GET /api/compliance/breaches` — items past deadline
- [x] Escalation: when an item enters `AT_RISK` (e.g., \<30 min remaining), log/flag it (a stub email or webhook call is enough for the demo)

Definition of Done:

- [x] A seeded `USER_COMPLAINT` item's countdown correctly transitions `ON_TRACK → AT_RISK → BREACHED` under simulated time
- [x] `/api/compliance/queue` returns items ordered by urgency

---

## Phase 14 — Provenance / Traceability Metadata (Python ML service)

**Goal:** embed traceable, non-removable-style metadata into processed media, per the new labelling/traceability requirement.

Tasks:

- [x] `ml-service/provenance.py` — embed a manifest (C2PA-style if the library fits your timeline, otherwise a pragmatic XMP/EXIF-embedded custom manifest) containing: content hash, analysis timestamp, forgery score, decision, a unique traceable ID
- [x] Add `provenance_id` to the `/infer` response and to the Spring Boot `AnalysisResult` entity
- [x] `GET /api/verify/{provenance_id}` — public lookup endpoint returning an item's authenticity/audit history by its embedded ID

Definition of Done:

- [x] A processed image's embedded manifest is read back correctly if re-uploaded
- [x] `/api/verify/{id}` returns the audit trail for a known ID, 404 for an unknown one

---

## Phase 15 — Evidentiary Report Generation

**Goal:** one-click PDF report per flagged item, suitable for a platform's legal/compliance team.

Tasks:

- [ ] `POST /api/queue/{id}/report` — generates a PDF containing: scores, heatmap, FFT/ELA panels, decision, SLA timestamps, provenance ID
- [ ] Use a Java PDF library (OpenPDF/iText) or call a small Python report-render helper from the ML service
- [ ] Add a "Download Report" button in the moderator dashboard

Definition of Done:

- [ ] Clicking "Download Report" on a flagged item produces a readable PDF with every field populated

---

## Phase 16 — Self-Serve API Productization

**Goal:** turn the internal API into something a third-party platform could integrate.

Tasks:

- [ ] `security/ApiKeyAuthFilter.java` — API key auth path alongside the existing moderator session auth, scoped to `/api/v1/analyze`
- [ ] `model/ApiClient.java` + repository — client name, hashed API key, request count, plan tier
- [ ] Rate limiting per key (in-memory counter or Bucket4j)
- [ ] `GET /api/v1/usage` — client-facing usage stats endpoint
- [ ] Write `API_DOCS.md` — how a third-party platform integrates `/api/v1/analyze` into its own upload pipeline

Definition of Done:

- [ ] A request with a valid API key succeeds; missing/invalid key returns 401
- [ ] Exceeding the configured rate limit returns 429
