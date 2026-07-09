# Next steps — M1: real bank connection

M0 (seeded ledger + forecast engine + Compose dashboard) is done. M1 turns the app
from a demo on fake data into one that forecasts **real** balances, feeding live bank
data through the *same* forecast engine via the existing
`TransactionRepository.loadLedger(today)` seam
(`app/src/main/kotlin/com/riseup/clone/data/TransactionRepository.kt`).

These are tracked in the task list (run `TaskList`). They're dependency-ordered —
start with M1-1, and each later task unblocks as its dependencies complete.

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
