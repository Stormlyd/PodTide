package eu.darken.capod.common.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.TestTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class BleScannerDutyCycleTest : BaseTest() {

    private val settings = slot<ScanSettings>()
    private val callback = slot<ScanCallback>()
    private val platform = mockk<BluetoothLeScanner>(relaxed = true).apply {
        every { startScan(any(), capture(settings), capture(callback)) } returns Unit
    }

    private fun scanner(batchingSupported: Boolean = true): BleScanner {
        val adapter = mockk<BluetoothAdapter>(relaxed = true).apply {
            every { isOffloadedFilteringSupported } returns true
            every { isOffloadedScanBatchingSupported } returns batchingSupported
        }
        val manager = mockk<BluetoothManager2> {
            every { this@mockk.adapter } returns adapter
            every { scanner } returns platform
        }
        return BleScanner(ApplicationProvider.getApplicationContext(), manager, BleScanResultForwarder(), TestTimeSource())
    }

    @Test
    fun `balanced direct scan reports in one second batches and flushes every two seconds until cancelled`() = runTest {
        val collecting = scanner().scan(emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING, ScannerMode.BALANCED)
            .launchIn(backgroundScope)
        runCurrent()

        settings.captured.scanMode shouldBe ScanSettings.SCAN_MODE_BALANCED
        settings.captured.reportDelayMillis shouldBe 1000L
        verify(exactly = 1) { platform.startScan(any(), any<ScanSettings>(), any<ScanCallback>()) }
        verify(exactly = 1) { platform.flushPendingScanResults(callback.captured) }

        advanceTimeBy(1999); runCurrent()
        verify(exactly = 1) { platform.flushPendingScanResults(callback.captured) }
        advanceTimeBy(1); runCurrent()
        verify(exactly = 2) { platform.flushPendingScanResults(callback.captured) }

        collecting.cancel(); runCurrent()
        verify(exactly = 1) { platform.stopScan(callback.captured) }
        advanceTimeBy(5000); runCurrent()
        verify(exactly = 2) { platform.flushPendingScanResults(callback.captured) }
    }

    @Test
    fun `direct scan with offload batching disabled never manually flushes`() = runTest {
        val collecting = scanner().scan(
            emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING, ScannerMode.BALANCED,
            disableOffloadBatching = true,
        ).launchIn(backgroundScope)
        runCurrent()

        settings.captured.scanMode shouldBe ScanSettings.SCAN_MODE_BALANCED
        settings.captured.reportDelayMillis shouldBe 0L
        verify(exactly = 1) { platform.startScan(any(), any<ScanSettings>(), any<ScanCallback>()) }
        verify(exactly = 0) { platform.flushPendingScanResults(any()) }
        advanceTimeBy(6000); runCurrent()
        verify(exactly = 0) { platform.flushPendingScanResults(any()) }

        collecting.cancel(); runCurrent()
        verify(exactly = 1) { platform.stopScan(callback.captured) }
    }
}
