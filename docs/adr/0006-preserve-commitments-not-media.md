# 0006. Preserve commitments, not media

- **Status:** Accepted
- **Date:** 2026-09-29 (Phase 27)

## Context

An investigation needs its evidence to survive until a court, a regulator or the police asks for it,
and to arrive in a state nobody could have quietly changed. That normally means *preserving the file*.
[ADR 0001](0001-no-raw-media-persistence.md) says this system never stores the file, and its
"Revisit when" section names exactly this pressure: a requirement to preserve the original.

What the investigation actually needs:

- Proof that the media an investigator receives later is the media this system analysed.
- A record of what the case contained at a moment in time (analyses, scores, signed reports,
  sightings), and proof that it hasn't changed since.
- A record of who did what to the case, in what order, that can't be edited or trimmed silently.
- Someone who actually keeps the file for as long as the case needs it.

Options considered:

| Option | Why not |
|--------|---------|
| Keep the upload in the database or object storage while a case is open | Reverses ADR 0001 for the most harmful media the system sees. Every open case becomes a copy of intimate imagery waiting to leak, and holding it creates legal exposure for the operator. |
| A separate encrypted evidence vault (the option ADR 0001 left open) | Better, but still a second copy under our control, with its own keys, access control, retention and breach surface. It also duplicates what the platform hosting the post already has. |
| Ask the complainant to keep the file | Puts the burden on the person harmed, and a later copy from them proves nothing about what was posted. |
| **Record commitments and ask the host to hold the file** | Chosen. See below. |

## Decision

- **We keep commitments, not copies.** For each analysis the system already keeps a SHA-256 of the
  pixels (`contentHash`) and a pHash. A preservation snapshot signs those, together with the scores,
  the signed report's ID and SHA-256, and every linked sighting (platform, opaque post ID, account
  pseudonym, how it matched), into one canonical manifest (`fmd-preservation-manifest/v1`). The
  manifest's SHA-256 is signed with the report-signing key ([ADR 0003](0003-ed25519-report-signatures.md))
  and, when a TSA is configured, timestamped (RFC 3161).
- **The platform hosting the post keeps the file.** A legal hold request names the platform and the
  exact post IDs; the platform keeps the media under its own legal hold. The request, the platform's
  confirmation and the release are custody events. Nothing in this system fetches or receives the
  media.
- **The custody log is a hash chain.** Every case event stores `SHA-256(previous hash + canonical
  event)`. The case row records the chain's length and head, and every preservation signs the head it
  saw. Editing an event breaks its hash, deleting one leaves a gap, trimming the newest no longer
  reaches the recorded head, and rewriting the whole chain no longer matches a signed preservation.
- **Proving a later copy is the same.** When the platform hands the file to an investigator, the
  investigator recomputes its content hash (for an image, SHA-256 of `"<width>x<height>:"` plus the
  decoded RGBA pixels; for a video, SHA-256 of the file) and compares it with
  `analysis.N.content_sha256` in the preserved manifest, then checks the manifest's signature with the
  published public key. A match proves the file carries exactly the pixels (or bytes) this system
  analysed at that time; the pHash links it to re-encoded copies that aren't identical.

## Consequences

**Easier**
- ADR 0001 holds without exception: a breach of this system still leaks no media, even for cases
  under legal hold.
- An investigator doesn't have to trust our database. The manifest, signature and public key are
  enough to check what was preserved, and the hash is enough to check the file.
- Tampering with the custody record or the preserved facts is detectable, and verification names the
  event or field that changed.

**Harder, and accepted**
- If the hosting platform deletes the file despite the hold, or never had it, the evidence is gone.
  We can prove what the file *was*, not produce it. The legal hold request and the platform's
  confirmation are recorded so that responsibility is clear.
- For images, `contentHash` covers the decoded pixels, not the file bytes as posted, so the
  platform's copy must be decoded the same way to compare. Metadata-only differences still match;
  re-encoded copies match only on pHash, which is evidence of similarity, not identity. For videos it
  is the file hash, so any re-encode breaks it.
- A plain hash chain can be recomputed end to end by someone with write access to the database. What
  stops that is the signed head in each preservation and the head on the case row. Events written
  after the most recent preservation are protected only by the case row until the next preservation.
- Preserving a case with many sightings produces a large manifest; one manifest is capped at 1 MB.

## Enforced by

- `backend/src/main/java/com/forgeddetect/investigation/CustodyChain.java` (canonical event, chain
  verification, anchors) and `CustodyLog.java` (append under a row lock, sealing of pre-Phase-27 logs)
- `PreservationService.java` (manifest, signing, timestamp, rebuild-and-diff verification) and
  `Preservation` (no column for media: text manifest, hashes and signature only)
- `LegalHoldService.java` (holds only name posts already linked to the case; every step is a custody
  event)
- Tests: `CustodyChainTest`, `CustodyIntegrationTest` (edits and deletions detected; manifest `VALID`
  then `TAMPERED`; no upload bytes anywhere in a full database dump after preservation),
  `DemoScenarioIntegrationTest`

## Revisit when

- A jurisdiction requires the operator itself to hold the original as evidence. Then add the separate
  encrypted vault ADR 0001 describes, keyed per case, and keep this manifest as the integrity layer on
  top of it.
- Partners start exchanging files through a standard evidence-transfer protocol. The legal hold
  request could then be sent through it rather than out of band.
