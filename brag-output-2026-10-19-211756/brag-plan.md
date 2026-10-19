# Brag Plan: Forged Media Detection

## What is this app?
A trust-and-safety platform that detects deepfakes and explicit imagery, routes uncertain cases to humans, races legal takedown deadlines, and seals every decision with a signed evidence trail — without ever storing the image.

## The angle
**Time is the antagonist.** Christopher Nolan treats time as pressure and truth as something that must be proven under it. This film treats the legal clock the same way: fakes are made in minutes; the law gives hours; the platform never keeps the pixels — only the proof. Non-linear trailer structure: open on the deadline, cut back to the check, cut forward to the seal.

## Hook (first 2-3 seconds)
Black frame. A single tick. Giant type: **2 HOURS.** Then the jurisdiction line: *India IT Rules 2026*. The viewer feels the clock before they see the product.

## Key moments (the middle)
- Analyze flow: drop → two independent scores climb → route lands on **REVIEW**
- Moderator console: blurred media, **Due in 1h 42m**, AT_RISK pulse
- Seal: **0 images stored** / Ed25519 **VALID** report verify

## Outro / punchline
Full-bleed product name. Line holds: **Act before the legal deadline.** Cut to black on the last tick.

## User flow worth showing
1. **Entry** — Check an image (upload drop zone)
2. **Key action** — Two models score; decision engine routes to REVIEW
3. **Result** — Moderator queue against the clock → signed report verifies VALID; pixels discarded

## Tone
- Preset: `cinematic`
- Creative direction: Christopher Nolan–directed trailer ad — IMAX type, ticking clock, practical dark UI, non-linear time cuts, gravitas without hype slang
- Interpretation: Slow reveals, hard cuts on ticks, sparse copy (short declarative lines), epic scale type, restrained color (near-black canvas + brand terracotta accents). No startup cheer.

## Format: landscape — 1920x1080
## Duration: 50 seconds (under 60s cap; longer than default brag for trailer pacing)

## Visual identity (from the project)
- Background: `#0f1114` (canvas) / `#15181c` (surface)
- Accent: `#d97757` (brand) / `#b85c3e` (brand-2)
- Text: `#e3e6ea` (fg) / `#9aa1a9` (muted)
- Display font: Source Serif 4 (serif headlines)
- Body font: Manrope
- Mono: JetBrains Mono (scores, deadlines, VALID)
- Strongest visual element: Moderator queue card — blurred media, VerdictBadge LIKELY_FORGED, amber deadline chip, sealed evidence rows

## Share copy (draft)
Fakes take minutes. The law gives hours. We never keep the image — only the proof. Forged Media Detection.

## Audio direction
- Role: cinematic support — sparse, tense, trailer-scale
- Music: transformed bed from bundled `happy-beats-business-moves-vol-10` (60s) — slowed, pitch-dropped, low-passed into a dark pulse (happy original unfit for Nolan); if transform fails, SFX-led score only
- Music treatment: enter under first tick at ~0.35 vol; swell under seal/outro; hard fade last 1.2s under final impact
- Music cue guidance: cues detected at composition time on the transformed bed; strong moments target ~0:00 tick, ~0:12 analyze reveal, ~0:28 deadline, ~0:42 VALID, ~0:48 logo
- Audio-reactive treatment: subtle; vignette breath and brand ember glow on bass/RMS — no waveforms
- SFX posture: moderate-dense cinematic; motion-matched ticks, soft impacts, resonant bells, plate accents
- Audio-coupled moments: countdown digits, score meters climbing, REVIEW stamp, AT_RISK pulse, VALID verify, logo slam
- Restraint rule: no cheerful stings, no casino wins, no comic fails; never let SFX bury readable type

## Storyboard

### Scene 1 — The Clock — 5s
Black → giant **2 HOURS** fades in (IMAX scale). Subline in mono: `INDIA IT RULES 2026 · COMPLAINT WINDOW`. Soft tick every ~0.8s. Brand ember vignette breathes once.
Sequential/interaction: none
Audio intent: dread; establish the antagonist (time)
Audio-coupled idea: tick SFX on each second mark
Music: dark pulse under
Transition mood: hard cut on tick → Scene 2

### Scene 2 — The Cost — 6s
Full-bleed serif: **Fakes take minutes to make.** Hold. Cut: **The harm lasts much longer.** Product eyebrow tiny at bottom: *Trust & safety for the deepfake era*
Sequential/interaction: yes — two lines land one after the other
Audio intent: gravity; no rush
Audio-coupled idea: soft impact under each line settle
Transition mood: dramatic crossfade with slight scale → Scene 3

### Scene 3 — The Check — 12s
Recreate Analyze UI on dark canvas: drop zone receives `case-sample.jpg` (fictional filename). Pipeline labels appear: Forgery detector → Explicit-content classifier → Decision engine. Two score meters fill (forgery 0.87, explicit 0.12). Stamp: **REVIEW**. Verdict chip: **Likely forged**.
Sequential/interaction: yes — filename lands, meters fill, REVIEW stamps
Audio intent: precise machine tension building to a human handoff
Audio-coupled idea: drop, soft UI clicks, meter ticks, stamp impact
Transition mood: hard cut → Scene 4

### Scene 4 — Against the Clock — 11s
Recreate moderator console card from landing preview: blurred media + EyeOff, **LIKELY_FORGED**, deadline chip **Due in 1h 42m** counting down visually to **1h 41m**, rule chip **India IT Rules 2026 · 2h**, rows: Evidence report signed / A person makes the final call. Status pulse: **AT_RISK**.
Sequential/interaction: yes — card assembles, countdown ticks, AT_RISK flares
Audio intent: urgency without panic
Audio-coupled idea: tick on countdown, plate/soft impact on AT_RISK
Transition mood: dramatic wipe → Scene 5

### Scene 5 — The Seal — 8s
Three sealed facts land one by one in mono/serif mix:
1. **0** images stored — ever
2. Report signature: **VALID**
3. Pixels discarded. Proof remains.
Sequential/interaction: yes — three facts, one by one, hold all three
Audio intent: resolution; the Nolan “proof” beat
Audio-coupled idea: bell/impact on VALID; quieter drops on the other two
Transition mood: slow scale crossfade → Scene 6

### Scene 6 — Lockup — 8s
Full-bleed: **FORGED MEDIA DETECTION**. Tagline: **Act before the legal deadline.** Small CTAs as end card text only: Check an image · Report an image. Hold, then cut to black on final tick.
Sequential/interaction: none
Audio intent: final swell then silence after last tick
Audio-coupled idea: logo slam (impactBell) then music fade; last tick in black
Transition mood: cut to black

**Music mood for this video:** cinematic (dark transformed bed)
**Audio summary:** Tick-led trailer score — sparse dread opens, mechanical precision mid-film, resonant seal, swell into logo, silence after the last tick.

## Duration check
5 + 6 + 12 + 11 + 8 + 8 = **50s**
