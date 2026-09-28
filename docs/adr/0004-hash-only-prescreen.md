# 0004. The prescreen accepts a hash, never an image

- **Status:** Accepted
- **Date:** 2026-09-28 (recorded in Phase 25; the prescreen arrived in Phase 18)

## Context

"Check before you post" lets anyone ask *does this image match something already confirmed as
abusive?* before sharing it. The people most likely to use it are also the most exposed:

- a victim checking whether a picture of them is already known
- a worried friend who was sent an image
- a platform checking uploads at scale

If the check required uploading the image, we would be asking victims to send us the very image they
fear is circulating, and we would be receiving a stream of intimate images from people who never
filed a complaint. That conflicts with [ADR 0001](0001-no-raw-media-persistence.md) and with basic
trust.

Hash-sharing schemes such as StopNCII already work this way: the image stays on the device, only a
perceptual hash leaves it.

## Decision

- The browser computes the perceptual hash **on the device** (`frontend/src/utils/onDeviceHash.js`:
  a 64-bit DCT pHash). The image is never sent.
- `POST /api/prescreen` accepts only JSON `{ "hash": "...", "algorithm": "phash" | "pdq" }`. Any other
  content type (for example a multipart upload) is refused with `400 only_hash_accepted`.
- The answer is a bare `match` / `no match`: no distance, no source, no row ID. A caller cannot learn
  how close it came, so it cannot nudge an image until it slips just under the threshold, and cannot
  learn where a hash came from.
- Matching uses Hamming distance with conservative thresholds (`PRESCREEN_PHASH_MAX_DISTANCE=8` of
  64 bits, `PRESCREEN_PDQ_MAX_DISTANCE=31` of 256 bits).
- The known-hash list grows from three sources only: moderator-confirmed takedowns
  (`INTERNAL_CONFIRMED`), bulk import of a trusted list such as StopNCII (moderator-only admin
  endpoint), and configured seeds. A takedown reversed on appeal removes the hash it added.
- A prescreen match is **advisory**. It never removes or blocks content by itself; takedowns always go
  through the models and a human decision.

## Consequences

**Easier**
- Victims can check without disclosing the image.
- The endpoint can be public and cheap: a hash lookup, no model inference.
- The same contract works for a partner platform hashing server-side.

**Harder, and accepted**
- Perceptual hashes are robust to recompression and resizing, but not to heavy crops, mirroring or
  overlays. Those evade the prescreen and fall through to the models.
- Hashes can be deliberately collided: an attacker can craft an innocent-looking image whose pHash
  matches a listed one. Because a match is advisory and every takedown needs a human, a collision
  causes a false warning, not a takedown.
- The list is only as trustworthy as the people who can add to it. Import is limited to moderators,
  every addition records its source and note, and poisoning is analysed in the
  [threat model](../THREAT_MODEL.md).
- A pHash reveals a little about an image (see ADR 0001), so the list itself is never published and the
  endpoint never echoes stored hashes.
- The linear scan over the list is fine for thousands of hashes. Millions would need a BK-tree or
  multi-index hashing.

## Enforced by

- `backend/src/main/java/com/forgeddetect/controller/PrescreenController.java` (`only_hash_accepted`)
- `backend/src/main/java/com/forgeddetect/service/PrescreenService.java` (format checks, bare answer,
  takedown add and release, import)
- `frontend/src/utils/onDeviceHash.js` and its tests
- Prescreen integration tests in the backend suite

## Revisit when

- A PDQ implementation ships in the browser. The contract already carries the algorithm name, so this
  is additive.
- Private set intersection becomes practical in browsers, so the server would not even learn the hash
  a user checked.
