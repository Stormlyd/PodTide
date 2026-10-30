package eu.darken.capod.main.ui.heartrate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import eu.darken.capod.reaction.core.heartrate.HeartRateSample
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.roundToInt

// Do not draw an invented line across periods when no measurements were recorded.
internal fun heartRateSegments(history: List<HeartRateSample>): List<List<HeartRateSample>> {
    val sorted = history.filter { it.bpm in 1..255 }.sortedBy { it.atMillis }
    val segments = mutableListOf<MutableList<HeartRateSample>>()
    sorted.forEach { sample ->
        if (segments.isEmpty() || sample.atMillis - segments.last().last().atMillis > 15_000L) {
            segments.add(mutableListOf())
        }
        segments.last().add(sample)
    }
    return segments
}

@Composable
internal fun HeartRateHistoryChart(history: List<HeartRateSample>, onClear: () -> Unit) {
    val segments = remember(history) { heartRateSegments(history) }
    val samples = remember(segments) { segments.flatten() }
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.heart_rate_chart_title), style = MaterialTheme.typography.titleMedium)
                if (samples.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.heart_rate_clear)) }
            }
            if (samples.isEmpty()) {
                Text(stringResource(R.string.heart_rate_history_empty), Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            val average = samples.map { it.bpm }.average().roundToInt()
            val maximum = samples.maxBy { it.bpm }
            val minimum = samples.minOf { it.bpm }
            val pad = maxOf(5, (maximum.bpm - minimum) / 4)
            val lower = (minimum - pad).coerceAtLeast(0)
            val upper = maximum.bpm + pad
            val start = samples.first().atMillis
            val end = samples.last().atMillis
            val duration = (end - start).coerceAtLeast(1L)
            val accent = MaterialTheme.colorScheme.primary
            val maximumColor = MaterialTheme.colorScheme.error
            val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
            val surface = MaterialTheme.colorScheme.surfaceContainerLow
            val averageText = stringResource(R.string.heart_rate_chart_average, average)
            val maximumText = stringResource(R.string.heart_rate_chart_maximum, maximum.bpm)

            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().height(210.dp)) {
                val plotHeight = maxHeight - 56.dp
                fun yFor(bpm: Int) = 28.dp + plotHeight * ((upper - bpm).toFloat() / (upper - lower))
                fun xFor(at: Long) = if (start == end) 34.dp + (maxWidth - 42.dp) / 2 else
                    34.dp + (maxWidth - 42.dp) * ((at - start).toDouble() / duration.toDouble()).toFloat()
                val averageY = yFor(average)
                val peakX = xFor(maximum.atMillis)
                val peakY = yFor(maximum.bpm)

                Canvas(Modifier.matchParentSize().testTag("heart_rate_chart")) {
                    val leftInset = 34.dp.toPx()
                    val rightInset = 8.dp.toPx()
                    val top = 28.dp.toPx()
                    val height = size.height - 56.dp.toPx()
                    fun point(sample: HeartRateSample): Offset {
                        val x = if (start == end) leftInset + (size.width - leftInset - rightInset) / 2 else
                            leftInset + (size.width - leftInset - rightInset) * ((sample.atMillis - start).toDouble() / duration.toDouble()).toFloat()
                        val y = top + height * ((upper - sample.bpm).toFloat() / (upper - lower))
                        return Offset(x, y)
                    }
                    repeat(3) { index ->
                        val y = top + height * index / 2f
                        drawLine(grid, Offset(leftInset, y), Offset(size.width - rightInset, y), strokeWidth = 1.dp.toPx())
                    }
                    val meanY = top + height * ((upper - average).toFloat() / (upper - lower))
                    drawLine(accent.copy(alpha = .6f), Offset(leftInset, meanY), Offset(size.width - rightInset, meanY),
                        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                    segments.forEach { segment ->
                        segment.zipWithNext().forEach { (a, b) ->
                            drawLine(accent, point(a), point(b), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        }
                        segment.forEach { drawCircle(accent, radius = 2.5.dp.toPx(), center = point(it)) }
                    }
                    drawCircle(maximumColor, radius = 5.dp.toPx(), center = point(maximum))
                }
                Text("$upper", Modifier.offset(x = 0.dp, y = 20.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$lower", Modifier.offset(x = 0.dp, y = maxHeight - 34.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(averageText,
                    modifier = Modifier.offset(x = 34.dp, y = (averageY + 4.dp).coerceAtMost(maxHeight - 30.dp))
                        .widthIn(max = maxWidth / 2 - 8.dp).background(surface, RoundedCornerShape(6.dp)).padding(2.dp),
                    style = MaterialTheme.typography.labelSmall, color = accent)
                Text(maximumText,
                    modifier = Modifier.offset(x = (peakX - maxWidth / 4).coerceIn(0.dp, maxWidth / 2),
                        y = (peakY - 36.dp).coerceAtLeast(0.dp))
                        .widthIn(max = maxWidth / 2).background(surface, RoundedCornerShape(6.dp)).padding(2.dp),
                    style = MaterialTheme.typography.labelSmall, color = maximumColor)
            }
            val zone = ZoneId.systemDefault()
            val crossesDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
            val timeFormat = if (crossesDate)
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
            else DateFormat.getTimeInstance(DateFormat.MEDIUM, locale)
            Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
                Text(timeFormat.format(Date(start)), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (start != end) Text(timeFormat.format(Date(end)), Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.heart_rate_history_description), Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
