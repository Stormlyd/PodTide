package eu.darken.capod.common.bluetooth.l2cap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates BR/EDR L2CAP sockets for connecting to devices like AirPods.
 *
 * Tries the public [android.bluetooth.BluetoothSocketSettings] API first (API 37+).
 * Falls back to the hidden `createInsecureL2capSocket` method via [HiddenApiBypass].
 */
@Singleton
@SuppressLint("MissingPermission")
class L2capSocketFactory @Inject constructor() {

    private val TAG = logTag("L2capSocketFactory")
    private val TYPE_L2CAP = 3

    /**
     * Creates an insecure BR/EDR L2CAP socket for the given [device] and [psm].
     *
     * The socket is created but not connected — call [BluetoothSocket.connect] separately.
     *
     * @throws IllegalArgumentException if [psm] is invalid
     * @throws SecurityException if BLUETOOTH_CONNECT permission is missing
     * @throws NoSuchMethodException if the hidden API is unavailable on this Android version
     * @throws IOException if socket creation fails at the transport level
     */
    fun createSocket(device: BluetoothDevice, psm: Int): BluetoothSocket {
        require(psm > 0) { "Invalid PSM: $psm" }

        // Strategy 1: Public API via BluetoothSocketSettings (API 37+)
        tryPublicApi(device, psm, secure = false)?.let { socket ->
            log(TAG, VERBOSE) { "Socket created via public BluetoothSocketSettings API" }
            return socket
        }

        // Strategy 2: Hidden API via reflection + bypass
        log(TAG, VERBOSE) { "Public API unavailable, using hidden API bypass" }
        return createViaHiddenApi(device, psm, secure = false)
    }

    /**
     * Creates an authenticated/encrypted BR/EDR L2CAP socket as a fallback when an insecure L2CAP
     * connection is rejected by the OEM Bluetooth stack or firmware for a bonded device.
     */
    fun createSecureSocket(device: BluetoothDevice, psm: Int): BluetoothSocket? {
        require(psm > 0) { "Invalid PSM: $psm" }
        tryPublicApi(device, psm, secure = true)?.let { socket ->
            log(TAG, VERBOSE) { "Secure socket created via public BluetoothSocketSettings API" }
            return socket
        }
        return runCatching {
            createViaHiddenApi(device, psm, secure = true)
        }.onFailure { e ->
            log(TAG, VERBOSE) { "Secure L2CAP socket creation unavailable: ${e.message}" }
        }.getOrNull()
    }

    private fun tryPublicApi(device: BluetoothDevice, psm: Int, secure: Boolean = false): BluetoothSocket? {
        return try {
            val settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings")
            val builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings\$Builder")

            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setSocketType", Int::class.javaPrimitiveType).invoke(builder, TYPE_L2CAP)
            builderClass.getMethod("setL2capPsm", Int::class.javaPrimitiveType).invoke(builder, psm)
            builderClass.getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType).invoke(builder, secure)
            builderClass.getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType).invoke(builder, secure)
            val settings = builderClass.getMethod("build").invoke(builder)

            val createMethod = BluetoothDevice::class.java.getMethod("createUsingSocketSettings", settingsClass)
            createMethod.invoke(device, settings) as BluetoothSocket
        } catch (e: ClassNotFoundException) {
            log(TAG, VERBOSE) { "BluetoothSocketSettings not available (pre-API 37)" }
            null
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            when (cause) {
                is IllegalArgumentException -> {
                    log(TAG, VERBOSE) { "BluetoothSocketSettings does not support TYPE_L2CAP: ${cause.message}" }
                    null
                }
                is SecurityException -> throw cause
                else -> {
                    log(TAG, VERBOSE) { "BluetoothSocketSettings failed: ${cause::class.simpleName}: ${cause.message}" }
                    null
                }
            }
        }
    }

    // The BR/EDR fallback explicitly installs VMRuntime exemptions before reflection. Its
    // availability is still checked at runtime and remains part of physical-device validation.
    @SuppressLint("SoonBlockedPrivateApi")
    private fun createViaHiddenApi(device: BluetoothDevice, psm: Int, secure: Boolean = false): BluetoothSocket {
        HiddenApiBypass.setExemptions("Landroid/bluetooth/")

        val methodName = if (secure) "createL2capSocket" else "createInsecureL2capSocket"
        return try {
            val method = BluetoothDevice::class.java.getDeclaredMethod(
                methodName,
                Int::class.javaPrimitiveType
            )
            method.invoke(device, psm) as BluetoothSocket
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: IOException("$methodName failed", e)
        } catch (e: NoSuchMethodException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: ReflectiveOperationException) {
            throw IOException("Failed to create L2CAP socket via hidden API", e)
        }
    }
}
