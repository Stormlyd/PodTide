package eu.darken.capod.main.ui.widget

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
@Suppress("DEPRECATION")
class PhoneBatteryWidgetUpdaterTest : BaseTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun battery(level: Int, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, status)

    private fun receiverCount(): Int = Shadows.shadowOf(context as android.app.Application)
        .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size

    @Test fun `two installed enabled ids share observation and last removal releases receiver`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = WidgetSettings(context, json)
            val source = PhoneBatterySource(context, scope)
            val manager = mockk<WidgetManager>()
            every { manager.installedBatteryWidgetIds() } returns setOf(10, 11)
            coEvery { manager.refreshBatteryWidgets(any()) } returns Unit
            val updater = PhoneBatteryWidgetUpdater(settings, source, manager, scope)
            updater.setup()
            updater.setup()
            Shadows.shadowOf(context as android.app.Application)
                .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size shouldBe 0
            settings.saveWidgetConfig(10, WidgetConfig(showPhoneBattery = true))
            settings.saveWidgetConfig(11, WidgetConfig(showPhoneBattery = true))
            withTimeout(10_000) {
                while (Shadows.shadowOf(context as android.app.Application)
                        .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 1) delay(10)
            }
            context.sendBroadcast(battery(42, BatteryManager.BATTERY_STATUS_CHARGING))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            coVerify(atLeast = 1) { manager.refreshBatteryWidgets(setOf(10, 11)) }
            settings.removeWidget(10)
            withTimeout(10_000) {
                while (Shadows.shadowOf(context as android.app.Application)
                        .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 1) delay(10)
            }
            context.sendBroadcast(battery(73))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            coVerify(atLeast = 1) { manager.refreshBatteryWidgets(setOf(11)) }
            settings.removeWidget(11)
            withTimeout(10_000) {
                while (Shadows.shadowOf(context as android.app.Application)
                        .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 0) delay(10)
            }
            Shadows.shadowOf(context as android.app.Application)
                .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size shouldBe 0
        } finally {
            scope.cancel()
        }
    }

    @Test fun `recreated source and updater read fresh sticky value from saved config`() = runBlocking {
        val settings = WidgetSettings(context, json)
        settings.saveWidgetConfig(20, WidgetConfig(showPhoneBattery = true))
        context.sendStickyBroadcast(battery(42))
        val manager = mockk<WidgetManager>()
        every { manager.installedBatteryWidgetIds() } returns setOf(20)
        coEvery { manager.refreshBatteryWidgets(any()) } returns Unit
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstSource = PhoneBatterySource(context, firstScope)
        PhoneBatteryWidgetUpdater(settings, firstSource, manager, firstScope).setup()
        withTimeout(10_000) {
            while (Shadows.shadowOf(context as android.app.Application)
                    .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 1) delay(10)
        }
        firstSource.snapshot() shouldBe PhoneBatteryState(42, false)
        firstScope.cancel()
        withTimeout(10_000) {
            while (Shadows.shadowOf(context as android.app.Application)
                    .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 0) delay(10)
        }
        context.sendStickyBroadcast(battery(73))
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val secondSource = PhoneBatterySource(context, secondScope)
            secondSource.snapshot() shouldBe PhoneBatteryState(73, false)
            PhoneBatteryWidgetUpdater(settings, secondSource, manager, secondScope).setup()
            withTimeout(10_000) {
                while (Shadows.shadowOf(context as android.app.Application)
                        .getReceiversForIntent(Intent(Intent.ACTION_BATTERY_CHANGED)).size != 1) delay(10)
            }
            coVerify(atLeast = 2) { manager.refreshBatteryWidgets(setOf(20)) }
        } finally {
            secondScope.cancel()
        }
    }

    @Test fun `failed refresh does not cancel observation of later battery events`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val settings = WidgetSettings(context, json)
        settings.saveWidgetConfig(30, WidgetConfig(showPhoneBattery = true))
        val manager = mockk<WidgetManager>()
        every { manager.installedBatteryWidgetIds() } returns setOf(30)
        var attempts = 0
        coEvery { manager.refreshBatteryWidgets(setOf(30)) } coAnswers {
            attempts++
            if (attempts == 1) throw java.io.IOException("launcher rejected update")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            withTimeout(10_000) { while (attempts < 1) delay(10) }
            context.sendBroadcast(battery(43))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            withTimeout(10_000) { while (attempts < 2) delay(10) }
            scope.isActive shouldBe true
        } finally {
            scope.cancel()
        }
    }

    @Test fun `installed id lookup failure retries without a settings change`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val settings = WidgetSettings(context, json)
        settings.saveWidgetConfig(40, WidgetConfig(showPhoneBattery = true))
        val manager = mockk<WidgetManager>()
        var lookups = 0
        every { manager.installedBatteryWidgetIds() } answers {
            if (++lookups == 1) throw java.io.IOException("widget service temporarily unavailable")
            setOf(40)
        }
        coEvery { manager.refreshBatteryWidgets(setOf(40)) } returns Unit
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            withTimeout(4_000) { while (lookups < 2 || receiverCount() != 1) delay(10) }
            coVerify(atLeast = 1) { manager.refreshBatteryWidgets(setOf(40)) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `settings flow failure retries without a new saved configuration`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val settings = mockk<WidgetSettings>()
        var subscriptions = 0
        every { settings.enabledPhoneWidgetIds } returns flow {
            if (++subscriptions == 1) throw java.io.IOException("DataStore read temporarily unavailable")
            emit(setOf(41))
            awaitCancellation()
        }
        val manager = mockk<WidgetManager>()
        every { manager.installedBatteryWidgetIds() } returns setOf(41)
        coEvery { manager.refreshBatteryWidgets(setOf(41)) } returns Unit
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            withTimeout(4_000) { while (subscriptions < 2 || receiverCount() != 1) delay(10) }
            coVerify(atLeast = 1) { manager.refreshBatteryWidgets(setOf(41)) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `settings read failure releases the previous receiver before retry`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val failRead = CompletableDeferred<Unit>()
        val settings = mockk<WidgetSettings>()
        var subscriptions = 0
        every { settings.enabledPhoneWidgetIds } returns flow {
            if (++subscriptions == 1) {
                emit(setOf(42))
                failRead.await()
                throw java.io.IOException("DataStore read failed")
            }
            emit(emptySet())
            awaitCancellation()
        }
        val manager = mockk<WidgetManager>()
        every { manager.installedBatteryWidgetIds() } returns setOf(42)
        coEvery { manager.refreshBatteryWidgets(any()) } returns Unit
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            withTimeout(4_000) { while (receiverCount() != 1) delay(10) }
            failRead.complete(Unit)
            withTimeout(4_000) { while (receiverCount() != 0) delay(10) }
            withTimeout(4_000) { while (subscriptions < 2) delay(10) }
            receiverCount() shouldBe 0
        } finally {
            scope.cancel()
        }
    }

    @Test fun `disable delete and scope cancellation stop lookup retries without a receiver`() = runBlocking {
        val settings = WidgetSettings(context, json)
        val manager = mockk<WidgetManager>()
        val lookups = AtomicInteger()
        every { manager.installedBatteryWidgetIds() } answers {
            lookups.incrementAndGet()
            throw java.io.IOException("widget service unavailable")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            settings.saveWidgetConfig(50, WidgetConfig(showPhoneBattery = true))
            withTimeout(4_000) { while (lookups.get() < 1) delay(10) }
            receiverCount() shouldBe 0
            settings.saveWidgetConfig(50, WidgetConfig(showPhoneBattery = false))
            delay(1_300)
            lookups.get() shouldBe 1
            receiverCount() shouldBe 0

            settings.saveWidgetConfig(51, WidgetConfig(showPhoneBattery = true))
            withTimeout(4_000) { while (lookups.get() < 2) delay(10) }
            settings.removeWidget(51)
            delay(1_300)
            lookups.get() shouldBe 2
            receiverCount() shouldBe 0

            settings.saveWidgetConfig(52, WidgetConfig(showPhoneBattery = true))
            withTimeout(4_000) { while (lookups.get() < 3) delay(10) }
            scope.cancel()
            delay(1_300)
            lookups.get() shouldBe 3
            receiverCount() shouldBe 0
        } finally {
            scope.cancel()
        }
    }

    @Test fun `enabled saved widget with no installed battery id never subscribes`() = runBlocking {
        context.sendStickyBroadcast(battery(42))
        val settings = WidgetSettings(context, json)
        settings.saveWidgetConfig(60, WidgetConfig(showPhoneBattery = true))
        val manager = mockk<WidgetManager>()
        val lookups = AtomicInteger()
        every { manager.installedBatteryWidgetIds() } answers {
            lookups.incrementAndGet()
            emptySet()
        }
        coEvery { manager.refreshBatteryWidgets(any()) } returns Unit
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            PhoneBatteryWidgetUpdater(settings, PhoneBatterySource(context, scope), manager, scope).setup()
            withTimeout(4_000) { while (lookups.get() < 1) delay(10) }
            receiverCount() shouldBe 0
            context.sendBroadcast(battery(43))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            coVerify(exactly = 0) { manager.refreshBatteryWidgets(any()) }
            settings.removeWidget(60)
            receiverCount() shouldBe 0
        } finally {
            scope.cancel()
        }
    }
}
