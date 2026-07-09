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

## Later milestones (explicitly out of M0 scope)

Bank scraping, credential storage (Keystore), networking/backend, multiple accounts,
transaction editing, notifications.

**Next up is M1 (real bank connection)** — see [NEXT_STEPS.md](NEXT_STEPS.md) for the
dependency-ordered plan, mirrored in the task list (`TaskList`).
