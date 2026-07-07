package com.riseup.clone.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riseup.clone.ui.format.formatShekel
import com.riseup.clone.ui.theme.LocalFlowColors
import com.riseup.clone.ui.theme.TABULAR_FIGURES
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The hero chart: recent actual balance (muted) flowing into the projected
 * balance (teal above zero, rust below), with the below-zero region shaded,
 * a zero line, and a dashed "today" marker.
 */
@Composable
fun ForecastChart(
    points: List<ChartPoint>,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    val flow = LocalFlowColors.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = flow.mutedText,
        fontFeatureSettings = TABULAR_FIGURES,
    )
    val badgeStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp),
    ) {
        drawChart(points, today, flow.positive, flow.negative, flow.chartHistory, flow.gridline, flow.mutedText, textMeasurer, labelStyle, badgeStyle)
    }
}

private fun DrawScope.drawChart(
    points: List<ChartPoint>,
    today: LocalDate,
    positive: Color,
    negative: Color,
    history: Color,
    gridline: Color,
    muted: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    badgeStyle: TextStyle,
) {
    val padLeft = 8.dp.toPx()
    val padRight = 8.dp.toPx()
    val padTop = 22.dp.toPx()
    val padBottom = 24.dp.toPx()
    val w = size.width - padLeft - padRight
    val h = size.height - padTop - padBottom

    val balances = points.map { it.balance }
    var minY = minOf(balances.min(), 0.0)
    var maxY = maxOf(balances.max(), 0.0)
    val span = (maxY - minY).takeIf { it > 1.0 } ?: 1.0
    minY -= span * 0.08
    maxY += span * 0.08

    fun x(i: Int) = padLeft + w * i / (points.size - 1).toFloat()
    fun y(v: Double) = padTop + h * (1f - ((v - minY) / (maxY - minY)).toFloat())

    val zeroY = y(0.0)

    // --- Gridlines (recessive) + ₪ labels -------------------------------
    val gridValues = niceGridValues(minY, maxY)
    for (v in gridValues) {
        val gy = y(v)
        drawLine(gridline, Offset(padLeft, gy), Offset(padLeft + w, gy), strokeWidth = 1.dp.toPx())
        val layout = textMeasurer.measure(formatShekel(v), labelStyle)
        drawText(layout, topLeft = Offset(padLeft, gy - layout.size.height - 2.dp.toPx()))
    }

    // --- Zero line (slightly stronger than grid) -------------------------
    if (minY < 0) {
        drawLine(
            muted.copy(alpha = 0.55f),
            Offset(padLeft, zeroY),
            Offset(padLeft + w, zeroY),
            strokeWidth = 1.dp.toPx(),
        )
    }

    // --- Paths ------------------------------------------------------------
    val todayIdx = points.indexOfFirst { it.projected }.coerceAtLeast(0)

    fun polyline(from: Int, to: Int): Path {
        val p = Path()
        p.moveTo(x(from), y(points[from].balance))
        for (i in from + 1..to) p.lineTo(x(i), y(points[i].balance))
        return p
    }

    val projPath = polyline(todayIdx, points.lastIndex)

    // Below-zero shading under the projection (clip to the region below 0).
    val area = Path().apply {
        addPath(projPath)
        lineTo(x(points.lastIndex), zeroY)
        lineTo(x(todayIdx), zeroY)
        close()
    }
    clipRect(padLeft, zeroY, padLeft + w, padTop + h) {
        drawPath(area, negative.copy(alpha = 0.22f))
    }

    val strokeW = 2.dp.toPx()

    // History: muted solid line.
    if (todayIdx > 0) {
        drawPath(polyline(0, todayIdx), history, style = Stroke(strokeW, cap = StrokeCap.Round))
    }

    // Projection: teal above zero, rust below — split via clipping.
    clipRect(padLeft, 0f, padLeft + w, zeroY) {
        drawPath(projPath, positive, style = Stroke(strokeW, cap = StrokeCap.Round))
    }
    clipRect(padLeft, zeroY, padLeft + w, size.height) {
        drawPath(projPath, negative, style = Stroke(strokeW, cap = StrokeCap.Round))
    }

    // --- Today marker: dashed vertical + label + dot ----------------------
    val tx = x(todayIdx)
    drawLine(
        muted.copy(alpha = 0.8f),
        Offset(tx, padTop - 6.dp.toPx()),
        Offset(tx, padTop + h),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
    )
    val todayLabel = textMeasurer.measure("Today", labelStyle.copy(color = muted, fontWeight = FontWeight.SemiBold))
    val todayLabelX = (tx - todayLabel.size.width / 2f).coerceIn(padLeft, padLeft + w - todayLabel.size.width)
    drawText(todayLabel, topLeft = Offset(todayLabelX, 0f))
    val todayY = y(points[todayIdx].balance)
    drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(tx, todayY))
    drawCircle(positive, radius = 3.5f.dp.toPx(), center = Offset(tx, todayY))

    // --- Lowest projected point: dot + direct value label -----------------
    val minIdx = (todayIdx..points.lastIndex).minBy { points[it].balance }
    if (points[minIdx].balance < 0) {
        val mx = x(minIdx)
        val my = y(points[minIdx].balance)
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(mx, my))
        drawCircle(negative, radius = 3.5f.dp.toPx(), center = Offset(mx, my))
        val label = textMeasurer.measure(formatShekel(points[minIdx].balance), badgeStyle.copy(color = negative))
        val lx = (mx - label.size.width / 2f).coerceIn(padLeft, padLeft + w - label.size.width)
        val ly = (my + 6.dp.toPx()).coerceAtMost(size.height - padBottom - label.size.height + 14.dp.toPx())
        drawText(label, topLeft = Offset(lx, ly))
    }

    // --- X labels: month starts -------------------------------------------
    for (i in points.indices) {
        val d = points[i].date
        if (d.dayOfMonth == 1) {
            val name = d.month.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH)
            val layout = textMeasurer.measure(name, labelStyle)
            val lx = (x(i) - layout.size.width / 2f).coerceIn(padLeft, padLeft + w - layout.size.width)
            drawText(layout, topLeft = Offset(lx, padTop + h + 6.dp.toPx()))
        }
    }
}

/** Two or three round gridline values spanning the y range (excluding 0 — drawn separately). */
private fun niceGridValues(minY: Double, maxY: Double): List<Double> {
    val span = maxY - minY
    val rawStep = span / 3.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
        .map { it * magnitude }
        .first { it >= rawStep }
    val start = Math.ceil(minY / step) * step
    return generateSequence(start) { it + step }
        .takeWhile { it <= maxY }
        .filter { abs(it) > step / 2 } // skip zero; the zero line is drawn separately
        .toList()
}
