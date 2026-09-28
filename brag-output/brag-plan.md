# Brag Plan: Forged Media Detection

## What is this app?
A trust-and-safety pipeline that scores every image twice (forgery + explicit content), routes it to `ALLOW` / `REVIEW` / `AUTO_FLAG`, runs legal takedown clocks per jurisdiction, and produces signed, verifiable evidence, without ever storing the image.

## The angle
Treat it like a forensic-lab product film, not an AI demo. The story is a chain of custody: **DETECT → ROUTE → DEADLINE → EVIDENCE**. One anonymous, redacted media item enters the pipeline and we follow it all the way to a signed report. The power is that every claim on screen comes straight from the README: real endpoints, real statuses, real legal windows, real metrics (with their caveat).

## Hook (first 2-3 seconds)
A dark wall of blurred, redacted media thumbnails. One tile lights up under a scan line. Type lands in two beats:
"Fakes of real people take seconds." → "Laws now put a clock on the response."
(Both lines adapted from the README's "Why it exists".)

## Key moments (the middle)
- The media card slides into the `POST /api/analyze` frame; a scan line passes; **two independent meters** fill: Forgery score and Explicit score.
- **Grad-CAM** heat blooms over the abstract silhouette: "where the model looked".
- Three routing lanes light up; the item's token drops into **REVIEW** (human queue). Thresholds shown as configured defaults (0.50 review / 0.85 auto-flag).
- A legal **SLA countdown** ("India IT Rules 2026 — 2h") fast-forwards and flips `ON_TRACK` → `AT_RISK`, escalation fires; beside it the **deadline-first moderator queue** with blurred thumbnails.
- **Provenance label found · VERIFIED**, then the **Ed25519-signed evidentiary report** verifies `VALID`.
- **Raw media is never persisted**: only a pHash, scores, decision, timestamps.
- Two README metrics, with the caveat on screen.

## Outro / punchline
A terminal types `docker compose up --build -d`, then the shield mark and **Forged Media Detection** wordmark resolve with "Detect · Route · Deadline · Evidence".

## User flow worth showing
Upload hits `/api/analyze` → two scores + Grad-CAM + routing decision → REVIEW item lands in the deadline-first queue with an SLA clock → moderator report is signed and publicly verifiable (`VALID`).

## Tone
- Preset: `cinematic`
- Creative direction: premium forensic-lab / trust-and-safety product film; dark, deliberate, credible
- Interpretation: slow camera drift, confident holds, big mono readouts, no hype language, no jokes. Motion is deliberate (power/expo eases), transitions are soft crossfades with slight scale. Sound supports, never shouts.

## Format: landscape — 1920x1080
## Duration: 35s (user asked for 25–35s; overrides the /brag 15–25s default)

## Visual identity
The app itself is red/black/grey (`--color-canvas: #0a0a0a`, `--color-brand: #ef4444`, Inter Variable + JetBrains Mono Variable, red shield logo mark). The user asked for deep navy + teal + warm amber-red, so:
- Background: deep navy-black `#060a13` with navy panels `#0c1422`
- Text: `#e8eef6`; muted `#9fb0c4`
- Accent 1 (teal, "clear / verified / on track"): `#34d1bf`
- Accent 2 (amber, "review / at risk"): `#f5a524`
- Accent 3 (amber-red, "flag / breached", also the brand shield): `#f0563c`, shield gradient `#f87171 → #b91c1c` from `Logo.jsx`
- Display font: Inter Variable (800, tight tracking) — from the project
- Body/data font: JetBrains Mono Variable — from the project
- Strongest visual element: the app's status system (ALLOW/REVIEW/AUTO_FLAG, ON_TRACK/AT_RISK/BREACHED) plus the shield logo mark
- Faces: none. Abstract head-and-shoulders silhouettes, blurred and redacted.

## Share copy (draft)
Forged Media Detection scores every image twice, routes it to the right outcome, runs the legal takedown clock, and signs the evidence, without ever storing the image. One command: docker compose up --build -d

## Audio direction
- Role: cinematic support
- Music: `happy-beats-business-moves-vol-12-by-ende-dot-app.mp3` (steady and clean; the listed pick for cinematic), low bed ~0.28
- Music treatment: fade in over 1.2s, sit under the edit, fade out over the last ~1.6s, letting the final logo hit ring
- Music cue guidance: preset `assets/music/cues/happy-beats-business-moves-vol-12-by-ende-dot-app.music-cues.json`, ~110 BPM (beat ≈ 0.545s). Strong cues to target: **8.74s** (Grad-CAM bloom), **12.55s** (REVIEW lane lock), **17.47s** (AT_RISK flip), **22.93s** (report `VALID`), **32.74s** (wordmark land). Sequential text (queue rows, metric cards) on every *other* beat (~1.09s apart) and then held.
- Audio-reactive treatment: subtle; music bass/RMS gently breathes the teal rim glow and the background light field. No waveform/equalizer visuals.
- SFX posture: sparse, motion-matched, low high-frequency risk
- Audio-coupled moments: scan pass, meters locking, Grad-CAM bloom, lane lock, status flip, queue rows, VERIFIED/VALID stamps, typed terminal command, logo land
- Restraint rule: no comedic cues, no stacked hits, nothing louder than the logo bell

## Storyboard

### Scene 1 — Hook — 4.0s (0.0–4.0)
Wall of blurred/redacted media tiles (abstract silhouettes, redaction bars) drifting slowly. One tile highlighted by a teal scan. Line 1 "Fakes of real people take seconds." at ~0.4s, line 2 "Laws now put a clock on the response." at ~1.7s (amber-red "clock"). Both held to the end.
Sequential/interaction: two lines, one after the other, then held.
Audio intent: low, ominous entry.
Audio-coupled idea: soft impact on line 1.
Music: fade in.
Transition mood: dramatic → Scene 2

### Scene 2 — DETECT — 6.0s (4.0–10.0)
Chapter tag "01 DETECT". The highlighted tile becomes a media card that slides into a pipeline frame titled `POST /api/analyze`. Scan line passes. Two meters fill independently: "Forgery score 0.67" (EfficientNet-B0) and "Explicit score 0.08" (separate classifier). Label: "Two independent scores". Grad-CAM heatmap blooms over the silhouette at ~8.74s: "Grad-CAM · where the model looked". Scores are illustrative sample values in a UI mockup, not metrics.
Sequential/interaction: card slides in → scan → meter 1 → meter 2 → heatmap.
Audio intent: precise, lab-like.
Audio-coupled idea: scan swish, clicks when meters lock, soft drop on the heatmap.
Transition mood: soft crossfade → Scene 3

### Scene 3 — ROUTE — 4.0s (10.0–14.0)
"02 ROUTE". Three lanes: `ALLOW` (both scores below review thresholds), `REVIEW` (human moderation queue), `AUTO_FLAG` (blocked without review). Threshold readout: "review ≥ 0.50 · auto-flag ≥ 0.85 · configurable". The item token travels and locks into REVIEW at ~12.55s. Line: "Humans decide the hard cases."
Sequential/interaction: lanes appear; token travels and locks.
Audio intent: decisive.
Audio-coupled idea: selection click on lock.
Transition mood: soft crossfade → Scene 4

### Scene 4 — DEADLINE — 6.2s (14.0–20.2)
"03 DEADLINE". Left: big mono countdown with badge "India IT Rules 2026 — 2h", fast-forwarding from `01:59:59` toward `00:29:59`; status flips `ON_TRACK` → `AT_RISK` at ~17.47s with "Escalated · webhook". Right: "Moderator queue · deadline-first", three rows with blurred thumbnails and deadline chips (e.g. "India IT Rules 2026 — 2h", "US Take It Down Act — 48h"), footer "Blurred thumbnails · keyboard shortcuts · 10-second undo".
Sequential/interaction: queue rows arrive one by one (~1.09s apart) and hold.
Audio intent: tension rising.
Audio-coupled idea: soft drops on rows, a crisp notification on the AT_RISK flip.
Transition mood: soft crossfade → Scene 5

### Scene 5 — EVIDENCE — 4.8s (20.2–25.0)
"04 EVIDENCE". Left: provenance panel "Provenance label found · VERIFIED" with "HMAC-signed XMP manifest · pixels untouched". Right: evidentiary report PDF card (decision, scores, SLA timestamps, Grad-CAM / FFT / ELA panels, audit trail) with "Ed25519-signed"; verify result stamps `VALID` at ~22.93s. Small line: "Publicly verifiable: VALID / TAMPERED / NOT_FOUND".
Sequential/interaction: provenance → report → VALID stamp.
Audio intent: resolution, authority.
Audio-coupled idea: glass tick on VERIFIED, bell on VALID.
Transition mood: soft crossfade → Scene 6

### Scene 6 — PRIVACY — 3.0s (25.0–28.0)
Headline "Raw media is never persisted." The blurred image dissolves into a 64-bit hash chip `pHash · c2423d3d3b783cc6` (the README's synthetic demo pattern hash). Row: "stored: pHash · scores · decision · timestamps".
Sequential/interaction: headline → image-to-hash dissolve → stored row.
Audio intent: quiet, clean.
Transition mood: soft crossfade → Scene 7

### Scene 7 — RESULTS — 3.2s (28.0–31.2)
Two metric readouts from the README: "134.1 ms · mean end-to-end latency, CPU only" and "0 / 15 · false positives on the clean set". Footnote (must appear): "Smoke run on synthetic splits with the demo checkpoint."
Sequential/interaction: two readouts ~1.1s apart, held; count-up on 134.1.
Audio intent: steady.
Transition mood: soft crossfade → Scene 8

### Scene 8 — RUN IT / WORDMARK — 3.8s (31.2–35.0)
Terminal prompt types `docker compose up --build -d`, then shrinks down as the shield mark and **Forged Media Detection** wordmark land at ~32.74s, with "Trust & Safety Console" and "Detect · Route · Deadline · Evidence". Hold to the end.
Sequential/interaction: typed command with keypress sounds, then logo lockup.
Audio intent: confident finish.
Audio-coupled idea: keypress per character, deep bell on the logo.

**Music mood for this video:** cinematic
**Audio summary:** a low, steady bed fades in under the hook, precise lab-like UI accents track the pipeline, tension lifts at the deadline flip, and a single bell resolves on the signed report and again on the wordmark as the music fades.
