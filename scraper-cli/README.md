# discount-scraper-cli

A tiny local command-line tool that scrapes **Bank Discount** on demand and either:

- **File mode (default):** writes a CSV statement you copy to your phone and import, or
- **Serve mode (`serve`):** scrapes once, then serves that CSV over plain HTTP on your
  local network so the Android app can pull it with one tap — no hand-copying a file, or
- **Upload mode (`upload`):** scrapes once, uploads the CSV to **your own cloud storage**
  (Dropbox by default), and prints a **stable download URL**. Paste that URL into the app
  once and it can fetch your statement from **any network** — not just the same Wi-Fi.

## Requirements

- **Node.js >= 22** (`node --version`).
- On first `npm install`, Puppeteer downloads a private copy of Chromium (~100 MB).
  This is what actually drives the bank login.

## Setup

```bash
cd scraper-cli
npm install          # also downloads Chromium for Puppeteer
cp .env.example .env # then edit .env with your real credentials
```

Fill in `.env` (this file is gitignored — never commit it):

| Variable            | Required | Meaning                                                        |
| ------------------- | -------- | -------------------------------------------------------------- |
| `DISCOUNT_ID`       | yes      | Your Discount online-banking user id.                          |
| `DISCOUNT_PASSWORD` | yes      | Your Discount password.                                        |
| `DISCOUNT_NUM`      | yes      | Your Discount identifying number.                              |
| `START_DATE`        | no       | ISO date `YYYY-MM-DD`; how far back to fetch. Default ~12 months ago. |
| `OUT`               | no       | Output CSV path (file mode). Default `./discount-statement.csv`. |
| `SERVE_HOST`        | no       | Interface to bind in serve mode. Default `0.0.0.0` (all interfaces). |
| `SERVE_PORT`        | no       | TCP port in serve mode. Default `8788`.                        |
| `DROPBOX_TOKEN`     | upload   | Dropbox access token (upload mode). See "Upload mode" below.    |
| `DROPBOX_PATH`      | no       | Fixed Dropbox path to overwrite (upload mode). Default `/discount-statement.csv`. |

Credentials are held in memory for the single run only. They are **never** printed,
logged, or written to disk.

## Run

```bash
npm run build   # compile TypeScript -> dist/ (strict)
npm start       # run the compiled CLI
```

Or, without a build step, for a quick run during development:

```bash
npm run dev
```

On success it prints a short summary (accounts, transaction count, output path) and
writes the CSV to `OUT` (default `./discount-statement.csv`). On failure it prints a
clear message and exits non-zero.

## Serve mode (fetch from the phone over the LAN)

Instead of copying a file, run the scraper as a tiny HTTP server on your machine and
let the app pull the statement over your home Wi-Fi:

```bash
npm run build   # once
npm run serve   # scrape, then serve on the LAN until Ctrl-C
```

Or without a build step during development: `npm run dev:serve`.

It scrapes **once at startup**, then serves that snapshot. On startup it prints the
reachable URL(s), derived from the machine's LAN IPv4 address(es)
(`os.networkInterfaces()`), e.g.:

```
Scraped 1 account(s), 42 transaction(s) — snapshot captured 2026-07-11T18:57:02.328Z.
Serving on 0.0.0.0:8788. This snapshot is served until you stop with Ctrl-C; restart to re-scrape.
Serving statement at: http://192.168.1.23:8788/statement.csv
```

Type that `http://<your-lan-ip>:8788/statement.csv` URL into the app to fetch. The
snapshot is served until you stop the process with **Ctrl-C**; the data does not
refresh on its own — **restart the process to re-scrape** the bank. (We deliberately
do NOT re-scrape per request, to avoid hammering the bank.)

### Routes

| Method + path         | Response                                              |
| --------------------- | ---------------------------------------------------- |
| `GET /statement.csv`  | The freshly-scraped CSV (`Content-Type: text/csv`).  |
| `GET /health`         | `{"status":"ok"}` (`application/json`).              |
| anything else         | `404`.                                               |

Host/port are configurable via `SERVE_HOST` / `SERVE_PORT` (defaults `0.0.0.0:8788`).

### Security note

Serve mode is **LAN-only, unauthenticated, and plaintext (HTTP)** — this is fine for a
trusted home network. The server exposes **only** the transaction CSV; your bank
credentials are used solely for the scrape (exactly as in file mode) and are **never
served, printed, or logged**, and never leave the machine. Request bodies and headers
are not logged. Binding to `0.0.0.0` is acceptable here because it serves only your own
transaction data on your own network; set `SERVE_HOST=127.0.0.1` if you want to restrict
it further. Do not expose this port to the public internet or forward it through your
router.

## Upload mode (fetch from the phone over ANY network)

Serve mode only works when the phone is on the same Wi-Fi as the PC. **Upload mode**
lifts that limit: it uploads the scraped CSV to **your own cloud storage** and prints a
**stable download URL**. Paste that URL into the app once, and the app can pull your
statement from anywhere (mobile data, a different Wi-Fi, etc.). Bank credentials stay on
the PC; **only transactions** go to the cloud.

### One-time setup (Dropbox)

The shipped default backend is Dropbox.

1. Go to <https://www.dropbox.com/developers/apps> → **Create app**.
   - API: **Scoped access**.
   - Access type: **App folder** (simplest) or **Full Dropbox**.
   - Name it anything (e.g. `discount-statement`).
2. On the app's **Permissions** tab, enable these scopes and **Submit**:
   - `files.content.write`
   - `sharing.write`
3. On the **Settings** tab, under **OAuth 2 → Generated access token**, click
   **Generate** and copy the token.
4. Put it in `.env`:

   ```bash
   DROPBOX_TOKEN=sl.xxxxxxxx…        # the generated token
   DROPBOX_PATH=/discount-statement.csv   # optional; this is the default
   ```

   > Note: a **generated** access token is short-lived (it expires after a few hours).
   > That is fine for a manual run. For an unattended/scheduled refresh, generate a
   > long-lived token via the OAuth refresh-token flow and use that as `DROPBOX_TOKEN`.
   > The token is held in memory for the run only and is **never** logged or uploaded.

### Run

```bash
npm run build   # once
npm run upload  # scrape, upload the CSV, print the stable URL
```

Or without a build step during development: `npm run dev:upload`.

On success it prints something like:

```
Scraped 1 account(s), 42 transaction(s).
Uploaded the CSV statement to your cloud storage (transactions only — no credentials).
Paste this URL into the app ONCE; it stays stable, and each upload refreshes what it serves:
  https://www.dropbox.com/scl/fi/abc123/discount-statement.csv?rlkey=…&dl=1
```

**Paste that URL into the app once** (as the statement URL). The app remembers it and
auto-fetches on open. Because the tool always overwrites the **same** Dropbox path and
reuses the **same** shared link, the URL never changes — re-running `npm run upload`
refreshes the data behind the same URL. Note the `&dl=1` suffix: that turns the Dropbox
share link into a **direct download** the app can GET.

> The PC must run `npm run upload` to refresh the statement — this is a scheduled/manual
> push, not a live connection. Run it whenever you want fresh data (or wire it to a
> scheduled task/cron on the PC).

### Adding other cloud backends

Upload mode is written against an `Uploader` seam (`src/upload.ts`): a provider takes
file bytes + a content type and returns a stable direct-download URL. Dropbox
(`src/dropbox.ts`) is the one shipped implementation. Amazon S3, Cloudflare R2, or
Google Drive can be added as new `Uploader` classes **without touching** the scrape or
CSV code — only the default factory in `runUpload` picks the concrete backend.

### Security note

Upload mode stores **only your transaction CSV** in **your own** cloud account. Bank
credentials are used solely for the scrape (as in file/serve mode) and are **never**
uploaded, printed, or logged. The Dropbox token is a secret (keep it in `.env`, which is
gitignored). The shared link grants read access to that one file to anyone who has the
URL — treat the URL as private (it contains a random `rlkey`), and delete the file/token
from Dropbox to revoke access.

## Import into the app

Copy the generated CSV to your phone and import it in the app (file mode), point the app
at the serve-mode LAN URL, or paste the upload-mode cloud URL. Either way the payload
matches the exact format the app's `CsvBankScraper` parses, so no conversion is needed.

**Categories import as "Other."** The CSV intentionally has no Category column: the
library returns Hebrew category names that don't match the app's category enum, so
the app defaults every transaction to `OTHER`. You can recategorize in the app.

## CSV format

```
Date,Description,Debit,Credit,Balance,Reference
15/01/2026,"SUPER PHARM, TEL AVIV",50.25,,,987654
01/02/2026,MASKORET HEVRAT HI-TECH,,14000.00,15234.56,
```

- **Date** — `dd/MM/yyyy`.
- **Description** — raw transaction description (RFC-4180 quoted when it contains a comma).
- **Debit / Credit** — unsigned magnitude in exactly one column, chosen by the sign of
  the library's `chargedAmount` (negative -> Debit, positive -> Credit).
- **Balance** — the account's running balance if the library reports one (attached to the
  last row), otherwise blank. The app ignores this column.
- **Reference** — the library's transaction `identifier`; drives the app's stable dedupe id.

## Test

```bash
npm test        # vitest — no bank, no Chrome
```

Covers the library-tx -> CSV mapping, the serve-mode logic (request routing, LAN-IPv4
selection, URL building, plus a loopback smoke test of the HTTP server), and the
upload-mode logic (the uploader is handed the CSV bytes + right content type, the stable
`dl=1` URL is printed, Dropbox path/arg/URL/error-mapping helpers, and scrape/upload/
config failure paths — all with a fake uploader/scraper). Everything sits behind
injectable seams, so the tests need no credentials and never launch a browser, contact
the bank, or hit the real Dropbox API.

**Runtime-only (not unit-tested here):** the real scrape (drives Chrome via
`israeli-bank-scrapers`) and the real `DropboxUploader.upload()` (calls the Dropbox HTTP
API). Exercise those with `npm run upload` against a real `.env`.
