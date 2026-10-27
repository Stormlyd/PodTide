package eu.darken.capod.main.ui.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
@Suppress("DEPRECATION")
class PhoneBatterySourceTest : BaseTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun battery(level: Int, scale: Int = 100, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test fun `sticky snapshot validates capacity and charging independently`() {
        val source = PhoneBatterySource(context, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        context.sendStickyBroadcast(battery(37, 50, BatteryManager.BATTERY_STATUS_CHARGING))
        source.snapshot() shouldBe PhoneBatteryState(74, true)
        context.sendStickyBroadcast(battery(0))
        source.snapshot() shouldBe PhoneBatteryState(0, false)
        for (invalid in listOf(battery(-1), battery(101), battery(3, 0), battery(3).apply { removeExtra(BatteryManager.EXTRA_LEVEL) })) {
            context.sendStickyBroadcast(invalid)
            source.snapshot()?.percent shouldBe null
        }
        context.sendStickyBroadcast(battery(42).putExtra(BatteryManager.EXTRA_PRESENT, false))
        source.snapshot()?.percent shouldBe null
        context.sendStickyBroadcast(battery(-1, status = BatteryManager.BATTERY_STATUS_CHARGING))
        source.snapshot() shouldBe PhoneBatteryState(null, true)
        context.sendStickyBroadcast(battery(100, 100, BatteryManager.BATTERY_STATUS_FULL))
        source.snapshot() shouldBe PhoneBatteryState(100, false)
        context.sendStickyBroadcast(battery(Int.MAX_VALUE, Int.MAX_VALUE))
        source.snapshot() shouldBe PhoneBatteryState(100, false)
    }

    @Test fun `running receiver follows charge change and stops on cancellation`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val source = PhoneBatterySource(context, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        val seen = mutableListOf<PhoneBatteryState>()
        val job = launch(Dispatchers.Unconfined) { source.states.collect { seen += it } }
        val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED)
        val shadow = Shadows.shadowOf(context as android.app.Application)
        shadow.getReceiversForIntent(batteryIntent).size shouldBe 1
        seen.last() shouldBe PhoneBatteryState(42, false)
        context.sendBroadcast(Intent("irrelevant"))
        seen.last() shouldBe PhoneBatteryState(42, false)
        context.sendBroadcast(battery(42, status = BatteryManager.BATTERY_STATUS_CHARGING))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        seen.last() shouldBe PhoneBatteryState(42, true)
        val second = launch(Dispatchers.Unconfined) { source.states.collect {} }
        shadow.getReceiversForIntent(batteryIntent).size shouldBe 1
        job.cancelAndJoin()
        shadow.getReceiversForIntent(batteryIntent).size shouldBe 1
        second.cancelAndJoin()
        shadow.getReceiversForIntent(batteryIntent).size shouldBe 0
    }

}
