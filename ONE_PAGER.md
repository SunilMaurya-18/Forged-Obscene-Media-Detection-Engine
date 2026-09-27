# Forged Media Detection — Plain-Language One-Pager

*Keep this next to the poster. Technical terms live in the code and report — this page is for anyone just walking up.*

The same content is in the app at **http://localhost:3000/explained** (the *Explained* tab), with a link from each term to the screen where you can see it working, and a **Print** button that produces a light, poster-friendly copy.

---

## The 30-second pitch

> This checks if a photo — or a still frame from a video — has been faked, flags it if it has, and — because there's now a law requiring platforms to remove this kind of content within a couple of hours — it also keeps a countdown so nothing slips past the legal deadline. Everything it decides comes with a digital seal, so any change made afterward shows up.

---

## What each term actually means

| In the project | In plain English | Think of it like... |
| --- | --- | --- |
| Forgery detection model | Checks if a photo's been faked | A handwriting expert spotting a forged signature |
| Explicit content classifier | Content sensitivity check | A separate, independent check — doesn't care *who*, just *what* |
| Decision engine | The rulebook that decides what happens next | A referee applying fixed rules, not guessing |
| SLA / compliance clock | Legal deadline timer | A delivery app showing "must act within 2 hours" |
| Provenance ID | Tamper-evident tag on the file | A sealed evidence bag — you can tell if it's been opened |
| Evidentiary report | Printable proof report | The forensic report a detective hands to a court |
| Signed / tamper-evident report | A report that proves itself untouched | A wax seal on a letter — break it and everyone knows |
| Perceptual hash / photo fingerprint | Matches a photo even if it's resized or lightly edited | Like a fingerprint — unique, and still recognizable with a smudge |
| Cross-dataset generalization | Does it still work on photos it's never seen before | Testing a student on questions not in the textbook |
| Grad-CAM heatmap | Highlights the part of the face that looked fake | A highlighter pen marking the suspicious word in a document |
| Diffusion-era robustness | Keeps working as fake-photo tools get newer | An antivirus that updates for new viruses, not just old ones |
| Jurisdiction rules | Different countries, different legal deadlines | Speed limits — the rule depends on which country's road you're on |

---

## Quick answers, in plain language

**"How do you know it's fake?"** It looks for tiny, invisible-to-the-eye patterns that AI-generated or edited images leave behind — patterns a camera photo doesn't have.

**"What if it gets it wrong?"** Anything the system isn't confident about goes to a human moderator, not straight to an automatic decision. The system only acts alone when it's very sure.

**"Why does the timer matter?"** New laws (in India and the US) require platforms to take this kind of content down within a set number of hours. The timer counts down on every complaint and raises the alarm as the deadline gets close, so nothing is missed by accident.

**"Is my photo safe with you?"** For the most sensitive check — *Check before you post* — your photo never actually leaves your device. Only a unique "fingerprint" of it is sent, similar to how a barcode represents a product without being the product itself. When a photo is uploaded for a full check, it is analysed and then thrown away: only its fingerprint and scores are kept.

**"Can the report be faked or edited later?"** Not without it showing — each report is digitally sealed the moment it's created, so any later change to it is detectable.

---

*Full technical detail lives in [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md), the [README](README.md) and the phase files — this page is deliberately the short version.*
