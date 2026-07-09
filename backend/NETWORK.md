# Private network setup & verification (M2-3)

How to make the self-hosted scraper backend reachable **only** by the owner's
phone over a private mesh, and **never** by the public internet.

Read [`SECURITY.md`](./SECURITY.md) first. This guide operationalizes two things
from it:

- **R2** — the backend MUST bind only to localhost/mesh and MUST NOT publish or
  forward a public port. (Verify externally that the port is closed.)
- **Threat T1** — *"backend exposed to the public internet"* (Med likelihood,
  **High** impact). A private mesh with zero forwarded ports is the primary
  mitigation; the bearer token (R1) and TLS pinning (R3/R13) are the second and
  third gates.

The network layer is **admission only** (who can reach the port). It is separate
from app-layer auth (the bearer token) — see SECURITY.md §3.2.

---

## 1. Goal & threat addressed

**Goal:** the phone can reach the backend's `/scrape` and `/health` endpoints;
nothing else can. No port-forward on the home router, no public listener.

```
   ┌─────────────┐                              ┌───────────────────────────┐          ┌────────┐
   │   Phone     │   Tailscale mesh (WireGuard  │   Backend host            │  HTTPS   │  Bank  │
   │ (tailnet    │   under the hood, encrypted, │   Node/Fastify service    │ (public  │Discount│
   │  member)    │──no public ports)───────────▶│   binds tailnet iface OR  │  internet│  site  │
   │ OkHttp +    │   https://<host>:8443        │   localhost (see §2.2)     │─────────▶│        │
   │ cert pin +  │                              │   TLS + bearer token       │          │        │
   │ token       │                              │                            │          │        │
   └─────────────┘                              └───────────────────────────┘          └────────┘
        └──────────── only enrolled mesh devices can reach the port (T1 mitigated) ─────────┘
```

The mesh gives mutual, key-based device authentication + encryption at the
network layer (this is why mTLS is not required — SECURITY.md §3.2). The phone
reaches the backend by its tailnet name; the wider internet has no route to it.

---

## 2. Tailscale path (primary — recommended)

Recommended for a personal setup (SECURITY.md §3.4): automatic NAT traversal
(**no router port-forward**, which is what directly removes T1), easy key
rotation, MagicDNS, and device ACLs to restrict phone→backend.

### 2.1 Install & join both devices to one tailnet

1. Create a tailnet (sign in once at the Tailscale admin console with any
   supported identity provider). Both devices join **this same tailnet**.
2. **Backend host** (Linux):
   ```bash
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   # follow the printed auth URL once to enroll this machine
   tailscale ip -4      # the host's tailnet IPv4 (100.x.y.z)
   tailscale status     # confirm the host + phone are both listed
   ```
3. **Android phone:** install the **Tailscale** app from Play Store, sign in to
   the **same** tailnet, enable the VPN when prompted. It appears in
   `tailscale status` on the host.
4. Enable **MagicDNS** in the admin console (DNS tab). Each device now has a
   stable name like `riseup-host.<tailnet>.ts.net` instead of only the 100.x IP.

### 2.2 Point the backend at the mesh interface

The service reads `BIND_HOST`/`PORT`/`TLS_*` from the environment
(`src/config.ts`). Default `BIND_HOST` is `127.0.0.1`, default `PORT` is `8443`.
Two valid options — pick one:

- **Option A — bind the Tailscale interface (direct, no Docker publish nuance):**
  ```bash
  BIND_HOST=100.x.y.z   # the host's `tailscale ip -4`
  PORT=8443
  ```
  The listener is on the mesh IP only. The phone connects straight to it.
  Tradeoff: the tailnet IP is stable per-device but you must set it explicitly;
  it is still a private-only address (100.64.0.0/10, CGNAT range — never
  publicly routable).

- **Option B — keep `BIND_HOST=127.0.0.1` and let Tailscale reach loopback:**
  Simplest and matches the shipped default (R2 by construction). The phone
  connects to `https://<host-tailnet-name>:8443`; Tailscale routes that to the
  host, and the service answers on loopback because Tailscale delivers traffic
  locally. Tradeoff: works because the Tailscale daemon on the host forwards
  mesh traffic to local services — verify the phone can actually reach `:8443`
  (some setups need the service on the tailnet IP, i.e. Option A). If in doubt,
  use Option A.

> **Do NOT** set `BIND_HOST=0.0.0.0` on a host that has any public interface —
> `config.ts`/`server.ts` warn on a wildcard bind, and it would violate R2.

**Docker / compose implication (R2).** `docker-compose.yml` currently publishes
to `127.0.0.1:8443` only:
```yaml
ports:
  - "127.0.0.1:8443:8443"   # loopback ONLY — safe default
```
The container internally binds `0.0.0.0` inside its own netns (safe — see the
comment in the compose file); Docker's **publish address** is what matters. To
serve the mesh with Docker, change the publish address to the tailnet IP — never
`0.0.0.0`:
```yaml
ports:
  - "100.x.y.z:8443:8443"   # publish to the Tailscale interface ONLY
```
If you run the Node process directly (no Docker), use Option A/B above instead;
there is no publish step.

### 2.3 Lock it down

1. **Device ACLs — only the phone may reach the backend port.** In the admin
   console (Access Controls), tag the two devices and allow just the one flow.
   Example ACL (tags `phone` and `backend`, backend port 8443 only):
   ```jsonc
   {
     "tagOwners": {
       "tag:phone":   ["autogroup:admin"],
       "tag:backend": ["autogroup:admin"]
     },
     "acls": [
       // Phone may reach ONLY the backend's TLS port. Nothing else is allowed.
       { "action": "accept", "src": ["tag:phone"], "dst": ["tag:backend:8443"] }
     ]
   }
   ```
   Assign the tags to the devices (admin console → each machine → Edit ACL tags,
   or `tailscale up --advertise-tags=tag:backend` on the host). With a default
   `acls` block this narrow, no other tailnet device can even open `:8443`.

2. **Key expiry.** Leave key expiry **on** (the default) unless the host is a
   headless box you cannot easily re-authenticate; only then disable expiry for
   the **backend** node, and document why. Prefer keeping expiry and
   re-authenticating over a permanently non-expiring key.

3. **Tailnet lock (optional).** For extra assurance that a compromised
   coordination plane cannot silently enroll a rogue node, enable **tailnet
   lock**. Optional for a single-user setup; note it and move on.

4. **Revocation (maps to T6/T11).** Losing the phone → revoke that device's key
   in the admin console and rotate `AUTH_TOKEN`. Mesh admission and app-layer
   auth are independently revocable.

### 2.4 Backend URL the app will use (for M2-5 / M2-6)

The app's `RemoteBankScraper` base URL is:
```
https://<host-tailnet-name>:8443
```
e.g. `https://riseup-host.<tailnet>.ts.net:8443` (MagicDNS) — or
`https://100.x.y.z:8443` if you pin to the IP. **HTTPS only** (R3). Use the same
host string the TLS cert is issued for (see §5 — this matters for pinning and
hostname verification).

---

## 3. WireGuard path (alternative — no third-party coordination plane)

Choose this if you object to Tailscale's coordination server (SECURITY.md §3.4).
Fully self-owned; cost is manual key distribution and **one UDP port** on the
host that must accept inbound (Tailscale needs none because it does NAT
traversal for you).

**Outline (not a full tutorial):**

1. Generate a keypair on each side:
   ```bash
   wg genkey | tee privatekey | wg pubkey > publickey
   ```
2. **Backend host** `/etc/wireguard/wg0.conf`:
   ```ini
   [Interface]
   Address    = 10.9.0.1/24          # mesh subnet, private
   ListenPort = 51820                # the single UDP port to open (see NAT note)
   PrivateKey = <HOST_PRIVATE_KEY>

   [Peer]                            # the phone
   PublicKey  = <PHONE_PUBLIC_KEY>
   AllowedIPs = 10.9.0.2/32          # only this phone, single address
   ```
   Bring it up: `sudo wg-quick up wg0`. Then set `BIND_HOST=10.9.0.1` (the
   WireGuard interface) so the backend listens on the mesh only.
3. **Phone peer** (WireGuard Android app → new tunnel):
   ```ini
   [Interface]
   Address    = 10.9.0.2/32
   PrivateKey = <PHONE_PRIVATE_KEY>
   DNS        = 10.9.0.1             # optional

   [Peer]                            # the backend host
   PublicKey  = <HOST_PUBLIC_KEY>
   Endpoint   = <host-public-ip-or-ddns>:51820
   AllowedIPs = 10.9.0.1/32          # route only backend traffic over the tunnel
   PersistentKeepalive = 25          # keep NAT mapping alive
   ```
4. **NAT / port note:** the host needs inbound **UDP 51820** reachable from the
   phone. On a home router behind CGNAT you may need a dynamic-DNS name + a
   single UDP port-forward. This is the one bit of public exposure WireGuard
   requires — it is a UDP port to an encrypted tunnel (not an open TCP service),
   and the backend TLS port stays private on `10.9.0.1`. Tailscale avoids even
   this. The app URL becomes `https://10.9.0.1:8443` (whatever host string the
   cert matches).

---

## 4. Verification checklist (M2-7 runs this)

Concrete, testable. Substitute your `<host>` (tailnet name or mesh IP) and port
(`8443`).

**A. Reachable from the phone (on the mesh):**
- [ ] From the phone **on** the mesh, `https://<host>:8443/health` returns
  `{"status":"ok"}`. Quick check without the app: a terminal app (Termux) →
  ```bash
  curl -k https://<host>:8443/health      # expect: {"status":"ok"}
  ```
  (`-k` only skips CA trust for this manual probe; the app itself pins — R13.)
- [ ] `POST /scrape` with **no** token returns `401` (R1):
  ```bash
  curl -k -X POST https://<host>:8443/scrape    # expect: 401
  ```

**B. NOT reachable off the mesh (the core R2 / T1 test):**
- [ ] From a device **not** on the tailnet (e.g. phone on cellular with the
  Tailscale/WireGuard tunnel **disabled**), the same `curl` **fails to connect**
  (timeout / no route), not a TLS or 401 response.
- [ ] From an external network, scan the host's **public** IP — the port is
  closed/filtered:
  ```bash
  nmap -Pn -p 8443 <host-public-ip>       # expect: filtered or closed, NOT open
  ```
  Or use an external web port scanner against the public IP. **Nothing** should
  report `8443/tcp open`. (For the WireGuard path, only UDP 51820 may show as
  open; 8443 must not.)

**C. Not listening on a public interface (on the host):**
- [ ] Confirm the listener is bound to loopback or the mesh IP only — never
  `0.0.0.0` on a public NIC:
  ```bash
  ss -tlnp | grep 8443
  # expect LISTEN on 127.0.0.1:8443 or 100.x.y.z:8443 (or the docker-proxy on
  # 127.0.0.1 / the tailnet IP) — NOT 0.0.0.0:8443 on a public interface
  ```
  (`netstat -tlnp` works too where `ss` is unavailable.)

**D. TLS cert matches what the app pins:**
- [ ] Inspect the served cert and confirm its SPKI / CN-SAN is what the app
  pins and connects to:
  ```bash
  openssl s_client -connect <host>:8443 -servername <host> </dev/null 2>/dev/null \
    | openssl x509 -noout -subject -ext subjectAltName
  # Subject/SAN must match the <host> the app uses as its base URL (see §5)
  ```
  Derive the pinned SPKI to compare with the app's `CertificatePinner`:
  ```bash
  openssl s_client -connect <host>:8443 </dev/null 2>/dev/null \
    | openssl x509 -pubkey -noout \
    | openssl pkey -pubin -outform der \
    | openssl dgst -sha256 -binary | openssl enc -base64
  # -> sha256/<base64>  (must equal the app's primary or backup pin, R13)
  ```

Pass = A + C + D succeed and **B fails to connect from off-mesh**.

---

## 5. Failure / edge notes

- **Phone off-mesh → app can't sync (expected).** With the tunnel off (or the
  phone revoked), the backend URL is simply unreachable; the app surfaces
  "backend unreachable" and must **not** retry-loop (R16). This is the design
  working, not a bug.
- **MagicDNS gotchas.** If the app can't resolve `<host>.<tailnet>.ts.net`,
  MagicDNS may be off, or the phone's Tailscale DNS is not active (another VPN or
  a "no DNS" setting can shadow it). Fall back to the `100.x.y.z` tailnet IP as
  the base URL — but then the cert must match that literal (see next point).
- **Cert CN/SAN must match the host string the app connects to (pinning +
  hostname verification).** The README's example cert uses
  `-subj "/CN=riseup-backend"`. OkHttp pins the **SPKI**, so pinning itself
  survives any CN — but OkHttp **also does standard hostname verification**
  against the cert SAN. If the app connects to `riseup-host.<tailnet>.ts.net`
  (or `100.x.y.z`) while the cert only carries `CN=riseup-backend` with no
  matching SAN, the TLS handshake **fails hostname verification** before pinning
  even helps. **Fix:** regenerate the cert with a SAN for the exact host the app
  uses, e.g.:
  ```bash
  openssl req -x509 -newkey rsa:2048 -nodes -keyout certs/key.pem -out certs/cert.pem \
    -days 825 -subj "/CN=riseup-host.<tailnet>.ts.net" \
    -addext "subjectAltName=DNS:riseup-host.<tailnet>.ts.net,IP:100.x.y.z"
  ```
  Reuse the **same key** across renewals so the SPKI pin survives, and ship a
  backup pin before rotating (SECURITY.md §3.3). Decide the app's base-URL host
  **before** issuing the cert so the SAN matches.
- **Tailnet IP vs name churn.** Tailnet IPs are stable per device but change if
  you delete/re-add the machine. Prefer the MagicDNS name in the app URL and the
  cert SAN so a re-enroll doesn't require an app update just to fix pinning.
- **Docker publish vs bind confusion.** If verification C shows `0.0.0.0:8443`,
  it is almost always the compose `ports:` publish address — fix it to the
  loopback or tailnet IP (§2.2), not the in-container `BIND_HOST` (which is
  intentionally `0.0.0.0` inside the isolated netns).
