package eu.darken.capod.main.ui.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
@Suppress("DEPRECATION")
@OptIn(ExperimentalGlanceApi::class)
class PhoneBatteryGlanceSessionTest : BaseTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `one glance session follows config and real battery broadcasts`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val config = MutableStateFlow(WidgetConfig())
        val source = PhoneBatterySource(context, appScope)
        val host = SessionHost(config, source)
        val updates = Channel<RemoteViews>(Channel.UNLIMITED)
        val session = launch(Dispatchers.Default) {
            host.runComposition(
                context,
                AppWidgetId(707),
                Bundle(),
                listOf(DpSize(180.dp, 160.dp)),
                null,
                ComponentName(context, WidgetProvider::class.java),
            ).collect { updates.send(it) }
        }
        try {
            updates.awaitTexts { "No device configured" in it && "Phone" !in it }
            config.value = WidgetConfig(showPhoneBattery = true)
            updates.awaitTexts { "Phone" in it && "42%" in it }
            context.sendBroadcast(battery(73, BatteryManager.BATTERY_STATUS_CHARGING))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            updates.awaitTexts { "73%" in it && "Charging" in it }
            config.value = WidgetConfig(showPhoneBattery = false)
            updates.awaitTexts { "Phone" !in it && "73%" !in it }
            Shadows.shadowOf(context as android.app.Application)
                .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size shouldBe 0
        } finally {
            session.cancel()
            appScope.cancel()
        }
    }

    private suspend fun Channel<RemoteViews>.awaitTexts(predicate: (List<String>) -> Boolean) {
        withTimeout(10_000) {
            while (true) {
                val views = receive().apply(context, FrameLayout(context)).flatten()
                val strings = views.filterIsInstance<TextView>().map { it.text.toString() } +
                    views.mapNotNull { it.contentDescription?.toString() }
                println("GLANCE_LIVE strings=$strings")
                if (predicate(strings)) break
            }
        }
    }

    private fun battery(level: Int, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, status)

    private fun View.flatten(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).flatten() } else emptyList()

    private class SessionHost(
        private val config: MutableStateFlow<WidgetConfig>,
        private val source: PhoneBatterySource,
    ) : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact

        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent {
                val inputs = rememberWidgetInputs(
                    configFlow = config,
                    initialConfig = { config.value },
                    proFlow = flowOf(true),
                    initialPro = { true },
                    labelFlow = { flowOf(null) },
                    initialLabel = { null },
                    deviceFlow = { flowOf(null) },
                    initialDevice = { null },
                    phoneFlow = source.states,
                    initialPhone = source::snapshot,
                )
                val state = WidgetRenderStateMapper.map(
                    context = context,
                    device = inputs.device,
                    theme = inputs.config?.theme ?: WidgetTheme(),
                    isPro = inputs.isPro,
                    hasConfiguredProfile = inputs.config?.profileId != null,
                    profileLabel = inputs.profileLabel,
                    phoneBattery = inputs.phoneBattery,
                )
                GlanceWidgetContent(state, context)
            }
        }
    }
}
