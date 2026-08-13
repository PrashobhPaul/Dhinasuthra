package com.dhinasuthra.app.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.StatisticsEngine
import com.dhinasuthra.app.ui.foundation.rememberEntryProgress
import com.dhinasuthra.app.ui.theme.DsTokens
import kotlin.math.roundToInt

/**
 * The chart set behind Time Lab (spec §26, §33).
 *
 * The same analytical dataset is deliberately renderable several ways — donut,
 * bars, histogram, box plot, trend line, planned-vs-actual — because "same
 * information, different views" is a product requirement, not a nice-to-have.
 * None of these compute anything: they receive numbers the engines produced.
 */

data class Slice(val label: String, val minutes: Int, val color: Color)

@Composable
fun DonutChart(
    slices: List<Slice>,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 26.dp,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    centre: @Composable (() -> Unit)? = null
) {
    val total = slices.sumOf { it.minutes }.coerceAtLeast(1)
    val progress = rememberEntryProgress(slices, durationMs = 1200)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { pos ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val angle = ((Math.toDegrees(
                            kotlin.math.atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())
                        ) + 450.0) % 360.0)
                        var acc = 0.0
                        var hit: Int? = null
                        for ((i, s) in slices.withIndex()) {
                            val sweep = 360.0 * s.minutes / total
                            if (angle >= acc && angle < acc + sweep) { hit = i; break }
                            acc += sweep
                        }
                        onSelect?.invoke(if (hit == selectedIndex) null else hit)
                    }
                }
        ) {
            val stroke = ringWidth.toPx()
            var start = -90f
            for ((i, s) in slices.withIndex()) {
                val selected = i == selectedIndex
                val inset = stroke / 2 + (if (selected) 0.dp.toPx() else 4.dp.toPx())
                val sweepFull = 360f * s.minutes / total
                val sweep = (sweepFull - 1.5f).coerceAtLeast(0f) * progress
                drawArc(
                    color = s.color.copy(alpha = if (selectedIndex == null || selected) 1f else 0.35f),
                    startAngle = start, sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(if (selected) stroke * 1.18f else stroke, cap = StrokeCap.Butt)
                )
                start += sweepFull
            }
        }
        centre?.invoke()
    }
}

data class Bar(
    val label: String,
    val value: Float,
    val max: Float,
    val color: Color = DsTokens.Cyan,
    val highlight: Boolean = false,
    val caption: String? = null
)

@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    onSelect: ((Int) -> Unit)? = null,
    selectedIndex: Int? = null
) {
    val progress = rememberEntryProgress(bars, durationMs = 900)
    Row(
        modifier
            .fillMaxWidth()
            .height(height + 26.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, b ->
            Column(
                Modifier
                    .weight(1f)
                    .pointerInput(bars, index) {
                        detectTapGestures { onSelect?.invoke(index) }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                val frac = if (b.max <= 0f) 0f else (b.value / b.max).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth(if (selectedIndex == index) 0.95f else 0.72f)
                        .height((height * frac * progress).coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    b.color.copy(alpha = if (b.highlight) 1f else 0.85f),
                                    b.color.copy(alpha = 0.35f)
                                )
                            )
                        )
                )
                Spacer(Modifier.height(6.dp))
                Text(b.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                if (b.caption != null && selectedIndex == index) {
                    Text(
                        b.caption,
                        style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.Gold),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Stacked composition bars — one column per day, segments per activity. */
@Composable
fun StackedBars(
    columns: List<List<Pair<Color, Int>>>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    maxMinutes: Int = 1440
) {
    val progress = rememberEntryProgress(columns, durationMs = 950)
    Row(
        modifier.fillMaxWidth().height(height + 22.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        columns.forEachIndexed { i, segments ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth(0.8f).height(height)) {
                    var y = size.height
                    for ((color, minutes) in segments) {
                        val h = size.height * (minutes.toFloat() / maxMinutes) * progress
                        if (h <= 0f) continue
                        drawRect(color, topLeft = Offset(0f, y - h), size = Size(size.width, h))
                        y -= h
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Distribution of start times, in bins — "when does lunch usually begin?" */
@Composable
fun Histogram(
    bins: List<StatisticsEngine.Bin>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = DsTokens.Cyan
) {
    val progress = rememberEntryProgress(bins, durationMs = 800)
    val max = (bins.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bins.forEach { bin ->
                Box(
                    Modifier
                        .weight(1f)
                        .height((height * (bin.count.toFloat() / max) * progress).coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(color.copy(alpha = if (bin.count == max) 1f else 0.55f))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(bins.firstOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
            Text(bins.lastOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Tukey box plot: the spread of a habit, drawn honestly including its outliers. */
@Composable
fun BoxPlot(
    stats: StatisticsEngine.BoxStats,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Gold,
    minValue: Int = stats.min,
    maxValue: Int = stats.max,
    formatter: (Int) -> String = { TimeUtils.formatMinuteOfDay(it) }
) {
    val progress = rememberEntryProgress(stats, durationMs = 700)
    val lo = minOf(minValue, stats.outliers.minOrNull() ?: minValue)
    val hi = maxOf(maxValue, stats.outliers.maxOrNull() ?: maxValue)
    val span = (hi - lo).coerceAtLeast(1)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(52.dp)) {
            fun x(v: Int) = size.width * ((v - lo).toFloat() / span) * progress
            val midY = size.height / 2f
            val boxH = size.height * 0.52f

            drawLine(color.copy(alpha = 0.55f), Offset(x(stats.min), midY), Offset(x(stats.max), midY), 2f)
            listOf(stats.min, stats.max).forEach {
                drawLine(
                    color.copy(alpha = 0.55f),
                    Offset(x(it), midY - boxH / 3), Offset(x(it), midY + boxH / 3), 2f
                )
            }
            drawRoundRectCompat(
                color = color.copy(alpha = 0.28f),
                topLeft = Offset(x(stats.p25), midY - boxH / 2),
                size = Size((x(stats.p75) - x(stats.p25)).coerceAtLeast(2f), boxH),
                radius = 5f
            )
            drawRoundRectCompat(
                color = color,
                topLeft = Offset(x(stats.p25), midY - boxH / 2),
                size = Size((x(stats.p75) - x(stats.p25)).coerceAtLeast(2f), boxH),
                radius = 5f,
                stroke = 1.5f
            )
            drawLine(color, Offset(x(stats.median), midY - boxH / 2), Offset(x(stats.median), midY + boxH / 2), 3f)
            stats.outliers.forEach { drawCircle(DsTokens.Rose, 3.5f, Offset(x(it), midY)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("P25 ${formatter(stats.p25)}", style = MaterialTheme.typography.labelSmall)
            Text("Median ${formatter(stats.median)}", style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.Ink))
            Text("P75 ${formatter(stats.p75)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Trend line with an optional least-squares guide and a shaded band. */
@Composable
fun TrendLine(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = DsTokens.Cyan,
    trend: StatisticsEngine.Trend? = null,
    showPoints: Boolean = true
) {
    val progress = rememberEntryProgress(values, durationMs = 1000)
    if (values.isEmpty()) return
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).takeIf { it > 0.0001f } ?: 1f

    Canvas(modifier.fillMaxWidth().height(height)) {
        fun px(i: Int) = size.width * (i.toFloat() / (values.size - 1).coerceAtLeast(1))
        fun py(v: Float) = size.height - size.height * ((v - lo) / span) * 0.86f - size.height * 0.07f

        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = px(i)
            val y = py(v)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height) ; fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(px(values.lastIndex), size.height)
        fill.close()

        drawPath(
            fill,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.22f * progress), Color.Transparent))
        )
        drawPath(path, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))

        if (trend != null && values.size >= 3) {
            val y0 = py(trend.intercept)
            val y1 = py(trend.intercept + trend.slopePerDay * (values.size - 1))
            drawLine(
                DsTokens.Gold.copy(alpha = 0.75f),
                Offset(0f, y0), Offset(size.width, y1),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(9f, 9f), 0f
                )
            )
        }
        if (showPoints) {
            values.forEachIndexed { i, v -> drawCircle(color, 2.6.dp.toPx(), Offset(px(i), py(v))) }
        }
    }
}

/** Planned vs actual: two aligned lanes, with the deviation drawn between them. */
data class PlanActualRow(
    val label: String,
    val plannedMin: Int,
    val actualMin: Int?,
    val toleranceMin: Int,
    val color: Color
)

@Composable
fun PlannedVsActual(
    rows: List<PlanActualRow>,
    modifier: Modifier = Modifier,
    fromMin: Int = 0,
    toMin: Int = 1440
) {
    val progress = rememberEntryProgress(rows, durationMs = 850)
    val span = (toMin - fromMin).coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(row.color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(row.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val delta = row.actualMin?.let { it - row.plannedMin }
                    Text(
                        when {
                            delta == null -> "not yet"
                            delta == 0 -> "exact"
                            delta > 0 -> "+${delta}m"
                            else -> "$delta m"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = when {
                                delta == null -> DsTokens.InkMuted
                                kotlin.math.abs(delta) <= row.toleranceMin -> DsTokens.Green
                                else -> DsTokens.Gold
                            }
                        )
                    )
                }
                Spacer(Modifier.height(5.dp))
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    fun x(min: Int) = size.width * ((min - fromMin).toFloat() / span)
                    val yPlan = size.height * 0.3f
                    val yActual = size.height * 0.78f
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, yPlan), Offset(size.width, yPlan), 1f)
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, yActual), Offset(size.width, yActual), 1f)

                    // tolerance band around the plan
                    val tolFrom = x(row.plannedMin - row.toleranceMin)
                    val tolTo = x(row.plannedMin + row.toleranceMin)
                    drawRoundRectCompat(
                        color = row.color.copy(alpha = 0.16f),
                        topLeft = Offset(tolFrom, yPlan - 5f),
                        size = Size((tolTo - tolFrom).coerceAtLeast(2f), 10f),
                        radius = 5f
                    )
                    drawCircle(row.color, 4.5.dp.toPx(), Offset(x(row.plannedMin), yPlan))
                    if (row.actualMin != null) {
                        val ax = x(row.actualMin)
                        drawCircle(DsTokens.Ink, 4.5.dp.toPx(), Offset(ax, yActual))
                        drawLine(
                            row.color.copy(alpha = 0.7f * progress),
                            Offset(x(row.plannedMin), yPlan), Offset(ax, yActual),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f, 6f), 0f
                            )
                        )
                    }
                }
            }
        }
    }
}

/** A slim horizontal gauge for percentages that must not look like a progress bar. */
@Composable
fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Gold,
    height: Dp = 6.dp
) {
    val progress = rememberEntryProgress(fraction, durationMs = 800)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2f
        drawRoundRectCompat(Color.White.copy(alpha = 0.07f), Offset.Zero, size, r)
        drawRoundRectCompat(
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color)),
            topLeft = Offset.Zero,
            size = Size(size.width * fraction.coerceIn(0f, 1f) * progress, size.height),
            radius = r
        )
    }
}

/** Percentile band strip: p10–p90 with the median marked — your "usually" window. */
@Composable
fun BandStrip(
    p10: Int, p25: Int, median: Int, p75: Int, p90: Int,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Cyan,
    windowFrom: Int = (p10 - 60).coerceAtLeast(0),
    windowTo: Int = p90 + 60
) {
    val span = (windowTo - windowFrom).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        fun x(v: Int) = size.width * ((v - windowFrom).toFloat() / span)
        val mid = size.height / 2f
        drawRoundRectCompat(
            color.copy(alpha = 0.14f), Offset(x(p10), mid - 8f),
            Size((x(p90) - x(p10)).coerceAtLeast(2f), 16f), 8f
        )
        drawRoundRectCompat(
            color.copy(alpha = 0.4f), Offset(x(p25), mid - 8f),
            Size((x(p75) - x(p25)).coerceAtLeast(2f), 16f), 8f
        )
        drawLine(DsTokens.Ink, Offset(x(median), mid - 10f), Offset(x(median), mid + 10f), 2.5f)
    }
}

@Composable
fun ScatterPlot(
    points: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Violet,
    height: Dp = 150.dp
) {
    if (points.isEmpty()) return
    val progress = rememberEntryProgress(points, durationMs = 800)
    val xs = points.map { it.first }
    val ys = points.map { it.second }
    val xLo = xs.min(); val xHi = xs.max()
    val yLo = ys.min(); val yHi = ys.max()
    Canvas(modifier.fillMaxWidth().height(height)) {
        val xSpan = (xHi - xLo).takeIf { it > 0.001f } ?: 1f
        val ySpan = (yHi - yLo).takeIf { it > 0.001f } ?: 1f
        for (h in 1..3) {
            val y = size.height * h / 4f
            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        points.forEach { (px, py) ->
            val x = size.width * ((px - xLo) / xSpan) * 0.92f + size.width * 0.04f
            val y = size.height - (size.height * ((py - yLo) / ySpan) * 0.86f + size.height * 0.07f)
            drawCircle(color.copy(alpha = 0.85f * progress), 4.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
fun LegendRow(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.take(5).forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The score ring: a single number, earned.
 *
 * The sweep is drawn from a gradient of the thread colours and animated on
 * arrival, with a soft outer halo for depth. When there is no score to show it
 * says so, rather than drawing a confident-looking zero.
 */
@Composable
fun ScoreRing(
    score: Int?,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 14.dp,
    label: String = "Rhythm",
    emptyTitle: String = "Learning",
    emptyBody: String = "your rhythm"
) {
    val progress = rememberEntryProgress(score, durationMs = 1400)
    val accent = DsTokens.rhythmColor(score ?: 0)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ringWidth.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = Color.White.copy(alpha = 0.06f), startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (score != null) {
                val sweep = 360f * (score / 100f) * progress
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.9f), accent, DsTokens.GoldSoft, accent.copy(alpha = 0.9f))
                    ),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke * 2.1f, cap = StrokeCap.Round), alpha = 0.13f
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.75f), accent, DsTokens.GoldSoft, accent.copy(alpha = 0.75f))
                    ),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (score != null) {
                com.dhinasuthra.app.ui.foundation.AnimatedNumber(
                    target = score,
                    style = MaterialTheme.typography.displayLarge
                )
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
            } else {
                Text(emptyTitle, style = MaterialTheme.typography.headlineSmall)
                Text(emptyBody, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Formats a minutes value as a percentage of a total, for chart captions. */
fun percentOf(part: Int, total: Int): String =
    if (total <= 0) "0%" else "${((part * 100f) / total).roundToInt()}%"
