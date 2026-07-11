# RiseUp Clone — M0

A native Android clone of the Israeli cash-flow app RiseUp. **M0** proves the core
mechanic on seeded data — no bank connection, no networking:

- **Seeded ledger**: ~4 months of realistic fake Israeli-household transactions
  (salary ₪14,000 on the 1st, rent ₪5,500, arnona/electric/water, Netflix/Spotify/gym,
  Shufersal/Rami Levy, cafes, Paz/Delek fuel, Super-Pharm, Wolt...). Deterministic
  (fixed random seed), generated relative to today's date.
- **Forecast engine** (pure Kotlin, unit-tested): detects recurring items by
  clustering (normalized merchant + similar amount + steady weekly/monthly cadence),
  classifies salary / rent / subscriptions, then projects a daily balance from today
  to the end of next month: scheduled recurring events on their expected dates minus
  a discretionary daily rate derived from historical non-recurring spend.
- **Dashboard**: headline month-end + next-month balances, overdraft depth/date chip,
  a Compose-Canvas forecast chart (dashed "today" marker, zero line, below-zero
  region shaded rust), upcoming recurring events, and a 30-day category breakdown.

The seeded household runs a small monthly deficit, so the forecast dips into the
minus late in the month and recovers after payday — the product's hero visual.

## Structure

```
domain/   Pure Kotlin (JVM). No Android deps.
          model/   Transaction, Category, RecurringItem, ForecastResult...
          engine/  RecurringDetector, ForecastEngine
          seed/    SeedDataGenerator (deterministic fake ledger)
app/      Android application (Kotlin + Jetpack Compose + Material 3).
          data/    TransactionRepository + SeededTransactionRepository
          ui/      theme (light/dark, brand colors), dashboard screen,
                   Canvas forecast chart, ViewModel (MVVM, StateFlow)
```

- minSdk 26, target/compileSdk 35, Kotlin 2.0.21, AGP 8.7.3, Gradle 8.13.

## Build & run

```bash
# JDK 17+ required (repo was built with Microsoft OpenJDK 21 ARM64
# at C:\Users\shimo\.devtools\jdk-21.0.11+10; Android SDK at
# C:\Users\shimo\AppData\Local\Android\Sdk — see local.properties).

./gradlew :domain:test        # forecast engine unit tests (pure JVM)
./gradlew :app:assembleDebug  # build the APK
./gradlew installDebug        # install on a connected device
```

Or open the project in Android Studio and run the `app` configuration.

## Verified (M0)

- `:domain:test` — 17/17 tests pass: recurring detection (monthly/weekly, salary/
  rent/subscription classification, merchant normalization, amount-band splitting,
  irregular spend rejected), forecast horizon/scheduling, an overdraft-then-recovery
  scenario, a stays-positive scenario, and the seeded ledger reproducing the hero
  dip from multiple vantage dates.
- `:app:assembleDebug` — builds successfully (`app/build/outputs/apk/debug/app-debug.apk`).
- Not verified on a device/emulator (no emulator available on this machine).

## Since M0 — real transactions via CSV import

The app now imports **real** transactions, with **no backend, no networking, and no
bank credentials stored on the phone**:

- **`scraper-cli/`** — a small local Node CLI you run on your own machine. It scrapes
  Bank Discount (`israeli-bank-scrapers`) and writes a **CSV statement**; credentials
  live only in that machine's `.env`, in memory for a single run. See
  [`scraper-cli/README.md`](scraper-cli/README.md).
- **The app imports that CSV** (or a bundled sample) through `StatementImporter` →
  `ScrapeMapper` → Room, feeding the same forecast engine. The first-run screen offers
  *Import statement (CSV)* and *Load sample data*.

An earlier self-hosted-backend design (remote scraper over pinned HTTPS + Android
Keystore credentials + WorkManager background sync) was built and then removed in
favour of this simpler, credential-free local-import model — see
[M2_PLAN.md](M2_PLAN.md) and [NEXT_STEPS.md](NEXT_STEPS.md) for the history.
