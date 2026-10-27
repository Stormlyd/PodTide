package eu.darken.capod.main.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ProgressBar
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.Locale

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class PhoneBatteryGlanceContentTest : BaseTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `actual glance content displays phone independently across layouts and states`() = runBlocking {
        val phone = PhoneBatteryState(74, true)
        for (layout in BatteryLayout.entries) {
            val size = when (layout) {
                BatteryLayout.TINY_COLUMN -> DpSize(50.dp, 160.dp)
                BatteryLayout.NARROW -> DpSize(180.dp, 160.dp)
                BatteryLayout.WIDE -> DpSize(360.dp, 160.dp)
            }
            val states = listOf(
                WidgetRenderState.previewDualPod(layout = layout).copy(phoneBattery = phone),
                WidgetRenderState.previewSinglePod(layout = layout).copy(phoneBattery = phone),
                WidgetRenderStateMapper.map(
                    context, null, WidgetTheme(), true, false, null,
                    layout = layout, phoneBattery = phone,
                ),
            )
            for (state in states) {
                val remote = GlanceRemoteViews().compose(
                    context = context,
                    size = size,
                ) {
                    GlanceWidgetContent(state, context)
                }.remoteViews
                val view = remote.apply(context, FrameLayout(context))
                val texts = view.flatten().filterIsInstance<TextView>().map { it.text.toString() }
                (texts.any { it.contains("74%") }) shouldBe true
                if (layout != BatteryLayout.TINY_COLUMN) {
                    (texts.any { it == context.getString(eu.darken.capod.R.string.widget_phone_battery_label) }) shouldBe true
                }
                (view.flatten().any { it.contentDescription == context.getString(eu.darken.capod.R.string.widget_phone_battery_label) }) shouldBe true
                (view.flatten().any { it.contentDescription == context.getString(eu.darken.capod.R.string.widget_phone_charging_description) }) shouldBe true
                (view.flatten().any { it is ProgressBar }) shouldBe true
                if (state is WidgetRenderState.Message) {
                    (texts.any { it == state.primaryText }) shouldBe true
                }
                println("GLANCE_REMOTE layout=$layout state=${state.javaClass.simpleName} texts=$texts")
            }
        }
    }

    @Test fun `unknown phone capacity is shown without a fabricated percentage`() = runBlocking {
        val state = WidgetRenderStateMapper.map(
            context, null, WidgetTheme(), true, false, null,
            phoneBattery = PhoneBatteryState(null, true),
        )
        val remote = GlanceRemoteViews().compose(context, DpSize(200.dp, 100.dp)) {
            GlanceWidgetContent(state, context)
        }.remoteViews
        val texts = remote.apply(context, FrameLayout(context)).flatten()
            .filterIsInstance<TextView>().map { it.text.toString() }
        texts.contains("—") shouldBe true
        texts.any { it.contains("%") } shouldBe false
    }

    @Test fun `hidden phone leaves actual glance tree without phone label`() = runBlocking {
        val state = WidgetRenderState.previewDualPod()
        val remote = GlanceRemoteViews().compose(context, DpSize(200.dp, 100.dp)) {
            GlanceWidgetContent(state, context)
        }.remoteViews
        val view = remote.apply(context, FrameLayout(context))
        view.flatten().any {
            it.contentDescription == context.getString(eu.darken.capod.R.string.widget_phone_battery_label)
        } shouldBe false
    }

    @Test fun `actual glance phone uses custom text color and chinese tiny description`() = runBlocking {
        val theme = WidgetTheme(
            backgroundColor = 0xFF123456.toInt(),
            foregroundColor = 0xFFABCDEF.toInt(),
        )
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val zhContext = context.createConfigurationContext(configuration)
        val state = WidgetRenderStateMapper.map(
            zhContext, null, theme, true, false, null,
            layout = BatteryLayout.TINY_COLUMN,
            phoneBattery = PhoneBatteryState(0, false),
        )
        val remote = GlanceRemoteViews().compose(zhContext, DpSize(50.dp, 160.dp)) {
            GlanceWidgetContent(state, zhContext)
        }.remoteViews
        val view = remote.apply(zhContext, FrameLayout(zhContext))
        println("GLANCE_ZH texts=" + view.flatten().filterIsInstance<TextView>().map { it.text.toString() })
        view.flatten().any { it.contentDescription == "手机" } shouldBe true
        view.flatten().filterIsInstance<TextView>().first { it.text.toString() == "0%" }
            .currentTextColor shouldBe 0xFFABCDEF.toInt()
    }

    private fun View.flatten(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).flatten() } else emptyList()
}
