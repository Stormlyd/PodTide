package eu.darken.capod.main.ui.devicesettings.cards

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Headphones
import androidx.compose.material.icons.twotone.Phone
import androidx.compose.material.icons.twotone.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.capod.R
import eu.darken.capod.common.settings.SettingsSwitchItem
import eu.darken.capod.common.settings.SettingsListPreferenceItem
import eu.darken.capod.common.settings.SettingsInfoBox
import eu.darken.capod.reaction.core.headgesture.GestureAction
import eu.darken.capod.reaction.core.headgesture.HeadGestureSettings
import eu.darken.capod.reaction.core.headgesture.HeadGestureConfig
import javax.inject.Inject

@HiltViewModel
class HeadGestureViewModel @Inject constructor(val settings: HeadGestureSettings) : ViewModel()

@Composable
fun HeadGestureCard(config: HeadGestureConfig, onConfigChange: (HeadGestureConfig) -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onConfigChange(config)
    }
    fun requestCallPermissions() {
        val missing = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    val actions = listOf(GestureAction.NONE, GestureAction.PLAY_PAUSE, GestureAction.NEXT, GestureAction.PREVIOUS, GestureAction.VOLUME_UP, GestureAction.VOLUME_DOWN)
    Column {
        SettingsSwitchItem(Icons.TwoTone.Headphones, stringResource(R.string.head_gestures_title), stringResource(R.string.head_gestures_description), config.enabled, {
            onConfigChange(config.copy(enabled = it)); if (it && (config.calls || config.media)) requestCallPermissions()
        })
        if (config.enabled) {
            SettingsSwitchItem(Icons.TwoTone.Phone, stringResource(R.string.head_gestures_calls), stringResource(R.string.head_gestures_calls_description), config.calls, {
                onConfigChange(config.copy(calls = it)); if (it) requestCallPermissions()
            })
            SettingsSwitchItem(Icons.TwoTone.MusicNote, stringResource(R.string.head_gestures_media), stringResource(R.string.head_gestures_media_description), config.media, {
                onConfigChange(config.copy(media = it)); if (it) requestCallPermissions()
            })
            if (config.media) {
                SettingsListPreferenceItem(Icons.TwoTone.MusicNote, stringResource(R.string.head_gestures_nod), actions, config.nod,
                    { onConfigChange(config.copy(nod = it)) }, { gestureActionLabel(it) })
                SettingsListPreferenceItem(Icons.TwoTone.MusicNote, stringResource(R.string.head_gestures_shake), actions, config.shake,
                    { onConfigChange(config.copy(shake = it)) }, { gestureActionLabel(it) })
            }
            SettingsListPreferenceItem(Icons.TwoTone.Headphones, stringResource(R.string.head_gestures_sensitivity), listOf(600, 1_000, 1_600, 2_400), config.threshold,
                { onConfigChange(config.copy(threshold = it)) }, { it.toString() })
        }
    }
}

@Composable
private fun gestureActionLabel(action: GestureAction): String = stringResource(when (action) {
    GestureAction.NONE -> R.string.head_action_none
    GestureAction.PLAY_PAUSE -> R.string.head_action_play_pause
    GestureAction.NEXT -> R.string.head_action_next
    GestureAction.PREVIOUS -> R.string.head_action_previous
    GestureAction.VOLUME_UP -> R.string.head_action_volume_up
    GestureAction.VOLUME_DOWN -> R.string.head_action_volume_down
    else -> R.string.head_action_none
})
