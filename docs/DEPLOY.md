# Deploying to production

One Linux server runs the whole stack with Docker Compose. Caddy terminates HTTPS (certificates from
Let's Encrypt, renewed automatically) and is the only thing reachable from the internet. PostgreSQL,
Redis, the ML service and the backend stay on the internal Docker network.

```
internet ──443──> caddy ──> frontend (nginx) ──/api/──> backend ──> ml-service
                                                          ├──> postgres
                                                          └──> redis
```

This is the production setup: your own accounts and secrets, no sample seed data, and no published
default logins. The stack refuses to start until every required secret is set.

## Requirements

- A Linux VM (Ubuntu 22.04+ or Debian 12) with Docker Engine 24+ and the Compose plugin.
  4 vCPU and 8 GB RAM is comfortable (the ML service loads PyTorch); 2 vCPU and 4 GB works for light
  traffic. About 15 GB of disk.
- A domain or subdomain you control, for example `fmd.example.com`.
- Inbound TCP 80 and 443 (and UDP 443 for HTTP/3) open. Close everything else except SSH.

Install Docker on a fresh Ubuntu server:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # log out and back in
```

## 1. DNS

Create an `A` record (and `AAAA` if the server has IPv6) for your domain pointing at the server's
public IP. Wait until `dig +short fmd.example.com` returns it: Caddy needs this to get a certificate.

## 2. Configure

```bash
git clone https://github.com/SunilMaurya-18/Forged-Obscene-Media-Detection-Engine.git fmd && cd fmd
cp .env.production.example .env
```

Fill in every value marked REQUIRED in `.env`. Generate each secret separately:

```bash
openssl rand -hex 32
```

| Setting | What to put |
|---------|-------------|
| `DOMAIN` | Your host name, without `https://` |
| `POSTGRES_PASSWORD`, `REDIS_PASSWORD` | Random secrets |
| `PROVENANCE_SIGNING_KEY`, `INVESTIGATION_PSEUDONYM_KEY` | Random secrets of 32+ characters. Keep them: changing either later breaks verification of labelled files or unlinks pseudonyms |
| `MODERATOR_USERNAME`, `MODERATOR_PASSWORD` | The owner console account; password 12+ characters |
| `MODERATOR_ROLE` | Set to `ADMIN` so you can open `/admin`, manage staff, and solo-operate four-eyes checks when needed |
| `MODERATOR_EXTRA_ACCOUNTS` | Optional extra people, `username:password:ROLE:team`. You can also create them later from `/admin` |
| `VITE_LEGAL_OPERATOR`, `VITE_LEGAL_CONTACT_EMAIL` | Your organisation and privacy contact, printed in `/terms` and `/privacy` |

Keep `.env` private (`chmod 600 .env`). It is ignored by git.

The stack refuses to start with a weak or published default: Compose names any missing variable, and the
backend and ML service list every problem (weak password, short key) and exit.

## 3. Start

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait
```

The first build takes several minutes (PyTorch and the Java build). The rest of this guide uses a
shorter alias; add it to the shell profile on the server:

```bash
alias fmd='docker compose -f docker-compose.yml -f docker-compose.prod.yml'
fmd logs -f backend caddy   # watch startup and the certificate request
```

## 4. Check

```bash
curl -fsS https://fmd.example.com/api/stats     # JSON: frontend, backend and database are up
fmd ps                                           # every service "healthy"
```

Then open `https://fmd.example.com/login` and sign in with `MODERATOR_USERNAME`. With
`MODERATOR_ROLE=ADMIN` you land on `/admin` to create moderators and investigators. The queue
starts empty. Public visitors can still report and verify without an account — they cannot approve uploads.

## Operating it

- **Update:** `git pull && fmd up -d --build --wait`. Data lives in Docker volumes and survives rebuilds.
- **Back up** the `postgres_data` volume (cases, audit trail, custody logs) and the `signing_keys`
  volume (the report-signing key; losing it means new reports get a new key). For example, nightly:
  ```bash
  fmd exec -T postgres pg_dump -U forged forged_media | gzip > backup-$(date +%F).sql.gz
  ```
- **Accounts:** change `MODERATOR_EXTRA_ACCOUNTS` and run `fmd up -d backend`.
- **Logs:** `fmd logs --tail 200 backend`. Logs are JSON with request IDs; uploads are never logged or stored.
- **Monitoring:** add `-f docker-compose.observability.yml` for Prometheus and Grafana. Keep Grafana
  off the public internet (reach it through an SSH tunnel).
- **Partner API keys:** issue them from the API access page in the console. They are shown once.

## Before launch

- Have the Terms and Privacy Policy reviewed by a lawyer for the countries you serve.
- The bundled forgery model is a research checkpoint trained on small datasets. Read
  [MODEL_CARD.md](MODEL_CARD.md) and retrain on data you are licensed to use before relying on its scores.
- `NSFW_BACKEND=heuristic` is a fast approximation. For real explicit-content detection, install
  `transformers` in the ML service image and set `NSFW_BACKEND=transformers`.
- Report signing keys sit on the server's disk (`REPORT_SIGNING_PROVIDER=file`). For stronger key
  custody, switch to HashiCorp Vault Transit (`REPORT_SIGNING_PROVIDER=vault`).
