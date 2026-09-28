# Hosting the public demo

A hosted instance that strangers can use safely: synthetic samples only, shared moderator logins
printed on the tour, a reset button, and an automatic reset every few hours. First-time visitors land
on the home page, click **See it live in one click**, and see a fully populated story: a flagged upload
with an appeal, a signed report, a complaint about to miss its deadline and one that already has.

> **Run it on its own machine, with its own database and keys.** The demo prints moderator passwords
> on a public page and lets anyone wipe the data. Never point it at a database, Redis, Vault or
> signing keys used by a real deployment.

## What public mode changes

| | Local stack (`docker compose up`) | Hosted demo (`docker-compose.demo.yml`) |
|---|---|---|
| Tour at `/tour` | Yes | Yes |
| Moderator logins on the tour | Usernames only | Usernames **and** passwords, one-click sign-in |
| Uploads | Anything | Only the bundled synthetic samples and the labelled copies issued for them (`403 demo_samples_only` otherwise) |
| Upload rate | Unlimited | `DEMO_UPLOADS_PER_MINUTE` shared by all visitors (`429 demo_rate_limited`) |
| Reset | Senior moderators only | Anyone, at most once per `DEMO_RESET_COOLDOWN`; automatic every `DEMO_RESET_INTERVAL` |
| Banner | None | "Public demo: synthetic samples only, and all data is reset regularly." with the next reset time |
| Partner API demo key | `API_DEMO_KEY` if set | None (visitors may create keys; deleted on reset) |
| Published ports | 3000, 3001, 8081 | Only 80 and 443, through Caddy with automatic HTTPS |

The demo only runs under the `dev` Spring profile: `DemoSecretsGuard` refuses `DEMO_ENABLED=true`
under any other profile, which keeps it out of production deployments by construction.

## Requirements

- A Linux VM with Docker Engine 24+ and the Compose plugin. 4 vCPU and 8 GB RAM is comfortable
  (the ml-service loads PyTorch); 2 vCPU and 4 GB works for light traffic. About 15 GB of disk for images.
- A domain name you control, for example `demo.example.org`.
- Inbound TCP 80 and 443 (and UDP 443 for HTTP/3) open to the internet. Nothing else needs to be open.

## 1. DNS

Create two records pointing at the VM's public IP:

| Name | Type | Value |
|------|------|-------|
| `demo.example.org` | A (and AAAA if you have IPv6) | VM IP |
| `feed.demo.example.org` | A (and AAAA) | VM IP |

The console lives on the first host and the mock social feed on the second. Each links to the other
automatically: with no port in the URL, the console links to `feed.<host>` and the feed links back
to the host without `feed.`.

## 2. Configure

```bash
git clone <this repo> fmd-demo && cd fmd-demo
cp .env.example .env
```

In `.env`, set at least:

```dotenv
DEMO_DOMAIN=demo.example.org

# Optional (defaults shown)
DEMO_RESET_INTERVAL=PT6H
DEMO_RESET_COOLDOWN=PT2M
DEMO_UPLOADS_PER_MINUTE=60
```

Leave the moderator passwords at their dev defaults or change them. Either way they are shown on the
tour, which is the point. Do **not** reuse any password, key or token from a real deployment.

## 3. Start

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait
```

Caddy requests certificates from Let's Encrypt for both hosts on first start. This needs DNS to
resolve and port 80 to be reachable, so it can take a minute. Watch it with:

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml logs -f caddy
```

## 4. Check

```bash
curl -fsS https://demo.example.org/api/demo | jq '{enabled, publicMode, nextResetAt}'
# {"enabled": true, "publicMode": true, "nextResetAt": "..."}

curl -fsS -X POST https://demo.example.org/api/demo/story | jq '.story.report.reportId'
curl -fsS https://demo.example.org/api/verify-report/<that report id> | jq .status
# "VALID"

# Real uploads are refused
curl -s -F file=@some-photo.jpg https://demo.example.org/api/analyze | jq .error
# "demo_samples_only"
```

Then open `https://demo.example.org/` in a private window, click **See it live in one click**, and
use the **Sign in as ...** buttons on the tour to check the queue and compliance views.

## Operating it

- **Reset now:** `curl -X POST https://demo.example.org/api/demo/reset` (the visitor cooldown
  applies), or sign in as the senior moderator and use the button on the tour (no cooldown).
- **Update:** `git pull`, then run the same `up -d --build --wait` command. The database survives
  restarts; the next scheduled reset tidies up whatever visitors left.
- **Logs:** the backend logs one access line per request in ECS JSON (`BACKEND_LOG_FORMAT=ecs`).
  Uploads are never logged or stored. Add the observability overlay
  (`-f docker-compose.observability.yml`) if you want Grafana, and keep it off the public internet.
- **Abuse:** if someone floods uploads, lower `DEMO_UPLOADS_PER_MINUTE`. If offensive text keeps
  appearing in appeals or complaints, shorten `DEMO_RESET_INTERVAL`. Add per-IP limits at your cloud
  firewall or CDN if needed.
- **Search engines:** Caddy sends `X-Robots-Tag: noindex` on every response.

## What it does not protect against

See [T6 in the threat model](THREAT_MODEL.md#t6-public-demo-abuse). In short, visitors can type
offensive free text that stays visible until the next reset, the upload budget is shared (one abuser
can slow everyone for a minute), and the shared logins give no accountability, by design.

## Configuration reference

| Variable | Default | Meaning |
|----------|---------|---------|
| `DEMO_DOMAIN` | (required) | Public host name; `feed.` + this serves the mock feed |
| `DEMO_ENABLED` | `true` in compose, `false` in the jar | Tour, story endpoints and bundled samples |
| `DEMO_PUBLIC` | `true` in the demo overlay | Public mode (table above) |
| `DEMO_RESET_INTERVAL` | `PT6H` | Automatic reset period; `PT0S` turns it off |
| `DEMO_RESET_COOLDOWN` | `PT2M` | Minimum gap between visitor-triggered resets |
| `DEMO_UPLOADS_PER_MINUTE` | `60` | Instance-wide upload budget in public mode; `0` means unlimited |
| `DEMO_JURISDICTION` | `IN` | Jurisdiction whose rules the story's deadlines follow |
