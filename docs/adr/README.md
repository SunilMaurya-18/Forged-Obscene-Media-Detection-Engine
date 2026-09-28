# Architecture Decision Records

Short records of the decisions that shape this system: what we chose, why, what we gave up, and when
to look again. The format follows [Michael Nygard's ADRs](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
**Context**, **Decision**, **Consequences**. Each record also points to the code that enforces it, so
a reviewer can check the claim instead of trusting it.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-no-raw-media-persistence.md) | Never persist raw media; keep only hashes, scores and forensic summaries | Accepted |
| [0002](0002-two-independent-models.md) | Two independent models (forgery, explicit) with separate thresholds | Accepted |
| [0003](0003-ed25519-report-signatures.md) | Sign evidentiary reports with Ed25519 over a canonical record snapshot | Accepted |
| [0004](0004-hash-only-prescreen.md) | The "check before you post" prescreen accepts a hash, never an image | Accepted |
| [0005](0005-explicit-jurisdiction-no-geoip.md) | Jurisdiction is always stated explicitly; never inferred from geo-IP | Accepted |

These decisions were taken while the phases in [`IMPLEMENTATION_PLAN.md`](../../IMPLEMENTATION_PLAN.md)
were built and were written down in Phase 25. The [threat model](../THREAT_MODEL.md) leans on
them: several of its mitigations exist only because of these choices.

## Adding a record

1. Copy the template below to `NNNN-short-title.md`, using the next number.
2. Fill in the sections, then link it from the table above.
3. Never rewrite an accepted record. To reverse a decision, add a new record that supersedes it and
   set the old one's status to `Superseded by NNNN`.

```markdown
# NNNN. Title

- **Status:** Proposed | Accepted | Superseded by NNNN
- **Date:** YYYY-MM-DD

## Context
What forces are at play, and what problem needs a decision.

## Decision
What we do, stated so it can be checked.

## Consequences
What gets easier, what gets harder, and what we accept as a cost.

## Enforced by
Files, tests and guards that keep the decision true.

## Revisit when
Conditions under which this decision should be reopened.
```
