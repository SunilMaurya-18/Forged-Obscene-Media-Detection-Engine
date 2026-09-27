# Mock Social Feed (Phase 10)

Tiny static app that mimics a social platform posting pipeline.

## Run with Compose

```bash
docker compose up -d mock-social-feed
```

Open **http://localhost:3001**

## Behaviour

1. Seed posts render locally (no API call) and are marked "Not screened".
2. **Post** (with a photo) / **Post manipulated sample** call `POST /api/analyze` via the nginx proxy.
3. The decision is applied to the post inline: `ALLOW` publishes normally, `REVIEW` blurs the image
   with a "Held for review" notice, and `AUTO_FLAG` blurs it with a "Removed automatically" notice.
4. If decision is `REVIEW`, the item appears in the main moderator queue:
   http://localhost:3000/queue (login: `moderator` / `moderator`).

## Local static server (optional)

```bash
# From repo root, with backend on :8081
npx --yes serve mock-social-feed -p 3001
# Point API manually or use a reverse proxy; Compose is preferred.
```

Ensure `DECISION_FORGERY_REVIEW` is low enough for the demo model scores if you need REVIEW
on synthetic samples (e.g. `0.05` in `.env`).
