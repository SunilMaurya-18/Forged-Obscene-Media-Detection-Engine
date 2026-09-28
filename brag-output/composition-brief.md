# Hyperframes Composition Brief: Forged Media Detection

## Objective
Create a cinematic launch-style brag video for Forged Media Detection that reads like a serious trust-and-safety / cybersecurity product film.

## Output
- Composition directory: `brag-output/composition/`
- Rendered video: `brag-output/brag.mp4`
- Format: landscape — 1920x1080
- Duration: 35s (user-requested 25–35s range)

## Source Material
- Project root: `D:\Forged Media Detection`
- Primary files read: `README.md` (source of truth), `frontend/src/index.css`, `frontend/src/components/Logo.jsx`, `frontend/src/lib/decision.js`, `frontend/src/lib/sla.js`, `frontend/src/i18n/en.json`, `frontend/public/metrics.json`
- Product name: Forged Media Detection (subtitle "Trust & Safety Console")
- Strongest claim: "Detect deepfakes and explicit imagery, route them to the right outcome, meet legal takedown deadlines, and prove what happened, all without ever storing the image."
- Key UI to recreate: analyze result (two scores + Grad-CAM), routing decision (ALLOW / REVIEW / AUTO_FLAG), SLA countdown with jurisdiction badge + ON_TRACK/AT_RISK, deadline-first moderation queue with blurred thumbnails, provenance VERIFIED, signed report VALID
- Copy that must appear verbatim:
  - `POST /api/analyze`
  - `ALLOW` / `REVIEW` / `AUTO_FLAG`
  - `ON_TRACK` → `AT_RISK`
  - "India IT Rules 2026 — 2h", "US Take It Down Act — 48h"
  - "Provenance label found · Verified"
  - `VALID` / `TAMPERED` / `NOT_FOUND`
  - "Ed25519-signed"
  - "Raw media is never persisted."
  - "134.1 ms" mean end-to-end latency; "0 / 15" false positives on the clean set; caveat "Smoke run on synthetic splits with the demo checkpoint."
  - `docker compose up --build -d`
  - "Forged Media Detection"
- Must NOT appear: invented features, invented metrics, legal-admissibility claims, real names/emails, photoreal faces.

## Creative Direction
- Tone preset: cinematic
- Creative direction: premium forensic-lab / trust-and-safety product film
- Interpretation: slow deliberate camera drift per scene, confident holds, big mono readouts, soft crossfades with slight scale, restrained sound
- Angle: follow one redacted media item through the chain of custody — DETECT → ROUTE → DEADLINE → EVIDENCE — ending on privacy, honest metrics, the one-command run, and the wordmark
- Hook: blurred/redacted media wall + "Fakes of real people take seconds." / "Laws now put a clock on the response."
- Outro: typed `docker compose up --build -d`, then shield mark + wordmark + "Detect · Route · Deadline · Evidence"
- Avoid: generic SaaS language, abstract filler, hype superlatives, gradient text, human faces

## Visual Identity
- Background: `#060a13` (deep navy-black), panels `#0c1422`, lines `rgba(160,190,230,0.14)`
- Text: `#e8eef6`, muted `#9fb0c4`
- Teal (clear / verified / on track / ALLOW): `#34d1bf`
- Amber (review / at risk): `#f5a524`
- Amber-red (flag / breached / brand): `#f0563c`; shield gradient `#f87171 → #b91c1c`
- Display font: Inter Variable (local woff2 from `frontend/node_modules/@fontsource-variable/inter`)
- Data font: JetBrains Mono Variable (local woff2)
- Texture: fine film grain overlay, soft teal/amber rim light at frame edges, faint 44px grid (the app's `bg-grid`)

## Storyboard
Use `brag-output/brag-plan.md` as the creative contract.
1. Hook — 4.0s — redacted media wall, two hook lines
2. DETECT — 6.0s — `POST /api/analyze`, two independent meters, Grad-CAM bloom
3. ROUTE — 4.0s — three lanes, token locks into REVIEW, "Humans decide the hard cases."
4. DEADLINE — 6.2s — SLA countdown ON_TRACK → AT_RISK + deadline-first queue
5. EVIDENCE — 4.8s — provenance VERIFIED + Ed25519 report VALID
6. PRIVACY — 3.0s — "Raw media is never persisted." + pHash chip
7. RESULTS — 3.2s — 134.1 ms, 0 / 15, caveat
8. RUN IT / WORDMARK — 3.8s — terminal command + logo lockup

## Audio
- Audio role: cinematic support
- Audio arc: low bed fades in → precise UI accents → tension at the deadline flip → bell on VALID → bell on logo as the bed fades
- Music: `assets/music/happy-beats-business-moves-vol-12-by-ende-dot-app.mp3`, base volume 0.28, volume lane fade-in 0–1.2s, fade-out 33.4–35.0s
- Music cue guidance: `.cursor/skills/brag/assets/music/cues/happy-beats-business-moves-vol-12-by-ende-dot-app.music-cues.json` (~110 BPM). Beat-lock: 8.74 Grad-CAM, 12.55 REVIEW lock, 17.47 AT_RISK, 22.93 VALID, 32.74 wordmark. Sequential text on every other beat.
- Audio-reactive treatment: subtle; bass/RMS breathes the rim-light glow and the background light field
- Audio-coupled moments: scan, meter locks, heatmap, lane lock, status flip, queue rows, VERIFIED, VALID, typed command, logo
- SFX selection guidance: prefer the low-HF-risk picks in `.cursor/skills/brag/assets/sfx/sfx-analysis.md` (impactSoft_medium, bong, click_00x, drop), bells only for the two payoffs
- Audio files: copied into `brag-output/composition/assets/`

## Hyperframes Instructions
Follow `hyperframes-core`, `hyperframes-animation`, `hyperframes-creative`, `hyperframes-keyframes`, `hyperframes-cli`. Single standalone `index.html`, one paused GSAP timeline registered at `window.__timelines["main"]`, deterministic (no clocks / random), local assets only. Run `npx hyperframes check` before render.
