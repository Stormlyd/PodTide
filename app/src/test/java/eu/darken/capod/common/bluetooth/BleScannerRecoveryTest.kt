package eu.darken.capod.common.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
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
class BleScannerRecoveryTest : BaseTest() {
    private val callback = slot<ScanCallback>()
    private val platform = mockk<BluetoothLeScanner>(relaxed = true).apply {
        every { startScan(any(), any(), capture(callback)) } returns Unit
    }
    private val forwarder = BleScanResultForwarder()
    private fun scanner(): BleScanner {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val manager = mockk<BluetoothManager2> {
            every { this@mockk.adapter } returns adapter
            every { scanner } returns platform
        }
        return BleScanner(ApplicationProvider.getApplicationContext(), manager, forwarder, TestTimeSource())
    }

    @Test fun `direct scan failure closes the dead scan and releases its callback`() = runTest {
        var failure: Throwable? = null
        scanner().scan(emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING)
            .catch { failure = it }.launchIn(backgroundScope)
        runCurrent()
        callback.captured.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        runCurrent()
        failure.shouldNotBeNull()
        verify(exactly = 1) { platform.stopScan(callback.captured) }
    }

    @Test fun `intent start rejection reaches the owner instead of waiting forever`() = runTest {
        every { platform.startScan(any(), any(), any<android.app.PendingIntent>()) } returns 6
        var failure: Throwable? = null
        val collecting = scanner().scan(emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING, disableDirectScanCallback = true)
            .catch { failure = it }.launchIn(backgroundScope)
        runCurrent()
        failure.shouldNotBeNull()
        collecting.isCompleted shouldBe true
    }

    @Test fun `closing intent scanner releases the original registration token`() = runTest {
        val started = slot<android.app.PendingIntent>()
        val stopped = slot<android.app.PendingIntent>()
        every { platform.startScan(any(), any(), capture(started)) } returns 0
        every { platform.stopScan(capture(stopped)) } returns Unit
        val collecting = scanner().scan(emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING, disableDirectScanCallback = true)
            .launchIn(backgroundScope)
        runCurrent()
        collecting.cancel()
        runCurrent()
        stopped.captured.isImmutable shouldBe started.captured.isImmutable
        stopped.captured shouldBe started.captured
    }

    @Test fun `intent callback error reaches the active scanner`() = runTest {
        var failure: Throwable? = null
        scanner().scan(emptySet(), ScanFilterPolicy.PROXIMITY_PAIRING, disableDirectScanCallback = true)
            .catch { failure = it }.launchIn(backgroundScope)
        runCurrent()
        BleScanResultReceiver().apply {
            appScope = backgroundScope
            scanResultForwarder = forwarder
            // Dependencies are assigned above; isolate receiver behavior from the Hilt app graph.
            javaClass.superclass.getDeclaredField("injected").apply { isAccessible = true }.setBoolean(this, true)
        }.onReceive(ApplicationProvider.getApplicationContext(), Intent(BleScanResultReceiver.ACTION)
            .putExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, ScanCallback.SCAN_FAILED_INTERNAL_ERROR))
        runCurrent()
        failure.shouldNotBeNull()
    }
}
