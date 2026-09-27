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
