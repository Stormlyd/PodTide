package eu.darken.capod.monitor.core.ble

import android.app.Application
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.monitor.core.worker.MonitoringState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The wake scan is an "opportunity", and it must stay one: registering twice runs one platform
 * scan, a live session drops the registration, and switching monitoring off removes it for good.
 * None of these paths may retry, or a rejected start would become a restart loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class MonitorWakeScanTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun TestScope.newScanner(errorCode: Int = 0): BluetoothLeScanner =
        mockk<BluetoothLeScanner>(relaxed = true) {
            every { startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<PendingIntent>()) } returns errorCode
        }

    private class Fixture(val wakeScan: MonitorWakeScan, val bluetoothManager: BluetoothManager2)

    private fun TestScope.fixture(
        monitoringEnabled: Boolean = true,
        scanner: BluetoothLeScanner? = newScanner(),
    ): Fixture {
        val bluetoothManager = mockk<BluetoothManager2> {
            every { this@mockk.scanner } returns scanner
        }
        val monitoringState = mockk<MonitoringState> {
            coEvery { isEnabled() } returns monitoringEnabled
        }
        val wakeScan = MonitorWakeScan(
            context = context,
            bluetoothManager = bluetoothManager,
            monitoringState = monitoringState,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        return Fixture(wakeScan, bluetoothManager)
    }

    @Test
    fun `registering twice runs a single platform scan`() = runTest {
        val scanner = newScanner()
        val wake = fixture(scanner = scanner).wakeScan

        wake.ensureRegistered()
        wake.ensureRegistered()
        runCurrent()

        verify(exactly = 1) {
            scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<PendingIntent>())
        }
    }

    @Test
    fun `a running monitor session suppresses the wake scan and releasing it restores one registration`() = runTest {
        val scanner = newScanner()
        val wake = fixture(scanner = scanner).wakeScan

        wake.suppressForSession()
        wake.ensureRegistered()
        runCurrent()
        verify(exactly = 0) {
            scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<PendingIntent>())
        }

        wake.releaseFromSession()
        wake.releaseFromSession()
        runCurrent()
        verify(exactly = 1) {
            scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<PendingIntent>())
        }
    }

    @Test
    fun `switching monitoring off removes the registration and blocks a new one`() = runTest {
        val scanner = newScanner()
        val wake = fixture(monitoringEnabled = false, scanner = scanner).wakeScan

        wake.unregister()
        wake.releaseFromSession()
        runCurrent()

        verify(exactly = 0) {
            scanner.startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<PendingIntent>())
        }
    }

    @Test
    fun `a platform error code is not treated as a successful registration`() = runTest {
        val scanner = newScanner(errorCode = ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        val wake = fixture(scanner = scanner).wakeScan

        wake.ensureRegistered()
        runCurrent()
        wake.suppressForSession()
        runCurrent()

        // Nothing was registered, so there is nothing to stop either.
        verify(exactly = 0) { scanner.stopScan(any<PendingIntent>()) }
    }

    /** Bluetooth off or unavailable: no registration now, and crucially no retry loop. */
    @Test
    fun `no scanner means no registration and no crash`() = runTest {
        val fixture = fixture(scanner = null)

        fixture.wakeScan.ensureRegistered()
        fixture.wakeScan.releaseFromSession()
        runCurrent()

        // One attempt per request and nothing else: no retry loop, and it never crashes.
        verify(exactly = 2) { fixture.bluetoothManager.scanner }
    }
}