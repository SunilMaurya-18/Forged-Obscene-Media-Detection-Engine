# Hyperframes Composition Brief: Forged Media Detection

## Objective
Create a Christopher Nolan–directed cinematic advertisement for Forged Media Detection — trailer-scale, ticking-clock pressure, product UI in use, under 60 seconds.

## Output
- Composition directory: `brag-output-2026-10-19-211756/composition/`
- Rendered video: `brag-output-2026-10-19-211756/brag.mp4`
- Format: landscape — 1920x1080
- Duration: 50 seconds

## Source Material
- Project root: `D:\Forged Media Detection`
- Primary files read: `README.md`, `frontend/src/pages/Landing.jsx`, `frontend/src/pages/Analyze.jsx`, `frontend/src/i18n/en.json`, `frontend/src/index.css`
- Product name: Forged Media Detection
- Tagline / strongest claim: Spot fake and intimate images. Act before the legal deadline. / Fakes take minutes to make. The harm lasts much longer. / Images are never stored.
- Key UI or visual moment to recreate: Analyze upload → dual scores → REVIEW; moderator queue card with blurred media + deadline; VALID sealed report
- Copy that must appear verbatim (or near-verbatim from product):
  - Fakes take minutes to make.
  - The harm lasts much longer.
  - Act before the legal deadline.
  - India IT Rules 2026
  - REVIEW / Likely forged / VALID
  - 0 images stored (product claim: images never stored)

## Creative Direction
- Tone preset: cinematic
- Creative direction: Christopher Nolan trailer ad — IMAX type, non-linear time, practical dark UI, ticking clock as antagonist
- Interpretation: Slow holds, hard cuts on ticks, sparse declarative copy, epic scale, no startup cheer
- Angle: Time is the antagonist; the platform races legal deadlines and seals proof without keeping pixels
- Hook: Black + tick + **2 HOURS**
- Outro / punchline: FORGED MEDIA DETECTION · Act before the legal deadline · cut to black on last tick
- Avoid:
  - Generic SaaS language
  - Abstract filler visuals
  - Unrelated visual redesign
  - Happy/upbeat music character (use dark transformed bed)
  - Showing any real harmful imagery (blurred abstract only)

## Visual Identity
- Background: `#0f1114`
- Text: `#e3e6ea`
- Accent: `#d97757`
- Display font: Source Serif 4 (or Georgia fallback / loaded woff2)
- Body font: Manrope
- Mono: JetBrains Mono
- Visual references from the project: Landing HeroPreview card, VerdictBadge, Analyze pipeline + ScoreMeter, brand terracotta on near-black

## Storyboard
Use `brag-plan.md` as the creative contract.

Scene summary:
1. The Clock — 5s — 2 HOURS + India IT Rules 2026
2. The Cost — 6s — Fakes take minutes / harm lasts longer
3. The Check — 12s — Analyze UI: dual scores → REVIEW
4. Against the Clock — 11s — Moderator card + countdown + AT_RISK
5. The Seal — 8s — 0 stored / VALID / proof remains
6. Lockup — 8s — Product name + tagline → black

## Audio
- Audio role: cinematic support
- Audio arc: dread ticks → mechanical precision → urgent deadline → resonant seal → swell → silence
- Music: `assets/music/nolan-pulse.mp3` (transformed from bundled vol-10)
- Music treatment: ~0.32–0.40 vol under; swell on seal/outro; fade last 1.2s
- Music cue guidance: detect at composition via `npx hyperframes beats` on transformed track; optional strong locks near 0s, 12s, 28s, 42s, 48s
- Audio-reactive treatment: subtle; vignette + brand ember glow on RMS/bass
- Audio-coupled moments:
  - Scene 1 — ticks
  - Scene 3 — drop, meter ticks, REVIEW stamp
  - Scene 4 — countdown ticks, AT_RISK
  - Scene 5 — VALID bell
  - Scene 6 — logo slam + final tick in black
- SFX selection guidance: impactSoft, impactBell, impactPlate_light, interface drop/click/bong; avoid casino/error/glitch-heavy chaos
- SFX analysis guidance: `.cursor/skills/brag/assets/sfx/sfx-analysis.md`
- Exact SFX choice: Hyperframes chooses after animation exists
- Audio files: copy into composition/assets/

## Hyperframes Instructions
Load hyperframes-core, hyperframes-animation, hyperframes-creative, hyperframes-keyframes, hyperframes-cli. Do not enter the generic promo interview.

Requirements:
- Show real product UI recreations (Analyze + moderator card)
- All text readable with adequate settled holds
- Duration ~50s (max 60)
- Include music/SFX layer
- Run `npx hyperframes check` before render
- Poster frame baked as frame 0 of final mp4
