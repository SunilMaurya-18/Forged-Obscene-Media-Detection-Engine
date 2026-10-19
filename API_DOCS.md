# Forged Media Detection API (v1)

Integrate forgery and explicit-content screening into your platform's upload pipeline. You send an image, and the API returns two independent scores and a routing decision (publish, hold for review, or block) in about 150 ms on CPU. It also returns a provenance ID you can use to trace re-uploads later.

- **Base URL:** `https://<your-deployment>` (local: `http://localhost:8081`, or `http://localhost:3000` through the web proxy)
- **Version:** everything below lives under `/api/v1`. Breaking changes will ship as `/api/v2`, and v1 keeps working.
- **Format:** requests are `multipart/form-data`, responses are JSON (UTF-8). Timestamps are ISO-8601 UTC.

---

## 1. Authentication

Every request needs an API key. A platform operator issues keys from the **API access** page (`/api-access`, moderator login), or with `POST /api/admin/clients`.

Send the key in either header:

```http
X-API-Key: fmd_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

```http
Authorization: Bearer fmd_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

- Keys start with `fmd_live_` followed by 43 random characters (256 bits). The server stores only a SHA-256 hash and the first 15 characters, which it shows so you can tell keys apart. **A lost key can't be recovered. Rotate it instead.**
- **Keep keys on your servers.** Never embed one in a web page, mobile app, or public repo. Call the API from your upload backend or worker.
- Rotating a key takes effect immediately, and the old key gets `401` from then on. To rotate without downtime, deploy the new key and then retire the old one within your own config.
- Moderator logins (Basic auth or session cookies) aren't accepted on `/api/v1`, and API keys never grant access to moderator endpoints.

## 2. Endpoints

### `POST /api/v1/analyze`

Scores one image or short video. It counts against your rate limit.

| Part / param | Where | Required | Description |
|---|---|---|---|
| `file` | multipart | yes | The image (JPEG, PNG or WebP, up to 25 MB) or video (MP4, WebM, MOV, MKV or AVI, up to 50 MB). Videos are sampled at the deployment's frame rate (default 1 fps, lowered for long clips so the whole clip is covered within the frame cap) and routed once on an aggregate of the frame scores. Uploads are held in memory, never written to disk |
| `include` | query | no | Comma-separated extras: `heatmap` (Grad-CAM overlay PNG, base64) and/or `labelled` (your image with a signed provenance manifest embedded, base64). Omitted by default to keep responses small |

```bash
curl -H "X-API-Key: $FMD_API_KEY" \
  -F file=@upload.jpg \
  "http://localhost:8081/api/v1/analyze"
```

**200 OK**

```json
{
  "id": 1042,
  "decision": "REVIEW",
  "forgeryScore": 0.7312,
  "explicitScore": 0.0418,
  "generationFamily": "diffusion",
  "phash": "c3d1e0f0b8a49687",
  "contentHash": "sha256:9f2c…",
  "provenanceId": "5b138708-f7c9-45f0-8f86-191fea041dd0",
  "verifyUrl": "/api/verify/5b138708-f7c9-45f0-8f86-191fea041dd0",
  "reuploadOf": null,
  "createdAt": "2026-09-27T05:12:31.434512Z",
  "mediaType": "IMAGE",
  "video": null,
  "contentCredentials": null,
  "notice": { "noticeNumber": "UPL-7K3Q-9XPA", "pin": "042917", "appealable": false, "statusUrl": "/api/appeals/UPL-7K3Q-9XPA/status" }
}
```

| Field | Meaning |
|---|---|
| `decision` | `ALLOW`, `REVIEW` or `AUTO_FLAG`. This is the field to act on (see §3) |
| `forgeryScore` | 0–1 confidence that the face is manipulated (EfficientNet-B0 trained on FaceForensics++, DFDC and Celeb-DF) |
| `explicitScore` | 0–1 explicit-content score from an independent classifier |
| `generationFamily` | Best-effort guess at what produced a detected fake: `gan` (face-swap / reenactment era), `diffusion`, or `unknown` (always `unknown` when the forgery score is below 0.5 or the model is unsure). Informational only; route on `decision`, not on this field |
| `phash` | 64-bit perceptual hash (hex). Near-duplicates have a small Hamming distance |
| `contentHash` | SHA-256 of the decoded pixels. It's identical for byte-different copies with the same pixels |
| `provenanceId` | Stable ID for this analysis. **Store it next to your post.** Anyone can look up its audit trail at `verifyUrl` (public) |
| `reuploadOf` | `null` for new content. If the image matches earlier content, this is an object identifying the original: `via` (`MANIFEST`, `CONTENT_HASH` or `PHASH`), `known`, `provenanceId`, `analyzedAt`, `decision`, and `manifest` (signature and pixel check, when a manifest was embedded) |
| `heatmap` | Only with `include=heatmap`. Base64 PNG |
| `labelledImage`, `labelledMimeType`, `labelledContentCredentials` | Only with `include=labelled`, images only. Base64 copy of your image with an HMAC-signed XMP manifest and a C2PA manifest, plus the summary of the C2PA manifest written into it (same shape as `contentCredentials`; `null` when C2PA signing is off). Pixels are unchanged, so it scores identically. Publish this copy if you want re-uploads traced by manifest |
| `mediaType` | `IMAGE` or `VIDEO` |
| `video` | Videos only. `fps` (the effective rate: lowered for long clips so the samples span the whole clip within the frame cap), `sampled`, `durationSeconds`, `peak` (`index`, `t` of the highest-scoring frame), `routing` (which aggregate each score was routed on: `MAX` or `TOPK_MEAN`), `aggregate` (`max` and `topkMean` per score) and `frames`, a compact timeline of `[seconds, forgeryScore, explicitScore]` triples. Frames themselves are never stored or returned |
| `contentCredentials` | `null` if the upload has no C2PA manifest. Otherwise `status` (`TRUSTED`, `VALID`, `VALID_UNTRUSTED` or `INVALID`), `signer` (`common_name`, `issuer`, `signed_at`), `claim_generator`, `actions` (`action`, `software_agent`, …), `ai_generated` and `manifest_count`. C2PA proves who signed which claims, not that they are true; route on `decision` |
| `notice` | A notice number and one-time PIN for the uploader. **Pass both to the uploader** if you use this service's appeals: they can appeal an `AUTO_FLAG` at `/appeal` or through `POST /api/appeals`. The PIN is stored only as a hash and cannot be recovered |

### `GET /api/v1/usage`

Usage for the calling key. **It doesn't count against the rate limit**, so you can call it while throttled.

```bash
curl -H "X-API-Key: $FMD_API_KEY" http://localhost:8081/api/v1/usage
```

```json
{
  "client":   { "name": "Acme Social", "plan": "PRO", "keyPrefix": "fmd_live_Q2x9aB", "createdAt": "2026-09-27T04:58:10Z" },
  "rateLimit":{ "limitPerMinute": 60, "remaining": 57, "resetSeconds": 3 },
  "requests": { "total": 1284, "throttled": 6, "lastUsedAt": "2026-09-27T05:12:31Z" },
  "analyses": { "total": 1279, "last24h": 212, "byDecision": { "AUTO_FLAG": 9, "REVIEW": 41, "ALLOW": 1229 } }
}
```

`requests.total` counts accepted, metered calls. `analyses` counts images that were actually scored.

### `POST /api/v1/sightings`

Tell the service where a piece of media has appeared on your platform, for example when your own hash matching finds a copy of an item you received an `AUTO_FLAG` for. Moderators see sightings in their investigations. You never learn whether a sighting matched anything, and there is no way to query sightings back. It is metered like `analyze`.

```bash
curl -X POST http://localhost:8081/api/v1/sightings \
  -H "X-API-Key: $FMD_API_KEY" -H "Content-Type: application/json" \
  -d '{"phash":"c3a5e1f00f1e5a3c","platform":"acme-social","postRef":"p-88213","accountId":"u-40917","observedAt":"2026-09-28T10:15:00Z"}'
```

| Field | Required | Notes |
|---|---|---|
| `phash` | one of `phash` / `provenanceId` | 16 hex characters: the 64-bit pHash of your copy (the same algorithm as `/api/prescreen`) |
| `provenanceId` | one of `phash` / `provenanceId` | From the manifest of a labelled copy (`include=labelled`) |
| `platform` | no | 1–48 letters, digits, `.` or `-` (lower-cased; spaces become `-`). Defaults to your client name |
| `postRef` | yes | **Your own opaque post ID**, 1–128 of `A-Z a-z 0-9 . _ : -`. URLs, usernames and e-mail addresses are refused |
| `accountId` | no | Your internal account ID for the poster. It is replaced on arrival by `HMAC-SHA256(key, platform + "\n" + accountId)` and never stored or logged. The same account on the same platform always maps to the same pseudonym, while other platforms get different ones |
| `observedAt` | no | ISO-8601 with an offset. Defaults to now; more than 5 minutes in the future is refused |

`201` for a new sighting, `200` with `"duplicate": true` when the same platform + post + hash was already reported:

```json
{ "id": 912, "duplicate": false, "platform": "acme-social", "postRef": "p-88213", "accountRecorded": true,
  "observedAt": "2026-09-28T10:15:00Z", "receivedAt": "2026-09-28T10:15:02.114Z" }
```

A sighting is linked to every **open** investigation with a case item that has the same provenance ID, or whose pHash is within the deployment's match distance (`PRESCREEN_PHASH_MAX_DISTANCE`, 8 bits by default). That applies whether it arrives before or after the case is opened.

## 3. Routing the decision

The thresholds are set by the deployment (defaults: review at 0.50, auto-flag at 0.85, for each score separately). The higher of the two decisions wins.

| `decision` | Recommended action in your pipeline |
|---|---|
| `ALLOW` | Publish. |
| `REVIEW` | Hold the post (for example, visible only to the uploader) and send it to your moderation queue with both scores. Don't show it publicly until a human decides. |
| `AUTO_FLAG` | Don't publish. Tell the uploader with a statement of reasons and the `notice` number + PIN so they can appeal, keep `provenanceId` for legal requests, and treat repeat `reuploadOf` hits as a signal about the account. |

Scores are model confidences, not proof. Keep a human in the loop for anything consequential.

## 4. Rate limits

Each key has a token bucket sized by its plan. You get that many requests in a burst, refilled evenly over 60 seconds.

| Plan | Requests / minute (default) |
|---|---|
| `FREE` | 10 |
| `PRO` | 60 |
| `ENTERPRISE` | 600 |

Every metered response carries:

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | Bucket size (requests per minute) |
| `X-RateLimit-Remaining` | Requests you can make right now |
| `X-RateLimit-Reset` | Seconds until the bucket is full again |
| `Retry-After` | On `429` only: seconds until the next request will be accepted |

When you get a `429`, wait `Retry-After` seconds, then retry, adding jitter if you run several workers. Don't retry in a tight loop, because throttled calls are counted and visible to the operator. If you're throttled regularly, ask for a higher plan.

## 5. Errors

Every error is JSON with a stable machine-readable `error` code and a human-readable `message`.

| Status | `error` | When | What to do |
|---|---|---|---|
| 400 | `file_required` | No `file` part | Fix the request |
| 400 | `invalid_include` | Unknown `include` value | Use `heatmap` and/or `labelled` |
| 400 | `invalid_sighting` | `POST /sightings`: no hash or provenance ID, malformed hash, a URL or handle as `postRef`, bad `platform`, future `observedAt` | Fix the field named in `message`. Don't retry unchanged |
| 401 | `missing_api_key` | No key header | Send `X-API-Key` |
| 401 | `invalid_api_key` | Key unknown, rotated, or revoked | Check config. Don't retry |
| 429 | `rate_limited` | Bucket empty (body also has `retryAfterSeconds`) | Back off for `Retry-After` |
| 413 | `too_large` | File over the limit (25 MB images, 50 MB videos) | Downscale or trim before sending |
| 422 | `unreadable_media` | The scoring service rejected the file as not a readable image or video | Don't retry the same file |
| 502 | `analysis_unavailable` | The scoring service is down or failed unexpectedly | Retry with backoff |
`401` responses also include `WWW-Authenticate: ApiKey header="X-API-Key"`.

Every response carries an `X-Request-ID`. Send your own (1–64 characters from `A-Z a-z 0-9 . _ : -`) to have it echoed back and written to both services' logs; otherwise one is generated. Quote it when reporting a problem.

## 6. Integrating into an upload pipeline

A typical flow for a platform with its own storage:

```
user uploads ─▶ your API server ─▶ store in private/quarantine bucket
                       │
                       └─▶ POST /api/v1/analyze  (server-side, with your key)
                                 │
          ALLOW ─────────────────┼──▶ move to public bucket, publish
          REVIEW ────────────────┼──▶ keep private, enqueue for your moderators
          AUTO_FLAG ─────────────┴──▶ reject, keep provenanceId for appeals
```

- **Call it synchronously** for interactive uploads (p95 is about 190 ms on CPU), or from a queue worker for bulk and backfill jobs. Workers should respect `X-RateLimit-Remaining`.
- **Fail closed or open deliberately.** A `422` means the file itself is unreadable. On a `502` or timeout, the safe default for user-generated content is to hold the post (treat it as `REVIEW`) and retry later, not to publish unscreened.
- **Store `provenanceId`** alongside the post. It links your record to the public audit trail and lets you match future re-uploads.
- **Timeouts:** 10 s is plenty. The first request after a cold start can take a few seconds.

### Node.js (18+, built-in `fetch`)

```js
import { readFile } from 'node:fs/promises'

export async function screenUpload(path, filename) {
  const form = new FormData()
  form.append('file', new Blob([await readFile(path)]), filename)

  const res = await fetch(`${process.env.FMD_BASE_URL}/api/v1/analyze`, {
    method: 'POST',
    headers: { 'X-API-Key': process.env.FMD_API_KEY },
    body: form,
    signal: AbortSignal.timeout(10_000),
  })

  if (res.status === 429) {
    const wait = Number(res.headers.get('retry-after') || 1)
    throw Object.assign(new Error('rate_limited'), { retryAfter: wait })
  }
  if (!res.ok) throw new Error((await res.json()).error)
  return res.json() // { decision, forgeryScore, explicitScore, provenanceId, ... }
}
```

### Python (`requests`)

```python
import os, time, requests

def screen_upload(path: str) -> dict:
    url = f"{os.environ['FMD_BASE_URL']}/api/v1/analyze"
    headers = {"X-API-Key": os.environ["FMD_API_KEY"]}
    for attempt in range(3):
        with open(path, "rb") as f:
            r = requests.post(url, headers=headers, files={"file": f}, timeout=10)
        if r.status_code == 429:
            time.sleep(int(r.headers.get("Retry-After", "1")))
            continue
        r.raise_for_status()
        return r.json()
    raise RuntimeError("rate limited after retries")

result = screen_upload("upload.jpg")
if result["decision"] == "ALLOW":
    publish()
elif result["decision"] == "REVIEW":
    hold_for_moderation(result["provenanceId"])
else:
    reject(result["provenanceId"])
```

### Related public endpoints (no API key)

**Hash-only prescreen: `POST /api/prescreen`.** Lets a client check an image *before* uploading it, without sending the image. Compute a 64-bit DCT pHash on the device (the same algorithm as `imagehash.phash`; the web app's `frontend/src/utils/onDeviceHash.js` is a reference implementation) and send only the hash:

```bash
curl -H "Content-Type: application/json" \
  -d '{"hash":"c2423d3d3b783cc6","algorithm":"phash"}' \
  http://localhost:8081/api/prescreen
# {"match":true,"algorithm":"phash","checkedAt":"…","advice":"…"}
```

The body may contain only `hash` and `algorithm` (`phash`, 16 hex characters, or `pdq`, 64 hex characters). Anything else returns `400 only_hash_accepted`. The response never reveals the matched hash, the distance, or where the hash came from.

**Report verification: `GET /api/verify-report/{reportId}`.** Checks an evidentiary PDF's Ed25519 signature against the current record. It returns `status` `VALID`, `TAMPERED` (with `changedFields`, names only) or `NOT_FOUND`, plus `keyId`, `signedAt` and `contentHash`. The report ID is printed in the PDF and stored in its `FMD-Report-Id` metadata. The signing public key is at `GET /api/verify-report/public-key`.

**Reports by affected people: `POST /api/complaints` and `GET /api/complaints/{caseNumber}/status`.** This is what the web app's report wizard uses. A platform can link to `/report` instead of building its own form.

```bash
curl -F file=@image.jpg -F consent=true -F relationship=SELF \
  -F contact=reporter@example.com -F jurisdiction=IN -F locale=hi \
  http://localhost:8081/api/complaints
# 201 {"caseNumber":"FMD-7K3Q-9XPA","pin":"042917","stage":"IN_REVIEW","deadline":"…","deadlineHours":2,"rule":"India IT Rules 2026",…}

curl -H "X-Case-Pin: 042917" http://localhost:8081/api/complaints/FMD-7K3Q-9XPA/status
# {"caseNumber":"FMD-7K3Q-9XPA","stage":"REMOVED","verdict":"LIKELY_FORGED","timeline":[{"stage":"RECEIVED","at":"…"},{"stage":"REMOVED","at":"…"}],…}
```

- **Filing.** `consent` must be `true` and `relationship` must be `SELF` or `ON_BEHALF`. `details` (at most 1000 characters), `contact` (an email address or a phone number with 8–15 digits), `jurisdiction` and `locale` (`en` / `hi`) are optional. Invalid input returns `400 {"error": code, "message": …}` before anything is analysed. Codes: `file_required`, `consent_required`, `relationship_required`, `relationship_invalid`, `details_too_long`, `invalid_contact`, `invalid_jurisdiction`. The filing becomes a `USER_COMPLAINT` with an SLA clock from the jurisdiction's rule.
- **The PIN** is returned once and stored only as a BCrypt hash; it cannot be recovered. Send it in the `X-Case-Pin` header, never in the URL. Case numbers are case-insensitive, and dashes are optional.
- **Status.** The response contains `stage` (`IN_REVIEW`, `REMOVED`, `NO_ACTION`), `verdict` (`LIKELY_FORGED`, `NEEDS_REVIEW`, `LOOKS_GENUINE`), `receivedAt`, `deadline`, `resolvedAt`, `withinDeadline`, `rule`, `notifyChannel` and `timeline`. It never contains scores, hashes or internal IDs. An unknown case number and a wrong PIN both return `404 case_not_found`. After 5 wrong PINs the case returns `429 too_many_attempts` with `Retry-After` for 15 minutes.
- **Personal data.** Only the optional contact detail and notes are stored with the case, and they are used only for status notices. The image is discarded after scoring, as everywhere else.

**Uploader appeals: `POST /api/appeals` and `GET /api/appeals/{noticeNumber}/status`.** An uploader whose post was removed automatically can contest it with the `notice` number and PIN from the analysis (EU DSA Art. 20 style). A platform can link to `/appeal?n=UPL-…` instead of building its own form.

```bash
curl -H "Content-Type: application/json" \
  -d '{"noticeNumber":"UPL-7K3Q-9XPA","pin":"042917","statement":"This is my own unedited photo."}' \
  http://localhost:8081/api/appeals
# 201 {"caseNumber":"UPL-7K3Q-9XPA","status":"OPEN","decisionDueBy":"…","rule":"Platform policy (generic)",…}

curl -H "X-Case-Pin: 042917" http://localhost:8081/api/appeals/UPL-7K3Q-9XPA/status
# {"decision":"AUTO_FLAG","appealable":false,"appeal":{"status":"GRANTED","reinstated":true,"reason":"…",…},…}
```

- Only `AUTO_FLAG` decisions can be appealed (`409 not_appealable`), once each (`409 already_appealed`). `statement` is required, at most 1000 characters. A wrong number or PIN is `404 not_found`, and repeated wrong PINs lock the notice (`429` + `Retry-After`).
- The appeal runs on its own deadline (the jurisdiction's `APPEAL` rule, 72 h by default) and is decided by a moderator other than the one who made the original decision. A granted appeal is reinstated after a second moderator confirms it; `reinstated` turns `true` at that point.

**Transparency report: `GET /api/transparency?period=`.** Aggregate volumes, decisions, complaint SLA compliance, appeal outcomes and moderator overturn rate for `YYYY-MM`, `YYYY-Qn`, `YYYY` or `last-Nd` (default `last-30d`). No item IDs, hashes or moderator names.

## 7. Data handling

- **No media is retained.** The service keeps the perceptual hash, pixel hash, scores, decision, provenance ID, a coarse numeric forensic signature (too low-resolution to rebuild the image), the per-frame score timeline for videos, the C2PA manifest summary, and which API client submitted it. Image and video bytes are discarded after scoring (video frames are decoded in memory and never written to disk), and `labelledImage` is returned to you, never stored.
- The public audit trail (`GET /api/verify/{provenanceId}`) shows decisions and timestamps. It never shows your client name, complaint references, or moderator identities.
- Sightings keep a hash or provenance ID, the platform name, your opaque post ID, a keyed account pseudonym and two timestamps. They are never added to the public audit trail. The pseudonym key (`INVESTIGATION_PSEUDONYM_KEY`) stays on the server, so neither you nor anyone reading the database can confirm a guessed account ID.
- The detection models were trained and evaluated only on consenting-actor research benchmarks (FaceForensics++, DFDC, Celeb-DF). Don't send test data that you don't have the right to process.

## 8. Operator reference (key management)

These require a moderator login (HTTP Basic or session) and are what the **API access** page uses.

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/admin/clients` | — | `{ plans: {FREE,PRO,ENTERPRISE → limit}, clients: [...] }` (never includes keys or hashes) |
| `POST` | `/api/admin/clients` | `{"name": "Acme Social", "plan": "PRO"}` | `201` with the client and `apiKey`, **shown only this once**. `409` if the name exists |
| `POST` | `/api/admin/clients/{id}/rotate` | — | Client with a new `apiKey`. The old key stops working immediately |
| `POST` | `/api/admin/clients/{id}/revoke` | — | Client with `active: false`. Its key gets `401` from then on |
| `POST` | `/api/admin/clients/{id}/plan` | `{"plan": "ENTERPRISE"}` | Client with the new plan. Takes effect on the next request |

Configuration (environment variables): `API_RATE_LIMIT_FREE`, `API_RATE_LIMIT_PRO`, `API_RATE_LIMIT_ENTERPRISE` (requests per minute). Issue partner keys from the API access page (or `POST /api/admin/clients`); leave bootstrap keys empty outside tests.

> With `STATE_STORE=redis` (the docker-compose default) rate-limit buckets live in Redis, so every backend replica spends one shared per-minute budget per key and buckets survive a backend restart. `STATE_STORE=memory` keeps them per instance and is only suitable for a single replica.

### Investigations (moderators)

A moderator login is required, and API keys get `401`. Case numbers look like `INV-7K3Q-9XPA` and are accepted in any case, with or without dashes. Every mutating call returns the full case view.

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/investigations?status=open\|closed\|all` or `?analysisId=` | — | Case summaries: `caseNumber`, `title`, `status`, `items`, `sightings`, `platforms`, `accounts`, `preservations`, `activeLegalHolds`, `lastActivityAt` |
| `POST` | `/api/investigations` | `{"analysisId": 42, "title": "…"}` | `201` with the case. Sightings already reported for that media are linked immediately |
| `GET` | `/api/investigations/{caseNumber}` | — | `evidence` (scores, family, signed report, complaint and appeal state per analysis), `sightings` (platform, post, account pseudonym, how it matched), `appearances` (other uploads to this platform), `platforms`, `accounts` (with `otherCases` the same pseudonym appears in), `timeline`, `counts` |
| `POST` | `/api/investigations/{caseNumber}/items` | `{"analysisId": 43}` | Adds evidence and links its existing sightings |
| `POST` | `/api/investigations/{caseNumber}/sightings` | `{"platform","postRef","accountId?","observedAt?","analysisId?"}` | A sighting a moderator was told about, using the hash of a case item (the first by default) |
| `POST` | `/api/investigations/{caseNumber}/notes` | `{"text": "…"}` | Adds a note to the case log |
| `POST` | `/api/investigations/{caseNumber}/close` | `{"reason": "…"}` | Closed cases are read-only and link no new sightings |
| `POST` | `/api/investigations/{caseNumber}/reopen` | — | Reopens the case and links the sightings reported while it was closed |

Errors: `400` `analysis_required`, `note_required`, `invalid_status`, `invalid_sighting`, `not_in_case`; `404` `case_not_found`, `analysis_not_found`; `409` `case_closed`, `already_open`, `already_in_case`.

### Custody, preservation and legal holds (moderators)

Every case event is also a custody event, sealed into a hash chain: `hash = SHA-256(prevHash + "\n" + canonical event)`, starting from 64 zeros. The canonical event is `fmd-custody-event/v1` followed by `case`, `seq`, `occurred_at`, `actor`, `type`, `analysis_id`, `sighting_id` and `detail`, one `key=value` per line (`\`, newline and carriage return escaped). The case view carries a `custody` block with the chain `length` and `head`, `preservations` and `legalHolds`. These actions work on closed cases too.

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/investigations/{caseNumber}/custody` | — | `verification` (`VALID` or `BROKEN`, with `brokenAt`, the position of the first bad event, and a sentence saying what happened: edited, deleted, trimmed, inserted, or rewritten after a preservation) and every event with `seq`, `prevHash` and `hash` |
| `POST` | `/api/investigations/{caseNumber}/preservations` | `{"reason": "…"}` | `201` with `preservationId` (`PRS-XXXX-XXXX`), the `manifest` (`fmd-preservation-manifest/v1`), `manifestSha256`, Ed25519 `signature`, `signingKeyId`, `publicKeyPem`, and `timestampStatus` (`STAMPED`, `UNAVAILABLE` or `DISABLED`). The manifest covers each analysis (content SHA-256, pHash, scores, decision, signed report ID and SHA-256), each linked sighting (platform, post, account pseudonym, match) and the custody head. `409 custody_broken` if the log already fails verification |
| `GET` | `/api/investigations/{caseNumber}/preservations` \| `/{preservationId}` | — | Summaries / one preservation with its manifest, signature and public key |
| `GET` | `/api/investigations/{caseNumber}/preservations/{preservationId}/manifest` | — | The manifest exactly as signed (`text/plain`), with `X-Manifest-SHA256`, `X-Signature` and `X-Signing-Key-Id` headers |
| `GET` | `/api/investigations/{caseNumber}/preservations/{preservationId}/verify` | — | `VALID`, or `TAMPERED` with `changedFields` (for example `analysis.1.forgery_score`, `sighting.2.post_ref`, `custody_head`, `signature`). The manifest is rebuilt from the records it names, so evidence added later doesn't affect it. Also checks the RFC 3161 timestamp when there is one |
| `POST` | `/api/investigations/{caseNumber}/legal-holds` | `{"platform": "pixelgram", "postRefs": ["pg-1"]?, "note": "…"?}` | `201` with the case. Without `postRefs` the hold covers every linked sighting on that platform. `400 no_sightings`, `not_in_case`, `invalid_hold`; `409 already_held` |
| `POST` | `/api/investigations/{caseNumber}/legal-holds/{holdNumber}/acknowledge` | `{"partnerReference": "…"?}` | The platform confirmed the hold. `409 hold_not_pending` |
| `POST` | `/api/investigations/{caseNumber}/legal-holds/{holdNumber}/release` | `{"reason": "…"?}` | The hold is no longer needed. `409 hold_released` |

A legal hold is recorded here and sent to the platform through its own legal channel. The platform keeps the media; this system never fetches or stores it ([ADR 0006](docs/adr/0006-preserve-commitments-not-media.md)).

### Legal requests (moderators, Phase 28)

An investigator (role `INVESTIGATOR`) reads a case only under a legal request that names the case and the investigator, has been approved, and has not expired. Any moderator records the request; only a **senior moderator who did not file it** can approve or reject it. Request numbers look like `LRQ-XXXX-XXXX`. Filing, approval, rejection and revocation are custody events in every case the request names.

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/legal-requests` | — | Every request, newest first: `requestNumber`, `status` (`PENDING`, `APPROVED`, `REJECTED`, `REVOKED`, or `EXPIRED` once `expiresAt` passes), `authority`, `orderReference`, `investigator`, `cases`, `expiresAt`, who filed, decided or revoked it and why, and `canDecide` / `canRevoke` for the caller |
| `GET` | `/api/legal-requests/investigators` | — | Investigator accounts (`username`, `displayName`, `agency`) a request can name |
| `POST` | `/api/legal-requests` | `{"authority","orderReference","investigator","caseNumbers":[…],"expiresAt":"2026-12-31T23:59:59Z","note?"}` | `201` with the request (`PENDING`). `400 invalid_request` (missing fields, 1 to 25 cases), `unknown_investigator`, `invalid_expiry` (more than an hour and at most `HANDOVER_MAX_REQUEST_TERM` away); `404 case_not_found` |
| `POST` | `/api/legal-requests/{requestNumber}/approve` | `{"note?"}` | `APPROVED`. `403 senior_required`, `403 conflict_of_interest` (you filed it); `409 request_not_pending`, `request_expired` |
| `POST` | `/api/legal-requests/{requestNumber}/reject` | `{"note"}` | `REJECTED`; the note is required (`400 reason_required`). Same `403`/`409` rules |
| `POST` | `/api/legal-requests/{requestNumber}/revoke` | `{"note"}` | `REVOKED`: access ends on the investigator's next request. Seniors only; reason required; `409 request_not_active` if it is not currently granting access |

The case view's `custody` block also lists `legalRequests` naming the case and the evidence `packages` issued from it.

### Investigator API (Phase 28)

Only `INVESTIGATOR` accounts; moderators get `403`, and investigators get `403` on every moderator endpoint. Every call re-checks the request: without an approved, unexpired request covering the case the answer is `403 no_legal_authority`, with the same message whether the case exists or not.

| Method | Path | Returns |
|---|---|---|
| `GET` | `/api/investigator/cases` | The cases the caller may read now, each with the requests that cover it. Not logged |
| `GET` | `/api/investigator/cases/{caseNumber}` | The case, read-only, plus `authorisation` (the request relied on) and `custodyVerification`. **Logged** as `INVESTIGATOR_VIEWED`. Compared with the moderator view: no `appearances` (other uploads to this platform), accounts lose `totalSightings`, `otherCases` lists only cases the caller's own active requests cover, and `custody.legalRequests` / `packages` only the caller's own |
| `POST` | `/api/investigator/cases/{caseNumber}/packages` | `201`: issues a signed evidence package (below) from the case's latest preservation. **Logged** as `PACKAGE_ISSUED`. `409 not_preserved`, `custody_broken`, `evidence_tampered` |
| `GET` | `/api/investigator/packages` | The caller's packages: `packageId` (`PKG-XXXX-XXXX`), `caseNumber`, `requestNumber`, `issuedAt`, `downloadExpiresAt`, `downloadable`, `sha256` (the ZIP), `manifestSha256`, `signingKeyId`, `downloads` |
| `GET` | `/api/investigator/packages/{packageId}/download` | The ZIP (`application/zip`, `Cache-Control: no-store`) with `X-Package-SHA256`, `X-Manifest-SHA256` and `X-Signing-Key-Id`. **Logged** as `PACKAGE_DOWNLOADED` with the ZIP's SHA-256. `404 package_not_found` (not yours), `410 package_expired` after `HANDOVER_DOWNLOAD_WINDOW` (or the request's expiry, if sooner), `403` once the request lapses |

**What is in a package** (`fmd-evidence-package/v1`, no media):

| File | Contents |
|---|---|
| `PACKAGE-MANIFEST.txt` | `key=value` lines: package, case, request, authority, order reference, investigator, approver, expiries, preservation ID and manifest SHA-256, custody length and head, signing key; then `file_count` and `file.N.path` / `.sha256` / `.size` for every other file |
| `PACKAGE-SIGNATURE.txt` | `fmd-evidence-package-signature/v1`: `algorithm=Ed25519`, `signing_key_id`, `manifest_sha256`, `signature` (over SHA-256 of the manifest bytes), `timestamp_token` (RFC 3161 over the signature), `timestamp_time`, `timestamp_authority`, and `seal` (a second signature over SHA-256 of all the lines before it) |
| `case/report.json`, `case/report.pdf` | The case as the investigator view shows it, plus `package` and `authorisation` blocks |
| `case/sightings.json` | Linked sightings (platform, post ID, account pseudonym, match) |
| `custody/log.json` | Every custody event up to the package, with `seq`, `prevHash`, `hash` |
| `preservation/PRS-….manifest.txt`, `.json` | The preserved manifest exactly as signed, and its signature, key and timestamp |
| `keys/<keyId>.pem` | The public keys used (compare with the published ones) |
| `README.txt` | What each file is and how to check it |

**Checking a package.** Offline, with Python 3.9+ and nothing else: `python3 tools/verify_evidence_package.py PKG-XXXX-XXXX.zip --keys keys.json` (save `GET /api/verify-report/keys` as `keys.json`, or pass `--public-key key.pem`). It checks the signature and seal, every file's SHA-256 and size, that nothing was added, every custody link up to the manifest's head, the preservation signature and that the log still reaches the head it signed, and that the timestamp token was issued for this signature. `--sha256` also compares the whole ZIP with the download's `X-Package-SHA256`; `--extract-timestamp DIR` writes the token for `openssl ts -verify`. Exit status `0` valid, `1` tampered, `2` usage error.

Online, `POST /api/verify` (public, multipart `file`) treats any ZIP as a package and returns `kind: "evidence_package"`, `status` (`VALID`, `TAMPERED` or `INVALID`), `message`, the ZIP's `sha256`, `packageId`, `caseNumber`, `legalRequest`, `authority`, `investigator`, `issuedAt`, `signingKeyId` and `checks` (`structure`, `signature`, `files`, `preservation`, `custody`, plus `timestamp`, the RFC 3161 token checked against the TSA's certificate, and `issued`, whether the ZIP is byte for byte the one this server issued). Nothing is stored.

**Checking a preservation without this server.** Compute the SHA-256 of the manifest bytes and compare it with `manifestSha256`. Verify the base64 `signature` over those 32 digest bytes with the Ed25519 key in `publicKeyPem`; its ID, the first 16 hex characters of the SHA-256 of the DER public key, must equal `signingKeyId` and appear in `GET /api/verify-report/keys`. To tie a file obtained later from the platform to the case, recompute `analysis.N.content_sha256`: for an image, SHA-256 of `"<width>x<height>:"` followed by its decoded RGBA pixels (as Pillow's `convert("RGBA").tobytes()` produces them); for a video, SHA-256 of the file bytes.
