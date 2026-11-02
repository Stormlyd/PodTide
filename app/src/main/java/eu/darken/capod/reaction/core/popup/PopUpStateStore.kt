package eu.darken.capod.reaction.core.popup

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the minimum popup state that a process restart would otherwise lose: which configured
 * device opened the current popup round, and when. No keys and no raw broadcasts are stored.
 *
 * Timestamps are [TimeSource.elapsedRealtime] values — monotonic within one boot and immune to
 * wall-clock corrections — paired with the system boot count, so a record is never compared
 * against a clock from a different boot. Records also expire after [RESTORE_WINDOW]: persistence
 * assists de-duplication, it must not permanently suppress genuinely new events.
 */
@Singleton
class PopUpStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeSource: TimeSource,
) {

    private val Context.dataStore by preferencesDataStore(name = "popup_state")

    private val dataStore: DataStore<Preferences> get() = context.dataStore

    data class Round(
        val profileId: String,
        val shownAtElapsedMillis: Long,
        val bootCount: Int,
    )

    suspend fun saveRound(profileId: String, shownAtElapsedMillis: Long) {
        try {
            dataStore.edit { prefs ->
                prefs[PROFILE_KEY] = profileId
                prefs[SHOWN_AT_KEY] = shownAtElapsedMillis
                prefs[BOOT_COUNT_KEY] = currentBootCount()
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to persist popup round: ${e.message}" }
        }
    }

    /** Removes the stored round. A [profileId] only clears a record that still belongs to it. */
    suspend fun clearRound(profileId: String? = null) {
        try {
            dataStore.edit { prefs ->
                if (profileId == null || prefs[PROFILE_KEY] == profileId) prefs.clear()
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to clear popup round: ${e.message}" }
        }
    }

    /**
     * The persisted round, but only while it can still be trusted: same boot and recent enough.
     * Anything else returns null so the caller starts from a clean slate instead of suppressing
     * events based on stale information.
     */
    suspend fun loadRestorableRound(): Round? {
        val prefs = try {
            dataStore.data.first()
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to read popup state: ${e.message}" }
            return null
        }
        val profileId = prefs[PROFILE_KEY] ?: return null
        val shownAt = prefs[SHOWN_AT_KEY] ?: return null
        val bootCount = prefs[BOOT_COUNT_KEY] ?: return null

        val currentBoot = currentBootCount()
        if (bootCount != currentBoot) {
            log(TAG) { "Stored popup round is from boot $bootCount, current boot is $currentBoot, discarding." }
            return null
        }
        val age = timeSource.elapsedRealtime() - shownAt
        if (age < 0 || age > RESTORE_WINDOW.toMillis()) {
            log(TAG) { "Stored popup round is too old (${age}ms), discarding." }
            return null
        }
        return Round(profileId, shownAt, bootCount)
    }

    private fun currentBootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        log(TAG, WARN) { "Boot count unavailable: ${e.message}" }
        0
    }

    companion object {
        private val TAG = logTag("Reaction", "PopUp", "State")

        /** How long a persisted round may still de-duplicate popups after a process restart. */
        internal val RESTORE_WINDOW: Duration = Duration.ofMinutes(5)

        private val PROFILE_KEY = stringPreferencesKey("popup.round.profile_id")
        private val SHOWN_AT_KEY = longPreferencesKey("popup.round.shown_at_elapsed")
        private val BOOT_COUNT_KEY = intPreferencesKey("popup.round.boot_count")
    }
}
