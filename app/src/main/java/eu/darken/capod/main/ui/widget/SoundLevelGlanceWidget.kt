package eu.darken.capod.main.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import eu.darken.capod.R
import eu.darken.capod.main.core.sound.SoundLevelReading
import eu.darken.capod.main.core.sound.SoundLevelRepository
import eu.darken.capod.main.ui.MainActivity
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
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(ColorProvider(Color(0xFF1C1B1F)))
            .padding(14.dp)
            .clickable(openApp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.sound_level_title),
            style = TextStyle(color = ColorProvider(Color(0xFFCAC4D0)), fontSize = 12.sp),
        )
        Spacer(GlanceModifier.width(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = reading?.db?.roundToInt()?.toString() ?: "--",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = " dB",
                style = TextStyle(color = ColorProvider(Color(0xFFCAC4D0)), fontSize = 13.sp),
            )
        }
        Text(
            text = if (reading != null) {
                context.getString(
                    R.string.widget_sound_level_measured_at,
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(reading.measuredAt),
                )
            } else {
                context.getString(R.string.widget_sound_level_empty)
            },
            style = TextStyle(color = ColorProvider(Color(0xFF938F99)), fontSize = 11.sp),
        )
    }
}