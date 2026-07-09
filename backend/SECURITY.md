# Backend Security Design — RiseUp clone (M2)

**Scope:** the self-hosted `israeli-bank-scrapers` backend (Option A) and the app↔backend
path for **Bank Discount** (`companyId: discount`, fields `id` / `password` / `num`).
Single-user personal tool. Security is the explicit priority.

**Status:** design of record for M2-2 (backend), M2-3 (private network), M2-4 (scrape
endpoint), M2-5/6 (app client). M2-7 audits against the [checklist](#4-requirements-checklist-m2-7-audits-this).

**Guiding rule:** *minimize where live bank credentials live, and never expose the backend.*

---

## 1. Assets & trust boundaries

### Assets (what we protect)

| Asset | Sensitivity | Where it lives | For how long |
|---|---|---|---|
| Bank credentials (`id`, `password`, `num`) | **Critical** — live bank login | Phone: Android Keystore (AES-256/GCM, HW-backed) via `KeystoreCredentialStore`. Backend: **process RAM only** for one scrape. | Phone: until user disconnects. Backend: seconds — discarded when the scrape function returns. |
| Bank web session (Puppeteer cookies/tokens) | **Critical** — equivalent to being logged in | Backend: inside the headless Chrome instance | One scrape; Chrome context closed after. **Never** persisted. |
| Transaction / account data | **High** — financial PII | Phone: Room DB (app-private storage). Backend: transient JSON in the response. | Phone: durable. Backend: only while building the HTTP response. |
| Backend bearer token | **High** — grants `/scrape` access | Phone: Android Keystore. Backend: injected via env/secret at boot, held in RAM. | Long-lived; rotatable. |
| Pinned TLS public-key hash | Medium (integrity anchor) | App: compiled into OkHttp `CertificatePinner`. | Until cert rotation (app update). |
| TLS private key | **High** | Backend host: file, `0600`, non-root-readable | Until cert rotation. |
| Mesh keys (WireGuard/Tailscale) | **High** — network admission | Phone + backend host key stores | Long-lived; revocable. |

### Data flow & trust boundaries

```
┌─ Phone (trusted) ──────────────┐        ┌─ Backend host (semi-trusted) ─────────┐        ┌─ Bank ─┐
│ Keystore: creds + token        │  TLS   │ Node/TS service (non-root Docker)      │  HTTPS │Discount│
│ Room DB: transactions          │ pinned │  - creds in RAM only, 1 scrape        │ (public│  site  │
│ OkHttp client (RemoteScraper)  │───────▶│  - Puppeteer/Chrome drives bank login │  intn.)│        │
│                                │ +token │  - returns normalized JSON, persists 0 │───────▶│        │
└────────────────────────────────┘        └───────────────────────────────────────┘        └────────┘
        └──────── private WireGuard/Tailscale mesh (no public ports) ────────┘
```

**Boundaries crossed:**
- **B1 Phone → Backend** — leaves the device. Protected by: private mesh + TLS + cert pinning + bearer token. Carries live creds → the highest-value boundary in the system.
- **B2 Backend → Bank** — public internet HTTPS, driven by Puppeteer. We don't control the bank; trust its TLS. Creds are used here in RAM.
- **B3 Backend host** — semi-trusted: we own it but it runs a memory-hungry browser and third-party scraper code. Blast radius is minimized by holding nothing at rest.

---

## 2. Threat model

Rating = Likelihood × Impact (Low/Med/High). STRIDE tag in brackets.

| # | Threat | L | I | Mitigation in this design |
|---|---|---|---|---|
| T1 | **Backend exposed to public internet** → anyone can hit `/scrape` / attack Node/Chrome [S,E,I] | Med | **High** | Bind to `127.0.0.1`/mesh iface only; **no published/forwarded ports**; reachable only over the mesh (M2-3). Verify externally that the port is closed. Bearer token as second gate. |
| T2 | **Credential leakage via logs** (request bodies, scraper `verbose`, error traces) [I] | Med | **High** | `verbose=false`; **never log request bodies/headers/creds**; log scrubbing; structured logs with an allow-list of safe fields only. App already redacts `ScraperCredentials.toString()`. |
| T3 | **Credential leakage at rest** (DB, files, swap) [I] | Low | **High** | Backend **persists nothing sensitive** — no DB, no creds/tx to disk. Creds live in RAM for one scrape. Consider disabling swap / using tmpfs on the host. |
| T4 | **Credential leakage via crash reports / memory dumps** [I] | Low | High | No crash-reporter/APM that captures request payloads on the backend. Zero/overwrite cred variables after use where practical; short object lifetime. On the app, no Crashlytics-style payload capture of creds. |
| T5 | **MITM on Phone↔Backend** [T,S] | Low (on mesh) | **High** | TLS + **certificate pinning** in the app (fail closed on mismatch). Mesh already encrypts. Pin the leaf/intermediate SPKI. |
| T6 | **Stolen / lost phone** [S,I] | Med | **High** | Creds encrypted under HW-backed Keystore key (non-exportable). Recommend requiring device lock; **strongly recommend enabling a user-auth requirement on the Keystore key** (see §3 refinement). Remote: revoke the phone's mesh key + rotate bearer token. |
| T7 | **Compromised backend host** [E,I] | Low | **High** | Non-root container, minimal image, dropped caps, read-only FS where possible. Nothing sensitive at rest → attacker gets creds only if present *during* an active scrape. Patch host; dedicated box. |
| T8 | **Malicious / replayed requests** to `/scrape` [S,T] | Low | Med | Bearer token required on every request; TLS prevents capture; mesh limits origin to enrolled devices. Optional: short-TTL nonce/timestamp to blunt replay (low priority for single user). |
| T9 | **Scraper supply-chain** (`israeli-bank-scrapers` + Puppeteer + transitive deps) [T,E] | Med | High | Pin exact versions + lockfile; `npm ci`; review updates; run as non-root with no host mounts; egress limited to the bank. Accept residual (see §5). |
| T10 | **Bank OTP/2FA lockout / account lock from automation** [D] | Med | Med | Discount has no documented 2FA today; if enforced, sync fails **closed** with an OTP-required error (M2-6), not a silent retry storm. Rate-limit sync frequency to avoid tripping anti-automation locks. |
| T11 | **Token/mesh-key theft** [S] | Low | High | Both stored in Keystore / host key store; rotatable; mesh keys revocable per-device. |
| T12 | **SSRF / injection into scraper** (attacker-controlled bank/company param) [E] | Low | Med | Endpoint accepts only a fixed allow-list of `companyId` (Discount for now); validate/whitelist all inputs; no free-form URLs. |

---

## 3. Decisions (rationale + rejected alternative)

### 3.1 Credential persistence — **phone-only, backend never at rest**
- **Decision:** creds live only on the phone (Keystore); sent per-sync; backend holds in RAM for one scrape, then discards. Backend persists nothing sensitive.
- **Why:** smallest blast radius. A compromised backend at rest yields nothing; creds are exposed only during the seconds of an active scrape.
- **Rejected:** *backend stores creds at rest* (would enable server-scheduled auto-sync). Rejected — turns the server into a high-value credential vault; a single host compromise = full bank-credential loss. Cost accepted: **sync must be phone-triggered** (fine for one user).

### 3.2 Auth — **bearer token (required baseline); mTLS NOT required for this deployment**
- **Decision:** every `/scrape` call carries a Keystore-held **bearer token**; backend rejects anything without a valid token. **Recommendation: token + private mesh is sufficient; do not add app-layer mTLS for the mesh-only deployment.**
- **Why:** the WireGuard/Tailscale mesh *already provides mutual, key-based device authentication and encryption at the network layer* — functionally the property mTLS would add. Layering mTLS on top is redundant for a single-user, mesh-only backend and adds client-cert lifecycle pain (provisioning, rotation, storage). The token gives an app-layer "only my phone" gate and is trivially rotatable.
- **When to upgrade to mTLS:** if the backend is ever reachable **outside** the mesh, made multi-user, or the mesh is dropped. Then require **mTLS *and* keep the token** (defense in depth). Design the reverse-proxy/TLS layer so mTLS can be switched on without an app rewrite.
- **Rejected:** *mTLS-only now* (drops the token) — loses the cheap, independently-rotatable app-layer gate and couples auth entirely to cert management.

### 3.3 Transport — **TLS + certificate pinning**
- **Pin:** pin the **SPKI (SHA-256 public-key hash)**, not the whole cert — survives cert renewal if the key is reused. Pin the **leaf** key; **include a backup pin** (a second key you control) so rotation doesn't brick the app.
- **Fail closed:** any pin mismatch → request fails, no fallback to system trust.
- **Rotation:** ship current + backup pin in the app; rotate cert to the backup key; publish an app update carrying the next backup pin *before* retiring the old one.
- **Cert:** self-signed or internal-CA leaf is fine (pinning, not CA trust, is the anchor); private key `0600`, non-root-owned.
- **Rejected:** *plain TLS w/ system trust only* — mesh already limits reach, but pinning cheaply defeats a rogue/mis-issued CA and any on-path proxy.

### 3.4 Network — **Tailscale (recommended); WireGuard as the no-third-party alternative**
- **Recommendation for a personal setup: Tailscale.** Rationale: automatic NAT traversal (no port-forwarding on the home router — directly removes T1's exposure), effortless key management + rotation, MagicDNS, and device ACLs to restrict the phone→backend flow. Fastest path to "backend has zero public ports."
- **Alternative: raw WireGuard** — no third-party coordination server, fully self-owned, minimal. Choose it if you object to Tailscale's coordination plane; cost is manual key distribution + endpoint/NAT handling.
- Either way: **backend MUST NOT publish a public port**; only the mesh interface is reachable.

### 3.5 Logging policy
- Scraper `verbose=false`. **Never** log request bodies, headers, creds, cookies, or Puppeteer network traffic.
- Log only an allow-list of safe fields: timestamp, route, status, duration, coarse outcome (`success` / `invalid_credentials` / `network` / `parse_error`). No amounts, no account numbers.
- Error handling maps to the existing `FailureReason` buckets — no raw exception with embedded creds/URLs to client or logs.

### 3.6 Backend secrets handling
- **Bearer token:** injected via env var / Docker secret (never baked into the image, never committed). Rotatable.
- **TLS private key:** mounted read-only, `0600`, owned by the non-root container UID; not in the image layer, not in git.
- `.dockerignore` / `.gitignore` MUST exclude keys, tokens, `.env`.

---

## 4. Requirements checklist (M2-7 audits this)

Phrased as testable MUSTs.

### Backend — auth & network
- [ ] **R1** — Backend MUST reject any `/scrape` request without a valid bearer token (401/403), before any scraping work.
- [ ] **R2** — Backend MUST bind only to localhost/mesh interface and MUST NOT publish or forward a public port. (Verify externally: port closed from the internet.)
- [ ] **R3** — Backend MUST serve over TLS; the app MUST connect only via HTTPS.
- [ ] **R4** — `/scrape` MUST accept only an allow-listed `companyId` (Discount) and MUST validate/whitelist all input fields; no free-form URLs.

### Credentials & data at rest
- [ ] **R5** — Backend MUST NOT persist credentials, bank session, or transaction data to any disk, DB, or cache. (Static + runtime check: no writes of sensitive data.)
- [ ] **R6** — Credentials MUST exist on the backend only in process memory for the duration of one scrape; the Chrome/Puppeteer context MUST be closed after each scrape.
- [ ] **R7** — Bearer token and TLS private key MUST be supplied at runtime (env/secret/mounted file), MUST NOT be committed to git or baked into the image, and the key MUST be non-root-readable (`0600`).

### Logging
- [ ] **R8** — Backend MUST NOT log request bodies, headers, credentials, cookies, or scraper network traffic; scraper `verbose` MUST be off.
- [ ] **R9** — Logs MUST contain only allow-listed non-sensitive fields (route, status, duration, coarse outcome).

### Container hardening
- [ ] **R10** — Container MUST run as a non-root UID.
- [ ] **R11** — Image MUST be minimal, MUST pin the scraper + deps to exact versions with a committed lockfile, and MUST build with `npm ci`.
- [ ] **R12** — Container SHOULD drop unneeded Linux capabilities and use a read-only root filesystem where feasible.

### App client
- [ ] **R13** — App MUST fail closed on certificate-pin mismatch (no fallback to system trust); a backup pin MUST be present.
- [ ] **R14** — App MUST attach the bearer token to every backend request and MUST source token + creds only from the Android Keystore.
- [ ] **R15** — App MUST NOT log or crash-report credential material; `ScraperCredentials.toString()` stays redacted (already true) and applies end-to-end.
- [ ] **R16** — On OTP/2FA-required or invalid-credentials responses, the app MUST surface a distinct error and MUST NOT retry-loop (respects `FailureReason`).

---

## 5. Residual risks — explicitly accepted (personal tool)

| Risk | Why accepted |
|---|---|
| **ToS violation** — automated scraping likely breaks Bank Discount's terms | Personal, single-user, non-commercial; owner's own account and risk. |
| **Scraper brittleness** — a Discount site change breaks login until the library updates | Inherent to screen-scraping; fails closed (no data), not a security breach. The only fix is the regulated aggregator path (Option C). |
| **Still handling your own live bank credentials** — cannot reach zero | Mitigated (Keystore + no server persistence + private mesh + short RAM lifetime) but non-zero by nature of Option A. |
| **OTP/2FA future enforcement** — unattended sync would stop | No documented 2FA today; handled as a graceful "OTP required" failure, not a security hole. |
| **Supply-chain trust** in `israeli-bank-scrapers` + Puppeteer | Reduced (pinned versions, non-root, no host mounts, limited egress) but a malicious update could still see creds during a scrape. Reviewed on upgrade. |

---

## Notes / refinements to `M2_PLAN.md`

1. **Auth wording.** M2-1's line "network model (Tailscale vs mTLS)" conflates two layers. They're orthogonal: **Tailscale/WireGuard = network admission; token/mTLS = app-layer auth.** This doc treats them separately and lands on **token + mesh now, mTLS only if the backend leaves the mesh** — worth reflecting back into the plan.
2. **Keystore key has no user-auth requirement.** `KeystoreCredentialStore.getOrCreateKey()` deliberately sets no `setUserAuthenticationRequired` (so the background worker can decrypt without unlock). Given creds are live bank logins (T6), consider requiring device-unlock-bound keys for the *credential* key and keeping sync phone-triggered/foreground — a stronger stolen-phone posture. Flagged, not mandated.
3. **Replay hardening (T8)** is listed as optional; fine to defer for a single user but note it explicitly as deferred so M2-7 doesn't flag it as a miss.
4. **Host-level swap/tmpfs (T3)** — worth adding to M2-2/M2-3 hardening notes so RAM-only creds can't hit swap.
