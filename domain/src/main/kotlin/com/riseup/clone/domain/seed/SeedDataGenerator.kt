package com.riseup.clone.domain.seed

import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlin.random.Random

/** A seeded ledger: transaction history plus the balance it implies today. */
data class SeededLedger(
    val transactions: List<Transaction>,
    val currentBalance: Double,
)

/**
 * Generates ~4 months of realistic fake Israeli-household transactions,
 * ending yesterday (relative to [today]).
 *
 * The household runs a slight monthly deficit: salary ₪14,000 on the 1st,
 * fixed obligations ~₪7,300, discretionary ~₪7,200/month. So the balance
 * climbs after payday and dips into the minus in the last week of each
 * month — the RiseUp hero scenario.
 */
class SeedDataGenerator(
    private val seed: Long = 42L,
    private val historyMonths: Long = 4,
    private val openingBalance: Double = -650.0,
) {

    private val account = "leumi-checking-001"

    fun generate(today: LocalDate): SeededLedger {
        val rng = Random(seed)
        val start = YearMonth.from(today).minusMonths(historyMonths).atDay(1)
        val txs = mutableListOf<Transaction>()
        var idCounter = 0
        fun add(date: LocalDate, amount: Double, merchant: String, category: Category) {
            if (date >= start && date < today) {
                txs += Transaction(
                    id = "seed-${idCounter++}",
                    accountId = account,
                    date = date,
                    amount = (amount * 100).roundToInt() / 100.0,
                    merchant = merchant,
                    category = category,
                )
            }
        }

        var ym = YearMonth.from(start)
        val endYm = YearMonth.from(today)
        while (ym <= endYm) {
            seedMonthlyFixed(ym, rng, ::add)
            ym = ym.plusMonths(1)
        }

        // Weekly cleaner: every Sunday, fixed price.
        var cleanerDay = start.with(java.time.DayOfWeek.SUNDAY)
        if (cleanerDay < start) cleanerDay = cleanerDay.plusWeeks(1)
        while (cleanerDay < today) {
            add(cleanerDay, -320.0, "Nikayon Dira - Marina", Category.OTHER)
            cleanerDay = cleanerDay.plusWeeks(1)
        }

        seedDiscretionary(start, today, rng, ::add)

        txs.sortBy { it.date }
        val currentBalance = openingBalance + txs.sumOf { it.amount }
        return SeededLedger(txs, currentBalance)
    }

    private inline fun seedMonthlyFixed(
        ym: YearMonth,
        rng: Random,
        add: (LocalDate, Double, String, Category) -> Unit,
    ) {
        // Salary lands on the 1st.
        add(ym.atDay(1), 14_000.0, "Maskoret - Hevrat Hi-Tech BM", Category.SALARY)
        // Rent on the 3rd.
        add(ym.atDay(3), -5_500.0, "Sechar Dira - Cohen Nechasim", Category.RENT)
        // Utilities: electric (bi-monthly-ish amounts vary; keep monthly for M0).
        add(ym.atDay(10), -(380.0 + rng.nextDouble(-40.0, 40.0)), "Hevrat HaChashmal", Category.UTILITIES)
        add(ym.atDay(12), -(95.0 + rng.nextDouble(-12.0, 12.0)), "Mei Avivim - Mayim", Category.UTILITIES)
        add(ym.atDay(15), -742.0, "Arnona - Iriyat Tel Aviv", Category.UTILITIES)
        // Subscriptions.
        add(ym.atDay(5), -54.90, "Netflix.com", Category.SUBSCRIPTIONS)
        add(ym.atDay(7), -23.90, "Spotify AB", Category.SUBSCRIPTIONS)
        add(ym.atDay(2), -189.0, "Holmes Place TLV", Category.SUBSCRIPTIONS)
        add(ym.atDay(18), -129.0, "Cellcom Tikshoret", Category.UTILITIES)
        add(ym.atDay(20), -99.90, "Partner TV", Category.SUBSCRIPTIONS)
    }

    private inline fun seedDiscretionary(
        start: LocalDate,
        today: LocalDate,
        rng: Random,
        add: (LocalDate, Double, String, Category) -> Unit,
    ) {
        var day = start
        while (day < today) {
            val dow = day.dayOfWeek.value // 1=Mon..7=Sun

            // Supermarket runs ~ twice a week (Israeli weekend: Thu/Fri big shop).
            if (dow == 4 && rng.nextDouble() < 0.9) {
                add(day, -rng.nextDouble(280.0, 520.0), pick(rng, "Shufersal Deal", "Rami Levy Shivuk Hashikma"), Category.GROCERIES)
            }
            if (dow == 1 && rng.nextDouble() < 0.7) {
                add(day, -rng.nextDouble(90.0, 220.0), pick(rng, "Shufersal Sheli", "Victory Supermarket", "AM:PM"), Category.GROCERIES)
            }

            // Cafes on weekdays.
            if (dow <= 5 && rng.nextDouble() < 0.6) {
                add(day, -rng.nextDouble(14.0, 58.0), pick(rng, "Cafe Greg", "Aroma Espresso Bar", "Cofix", "Landwer Cafe"), Category.CAFES)
            }

            // Restaurants / delivery ~ 6x a month, pricier on weekends.
            if (rng.nextDouble() < 0.20) {
                val base = if (dow >= 5) 260.0 else 150.0
                add(day, -rng.nextDouble(base * 0.6, base * 1.4), pick(rng, "Wolt", "Miznon TLV", "HaKosem Falafel", "Japanika", "Vitrina Burger"), Category.RESTAURANTS)
            }

            // Fuel ~ 3x a month.
            if (rng.nextDouble() < 0.10) {
                add(day, -rng.nextDouble(210.0, 360.0), pick(rng, "Paz Yellow", "Delek Menta", "Sonol"), Category.FUEL)
            }

            // Pharmacy / health ~ 3x a month.
            if (rng.nextDouble() < 0.10) {
                add(day, -rng.nextDouble(35.0, 190.0), pick(rng, "Super-Pharm", "Be Pharm", "Maccabi Pharm"), Category.HEALTH)
            }

            // Misc shopping ~ 3x a month.
            if (rng.nextDouble() < 0.09) {
                add(day, -rng.nextDouble(60.0, 480.0), pick(rng, "KSP Computers", "Fox Home", "Max Stock", "Steimatzky"), Category.OTHER)
            }

            day = day.plusDays(1)
        }
    }

    private fun pick(rng: Random, vararg options: String): String =
        options[rng.nextInt(options.size)]
}
