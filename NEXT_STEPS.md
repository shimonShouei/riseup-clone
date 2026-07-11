# Next steps

## Current architecture (2026-07)

The app has **no backend, no networking, and stores no bank credentials.** Real data
enters as a CSV file import:

- **`scraper-cli/`** — a local Node CLI you run on your own machine. It scrapes Bank
  Discount with `israeli-bank-scrapers` and writes a CSV statement. Credentials live
  only in that machine's `.env` (in memory for one run); nothing is logged or
  persisted. See `scraper-cli/README.md`.
- **The app imports that CSV** (or a bundled sample) through
  `StatementImporter` → `ScrapeMapper` → Room, feeding the *same* forecast engine via
  `TransactionRepository.loadLedger(today)`
  (`app/src/main/kotlin/com/riseup/clone/data/TransactionRepository.kt`). The first-run
  screen offers *Import statement (CSV)* and *Load sample data*; a successful import
  persists "connected" so a cold restart opens the dashboard directly.

The earlier self-hosted-backend design (remote scraper over pinned HTTPS + Keystore
credentials + background sync) was built and then removed — see `M2_PLAN.md` for the
history.

## Possible future work

Ideas, not committed work. Multiple institutions, transaction editing, richer
categorisation, an in-app "re-import latest statement" shortcut, or (only if this ever
went real) a regulated open-banking aggregator — the one credential-safe, legal path.

---

## History — M1: real bank connection (done)

M1 turned the app from a demo on fake data into one that forecasts **real** balances.
Tracked in the task list (run `TaskList`), dependency-ordered.

| # | Task | Depends on |
|---|------|-----------|
| **M1-1** | Generalize the data layer: add an `Account` model and a first-class `Ledger` type independent of the seed generator; keep `SeededTransactionRepository` working against the new shape. | — |
| **M1-2** | Add local persistence (Room) — entities, DAO, DB, and a `PersistedTransactionRepository`; seed the DB on first run so behavior is unchanged until real sync lands. | M1-1 |
| **M1-3** | Define a `BankScraper` boundary (login → fetch accounts + transactions over a date range) and implement one real Israeli provider; map raw rows → domain `Transaction`/`Account` with a unit-tested mapper. | M1-1 |
| **M1-4** | Secure credential storage via the Android Keystore (`EncryptedSharedPreferences` or Keystore-wrapped key); never log/persist plaintext; clear on logout. | M1-3 |
| **M1-5** | Background sync with WorkManager — scheduled + on-demand, dedupe by stable id, retry/backoff, network constraints, and a `SyncState` (idle/syncing/error/last-synced). | M1-2, M1-3, M1-4 |
| **M1-6** | Connect-bank onboarding (pick institution, enter credentials, first sync, progress/errors) and switch `DashboardViewModel` to the persisted/real repository, keeping the seeded repo behind a debug/demo flavor. | M1-5 |

**Definition of done for M1:** a user connects a real Israeli bank account, transactions
sync into a persistent store, and the existing forecast engine renders the dashboard's
hero forecast from real balances.

Out of scope for M1 (later milestones): multiple institutions, transaction editing,
push notifications, backend/cloud sync.
