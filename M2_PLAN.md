# M2 plan — a *real* bank connection

M1 built the data pipeline (persistence, scraper boundary, credentials, sync,
connect UI) but the only data source is a synthetic sample. This plan is about
the thing that's actually missing: pulling a user's real transactions.

## The one fact that decides the architecture

The de-facto library for Israeli banks, **`israeli-bank-scrapers`**, is
**Node.js + Puppeteer (headless Chrome)**. It automates each bank's website in a
real browser. It **cannot run inside an Android app** — no Node runtime, no
Chrome-automation, and it needs ≥ Node 22. So a real connection is not "add a
dependency to the app"; it's "stand up something outside the app that can drive a
browser, and call it." That splits the options into two families: **screen-scraping
(unofficial)** and **open banking (official API)**.

Good news: M1's `BankScraper` interface is the right seam — every option below
plugs in behind it. What changes is where the implementation *runs* and how the
app talks to it (networking + a real consent flow).

## Options

### A. Self-hosted `israeli-bank-scrapers` backend  ← recommended for a personal build
A small Node service (Docker) runs the scraper library; the Android app calls it
over HTTPS. The service becomes the real `BankScraper` implementation.
- **Covers:** Hapoalim, Leumi, Discount, Otsar Hahayal, Union, Beinleumi, Mizrahi,
  plus cards (CAL / Isracard / Amex / Max) — ~18 institutions in the ecosystem.
- **Pros:** mature, free, community-maintained; matches how consumer apps
  historically did this; you control the box, so **credentials stay in your
  infrastructure** (or even on your own machine on your LAN).
- **Cons:** you must build + host a backend (Chrome is memory-hungry); scrapers
  **break when banks change their site**; you are transmitting/holding live bank
  credentials — real security weight even for one user; against most banks' ToS.
- **Effort:** medium. Node service + a REST endpoint + app networking (Retrofit/
  Ktor) + swapping `SyncGraph`'s provider to a `RemoteBankScraper`.

### B. Companion scraper the user runs locally
Same library, but it runs on the user's own PC/Pi (CLI or tiny local server) and
the app reads from it on the LAN, or it writes CSV/JSON the app imports.
- **Pros:** credentials **never leave the user's own hardware**; simplest trust model.
- **Cons:** clunky UX (user has to run a thing); not a shippable product, fine for
  a personal tool.
- **Effort:** low–medium.

### C. Open-banking aggregator API (Finanda / Plaid / Tink / TrueLayer)
Israel's open-banking regulation is live; licensed aggregators expose official APIs
where the **user consents via the bank's own OAuth** and credentials never touch
your code.
- **Pros:** legitimate + regulated; **no credential handling**; stable official APIs
  instead of brittle scraping; the only sustainable path if this ever becomes a
  real product.
- **Cons:** commercial contract + almost certainly **ISA licensing/registration** as
  a regulated data recipient; costs money; heavy onboarding; overkill for a hobby
  clone; must verify each target bank is covered.
- **Effort:** high (mostly business/legal, not code).

### D. Real manual statement import (make what we have honest + useful)
Drop the fake generated statement; let the user pick a CSV/OFX they downloaded from
their bank. `CsvBankScraper` **already parses real bank CSVs** — this is ~an
afternoon and is genuinely useful with zero infra or credential risk.
- **Pros:** works now; no backend; no credentials; no legal exposure.
- **Cons:** manual, not live, no auto-sync.
- **Effort:** low.

## Tradeoff summary

| Option | Live/auto | Handles creds? | Infra needed | Legal | Effort | Fit |
|---|---|---|---|---|---|---|
| A. Self-hosted scraper | ✅ | Yes (your server) | Node backend | ToS gray | Medium | Personal build |
| B. Companion scraper | ~ | Yes (user's box) | User-run | ToS gray | Low–med | Tinkerer |
| C. Aggregator API | ✅ | **No** (bank OAuth) | Contract + app | ✅ licensed | High | Real product |
| D. Manual CSV import | ❌ | **No** | None | ✅ clean | Low | Ship today |

## Recommendation — phased

1. **M2a (now, small): real CSV import (D).** Replace the synthetic statement with a
   file picker over the existing CSV parser, and relabel the flow honestly. Turns
   today's stub into something that imports *your actual* transactions immediately.
2. **M2b (the real "connection"): self-hosted scraper backend (A).** Build the Node
   service and a `RemoteBankScraper` behind the existing interface, with credentials
   flowing to *your* server only. This is the genuine auto-sync experience for
   personal use.
3. **Later / if it ever goes real: aggregator (C).** The only credential-safe,
   legal, scalable path — but it's a business decision (licensing + cost), not just
   engineering.

Deliberately **not** recommending shipping option A/B as a public product: holding
other people's live bank credentials is a serious liability that only the regulated
aggregator path (C) removes.

## Decided (2026-07): personal · self-hosted scraper (A) · Bank Discount · security-first

**Discount credentials** (israeli-bank-scrapers `companyId: discount`): `id`,
`password`, `num`. No documented 2FA and no long-lived session token — so
unattended headless scraping is viable *today*, but a future OTP requirement would
break it (tracked as a risk).

### Security model — smallest blast radius
The guiding rule: **minimize where live bank credentials live, and never expose the
backend.**
- **Credentials live only on the phone**, in the Android Keystore (the M1-4
  `KeystoreCredentialStore`, reused). The app sends them to the backend *per sync*
  and the **backend never persists them** — it holds them in memory only for the
  duration of one scrape, then discards. No secrets at rest on the server.
- **Backend persists nothing sensitive.** It's a stateless "scrape function":
  receives creds + date range, returns transactions JSON. The phone persists the
  result in its existing Room DB. (Trade-off: scheduled auto-sync must be triggered
  by the phone — fine for personal use; avoids credential-at-rest on a server.)
- **Backend is never on the public internet.** It binds to localhost/LAN and is
  reached from the phone over a private **WireGuard/Tailscale** mesh — no open ports.
- **Transport:** TLS with **certificate pinning** in the app (OkHttp), plus a
  **bearer token** (stored in Keystore) so only your phone can call `/scrape`.
  mTLS (client cert) is the stronger alternative if we want it.
- **Backend hardening:** runs in a non-root **Docker** container, minimal image;
  **credential logging disabled** (scraper `verbose` off, log scrubbing); dedicated/
  patched host.
- **Reuse M1 unchanged:** the backend's JSON maps to the existing
  `ScrapedTransaction`/`ScrapedAccount` DTOs, so `ScrapeMapper` → dedupe → Room →
  sync all work as-is. The only new app piece is a `RemoteBankScraper` behind the
  existing `BankScraper` interface.

### Residual risks (accepted for a personal tool)
- Scraper **brittleness** — Discount site changes break logins until the library
  updates.
- **ToS** — automated scraping likely violates the bank's terms; personal use, your
  risk.
- **OTP/2FA** — if Discount later enforces it, unattended sync stops working and
  needs an interactive OTP step.
- You are still handling your **own** live bank credentials — mitigated (Keystore +
  no server persistence + private network) but never zero.

## M2 task breakdown (security-first ordering)
Security foundations land before the functional scraper, with a review pass at the end.

- **M2-1 — Threat model + security decisions (doc).** Nail the credential model,
  network model (Tailscale vs mTLS), pinning strategy, and no-persist policy. No code.
- **M2-2 — Backend skeleton, hardened first.** Dockerized Node/TS service, bound to
  localhost, TLS + bearer-token auth, health endpoint, credential-safe logging — *before*
  any scraping logic exists.
- **M2-3 — Private network path.** WireGuard/Tailscale between phone and backend;
  document setup; verify the backend is unreachable from the public internet.
- **M2-4 — Discount scrape endpoint.** Integrate israeli-bank-scrapers for Discount;
  creds in-memory only, never logged/persisted; return normalized transactions JSON.
- **M2-5 — App `RemoteBankScraper`.** OkHttp + cert pinning + Keystore-held token;
  map backend JSON → existing DTOs; plug into `SyncGraph`. Reuse ScrapeMapper/Room/sync.
- **M2-6 — Connect-Discount UI.** Three fields (id/ת״ז, password, num/code) → store via
  `KeystoreCredentialStore` (num in `extra`); wire to `RemoteBankScraper`; handle bad
  creds / backend-unreachable / OTP-required errors.
- **M2-7 — Security review + hardening pass.** Verify no creds in logs, nothing
  sensitive at rest, backend not publicly reachable, pinning enforced, container
  non-root. Run `/security-review`.

### Testing caveat
I can build and unit-test all of this, but I **cannot verify it against real Bank
Discount** — that needs your live credentials (which I won't ask for or handle) and a
running backend on your network. The scrape endpoint will be tested with mocked
scraper output; the real end-to-end test is you running the backend and connecting.

## Sources
- israeli-bank-scrapers — https://github.com/eshaham/israeli-bank-scrapers
- npm (Node ≥ 22, Puppeteer) — https://www.npmjs.com/package/israeli-bank-scrapers
- Open Banking in Israel (tracker) — https://www.openbankingtracker.com/country/israel
- Bank of Israel open-banking regulation — https://kamakama.gov.il/en/communication-and-publications/press-releases/the-banking-supervision-department-is-leading-the-regulation-of-an-open-banking-standard-in-israel/
- Finanda (Israeli licensed aggregator) — https://www.finanda.com/en/
