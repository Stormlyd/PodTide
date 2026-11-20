package eu.darken.capod.main.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import eu.darken.capod.R
import eu.darken.capod.main.core.sound.SoundLevelReading
import eu.darken.capod.main.core.sound.SoundLevelRepository
import eu.darken.capod.main.ui.MainActivity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class SoundLevelGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val reading = SoundLevelRepository.readCached(context)
        provideContent { SoundLevelWidgetContent(reading, context) }
    }
}

class SoundLevelWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SoundLevelGlanceWidget()
}

@Composable
private fun SoundLevelWidgetContent(reading: SoundLevelReading?, context: Context) {
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    )
    val bgColor = ColorProvider(
        Color(WidgetRenderStateMapper.resolveThemeColor(context, android.R.attr.colorBackground))
    )
    val primaryArgb = WidgetRenderStateMapper.resolveThemeColor(context, android.R.attr.textColorPrimary)
    val primaryColor = ColorProvider(Color(primaryArgb))
    val secondaryColor = ColorProvider(Color(primaryArgb).copy(alpha = 0.72f))

    val title = context.getString(R.string.sound_level_title)
    val dbValue = reading?.db?.roundToInt()?.toString() ?: "--"
    val subtitle = if (reading != null) {
        context.getString(
            R.string.widget_sound_level_measured_at,
            DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(reading.measuredAt),
        )
    } else {
        context.getString(R.string.widget_sound_level_empty)
    }

    val size = LocalSize.current
    val isCompactHeight = size.height < 96.dp

    if (isCompactHeight) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(openApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        color = primaryColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = secondaryColor,
                        fontSize = 10.sp,
                    ),
                    maxLines = 1,
                )
            }
            Row(
                modifier = GlanceModifier.padding(start = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = dbValue,
                    style = TextStyle(
                        color = primaryColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = " dB",
                    style = TextStyle(
                        color = secondaryColor,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    } else {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(20.dp)
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(openApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = secondaryColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = dbValue,
                    style = TextStyle(
                        color = primaryColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = " dB",
                    style = TextStyle(
                        color = secondaryColor,
                        fontSize = 13.sp,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = subtitle,
                style = TextStyle(
                    color = secondaryColor,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }
    }
}