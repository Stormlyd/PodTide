package eu.darken.capod.monitor.core.companion

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.core.content.ContextCompat
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Thin wrapper around [CompanionDeviceManager].
 *
 * This is the only Android mechanism that lets the *system* bring our process back when a paired
 * pod shows up while we are dead: the user associates the pod once, we observe its presence, and
 * the platform binds [MonitorCompanionService] on every appearance. Everything else we use for
 * background detection (BLE scans, manifest Bluetooth receivers, a sticky service) dies with the
 * process.
 *
 * Requirements and limits, all enforced/handled here:
 * - API 33+ only (the association list API we rely on);
 * - a one-time system association dialog per device address; if the system wants us to show a
 *   confirmation UI we surface its [IntentSender] so an Activity can launch it;
 * - if the user revokes the association the observation silently stops, so the UI re-checks
 *   [associatedAddresses] instead of assuming success;
 * - OEM battery policy can still delay or drop the callback; this improves the odds, it does not
 *   guarantee a wake-up.
 */
@Singleton
class CompanionPresence @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profilesRepo: DeviceProfilesRepo,
) {
    private val cdm: CompanionDeviceManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(CompanionDeviceManager::class.java)
        } else {
            null
        }
    }

    private val _associatedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val associatedAddresses: StateFlow<Set<String>> = _associatedAddresses.asStateFlow()

    val isSupported: Boolean
        get() = cdm != null

    /**
     * Reads the current association list. Cheap enough to call on screen entry; results are cached
     * in [associatedAddresses] for the UI.
     */
    fun refreshAssociations(): Set<String> {
        val manager = cdm ?: run {
            _associatedAddresses.value = emptySet()
            return emptySet()
        }
        val associations: List<AssociationInfo> = runCatching { manager.myAssociations }
            .onFailure { log(TAG, WARN) { "Could not read companion associations: ${it.message}" } }
            .getOrDefault(emptyList())
        val addresses = associations
            .mapNotNull { associationAddress(it) }
            .toSet()
        log(TAG, VERBOSE) { "Associated companion addresses: $addresses" }
        _associatedAddresses.value = addresses
        return addresses
    }

    fun isAssociated(address: String): Boolean = address in refreshAssociations()

    /**
     * Shows the system association flow for [address]. [onPending] is called when the system wants
     * our Activity to launch its confirmation [IntentSender]; [onResult] reports the final state.
     */
    fun associate(
        address: String,
        onPending: (IntentSender) -> Unit,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val manager = cdm
        if (manager == null) {
            onResult(false, UNSUPPORTED)
            return
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setAddress(address)
                    .build()
            )
            .setSingleDevice(true)
            .build()

        log(TAG) { "Requesting companion association for $address" }
        runCatching {
            manager.associate(
                request,
                ContextCompat.getMainExecutor(context),
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        log(TAG) { "Companion association created for $address" }
                        refreshAssociations()
                        onResult(true, null)
                    }

                    override fun onFailure(error: CharSequence?) {
                        log(TAG, WARN) { "Companion association failed for $address: $error" }
                        onResult(false, error?.toString())
                    }

                    override fun onFailure(errorCode: Int, error: CharSequence?) {
                        log(TAG, WARN) { "Companion association failed ($errorCode) for $address: $error" }
                        onResult(false, error?.toString())
                    }

                    // API 33+: the system hands us the confirmation dialog to launch ourselves.
                    override fun onAssociationPending(intentSender: IntentSender) {
                        log(TAG) { "Companion association needs confirmation for $address" }
                        onPending(intentSender)
                    }

                    // Pre-33 flow: the chooser dialog is returned here instead.
                    @Deprecated("Deprecated in Java")
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        log(TAG) { "Companion chooser needs launching for $address" }
                        onPending(chooserLauncher)
                    }
                },
            )
        }.onFailure {
            log(TAG, WARN) { "Could not start companion association for $address: ${it.message}" }
            onResult(false, it.message)
        }
    }

    /**
     * Registers presence observation for every configured address that is currently associated.
     * Safe to call repeatedly: the platform treats it as idempotent per address.
     */
    suspend fun syncObservation() {
        val manager = cdm ?: return
        val associated = refreshAssociations()
        if (associated.isEmpty()) return

        val addresses = profilesRepo.profiles.first().mapNotNull { it.address }
        addresses.filter { it in associated }.forEach { address ->
            runCatching { manager.startObservingDevicePresence(address) }
                .onSuccess { log(TAG) { "Observing companion presence for $address" } }
                .onFailure { log(TAG, WARN) { "Could not observe presence for $address: ${it.message}" } }
        }
    }

    private fun associationAddress(info: AssociationInfo): String? = runCatching {
        // getDeviceMacAddress() replaces the deprecated getDevice() lookup and needs no
        // BLUETOOTH_CONNECT; both are guarded because the accessor is not present on every
        // supported API level.
        info.deviceMacAddress?.toString()
    }.getOrNull()

    companion object {
        private val TAG = logTag("Monitor", "CompanionPresence")
        const val UNSUPPORTED = "unsupported"
    }
}