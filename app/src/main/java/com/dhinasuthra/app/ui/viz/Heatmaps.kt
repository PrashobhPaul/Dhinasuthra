package com.dhinasuthra.app.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.StatisticsEngine
import com.dhinasuthra.app.ui.foundation.rememberEntryProgress
import com.dhinasuthra.app.ui.theme.DsTokens
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Temporal heatmaps (spec §26): the week at a glance, the month as a calendar of
 * fingerprints, and the year as a single field of colour. All three read the same
 * [StatisticsEngine] output — only the geometry changes.
 */

/** Day × hour grid. 7 rows for a week, 30 for a month, and the same code for both. */
@Composable
fun TemporalHeatmap(
    cells: List<StatisticsEngine.HeatCell>,
    rowLabels: List<String>,
    modifier: Modifier = Modifier,
    cellHeight: Dp = 20.dp,
    onSelect: ((StatisticsEngine.HeatCell) -> Unit)? = null
) {
    val rows = (cells.maxOfOrNull { it.row } ?: 0) + 1
    val progress = rememberEntryProgress(cells, durationMs = 900)

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(30.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "23").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(30.dp)) {
                repeat(rows) { r ->
                    Box(Modifier.height(cellHeight), contentAlignment = Alignment.CenterStart) {
                        Text(
                            rowLabels.getOrElse(r) { "" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(cellHeight * rows)
                    .pointerInput(cells) {
                        detectTapGestures { pos ->
                            val cellW = size.width / 24f
                            val cellH = size.height / rows.coerceAtLeast(1)
                            val col = (pos.x / cellW).toInt().coerceIn(0, 23)
                            val row = (pos.y / cellH).toInt().coerceIn(0, rows - 1)
                            cells.firstOrNull { it.row == row && it.hour == col }?.let { onSelect?.invoke(it) }
                        }
                    }
            ) {
                val cellW = size.width / 24f
                val cellH = size.height / rows.coerceAtLeast(1)
                for (cell in cells) {
                    val intensity = (cell.minutes / 60f).coerceIn(0f, 1f) * progress
                    if (intensity <= 0.02f) continue
                    drawRoundRectCompat(
                        color = DsTokens.colorFor(cell.activity).copy(
                            alpha = if (cell.activity == ActivityType.UNKNOWN) 0.10f else 0.20f + 0.75f * intensity
                        ),
                        topLeft = Offset(cell.hour * cellW + 1f, cell.row * cellH + 1f),
                        size = Size(cellW - 2f, cellH - 2f),
                        radius = 3f
                    )
                }
            }
        }
    }
}

/**
 * A month as a calendar of day fingerprints. Each cell is a whole day compressed
 * to a strip — spec §56's "each day has a visual time fingerprint".
 */
@Composable
fun CalendarFingerprints(
    days: List<Pair<Long, List<ActivityType>>>,
    modifier: Modifier = Modifier,
    columns: Int = 7,
    cellHeight: Dp = 46.dp,
    onSelect: ((Long) -> Unit)? = null
) {
    if (days.isEmpty()) return
    val firstDow = TimeUtils.localDate(days.first().first).dayOfWeek.value  // 1 = Monday
    val padded: List<Pair<Long, List<ActivityType>>?> =
        List(firstDow - 1) { null } + days

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        padded.chunked(columns).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { entry ->
                    if (entry == null) {
                        Spacer(Modifier.weight(1f).height(cellHeight))
                    } else {
                        val (epochDay, fingerprint) = entry
                        Column(
                            Modifier
                                .weight(1f)
                                .height(cellHeight)
                                .pointerInput(epochDay) {
                                    detectTapGestures { onSelect?.invoke(epochDay) }
                                }
                        ) {
                            Text(
                                TimeUtils.localDate(epochDay).dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(Modifier.height(2.dp))
                            DayDna(fingerprint, Modifier.fillMaxWidth(), height = cellHeight - 20.dp)
                        }
                    }
                }
                repeat(columns - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The year: one small square per day, coloured by a single chosen measure. */
@Composable
fun YearField(
    values: List<StatisticsEngine.DayValue>,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Gold,
    cell: Dp = 9.dp,
    onSelect: ((StatisticsEngine.DayValue) -> Unit)? = null
) {
    if (values.isEmpty()) return
    val progress = rememberEntryProgress(values.size, durationMs = 1100)
    val max = values.maxOf { it.value }.takeIf { it > 0f } ?: 1f
    val weeks = ((values.size + 6) / 7).coerceAtLeast(1)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(cell * 7 + 6.dp)
                .pointerInput(values) {
                    detectTapGestures { pos ->
                        val cw = size.width / weeks
                        val ch = size.height / 7f
                        val w = (pos.x / cw).toInt()
                        val d = (pos.y / ch).toInt()
                        values.getOrNull(w * 7 + d)?.let { onSelect?.invoke(it) }
                    }
                }
        ) {
            val cw = size.width / weeks
            val ch = size.height / 7f
            values.forEachIndexed { i, v ->
                val week = i / 7
                val dow = i % 7
                val intensity = (v.value / max).coerceIn(0f, 1f) * progress
                drawRoundRectCompat(
                    color = color.copy(alpha = 0.07f + 0.9f * intensity),
                    topLeft = Offset(week * cw + 0.8f, dow * ch + 0.8f),
                    size = Size(cw - 1.6f, ch - 1.6f),
                    radius = 2f
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                TimeUtils.localDate(values.first().epochDay).month
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                TimeUtils.localDate(values.last().epochDay).month
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** A matrix of day-to-day similarity — how alike were any two days? */
@Composable
fun SimilarityMatrix(
    labels: List<String>,
    values: List<List<Float>>,
    modifier: Modifier = Modifier,
    color: Color = DsTokens.Cyan
) {
    if (values.isEmpty()) return
    val progress = rememberEntryProgress(values.size, durationMs = 800)
    val n = values.size
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height((22 * n).dp)) {
            val cw = size.width / n
            val ch = size.height / n
            for (r in 0 until n) {
                for (c in 0 until n) {
                    val v = values.getOrNull(r)?.getOrNull(c) ?: 0f
                    drawRoundRectCompat(
                        color = if (r == c) DsTokens.Hairline else color.copy(alpha = 0.08f + 0.85f * v * progress),
                        topLeft = Offset(c * cw + 1f, r * ch + 1f),
                        size = Size(cw - 2f, ch - 2f),
                        radius = 3f
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.take(n).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Legend for intensity ramps, so colour is never the only channel. */
@Composable
fun IntensityScale(
    lowLabel: String,
    highLabel: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(lowLabel, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val steps = 6
            val w = size.width / steps
            for (i in 0 until steps) {
                drawRoundRectCompat(
                    color = color.copy(alpha = 0.1f + 0.85f * (i / (steps - 1f))),
                    topLeft = Offset(i * w + 1f, 0f),
                    size = Size(w - 2f, size.height),
                    radius = 2f
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(highLabel, style = MaterialTheme.typography.labelSmall)
    }
}

internal fun formatPercent(value: Float): String = "${(value * 100).roundToInt()}%"
