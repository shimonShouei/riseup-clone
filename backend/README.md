# RiseUp clone — backend (M2-4)

A small, **stateless "scrape function"** the phone calls over a private mesh. It
scrapes **Bank Discount** via [`israeli-bank-scrapers`](https://github.com/eshaham/israeli-bank-scrapers)
(headless Chrome/Puppeteer) and returns normalized-but-raw JSON. It **persists
nothing**: credentials live in RAM for one scrape and the browser is torn down
after every call (SECURITY.md R5/R6).

Read [`SECURITY.md`](./SECURITY.md) first — it is the design of record and the
`R1..R16` requirements checklist this service is built against.

## What's here

- **Framework:** [Fastify](https://fastify.dev/) 5 + TypeScript (strict).
- **Endpoints:**
  - `GET /health` — unauthenticated liveness, returns `{ "status": "ok" }`. No
    version/build/state info.
  - `POST /scrape` — real Discount scrape. Requires a valid bearer token (R1),
    validates the request body against an allow-list (R4), drives a headless
    Chrome login via `israeli-bank-scrapers`, and returns the mapped result (see
    **Scrape API** below). Credentials stay in RAM only; the browser is always
    torn down (R5/R6); failures map to coarse, non-leaky error codes.
- **Auth:** bearer token from `AUTH_TOKEN`, compared in **constant time** (both
  sides SHA-256-hashed so no length/content timing leak). Rejects missing/invalid
  tokens with `401` *before* body parsing (R1). Token is never hardcoded or
  logged.
- **Bind address:** defaults to `127.0.0.1` (R2), overridable via `BIND_HOST` for
  the mesh interface. Warns on a wildcard bind.
- **TLS:** serves HTTPS when `TLS_CERT_PATH` + `TLS_KEY_PATH` are set (R3); key is
  read from a runtime file, never baked in (R7).
- **Logging:** pino (Fastify's built-in). Request auto-logging is disabled; only
  an allow-list of fields (route, method, status, duration) is logged on
  response. Bodies/headers/credentials are never logged, plus a `redact` safety
  net (R8/R9).
- **Container:** multi-stage `Dockerfile` on `node:22-slim`, runs as the non-root
  `node` user (R10); `docker-compose.yml` drops caps, uses a read-only FS, and
  publishes to loopback only (R2/R12).

## Configuration (all via env)

Copy the example and fill it in. **Never commit `.env`.**

```bash
cp .env.example .env
# edit .env
```

| Var             | Default       | Notes                                                        |
| --------------- | ------------- | ------------------------------------------------------------ |
| `AUTH_TOKEN`    | *(required)*  | Bearer token, ≥ 32 chars. Service refuses to start without it. |
| `BIND_HOST`     | `127.0.0.1`   | Set to the mesh iface IP for phone access. Never `0.0.0.0` on a public host. |
| `PORT`          | `8443`        | Listen port.                                                 |
| `TLS_CERT_PATH` | *(unset)*     | PEM cert path. Set with the key to enable HTTPS.             |
| `TLS_KEY_PATH`  | *(unset)*     | PEM key path (`0600`, non-root-owned).                       |
| `LOG_LEVEL`     | `info`        | Verbosity only — never enables payload logging.              |

Generate a token:

```bash
openssl rand -hex 32
```

## Generate a self-signed cert (local / mesh)

Certificate *pinning* (not CA trust) is the app's anchor (SECURITY.md §3.3), so a
self-signed leaf is fine. Generate a stable key/cert pair:

```bash
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout certs/key.pem -out certs/cert.pem \
  -days 825 -subj "/CN=riseup-backend"
chmod 600 certs/key.pem   # non-root-readable only (R7)
```

The Android app pins this cert's SPKI. Reuse the **same key** across renewals so
the pin survives, and ship a backup pin before rotating (SECURITY.md §3.3).

**Reverse-proxy alternative:** terminate TLS at a proxy (Caddy/nginx) instead of
in-process — leave `TLS_*` unset and put the proxy in front. The proxy must
present the **same pinned cert** the app expects (R3/R13).

## Run locally

Requires **Node ≥ 22** (Fastify + the eventual scraper need it).

```bash
npm ci            # or: npm install
npm run build     # tsc, strict — must pass clean
npm test          # vitest — auth/validation tests, no network/Chrome
npm run dev       # tsx watch, live reload
npm start         # run compiled dist/
npm run lint      # type-check (tsc --noEmit)
```

Smoke test (with TLS + a token in your env):

```bash
curl -k https://127.0.0.1:8443/health
# {"status":"ok"}

curl -k -X POST https://127.0.0.1:8443/scrape           # -> 401
curl -k -X POST https://127.0.0.1:8443/scrape \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"companyId":"discount","credentials":{"id":"x","password":"y","num":"z"}}'
# -> 200 {"accounts":[...],"transactions":[...]}  (or a coarse error, see below)
```

## Scrape API

`POST /scrape` request body (allow-listed, `additionalProperties:false`):

```json
{
  "companyId": "discount",
  "credentials": { "id": "<national-id>", "password": "<pw>", "num": "<user-code>" },
  "startDate": "2025-07-01"
}
```

`startDate` is optional (ISO `YYYY-MM-DD`); it defaults to ~12 months back.

Success (`200`) — deserializes into the app's `ScrapedAccount` / `ScrapedTransaction`
DTOs, so `ScrapeMapper` is reused unchanged. All values are **raw strings**; the
app does the parsing. `rawAmount` carries a **signed** magnitude and
`rawDirection` is `null` (the mapper's fallback):

```json
{
  "accounts": [
    { "externalId": "12-345-6789", "label": "12-345-6789",
      "institution": "Discount Bank", "rawType": "bankIssued" }
  ],
  "transactions": [
    { "accountExternalId": "12-345-6789", "rawDate": "2026-01-15T00:00:00.000Z",
      "rawMerchant": "SUPER PHARM  TEL AVIV", "rawAmount": "-50.25",
      "rawDirection": null, "rawCategory": "Health", "reference": "987654" }
  ]
}
```

Failures — coarse, non-leaky `{ "error": "<code>" }` (never a message, stack, or
credential; SECURITY.md T2/R8):

| Status | `error`               | Cause |
| ------ | --------------------- | ----- |
| 401    | `invalid_credentials` | Wrong id/password/num, or a forced password change |
| 403    | `account_blocked`     | Bank locked the account (often anti-automation) |
| 409    | `otp_required`        | Bank demanded OTP/2FA we can't satisfy unattended — app shows a 2FA message, must NOT retry-loop (R16) |
| 502    | `network`             | Bank/network/unknown scrape failure |
| 504    | `timeout`             | Scrape exceeded `SCRAPE_TIMEOUT_MS` |
| 500    | `internal_error`      | Unexpected (e.g. Chrome failed to launch) |

## Runtime requirement: headless Chrome

`israeli-bank-scrapers` drives **Puppeteer**, which needs a Chromium binary + its
shared libraries. The `Dockerfile` downloads Puppeteer's matching Chromium during
`npm ci` (into `/app/.cache/puppeteer`) and installs the runtime libs — this adds
**~400MB** over the slim base (final image ~600MB). Running locally, `npm install`
fetches Chromium too (set `PUPPETEER_SKIP_DOWNLOAD=true` to skip if you only build/test).

## Docker

```bash
docker build -t riseup-backend:local .

# compose (reads AUTH_TOKEN from your shell/.env; mounts ./certs read-only)
AUTH_TOKEN=$(openssl rand -hex 32) docker compose up --build
```

Runs as non-root (R10), read-only root FS + dropped caps (R12), published to
`127.0.0.1` only (R2). Secrets are injected at runtime, never in the image (R7).

## Networking note (R2)

The service must **only** be reachable over the private WireGuard/Tailscale mesh
(M2-3) — **no public/forwarded ports**. Bind to loopback or the mesh interface;
under Docker, publish to loopback/mesh only. Verify externally that the port is
closed from the internet.

## Testing note

Tests mock the scrape seam (`ScrapeFn`) — no real bank, no Chrome, no network.
The real library is isolated in `src/scraper/discount.real.ts` (the only module
importing `israeli-bank-scrapers`/`puppeteer`); everything else, including the
route and mapper, is driven through the injectable seam. A **real** end-to-end
scrape against Bank Discount is therefore **not** exercised by the test suite.
