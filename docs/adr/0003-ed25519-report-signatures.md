# 0003. Ed25519 signatures over a canonical record snapshot

- **Status:** Accepted
- **Date:** 2026-09-28 (recorded in Phase 25; signing arrived in Phase 20, key providers in Phase 24)

## Context

Evidentiary reports go to people who do not trust us by default: a platform's legal team, a grievance
officer, a court, the police, a regulator. Months later, someone will ask: *is this PDF what the
system actually decided, and has the record changed since?*

What we need:

- Anyone can check a report without being given a secret.
- A report whose underlying record changed after signing is caught, and the check names what changed.
- Keys can be rotated and kept in a KMS, and old reports keep verifying after a rotation.
- Signing is cheap enough to run on every report, and simple enough to reimplement in a few lines of
  any language.

Options considered:

| Option | Why not |
|--------|---------|
| HMAC-SHA256 | Symmetric: whoever can verify can also forge. Fine inside one trust boundary; that is why the ml-service provenance manifest uses it. Wrong for third parties. |
| RSA-PSS 3072 | Works, but keys and signatures are large (384-byte signatures) and there are more parameters to get wrong (padding, salt length, hash). |
| ECDSA P-256 | Needs a fresh random nonce per signature, and a biased or repeated nonce leaks the private key. |
| PAdES / CMS signature inside the PDF | Shows a signature panel in PDF viewers, but brings heavy dependencies and certificate chains, and it signs the *file*, not the facts. A re-rendered PDF of the same record would not verify. |
| **Ed25519** | Deterministic (no nonce to get wrong), 32-byte keys, 64-byte signatures, fast, built into the JDK since 15, supported by Vault Transit and most KMSs. |

## Decision

- Each report signs a **canonical snapshot** of the record: a fixed, ordered, versioned list of the
  fields the report asserts (`fmd-evidentiary-report/v2`: scores, decision, who decided, SLA
  timestamps, provenance ID, timeline, content-credential digests, escalation). The SHA-256 of that
  snapshot is signed with **Ed25519**.
- The snapshot, its hash, the signature, the key ID and an optional RFC 3161 timestamp token are
  stored on the analysis row. The PDF prints the report ID, key ID, content hash and signature.
- `GET /api/verify-report/{reportId}` checks the signature with the stored key ID, checks the
  timestamp token, then rebuilds the snapshot from the **current** record and diffs it. Results:
  `VALID`, `TAMPERED` (with the changed fields named) or `NOT_FOUND`. Older `v1` snapshots still
  verify with their original field list.
- Public keys, including retired ones, are published at `GET /api/verify-report/keys`. The key ID is
  the first 16 hex characters of the SHA-256 of the DER-encoded public key, so anyone can recompute it.
- Keys come from a `SigningKeyProvider`: a file keyring for development, or Vault Transit, where the
  private key never leaves Vault. Rotation (`POST /api/admin/signing-keys/rotate`, senior moderators
  only) keeps retired keys in the keyring.

## Consequences

**Easier**
- Verification needs only a public key, and a few lines of code in any language with an Ed25519
  library.
- A changed record is caught even when the PDF itself was never touched, and the diff names the
  fields, which is what an investigator needs.
- A regenerated PDF of an unchanged record still verifies, because the signature covers facts, not
  layout.
- With Vault Transit, stealing the backend host does not steal the signing key (see the
  [threat model](../THREAT_MODEL.md)).

**Harder, and accepted**
- PDF viewers show no "signed document" banner. Recipients must use the verify endpoint or the
  published key. The report says so on its signature page.
- The signature binds the record, not the PDF bytes. Someone could edit the prose of a PDF while
  keeping the signature block. The server check still reports the true record, and the PDF's SHA-256 is
  recorded in the provenance trail (and returned in the `X-Report-SHA256` header) when it is
  generated, so an edited file is detectable, but not by the signature alone.
- Without a trusted timestamp, a signature proves *who* signed but not *when*. A stolen key could sign
  back-dated snapshots. Configuring an RFC 3161 TSA (`REPORT_TSA_URL`) closes that gap.
- Adding a field to the snapshot needs a new canonical version, with the old one kept verifiable.

## Enforced by

- `backend/src/main/java/com/forgeddetect/service/ReportSigningService.java` (canonical v1/v2,
  signing, verification and diff)
- `backend/src/main/java/com/forgeddetect/signing/` (`Ed25519Keys`, `FileSigningKeyProvider`,
  `VaultTransitSigningKeyProvider`, `ReportTimestampService`)
- `ReportVerificationController`, `SigningKeyController`
- `DemoSecretsGuard` refuses the file keyring outside the dev profile unless explicitly allowed.
- Tests: `ReportSigningIntegrationTest`, `signing/*Test`, `FakeVaultTransit`, `FakeTsa`

## Revisit when

- Recipients need signatures that PDF viewers recognise. Add a PAdES signature *on top of* the record
  signature, not instead of it.
- Post-quantum signatures (for example ML-DSA) are required by a recipient. The versioned canonical
  format and provider interface allow a second algorithm alongside Ed25519.
