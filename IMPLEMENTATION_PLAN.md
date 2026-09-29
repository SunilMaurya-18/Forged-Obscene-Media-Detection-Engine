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

- [x] `POST /api/queue/{id}/report` — generates a PDF containing: scores, heatmap, FFT/ELA panels, decision, SLA timestamps, provenance ID
- [x] Use a Java PDF library (OpenPDF/iText) or call a small Python report-render helper from the ML service
- [x] Add a "Download Report" button in the moderator dashboard

Definition of Done:

- [x] Clicking "Download Report" on a flagged item produces a readable PDF with every field populated

---

## Phase 16 — Self-Serve API Productization

**Goal:** turn the internal API into something a third-party platform could integrate.

Tasks:

- [x] `security/ApiKeyAuthFilter.java` — API key auth path alongside the existing moderator session auth, scoped to `/api/v1/analyze`
- [x] `model/ApiClient.java` + repository — client name, hashed API key, request count, plan tier
- [x] Rate limiting per key (in-memory counter or Bucket4j)
- [x] `GET /api/v1/usage` — client-facing usage stats endpoint
- [x] Write `API_DOCS.md` — how a third-party platform integrates `/api/v1/analyze` into its own upload pipeline

Definition of Done:

- [x] A request with a valid API key succeeds; missing/invalid key returns 401
- [x] Exceeding the configured rate limit returns 429
# PHASE 17 — Diffusion-Era Robustness

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack and phase-gate rules apply. Depends on Phase 2 (Forgery Detection Model) and Phase 11 (Testing & Evaluation) already being complete.

## Why this phase exists

The existing model (Phase 2) trains on FaceForensics++, which is GAN-era manipulation (face-swap, Face2Face, NeuralTextures). Most deepfakes being produced today increasingly come from diffusion models, and the forensic artifacts are fundamentally different — GAN-trained detectors have been shown to lose large amounts of accuracy (in some studies up to half their AUC) when facing diffusion-generated content in the wild, because diffusion models don't leave the same grid-like frequency artifacts. Retraining on diffusion-generated examples has been shown to close this gap without hurting GAN-era accuracy — this phase implements that.

## Tasks

- [x] Source or construct a diffusion-forgery dataset: either use an existing public diffusion-forgery benchmark, or generate one by running a diffusion inpainting/face-swap pipeline over the real (non-fake) subset of your FF++ data, keeping identities consistent with your existing splits
- [x] `ml/data/build_diffusion_split.py` — build a held-out diffusion-fake test set, structured the same way as the Phase 1 GAN-era splits (train/val/test, no leakage across splits)
- [x] `ml/evaluate_diffusion.py` — run the current Phase 2 checkpoint against the diffusion test set; this is your **pre-retraining baseline** and is expected to be weak — that's the point, record it honestly
- [x] `ml/train_diffusion_augmented.py` — fine-tune (or retrain) the model with diffusion-fake examples mixed into the training data
- [x] Re-run the full cross-dataset evaluation (FF++, DFDC, Celeb-DF **and** the new diffusion set) on the retrained model
- [x] Add a `generation_family` field (`gan` / `diffusion` / `unknown`) to the inference output — a best-effort secondary classification of which generation family a detected fake likely came from

## Files touched

- `ml/data/build_diffusion_split.py`
- `ml/evaluate_diffusion.py`
- `ml/train_diffusion_augmented.py`
- `ml/models/forgery_model.py` (extend output head for `generation_family` if pursuing that classification)
- `ml-service/inference.py` (surface `generation_family` in the `/infer` response)

## Definition of Done

- [x] `ml/results/metrics.json` contains two clearly separated blocks: `gan_era` and `diffusion_era`, each with accuracy/precision/recall/AUC
- [x] Diffusion-era AUC after retraining is meaningfully higher than the pre-retraining baseline — both numbers documented side by side
- [x] GAN-era (FF++/DFDC/Celeb-DF) accuracy has not regressed beyond a small, explicitly documented margin after retraining
- [x] `README.md` / results doc states plainly that this is a known, active research area, not a fully solved problem — report the two numbers, don't claim universal coverage

# PHASE 18 — On-Device Hashing, StopNCII-Interoperable

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack and phase-gate rules apply. Depends on Phase 5 (ML microservice `/infer`, including the existing `hashing.py`).

## Why this phase exists

Every existing detection vendor is a cloud API: the image has to be sent to a server to be scored. For NCII specifically that's a real trust barrier. StopNCII.org, the existing global standard for this exact problem, works differently: it fingerprints an image on the victim's own device and shares only that hash — never the image — with participating platforms, which compare it against attempted uploads. Its hashing (PDQ, PhotoDNA) is open-source and already an industry standard, and as of late 2025 the system protects millions of images across hundreds of thousands of supported cases. This phase builds a compatible, on-device pre-screening path — not a full production StopNCII partnership, which requires becoming an approved participating organization (a manual/business step, not something to fake in code).

## Tasks

- [x] Integrate a PDQ-class perceptual hashing approach that can run client-side (browser). Note: a true PDQ implementation may need a WASM build or a lightweight JS perceptual-hash equivalent — document whichever path is used and why
- [x] `frontend/src/utils/onDeviceHash.js` — computes the perceptual hash of a selected image entirely in-browser, before any network request is made
- [x] New Spring Boot endpoint: `POST /api/prescreen` — accepts **only a hash string**, never image bytes; checks it against a local known-hash table; returns match/no-match
- [x] `model/KnownHash.java` + `repository/KnownHashRepository.java` — stores hash value, source (`INTERNAL_CONFIRMED` / `STOPNCII_IMPORT`), date added
- [x] Document (in `README.md`, not in code) how a real StopNCII partner integration would work, and flag explicitly that it requires formal approval as a participating platform
- [x] Add a "Check before you post" flow on the frontend `Demo` page: hash computed locally → only the hash sent to `/api/prescreen` → match/no-match shown → raw image bytes never transmitted for this specific flow

## Files touched

- `frontend/src/utils/onDeviceHash.js`
- `frontend/src/pages/Demo.jsx` (new "check before you post" UI section)
- `backend/.../controller/PrescreenController.java`
- `backend/.../model/KnownHash.java`, `.../repository/KnownHashRepository.java`
- `README.md` (StopNCII integration notes)

## Definition of Done

- [x] `POST /api/prescreen` with a known seeded test hash returns a match; an unseeded hash returns no-match
- [x] Browser network tab, inspected during the "check before you post" flow, confirms no image bytes are transmitted — only the hash string
- [x] `README.md` clearly states: this phase implements StopNCII-compatible matching logic; a live production integration additionally requires formal approval as a StopNCII participating organization

# PHASE 19 — Multi-Jurisdiction Compliance Rules

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack and phase-gate rules apply. Depends on Phase 13 (Compliance SLA Engine) already being complete.

## Why this phase exists

Phase 13 hardcodes India's IT Rules 2026 deadlines (2h for a user NCII complaint, 3h for a government order). The same SLA architecture generalizes globally with a rules table instead of constants — and the regulatory wave isn't India-only: the US Take It Down Act takes effect in May 2026 with its own 48-hour takedown requirement for non-consensual intimate images. This phase turns the compliance engine from "an India feature" into "a system that adapts to wherever the platform's users are."

## Tasks

- [x] `model/JurisdictionRule.java` + `repository/JurisdictionRuleRepository.java` — fields: jurisdiction code (`IN`, `US`, `GENERIC`, extensible), complaint type, deadline in hours, a human-readable legal reference label
- [x] Seed data: India (`IN`) — 2h user complaint / 3h government order; United States (`US`) — 48h under the Take It Down Act; a configurable `GENERIC` fallback for unlisted jurisdictions
- [x] Extend `AnalysisResult` (from Phase 7/13) with a `jurisdiction` field — set explicitly at analysis time via a request parameter or platform-level config; **do not** attempt real geo-IP inference in this phase, keep it explicit and auditable
- [x] Update `SlaClockService` (Phase 13) to look up the applicable deadline from `JurisdictionRule` based on `jurisdiction` + complaint type, instead of using a hardcoded constant
- [x] `GET /api/compliance/rules` — lists all configured jurisdiction rules and their legal reference labels, for transparency
- [x] Dashboard: show which legal deadline applies to each queue item as a badge (e.g. "India IT Rules 2026 — 2h" / "US Take It Down Act — 48h")

## Files touched

- `backend/.../model/JurisdictionRule.java`, `.../repository/JurisdictionRuleRepository.java`
- `backend/.../service/SlaClockService.java` (modify existing Phase 13 file)
- `backend/.../controller/ComplianceController.java` (add `GET /api/compliance/rules`)
- `frontend/src/pages/ModerationQueue.jsx` (jurisdiction badge)

## Definition of Done

- [x] Two seeded items with different `jurisdiction` values (e.g. `IN` and `US`) compute different, correct SLA deadlines
- [x] Adding a new `JurisdictionRule` row via the database — no code change — correctly affects SLA calculations for new items
- [x] `GET /api/compliance/rules` returns all seeded rules with their legal reference labels

# PHASE 20 — Cryptographically Signed Evidentiary Reports

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack and phase-gate rules apply. Depends on Phase 15 (Evidentiary Report Generation) already being complete.

## Why this phase exists

Phase 15 produces a PDF report per flagged item, but a plain PDF is trivially editable after the fact — not something that holds up as evidence. This phase adds tamper-evidence: signing each report's content hash at generation time so anyone can later cryptographically verify it hasn't been altered. That's a meaningfully stronger claim than "we generate a report," and closer to how real forensic chain-of-custody tooling works.

## Tasks

- [x] Generate a signing keypair for the backend at startup for development purposes; persist it locally, and add a clear `README.md` note that production use requires a proper KMS/HSM, not a key file on disk
- [x] `service/ReportSigningService.java` — computes a canonical hash of the report's content (scores, decision, SLA timestamps, provenance ID from Phase 14) and signs it with the private key
- [x] Embed the signature and a timestamp into the generated PDF (Phase 15), and store both alongside the corresponding `AnalysisResult` row
- [x] `GET /api/verify-report/{id}` — recomputes the content hash, verifies the stored signature against the public key, returns `VALID` / `TAMPERED` / `NOT_FOUND`
- [x] Frontend: add a "Verify report integrity" button on the report detail view, calling the new endpoint and showing the result clearly

## Files touched

- `backend/.../service/ReportSigningService.java`
- `backend/.../service/ReportGenerationService.java` (modify existing Phase 15 file to call signing)
- `backend/.../controller/ReportController.java` (add `GET /api/verify-report/{id}`)
- `frontend/src/pages/ReportDetail.jsx` (or wherever Phase 15's "Download Report" button lives — add verify action alongside it)

## Definition of Done

- [x] A freshly generated report verifies as `VALID` via `/api/verify-report/{id}`
- [x] Manually editing a stored `AnalysisResult` field after generation causes verification to return `TAMPERED`
- [x] `README.md` documents the honest scope of this phase: it demonstrates the chain-of-custody mechanism; real legal admissibility would additionally require a production KMS and a trusted third-party timestamp authority, not a self-signed development keyco

---

# PHASE 21 — User-Friendly UX

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack and phase-gate rules apply.

## Why this phase exists

The people most likely to use a reporting flow are distressed, non-technical, and may be on a phone. Moderators, meanwhile, work through queues all day against a legal clock. Both need an interface built for them, not for the engineers who built the models.

## Tasks

### Group A — end-user reporting flow

- [x] `frontend/src/components/VerdictBadge.jsx` — plain-language verdict ("Likely forged" / "Needs a closer look" / "Looks genuine") with a traffic-light colour; raw scores and heatmap live behind a "See details" expand, never shown first
- [x] `frontend/src/pages/ReportWizard.jsx` — multi-step flow: select image → confirm it's theirs / consent → optional details → submit. No blank single-page form
- [x] `POST /api/complaints` — files a `USER_COMPLAINT` (feeds the Phase 13/19 SLA clock), returns a human-friendly case number plus a PIN; no login required
- [x] `GET /api/complaints/{caseNumber}/status` — public, PIN-protected (PIN in the `X-Case-Pin` header, BCrypt-hashed at rest, lockout after repeated wrong PINs)
- [x] `frontend/src/pages/CaseStatus.jsx` — plain-language status and timeline
- [x] Notification hook on status change — `CaseNotificationService` logs a masked notice and optionally POSTs to a webhook (stub: no email/SMS provider)
- [x] Visible privacy statement on the upload/wizard screen before any file is selected, pointing to on-device hashing (Phase 18) where it applies

### Group B — moderator dashboard

- [x] Queue sorted by nearest SLA deadline by default
- [x] Single-key shortcuts for approve (A) / reject (R) / skip (S), with a visible legend
- [x] 10-second undo toast after any moderator decision; "Undo" reverses it before the decision is finalised (decisions are held client-side, so nothing server-side ever needs reversing)
- [x] Batch-select and batch actions, including "select similar" for near-duplicate pHashes

### Group C — cross-cutting

- [x] Accessibility: ARIA labels, full keyboard navigation, skip link, no state conveyed by colour alone
- [x] i18n with react-i18next — English + Hindi for end-user screens (wizard, case status, verdict badges, prescreen); moderator screens stay English-first
- [x] Standardised loading states (spinner + one plain-language line) and error states (plain language, never raw HTTP codes)

## Definition of Done

- [x] A first-time user completes the wizard without an account and receives a case number + PIN
- [x] Entering a valid case number + PIN shows a plain-language status
- [x] The moderation queue defaults to nearest-deadline-first
- [x] Keyboard approve / reject / skip work, and a decision can be undone within 10 s
- [x] The Hindi toggle updates all end-user screens
- [x] Every verdict / status indicator is distinguishable without colour

---

# CREDIBLE TRUST-AND-SAFETY EXTENSION (Phases 22–25)

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack, phase-gate rules and data rules apply: public research benchmarks and synthetic data only, no real non-consensual or explicit imagery, and no raw media persisted. These phases move the project from "impressive prototype" to "credible trust-and-safety system": the model's claims become measurable, the product covers the hard parts of real moderation, the engineering is verifiable on every commit, and the demo tells the story without hand-holding.

---

# PHASE 22 — Model Credibility & Evaluation Rigor

> Depends on Phase 11 (evaluation) and Phase 17 (diffusion-era robustness).

## Why this phase exists

Every headline metric today carries the caveat "synthetic smoke splits". A reviewer's first questions are: does a score of 0.67 actually mean anything, does the model survive a re-upload through a social app, does it fail some groups more than others, and how often do human moderators disagree with it? This phase makes each of those answerable with numbers, and is explicit about which numbers still need licensed data.

## Tasks

- [ ] **Real-data evaluation** — retrain and evaluate on licensed FaceForensics++ / DFDC / Celeb-DF crops plus real diffusion fakes; publish per-dataset results and the cross-dataset gap. *Blocked on dataset access (request forms in `ml/data/download_instructions.md`); every tool below accepts `--crops/--splits` so it re-runs unchanged on real data.*
- [x] `ml/calibration.py` — temperature scaling fitted on the validation split (NLL minimisation), expected calibration error (ECE) and Brier score before/after, reliability diagram PNG. Detects a perfectly separable fit set and reports the calibration as degenerate instead of publishing a meaningless temperature
- [x] Runtime calibration in `ml-service` — if `ml/models/calibration.json` exists (written only by `calibration.py --promote`), serve `sigmoid(logit / T)`; `/health` reports whether calibration is active and the temperature
- [x] `ml/robustness.py` — score stability under real-world transforms: JPEG re-encode (q 90/70/50), downscale-upscale, centre crop, Gaussian blur, "screenshot" (resize + gamma + JPEG), and "social transcode" (downscale + JPEG 75). Reports AUC, accuracy, mean score shift and decision flip rate per transform
- [x] `ml/fairness.py` — per-group FPR / FNR / AUC from any labels CSV with annotation columns (e.g. `skin_tone`, `age_band`, `gender_presentation`), with bootstrap confidence intervals, a minimum group size below which results are reported as "insufficient data", and the max disparity across groups. On splits without annotations it records `not_run` with the reason — never placeholder groups
- [x] `ml/phase22.py` — one command that runs calibration, robustness and fairness on the synthetic workspace (or `--crops/--splits`), merges `calibration` / `robustness` / `fairness` blocks into `ml/results/metrics.json`, and draws `reliability_diagram.png` and `robustness_chart.png`
- [x] **Moderator-agreement metric** — store the model's original routing (`modelDecision`) and `reviewedAt` on `AnalysisResult`; `/api/stats` gains a `moderation` block: REVIEW items confirmed vs cleared by a human, AUTO_FLAG overturn rate, and a precision proxy. This is the live, real-data signal that synthetic benchmarks can't give
- [x] `docs/MODEL_CARD.md` — intended use, out-of-scope uses, training data, evaluation data, metrics with caveats, calibration, robustness, fairness status, known failure modes, ethical considerations
- [x] Dashboard: calibration, robustness and moderator-agreement cards; README links the model card
- [ ] **Compression-robust retraining** (follow-up from the robustness finding: the demo checkpoint flips genuine images to "fake" under JPEG q≤70, blur, rescale and social transcode) — add JPEG / blur / rescale / transcode augmentation to `ml/train.py`, and gate checkpoint promotion on `robustness.py` flip rate ≤ 0.05 for `social_transcode`

## Files touched

- `ml/calibration.py`, `ml/robustness.py`, `ml/fairness.py`, `ml/phase22.py`, `ml/tests/test_phase22.py`
- `ml-service/inference.py`, `ml-service/main.py`, `ml-service/test_calibration.py`, `ml-service/Dockerfile`, `.dockerignore`
- `backend/.../model/AnalysisResult.java`, `backend/.../repository/AnalysisResultRepository.java`, `backend/.../service/AnalysisService.java`, `backend/.../controller/QueueController.java`, `backend/.../controller/ModerationAgreementIntegrationTest.java`
- `docs/MODEL_CARD.md`, `frontend/src/pages/Dashboard.jsx`, `frontend/public/reliability_diagram.png`, `frontend/public/robustness_chart.png`, `README.md`

## Definition of Done

- [x] `python ml/phase22.py` writes `calibration`, `robustness` and `fairness` blocks to `metrics.json` plus both charts, on synthetic data, in one run
- [x] With a `calibration.json` present, ml-service scores change accordingly and `/health` reports the temperature; without it, scores are unchanged
- [x] Fairness never reports a metric for a group below the minimum size, and reports `not_run` when no annotations exist
- [x] After a moderator clears a REVIEW item and overturns an AUTO_FLAG, `/api/stats` shows both in the `moderation` block
- [x] `docs/MODEL_CARD.md` exists and every number in it traces to `metrics.json`
- [x] New tests pass in `ml/`, `ml-service/` and `backend/`

---

# PHASE 23 — Trust & Safety Product Depth

> Depends on Phases 13, 14, 19 and 21.

## Why this phase exists

Detection is the easy part to demo; the hard parts of real moderation are video, standards-based provenance, due process for the people being moderated, and the wellbeing of the people doing the moderating. This phase covers those.

## Tasks

- [x] **Video support** — `ml-service /infer-video`: sample frames at N fps, score each, aggregate (max and top-k mean), return a per-frame timeline; backend routes on the aggregate; report includes a frame-score strip. Frames are processed in memory and discarded. *PyAV decodes from a `BytesIO`; forgery routes on the top-k mean and explicit on the max (both configurable); the compact timeline is stored, never frames. The fps is lowered for long clips so samples span the whole clip within `VIDEO_MAX_FRAMES`; multipart uploads stay in memory in both Spring and Starlette*
- [x] **C2PA Content Credentials** — read C2PA manifests on upload (surface signer, claim generator and edit actions in the analysis and report); write a C2PA-compatible manifest alongside the existing HMAC-signed XMP label. *Signed with a generated dev certificate (reported as `VALID_UNTRUSTED`) until a trust-list certificate is configured. The committed sample is signed with a long-lived dev leaf because untimestamped C2PA validates against the current time*
- [x] **Appeals workflow** — uploader can contest an AUTO_FLAG / REJECT decision with a case number; appeal gets its own SLA clock, must be decided by a different moderator than the original, and is recorded in the audit trail and the report (EU DSA Art. 20 style). *Every upload gets a notice number + PIN and a statement of reasons; granting proposes a restore that an unconflicted second moderator confirms*
- [x] **Moderator wellbeing controls** — greyscale + blur-by-default media, per-session exposure counter with a configurable limit and enforced break, opt-in "reveal" per item that is audit-logged, and a support-resources link. *Counters are shared across backend instances through Redis (Phase 24)*
- [x] **Teams and escalation** — moderator teams, item assignment, escalation to a senior role, and mandatory second review for AUTO_FLAG overrides. *Seniors can route an item to a team pool*
- [x] **More jurisdictions** — EU DSA, UK Online Safety Act and Australia eSafety as `JurisdictionRule` rows with legal-reference labels marked "pending legal review". *EU member-state codes resolve to the EU rules; each jurisdiction has an APPEAL row*
- [x] **Transparency report** — `GET /api/transparency?period=…` and a printable page: volumes, decisions, SLA compliance rate, appeal outcomes, moderator overturn rate (aggregates only). *Small counts are withheld (`TRANSPARENCY_MIN_CELL`), with a 7-day minimum window*

### Re-audit (gaps found and closed)

A second pass checked every item above against the code and fixed the following:

- **Appeals**: the moderators who made the takedown and the person who contested it are now all conflicted out of the appeal, including rejecting the reinstatement at second review. A second-review rejection records who decided it. Duplicate appeals filed concurrently are refused. A reinstated item is removed from the known-hash list. The appeal clock runs under the shared SLA lock, escalates through `EscalationService` (webhook and metric) and shows on the compliance summary. Uploaders see a statement of reasons: grounds, legal or policy basis, whether detection and decision were automated, and redress options.
- **Escalation and teams**: escalation also covers AUTO_FLAG items and their second review. A senior must make the decision and the second review, and the report records the senior who resolved it (`seniorResolvedBy`). Seniors can route an item to a team pool that only that team (or a senior) can claim. Escalation and team state are cleared once an item is decided.
- **Signed reports**: canonical snapshot `fmd-evidentiary-report/v2` adds the frame timeline, C2PA manifest digests and escalation fields. Verification uses the version a report was signed with, so v1 reports still verify.
- **Video and C2PA**: `/api/v1` returns `413 too_large` (25 MB images, 50 MB videos) and `422 unreadable_media` for files the scoring service can't read. `labelledContentCredentials` comes back with `include=labelled`. Video originals can't be attached to a report (`400`). AVI is detected by content sniffing. `VideoProperties` values are validated at startup.
- **Wellbeing**: reports filed during an enforced break are refused (`423`). Videos play only after a reveal. The support page is in the moderator navigation, and the demo page veils media until shown.
- **Jurisdictions**: `/api/analyze` accepts an explicit `jurisdiction` parameter (no geo-IP). Invalid codes are rejected with `400`. The compliance page names EU, GB and AU.
- **Transparency**: small-cell suppression, the reviewed-model-decision and moderator-overturn aggregates, a per-period cache, pending-legal-review labels, and a fully translated page.

Not done: there is no deadline on the second review itself, and the analyze page has no jurisdiction selector (the API accepts one).

## Files touched

- `ml-service/video.py`, `ml-service/content_credentials.py`, `ml-service/main.py`, `ml-service/requirements.txt`, `ml-service/test_video.py`, `ml-service/test_content_credentials.py`, `ml-service/tools/make_phase23_samples.py`
- `backend/.../model/` (`Appeal`, `ModerationAuditEvent`, `ModeratorAccount`, `AnalysisResult` fields), `backend/.../repository/`, `backend/.../service/` (`AnalysisService`, `MediaKinds`, `VideoProperties`, `NoticeService`, `AppealService`, `ModerationService`, `ModerationAuditService`, `WellbeingService`, `TransparencyService`, `CaseNumbers`, `PinAttemptGuard`, `ReportService`, `ReportSigningService`, `ForensicPanelRenderer`), `backend/.../controller/` (`AppealController`, `ModerationController`, `TransparencyController`, `QueueController`, `AnalyzeController`, `ApiV1Controller`, `MediaViews`), `backend/.../security/`, `backend/.../config/` (`ModeratorAccountSeeder`, `JurisdictionRuleSeeder`), `application.yml`
- Tests: `TrustSafetyIntegrationTest`, `ModeratorWellbeingIntegrationTest`, `MediaRoutingTest`, `TransparencySuppressionTest`, updated `ModerationAgreementIntegrationTest`, `ProvenanceIntegrationTest`, `ReportSigningIntegrationTest`; `frontend/src/lib/queue.test.js`
- `frontend/src/pages/` (`Demo`, `Appeal`, `Transparency`, `Support`, `ModerationQueue`, `Compliance`), `frontend/src/components/` (`FrameStrip`, `ContentCredentials`, `MediaPreview`, `ModerationControls`, `AppealsPanel`, `Wellbeing`, `Layout`), `frontend/src/lib/media.js`, `frontend/src/lib/appeals.js`, `frontend/src/lib/media.test.js`, `frontend/src/context/DecisionContext.jsx`, `frontend/src/api/client.js`, `frontend/src/i18n/*.json`, `frontend/nginx.conf`, `frontend/public/sample-clip.mp4`, `frontend/public/c2pa-sample.jpg`
- `README.md`, `API_DOCS.md`, `docs/MODEL_CARD.md`, `docs/PROJECT_DOCUMENTATION.md`

## Definition of Done

- [x] A synthetic test clip returns a per-frame timeline and a single routed decision; no frame is written to disk
- [x] A C2PA-signed sample shows its manifest details in the analysis response
- [x] An appeal can be filed, is blocked for the original moderator, and its outcome appears in the audit trail
- [x] The exposure limit forces a break; reveals are logged
- [x] The transparency report renders from live aggregates

---

# PHASE 24 — Engineering Maturity

> Depends on all previous phases. Can start in parallel with Phase 23.

## Why this phase exists

A trust-and-safety system has to be verifiably correct on every change and observable in production. Today tests exist per service but nothing runs them automatically, and the stack has no metrics.

## Tasks

- [x] **CI** — GitHub Actions: backend `mvn test`, ml-service `pytest`, `ml/` tests, frontend lint + tests, `docker compose build`, and a compose smoke test of `POST /api/analyze`; status badge in the README. *`.github/workflows/ci.yml`. The stack job also checks request-ID propagation and both metrics endpoints, and runs the Playwright, HA and prod-refusal checks, Trivy and SBOMs*
- [x] **End-to-end tests** — Playwright suite for the demo script: analyse → REVIEW → moderator approves → SLA countdown → provenance verify → report `VALID`; runs in CI against the compose stack. *`e2e/`, 3 tests: the demo script, the mock feed, request IDs through nginx*
- [x] **Observability** — Micrometer/Prometheus metrics (latency, decisions, SLA breaches, ml-service errors), a Grafana dashboard in `ops/`, structured JSON logs with request IDs across backend and ml-service. *Management port 8090 is internal-only. `docker-compose.observability.yml` adds Prometheus (with alert rules) and a provisioned Grafana dashboard. Both services write one ECS-style JSON access line per request, with matching field names*
- [x] **Production hardening** — Redis-backed rate limits and PIN lockouts; signing keys from a KMS/HSM interface with rotation; RFC 3161 timestamps on reports; startup refuses demo secrets outside the `dev` profile. *`SharedState` (Redis Lua scripts, or in-memory for one instance) also holds exposure counters and the SLA scheduler lock. `SigningKeyProvider` has a file keyring and Vault Transit, with audit-logged rotation and a published keyring. `DemoSecretsGuard` and the ml-service `startup_guard` enforce the dev-only secrets. The backend times out ml-service calls (`ML_SERVICE_TIMEOUT`)*
- [x] **Supply chain** — Dependabot, Trivy image scan, SBOM generation, non-root containers. *Trivy fails on fixable CRITICAL CVEs; CycloneDX SBOM per image; backend/ml-service run as UID 10001, frontend/feed on `nginx-unprivileged`*
- [x] **Load test** — k6 script and a documented throughput / saturation point for the CPU-only ml-service, with a GPU / batching option. *`load/README.md`: saturates at about 8 req/s on an 8-core laptop CPU; batched video-frame scoring (`VIDEO_BATCH_SIZE`); `docker-compose.gpu.yml` (GPU numbers not measured, no GPU available)*

## Files touched

- `.github/workflows/ci.yml`, `.github/dependabot.yml`
- `e2e/` (Playwright config, `tests/demo-script.spec.js`, `tests/helpers.js`)
- `ops/prometheus/` (scrape config, alerts), `ops/grafana/` (provisioning, `fmd-overview.json`), `ops/ha-ratelimit-check.sh`
- `load/analyze.js`, `load/synthetic-224.jpg`, `load/README.md`
- `docker-compose.yml` (Redis, management port, profiles, env), `docker-compose.ha.yml`, `docker-compose.observability.yml`, `docker-compose.gpu.yml`, `docker-compose.dev.yml`, all four Dockerfiles (non-root), `frontend/nginx.conf` (request IDs)
- `backend/.../state/` (`SharedState`, `RedisSharedState`, `InMemorySharedState`, `SharedStateConfig`), `backend/.../signing/` (`SigningKeyProvider`, `FileSigningKeyProvider`, `VaultTransitSigningKeyProvider`, `ReportTimestampService`, `Ed25519Keys`, `SigningConfig`), `backend/.../observability/` (`FmdMetrics`, `RequestIdFilter`), `config/DemoSecretsGuard`, `controller/SigningKeyController`, `RateLimiter`, `PinAttemptGuard`, `WellbeingService`, `SlaScheduler`, `ReportSigningService`, `MlClientService`, `WebClientConfig`, `application.yml`, `META-INF/spring.factories`
- `ml-service/observability.py`, `ml-service/startup_guard.py`, `ml-service/start.sh`, `ml-service/video.py` + `inference.py` (batched frame scoring)
- Tests: `EngineeringMaturityIntegrationTest`, `DemoSecretsGuardTest`, `RedisSharedStateTest` (Testcontainers), `SigningKeyProvidersTest`, `ReportTimestampServiceTest`, `RequestIdFilterTest`, `MlClientServiceTimeoutTest`, `support/FakeTsa`, `support/FakeVaultTransit`; `ml-service/test_observability.py`, `test_video.py` (batching)
- `README.md`, `API_DOCS.md`, `.env.example`, `docs/PROJECT_DOCUMENTATION.md`

## Definition of Done

- [ ] A pull request shows green CI across all services, including the Playwright run. *Not yet shown: the workflow has not been pushed. Every step it runs was run locally against the rebuilt stack and passed (backend, ml-service, frontend, smoke, metrics, Playwright 3/3, HA check, prod refusal)*
- [x] `/actuator/prometheus` exposes decision and latency metrics; the Grafana dashboard loads them. *All 25 dashboard queries return successfully from Prometheus; all four scrape targets up*
- [x] Two backend instances share rate-limit state through Redis. *`ops/ha-ratelimit-check.sh`: 10 requests alternated across both instances empty one bucket; the 11th is refused by both*
- [x] The backend fails to start in a non-dev profile with default secrets. *`SPRING_PROFILES_ACTIVE=prod` exits with each problem listed; the ml-service refuses `APP_PROFILE=prod` with the demo provenance key*

---

# PHASE 25 — Presentation & Exhibition Polish

> Depends on Phases 22–24.

## Why this phase exists

Reviewers, judges and interviewers should understand the system and its reasoning in minutes, without running Docker.

## Tasks

- [x] **Public live demo** — hosted instance with synthetic samples only and a reset button. *`docker-compose.demo.yml` + `ops/demo/Caddyfile` (HTTPS for the console and `feed.` host; only Caddy publishes ports) and `docs/PUBLIC_DEMO.md`. Public mode (`DEMO_PUBLIC`): every multipart upload on every route must be a bundled synthetic sample or a labelled copy the demo issued (`403 demo_samples_only`), uploads share an instance-wide budget (`429 demo_rate_limited`), anyone may reset with a cooldown, the instance resets itself every `DEMO_RESET_INTERVAL`, a banner shows the next reset, and the tour offers one-click moderator sign-in. The configuration is ready and validated; actually hosting it needs a VM and a domain*
- [x] **One-click demo scenario** — script / admin button that seeds a full story: a flagged upload, an at-risk complaint, a breach, an appeal and a signed report. *Landing CTA → `/tour` seeds the story on first click (idempotent, lock-protected across instances) from synthetic images drawn at runtime and walks through it in five live chapters with links to the verify trail, the appeal and case pages (PIN passed in navigation state, never the URL), the queue, compliance and the signed PDF. Senior moderators get a reset button; `POST /api/demo/reset` wipes and reseeds. Every upload form offers **Use a sample** chips*
- [x] **Architecture decision records** — `docs/adr/`: no raw-media persistence, two independent models, Ed25519 signatures, hash-only prescreen, explicit jurisdiction (no geo-IP). *Index + template in `docs/adr/README.md`; each record names the code and tests that enforce it and when to revisit*
- [x] **Threat model** — `docs/THREAT_MODEL.md`: adversarial uploads, key theft, hash-list poisoning, moderator account compromise, report forgery; mitigations and residual risk. *Plus public-demo abuse, trust boundaries and assets. Writing it surfaced two fixes made here: the moderator session cookie is now `SameSite=Strict`, and public-demo uploads are rate-limited. Open gaps are stated as residual risk: no MFA or login throttling for moderators, single-moderator hash import, no edge rate limit on the public analyze endpoint outside demo mode*
- [x] **Launch video** — embed `brag-output/brag.mp4` with `brag.jpg` as poster at the top of the README. *A `<video>` element with the poster, falling back to the poster image linked to the MP4 where `<video>` isn't rendered, plus a text link. The composition's render cache is git-ignored*

## Files touched

- `backend/.../demo/` (`DemoProperties`, `DemoStory`, `DemoStoryRepository`, `DemoSamples`, `DemoScenarioService`, `DemoResetService`, `DemoUploadGuard`, `DemoUploadFilter`, `DemoController`), `backend/src/main/resources/demo-samples/`, `service/LabelledCopyIssued`, `AnalysisService`, `ComplianceService`, `CaseService`, `TransparencyService`, `SecurityConfig`, `DemoSecretsGuard`, `ForgedMediaApplication`, `application.yml`
- Tests: `DemoScenarioIntegrationTest`, `DemoPublicModeIntegrationTest`, `TrustSafetyIntegrationTest`, `DemoSecretsGuardTest`; `frontend/src/lib/demo.test.js`; `e2e/tests/tour.spec.js`
- `frontend/src/pages/Tour.jsx`, `Landing.jsx`, `Demo.jsx`, `ReportWizard.jsx`, `Verify.jsx`, `Compliance.jsx`, `CaseStatus.jsx`, `Appeal.jsx`; `frontend/src/components/` (`DemoBanner`, `SamplePicker`, `Layout`); `frontend/src/context/DemoContext.jsx`, `frontend/src/lib/demo.js`, `frontend/src/lib/format.js`, `frontend/src/api/client.js`, `frontend/src/App.jsx`, `frontend/src/i18n/*.json`; `mock-social-feed/app.js`
- `docker-compose.demo.yml`, `ops/demo/Caddyfile`, `docker-compose.yml`, `.env.example`, `.gitignore`, `.gitattributes`
- `docs/adr/` (index + 5 records), `docs/THREAT_MODEL.md`, `docs/PUBLIC_DEMO.md`, `README.md`, `API_DOCS.md`, `docs/PROJECT_DOCUMENTATION.md`

## Definition of Done

- [x] A first-time visitor reaches a populated, meaningful demo in one click. *Landing → **See it live in one click** → `/tour` with all five chapters populated and the report verifying `VALID`; covered by `e2e/tests/tour.spec.js` and `DemoScenarioIntegrationTest`*
- [x] ADRs and the threat model are linked from the README. *In the header links and a "Design decisions and threat model" section*
- [x] The launch video plays from the README. *Plays inline wherever the README's HTML is rendered in full (IDE previews, most Markdown viewers). github.com does not reliably play a repo-relative `<video>` source, so the poster and a text link also open the MP4; for guaranteed inline playback there, drag `brag.mp4` into the README editor on GitHub once, and swap the `src` for the `user-attachments` URL it generates*

---

# INVESTIGATION LIFECYCLE EXTENSION (Phases 26–28)

> Extends `IMPLEMENTATION_PLAN.md`. Same locked tech stack, phase-gate rules and data rules apply. Phases 13–25 answer "is this image manipulated, and what evidence supports that?" for one upload. These phases carry a case through the rest of an investigation: **where has this media appeared → which accounts are associated with it → what evidence is preserved → how does an authorised investigator receive and verify it.**
>
> Boundaries that hold across all three phases:
> - **Reported sightings, not web search.** "Where has it appeared" means uploads to this platform plus sightings reported by partner platforms through the authenticated API. No scraping, crawling or reverse-image search of the open web.
> - **Pseudonymous accounts, not identities.** Account IDs are turned into a keyed HMAC scoped to the platform on arrival and never stored. The system can say "the same account posted 4 of these 6 sightings"; only the partner platform can say who that account is, and only in answer to a legal request.
> - **Commitments, not media (ADR 0001 stands).** Preservation records hashes, signed analyses and a custody log. The platform hosting the file keeps it under its own legal hold; the committed SHA-256 lets an investigator prove later that the file they receive is the same one.

---

# PHASE 26 — Sightings & Investigation Cases

> Depends on Phases 14 (provenance), 16 (partner API) and 18 (hash matching).

## Why this phase exists

A takedown decision covers one upload. Abusive media rarely appears once: the same image is re-posted across accounts and platforms, and the people handling a case need to see all of those appearances together, linked by hash, in one timeline.

## Tasks

- [x] **Investigation cases** — `Investigation` (case number `INV-XXXX-XXXX`, title, OPEN/CLOSED, opened/closed by), `InvestigationItem` (analyses in the case) and an internal `InvestigationEvent` log (opened, item added, sighting linked, note, closed, reopened) that names moderators and is never public. *Package `backend/.../investigation/`; enum columns are kept in step with PostgreSQL CHECK constraints by `EnumConstraintSync`. A closed case is read-only until reopened*
- [x] **Partner sightings** — `POST /api/v1/sightings` (API key): `phash` and/or `provenanceId`, `platform`, the partner's opaque `postRef`, optional `accountId` and `observedAt`. Idempotent per platform + post + hash. The response never says whether the sighting matched a case. *`201` new / `200` with `duplicate: true`; `400 invalid_sighting` with a sentence naming the field. Metered like `analyze`. `platform` defaults to the partner's name; a provenance-only sighting takes the pHash of that analysis so it also matches re-encoded copies. `observedAt` more than 5 minutes ahead is refused. Counted in `fmd_sightings_total{source,linked}`*
- [x] **Account pseudonyms** — `accountId` becomes `HMAC-SHA256(key, platform + accountId)` on arrival and is discarded; `postRef` must be an opaque ID (no URLs, handles or e-mail addresses). The key (`INVESTIGATION_PSEUDONYM_KEY`) is refused at its demo value outside the dev profile. *`AccountPseudonyms`: the input is `platform + "\n" + accountId` so the two parts can't run together; the console shows the first 12 hex characters. `DemoSecretsGuard` also refuses keys under 32 characters*
- [x] **Hash linking** — sightings link to every open case holding an analysis within the prescreen pHash distance (or carrying the sighting's provenance ID), both when a sighting arrives and when an analysis is added to a case. *Links are stored in `sighting_links` with how they matched (`PROVENANCE` or `PHASH` + distance) and against which item. Reopening a case links the sightings reported while it was closed*
- [x] **Case view** — `GET /api/investigations/{caseNumber}`: the evidence (analyses, scores, generation family, signed reports), appearances on this platform (pHash and lineage matches), partner sightings, a per-platform summary, an account graph (counts, platforms, first and last seen, other cases the same pseudonym appears in) and one merged timeline. *Appearances are computed on read from a hash index of all analyses, capped at 200. The timeline merges the case log, every item's provenance trail, sightings and pHash re-uploads*
- [x] **Moderator actions** — open a case from any analysis, add analyses, record a sighting reported to a moderator, add notes, close and reopen. *`/api/investigations/**` is `MODERATOR`-only in `SecurityConfig` and by `@PreAuthorize`. Errors are `{error, message}` like the rest of the moderator API*
- [x] **Console** — `/investigations` list and case page; "Investigate" on queue items. *`pages/Investigations.jsx` (status filter, case-number jump, open-a-case form; case page with evidence, sightings, platforms, appearances, accounts with links to other cases, timeline grouped by day, notes, record sighting, add evidence, close/reopen), `components/InvestigateButton.jsx` (goes to the item's open case or opens one), "Investigations" in the moderator nav, `lib/investigations.js` mirroring the server's validation*
- [x] **Demo** — the one-click story opens a case on the flagged item with synthetic sightings from fictional platforms. *Three partner sightings of the composite on `chirp`, `pixelgram` and `vidshare` (one reported before the case is opened, two as re-encoded copies a few bits away). A second case on the victim's report shares a made-up `pixelgram` account with the first. It is shown as chapter 6 of `/tour` (English and Hindi). A reset wipes the new tables*

## Definition of Done

- [x] A partner sighting whose pHash is within the threshold of a case's analysis appears in that case, before or after the case is opened; a distant one does not. *`InvestigationIntegrationTest.sightingsBeforeAndAfterOpeningLinkButDistantOnesDoNot` (2 bits before, 0 after, 64 not linked)*
- [x] No raw account ID is stored anywhere; the same account on the same platform yields the same pseudonym, on another platform a different one. *Same test reads the `sightings` rows back; `AccountPseudonymsTest`*
- [x] An account seen in two cases is shown as linked in both. *`anAccountSeenInTwoCasesIsLinkedFromBoth`; also asserted on the demo story in `DemoScenarioIntegrationTest`*
- [x] Investigations are moderator-only; partner API keys cannot read them. *`onlyModeratorsReadInvestigationsAndOnlyPartnersReport`: anonymous and API-key calls get `401`*

## Files touched

- `backend/.../investigation/` (`Investigation`, `InvestigationItem`, `InvestigationEvent`, `Sighting`, `SightingLink`, their enums and repositories, `InvestigationProperties`, `AccountPseudonyms`, `Hashes`, `InvestigationService`, `SightingService`, `InvestigationController`, `SightingApiController`)
- `backend/.../config/DemoSecretsGuard`, `EnumConstraintSync`; `security/SecurityConfig`; `service/PrescreenService`, `ApiKeyService`; `repository/AnalysisResultRepository`, `ApiClientRepository`; `observability/FmdMetrics`; `demo/DemoStory`, `DemoScenarioService`, `DemoResetService`; `application.yml`
- Tests: `InvestigationIntegrationTest`, `AccountPseudonymsTest`, updated `DemoScenarioIntegrationTest`, `DemoSecretsGuardTest`; `frontend/src/lib/investigations.test.js`
- `frontend/src/pages/Investigations.jsx`, `Tour.jsx`, `ModerationQueue.jsx`; `frontend/src/components/InvestigateButton.jsx`, `Layout.jsx`; `frontend/src/lib/investigations.js`, `frontend/src/api/client.js`, `frontend/src/App.jsx`, `frontend/src/i18n/*.json`
- `docker-compose.yml`, `.env.example`, `README.md`, `API_DOCS.md`, `docs/PROJECT_DOCUMENTATION.md`

---

# PHASE 27 — Evidence Preservation & Chain of Custody

> Depends on Phase 26 and Phase 20/24 (signed reports, RFC 3161, signing keys).

## Why this phase exists

Evidence is only useful to an investigator if nobody can quietly change it later. Today individual reports are signed, but nothing ties together what a case contained, when it was preserved and who has touched it since.

## Tasks

- [x] **Hash-chained custody log** — every case event carries `SHA-256(previous hash + canonical event)`; a verifier recomputes the chain and reports the first broken link. *The case log from Phase 26 is the custody log: `InvestigationEvent` gains `seq`, `prevHash` and `hash` (canonical `fmd-custody-event/v1`: case, seq, time, actor, type, analysis, sighting, detail), and the case row keeps the chain length and head. Appends take a row lock on the case so concurrent events queue instead of forking. `GET …/custody` names the first bad event and why: edited, deleted (gap), trimmed from the end (misses the recorded head), inserted by hand (no position), or rewritten end to end (no longer matches a signed preservation). Cases opened before Phase 27 are sealed once at startup; an unsealed event on an already sealed case is reported, never absorbed*
- [x] **Preservation snapshot** — "Preserve evidence" freezes the case: a canonical manifest of the analyses (content SHA-256, pHash, scores, signed report IDs and hashes), linked sightings and the custody head, signed with the report-signing key and optionally timestamped (RFC 3161). *`PRS-XXXX-XXXX`, manifest `fmd-preservation-manifest/v1` (also case, preserver, reason; sightings carry platform, post, account pseudonym and how they matched), Ed25519 via the `SigningKeyProvider`, RFC 3161 over the signature via `ReportTimestampService`. Verification rebuilds the manifest from the records it names, recomputes the custody head from event contents, and diffs: evidence added to the case later doesn't disturb an earlier preservation. The preservation itself is a custody event carrying the manifest SHA-256. Refused (`409 custody_broken`) while the log fails verification. Allowed on closed cases. The exact signed text is downloadable for offline checks*
- [x] **Legal hold requests** — per partner platform, recorded as custody events with the post references the hold covers; the platform keeps the media. *`HLD-XXXX-XXXX`, covering named posts that are linked sightings on that platform (all of them by default, at most 100). Requested → confirmed (with the platform's reference) → released, each a custody event; the request event carries the SHA-256 of the sorted post list. A request whose posts are all already under an active hold is refused. Delivery to the platform is out of band, so partners still can't learn through the API whether their sightings matched a case*
- [x] **ADR 0006** — "Preserve commitments, not media": why the system never escrows the file, and how the committed SHA-256 proves a later copy is the same. *`docs/adr/0006-preserve-commitments-not-media.md`, including exactly how to recompute `content_sha256` for images (pixels) and videos (file), and the residual risks*
- [x] **Console and demo** *(added)* — "Custody and preservation" card on the case page (verify chain with the event log, preserve, download and verify manifests, request / confirm / release holds); preservation and hold counts on case cards; the one-click story preserves the spread case and requests a `pixelgram` hold, shown in chapter 6 of `/tour`

## Definition of Done

- [x] Editing or deleting any custody event is detected by verification. *`CustodyIntegrationTest.editingOrDeletingAnyCustodyEventIsDetected` edits each stored field of an event, re-hashes one, deletes one from the middle and the newest, and inserts one by hand; `CustodyChainTest` also covers reordering and a fully recomputed chain caught by a signed anchor*
- [x] A preserved manifest verifies `VALID`, and `TAMPERED` after any stored field it covers changes. *`aPreservedManifestVerifiesAndCatchesAnyChangeItCovers`: `VALID` with a verified RFC 3161 timestamp and checkable offline with the public key; still `VALID` after new sightings and notes; `TAMPERED` naming the field for an analysis score, content hash, report hash, sighting post, match distance, a custody event before the head, the preservation row and the manifest text; `VALID` again once each is restored; deleting a covered link is caught. Also asserted on the demo story*
- [x] No media bytes are written by preservation. *`preservationWritesNoMediaBytes`: every manifest line is a known `key=value` field, the manifest is smaller than the upload, and a full H2 `SCRIPT` dump after preservation contains neither the hex nor the base64 of the uploaded bytes*

## Files touched

- `backend/.../investigation/` (`CustodyChain`, `CustodyLog`, `Preservation`, `PreservationRepository`, `PreservationService`, `LegalHold`, `LegalHoldStatus`, `LegalHoldRepository`, `LegalHoldService`, `CustodyController`; `InvestigationEvent`, `InvestigationEventType`, `InvestigationEventRepository`, `Investigation`, `InvestigationRepository`, `InvestigationService`)
- `backend/.../config/EnumConstraintSync`; `demo/DemoScenarioService`, `DemoResetService`
- Tests: `CustodyChainTest`, `CustodyIntegrationTest`, updated `DemoScenarioIntegrationTest`; `frontend/src/lib/investigations.test.js`
- `frontend/src/pages/Investigations.jsx`, `Tour.jsx`; `frontend/src/lib/investigations.js`, `frontend/src/api/client.js`, `frontend/src/i18n/*.json`
- `docs/adr/0006-preserve-commitments-not-media.md`, `docs/adr/README.md`, `README.md`, `API_DOCS.md`, `docs/PROJECT_DOCUMENTATION.md`

---

# PHASE 28 — Authorised Investigator Handover

> Depends on Phase 27.

## Why this phase exists

The last step is getting evidence to the person entitled to it, in a form they can verify without trusting this system's database, and without the account graph becoming a tool for anyone who can log in.

## Tasks

- [x] **INVESTIGATOR role** — sees only cases covered by an approved legal request, read-only, every read logged in the custody chain. *`ModeratorRole.INVESTIGATOR` (`ROLE_INVESTIGATOR` only, so no moderator endpoint accepts it; the account's team is its agency). `/api/investigator/**` is the only API it can call, and has no write endpoints except issuing a package. Access is checked on every request against requests that are `APPROVED`, unexpired and name this investigator; anything else, including a case that doesn't exist, returns the same `403 no_legal_authority`. Each case view is an `INVESTIGATOR_VIEWED` custody event naming the request. The account graph is cut to the order: `otherCases` lists only cases the same investigator is authorised for, and moderator-only fields (`totalSightings`, appearances on this platform) are left out*
- [x] **Legal requests with four-eyes approval** — authority, order reference, scope (cases), expiry; approved by a senior moderator who did not file it. *`LRQ-XXXX-XXXX` with authority, order reference, named investigator, up to 25 cases and an expiry between 1 hour and 180 days (`HANDOVER_MAX_REQUEST_TERM`). Any moderator files it (`PENDING`); only a senior who did not file it approves or rejects it (`403 conflict_of_interest` / `senior_required`), and a rejection needs a reason. A senior can revoke an approved request early with a reason. `EXPIRED` is derived from the clock, so nothing has to run for access to end. Filing, approval, rejection and revocation are custody events on every covered case*
- [x] **Signed evidence package** — ZIP of the preserved manifest, case report (JSON + PDF), sightings, custody log and the signing public key; package manifest signed and timestamped; downloads time-limited and logged. *`PKG-XXXX-XXXX.zip`: `preservation/manifest.txt` + `signature.json` (the latest preservation, which must still verify: `409 not_preserved` / `custody_broken` / `evidence_tampered`), `case/report.json` + `report.pdf`, `case/sightings.json`, `custody/log.json`, `keys/keys.json` + `public-key.pem`, `README.txt`, and `PACKAGE-MANIFEST.txt` (`fmd-evidence-package/v1`: authority, request, investigator, approver, custody head, and every file's SHA-256 and size) with `PACKAGE-SIGNATURE.txt` (Ed25519 over the manifest's SHA-256, a seal over the signature lines, an RFC 3161 token when a TSA is set). Account IDs appear only as pseudonyms. Issue and each download are custody events (the download records the ZIP's SHA-256, also sent as `X-Package-SHA256`); downloads stop after `HANDOVER_DOWNLOAD_WINDOW` (24 h, never past the request's expiry, `410 package_expired`) or when the request is revoked. The ZIP is built on demand and only its hash is stored*
- [x] **Offline verifier** — a small script that checks the package signature, every file hash and the custody chain with only the published public key; `/api/verify` accepts packages too. *`tools/verify_evidence_package.py`: Python 3.9+ standard library only, including a pure-Python Ed25519 (checked against RFC 8032 vectors). Checks structure, signature and seal against keys the user supplies (`--keys` / `--public-key`; embedded keys only with `--trust-embedded-keys`), every file's hash and size and that nothing was added, the custody chain up to the manifest's head, the preservation signature and that the log still reaches the head it signed, and that the timestamp token was issued for this signature; `--sha256` compares the whole ZIP with the download header, `--extract-timestamp` writes the token for `openssl ts -verify`. Exit 0 / 1 / 2. `POST /api/verify` checks any ZIP the same way in Java (`PackageVerifier`), plus the timestamp against the TSA certificate and whether it is byte for byte the ZIP this server issued. CI runs the script on the package the backend tests write*
- [x] **Threat model** — investigator misuse, account-graph abuse and package leakage, with mitigations and residual risk. *T7 in `docs/THREAT_MODEL.md`: forged or over-broad orders, self-approval, lingering access, a compromised investigator account, browsing the account graph, package forwarding and doctored copies. Residual risk: the platform can check that an order was recorded and approved, not that it is genuine; a downloaded package can't be recalled; offline, the ZIP container outside the signed files is covered only by `--sha256`*
- [x] **Console and demo** *(added)* — **Legal requests** page for moderators (file with case numbers prefilled from a case, approve / reject / revoke, awaiting / active / ended); **My cases** for investigators (authorised cases, the read-only case view with its authority, issue and download packages); `/verify` accepts a package ZIP; the case page's custody card lists requests and packages; the one-click story files and approves a request for the `investigator` account, shown in chapter 6 of `/tour`

## Definition of Done

- [x] An investigator without an approved, unexpired request gets 403 on every case. *`HandoverIntegrationTest.anInvestigatorWithoutAnApprovedUnexpiredRequestGets403OnEveryCase`: no request, a nonexistent case (same message), pending, another investigator's request, expired, revoked and rejected all return `403`; an approved request opens only the cases it names; the investigator gets `403` on moderator endpoints and moderators on the investigator API. Also asserted on the demo story*
- [x] A downloaded package verifies offline; changing any byte inside it fails verification. *`aDownloadedPackageVerifiesOfflineAndAnyChangedByteFails`: `VALID` in `PackageVerifier` with only the published keys and through `/api/verify`; a flipped byte in every entry, in the signature lines, an extra line, a re-signed manifest with an edited custody log, an added file and a removed file are all rejected by both, and about 400 sampled raw-byte flips across the ZIP by `/api/verify`. The test writes the package and tampered copies to `target/phase28/`, where `tools/test_verify_evidence_package.py` and CI run the Python verifier on them*
- [x] A requester cannot approve their own request. *`aRequesterCannotApproveTheirOwnRequest`: the filing senior gets `403 conflict_of_interest` on approve and reject, a plain moderator `403 senior_required`, and `canDecide` is false for both; a different senior approves, and a second decision is `409 request_not_pending`*

## Files touched

- `backend/.../investigation/` (`LegalRequest`, `LegalRequestStatus`, `LegalRequestRepository`, `LegalRequestService`, `LegalRequestController`, `InvestigatorService`, `InvestigatorController`, `EvidencePackage`, `EvidencePackageRepository`, `EvidencePackageService`, `PackageFormat`, `PackageVerifier`, `CaseReportRenderer`; `InvestigationEventType`, `InvestigationProperties`, `InvestigationService`)
- `backend/.../model/ModeratorRole`, `ModeratorAccount`; `security/SecurityConfig`, `ModeratorUserDetailsService`; `service/ModerationService`; `controller/VerifyController`; `signing/ReportTimestampService`; `config/EnumConstraintSync`; `demo/DemoScenarioService`, `DemoResetService`; `application.yml`, `pom.xml`
- Tests: `HandoverIntegrationTest`, updated `DemoScenarioIntegrationTest`; `tools/test_verify_evidence_package.py`; `frontend/src/lib/handover.test.js`
- `tools/verify_evidence_package.py`; `.github/workflows/ci.yml`; `docker-compose.yml`, `.env.example`
- `frontend/src/pages/LegalRequests.jsx`, `Investigator.jsx`, `Verify.jsx`, `Investigations.jsx`, `Login.jsx`, `Tour.jsx`; `components/Layout.jsx`, `RequestStatusBadge.jsx`; `auth/AuthContext.jsx`; `lib/handover.js`, `investigations.js`; `api/client.js`; `App.jsx`; `i18n/*.json`
- `docs/THREAT_MODEL.md`, `README.md`, `API_DOCS.md`, `docs/PROJECT_DOCUMENTATION.md`
