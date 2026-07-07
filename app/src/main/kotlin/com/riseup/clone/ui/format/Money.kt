package com.riseup.clone.ui.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private val grouping = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))

/** "₪12,450" / "-₪1,240". Whole shekels — agorot add noise to a forecast. */
fun formatShekel(amount: Double): String {
    val rounded = amount.roundToLong()
    val body = grouping.format(abs(rounded))
    return if (rounded < 0) "-₪$body" else "₪$body"
}

/** "+₪345" / "-₪1,240" — for deltas and transaction rows. */
fun formatShekelSigned(amount: Double): String {
    val rounded = amount.roundToLong()
    return if (rounded > 0) "+₪${grouping.format(rounded)}" else formatShekel(amount)
}
