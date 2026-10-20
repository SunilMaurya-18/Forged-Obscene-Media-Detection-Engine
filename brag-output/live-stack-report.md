# Live stack feature sweep — 2026-10-19

Against Docker backend `:8081`, UI `:3000` / Vite `:5173`, mock feed `:3001`. Synthetic JPEG only. Moderator `Admin` (dev).

## Summary

| Suite | Result |
|-------|--------|
| API feature sweep (`live-stack-test.py`) | **61 PASS**, 2 FAIL (payload typos), 2 SKIP |
| Corrected retests | note, sighting, legal hold + ack, appeal as `senior`, ALLOW as `reviewer` → **PASS** |
| Wellbeing break on Admin after sweep | **PASS** (423 `break_required` — feature working) |
| Playwright e2e (`smoke-flow.spec.js`) | **3/3 PASS** (after clearing Admin break key) |

## Feature matrix

| Area | Status | Notes |
|------|--------|-------|
| Stats / transparency | PASS | `forgeryReview=0.05` |
| Auth / moderation me / teams | PASS | Admin has ADMIN+MODERATOR+SENIOR |
| Analyze (dashboard) | PASS | REVIEW + notice `UPL-…` + PIN |
| Analyze (API v1 + demo key) | PASS | usage OK |
| Provenance verify | PASS | trail present |
| Queue list / assign / escalate / unassign / audit | PASS | |
| Queue decision ALLOW | PASS | via `reviewer` (Admin was on break) |
| Queue decision AUTO_FLAG | PASS | |
| Evidentiary PDF + verify-report VALID | PASS | `decision-record-*.pdf`, panels=SIGNATURE |
| Appeal file + status | PASS | |
| Appeal decide | PASS | as `senior` (Admin conflicted — by design) |
| Public complaint + case status | PASS | `IN_REVIEW` |
| Compliance file / queue / summary / breaches / rules | PASS | |
| Prescreen hash | PASS | no match on fresh hash |
| Admin overview / accounts / known-hashes | PASS | |
| Admin API clients create/plan/rotate/revoke | PASS | |
| Investigations open / get / list / add item | PASS | |
| Sighting + note + custody | PASS | (correct `postRef` / `text`) |
| Preservation | PASS | |
| Legal hold + acknowledge | PASS | |
| Legal request create + approve | PASS | |
| Investigation close / reopen | PASS | |
| Wellbeing break | PASS | enforced |
| UI vite / docker frontend / mock feed | PASS | |
| Grafana / Prometheus | PASS | reachable |
| Playwright: analyse→approve→SLA→report VALID | PASS | |
| Playwright: mock feed → REVIEW | PASS | |
| Playwright: request IDs | PASS | |

## Intentional skips / design behaviours

- **Appeal cannot be decided by the same moderator** who set AUTO_FLAG (`canDecide=false`, conflicted=`Admin`).
- **Admin break** after many exposures; clear `fmd:wellbeing:break:Admin` (and items) in Redis to continue, or wait out break duration.
- Fingerprint surrogate panels apply only when `X-Report-Panels: NONE`; this run used SIGNATURE.

## Artifacts

- `brag-output/live-stack-results.json` — full API sweep log  
- `brag-output/live-stack-retest.json` — corrected paths  
- `brag-output/live-stack-test.py` — reproducible sweep script  
