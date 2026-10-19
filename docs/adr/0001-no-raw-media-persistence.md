# 0001. Never persist raw media

- **Status:** Accepted
- **Date:** 2026-09-28 (recorded in Phase 25; the rule has held since Phase 1)

## Context

The system exists to help people get non-consensual intimate imagery (NCII) and forged media taken
down. The media it handles is, by definition, some of the most harmful content a service can hold:

- A stored copy of an intimate image is itself a harm. Every copy is one more place it can leak from,
  through a breach, a backup, a misconfigured bucket or an insider.
- In many jurisdictions, holding such content creates legal exposure for the operator, even when the
  intent is to remove it.
- Complainants trust the service because it takes their image away from the internet. They would not
  trust it if it kept a copy.

The platform still needs evidence: moderators have to see why an item was flagged, reports must
stand up later, and re-uploads must be recognised.

## Decision

Raw media bytes (uploads, complaint attachments, video frames, report originals and the labelled
copy) are never written to the database, disk, object storage or logs. Each upload is processed in
memory and then dropped. What survives is:

| Kept | Why it is enough |
|------|------------------|
| Perceptual hash (pHash) and a SHA-256 content hash of the pixels | Recognises re-uploads (prescreen, known-hash list) and binds a report to one exact image |
| Model scores, decision, generation-family guess | Explains and audits the routing |
| Forensic signature grids: 14×14 attention map, 16×16 error-level analysis, 32×32 frequency spectrum | Lets a moderator see *where* the model looked without seeing the image |
| Provenance timeline, SLA timestamps, moderator actions | The record the evidentiary report signs ([ADR 0003](0003-ed25519-report-signatures.md)) |

The labelled copy (the image with its visible and embedded provenance label) goes back to the caller
in the response and is never stored. When a report must show the original, the caller supplies it
again, the server checks it against the stored content hash, renders the panel and discards it. Items
at or above the explicit review threshold never embed the image in a PDF at all: their panels always
come from the coarse grids, so a report can be forwarded without forwarding the abuse.

## Consequences

**Easier**
- A database breach leaks hashes, numbers and small grids. None of them can be turned back into the
  image.
- Data-protection duties (retention, erasure, cross-border transfer) apply to far less data.
- Public report and analyze endpoints can accept strangers' uploads without anyone's photo ending up in a table.

**Harder, and accepted**
- A moderator reviewing a queue item sees the forensic grids and scores, not the picture. Where a
  person must look at the pixels (confirming a takedown, deciding an appeal), the original has to be
  supplied again by whoever holds it: the complainant, the uploader or the partner platform.
- A report can't be regenerated with the original image unless someone uploads it again.
- Retraining on production traffic is impossible by design. Models are trained on curated,
  consented or synthetic datasets only.
- A perceptual hash is not zero information. With enough compute, a coarse, blurred approximation of
  an image can sometimes be recovered from a pHash. This is why the hash list is not public, and it is
  listed as a residual risk in the [threat model](../THREAT_MODEL.md).

## Enforced by

- `backend/src/main/resources/application.yml`: `spring.servlet.multipart.file-size-threshold: 52MB`,
  above the upload limit, so Tomcat never spools a multipart part to a temporary file.
- `ml-service/main.py`: `MultiPartParser.spool_max_size` is raised above the image and video limits,
  so Starlette keeps uploads in memory too.
- `AnalysisResult` has no column for media bytes, only `phash`, `contentHash`, scores and the forensic
  grids. `ReportService` verifies a supplied original against `contentHash` and never saves it.
- `AnalyzeController` / `ApiV1Controller` return `labelledImage` as base64 in the response body only.
- `video.py` decodes frames into memory, scores them and keeps no frames.
- Integration tests such as `ProvenanceIntegrationTest` and `ReportSigningIntegrationTest` exercise the
  flows without any storage for media.

## Revisit when

- A regulator or court requires the operator to preserve the original as evidence. The answer then is
  a separate, encrypted evidence vault with its own access control and retention clock, never the
  application database.
- A lawful, consented retraining programme is set up. Opt-in samples would go to an isolated
  pipeline, not to production storage.
