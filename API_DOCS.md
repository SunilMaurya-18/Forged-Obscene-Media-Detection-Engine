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

Scores one image. It counts against your rate limit.

| Part / param | Where | Required | Description |
|---|---|---|---|
| `file` | multipart | yes | The image (JPEG, PNG or WebP, up to 25 MB) |
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
  "createdAt": "2026-09-27T05:12:31.434512Z"
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
| `labelledImage`, `labelledMimeType` | Only with `include=labelled`. Base64 copy of your image with an HMAC-signed XMP manifest. Pixels are unchanged, so it scores identically. Publish this copy if you want re-uploads traced by manifest |

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

## 3. Routing the decision

The thresholds are set by the deployment (defaults: review at 0.50, auto-flag at 0.85, for each score separately). The higher of the two decisions wins.

| `decision` | Recommended action in your pipeline |
|---|---|
| `ALLOW` | Publish. |
| `REVIEW` | Hold the post (for example, visible only to the uploader) and send it to your moderation queue with both scores. Don't show it publicly until a human decides. |
| `AUTO_FLAG` | Don't publish. Tell the uploader, keep `provenanceId` for appeals and legal requests, and treat repeat `reuploadOf` hits as a signal about the account. |

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
| 401 | `missing_api_key` | No key header | Send `X-API-Key` |
| 401 | `invalid_api_key` | Key unknown, rotated, or revoked | Check config. Don't retry |
| 429 | `rate_limited` | Bucket empty (body also has `retryAfterSeconds`) | Back off for `Retry-After` |
| 502 | `analysis_unavailable` | Image couldn't be decoded, or the scoring service is down | Check the file. Retry with backoff if it's valid |
| 413 | — | File over 25 MB | Downscale before sending |

`401` responses also include `WWW-Authenticate: ApiKey header="X-API-Key"`.

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
- **Fail closed or open deliberately.** On a `502` or timeout, the safe default for user-generated content is to hold the post (treat it as `REVIEW`) and retry later, not to publish unscreened.
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

## 7. Data handling

- **No media is retained.** The service keeps the perceptual hash, pixel hash, scores, decision, provenance ID, a coarse numeric forensic signature (too low-resolution to rebuild the image), and which API client submitted it. The image bytes are discarded after scoring, and `labelledImage` is returned to you, never stored.
- The public audit trail (`GET /api/verify/{provenanceId}`) shows decisions and timestamps. It never shows your client name, complaint references, or moderator identities.
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

Configuration (environment variables): `API_RATE_LIMIT_FREE`, `API_RATE_LIMIT_PRO`, `API_RATE_LIMIT_ENTERPRISE` (requests per minute), and `API_DEMO_KEY`, a dev-only variable that seeds a FREE "Demo Platform" client with that exact key. Leave it empty in production.

> Rate-limit buckets live in memory on each backend instance and reset on restart. That's fine for a single instance. If you run several replicas, move the buckets to a shared store such as Redis.
