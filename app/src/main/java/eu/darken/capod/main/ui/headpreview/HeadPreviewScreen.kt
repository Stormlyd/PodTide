package eu.darken.capod.main.ui.headpreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.capod.R
import eu.darken.capod.common.navigation.LocalNavigationController
import eu.darken.capod.common.settings.SettingsBaseItem
import eu.darken.capod.reaction.core.headgesture.HeadPreviewStatus
import eu.darken.capod.reaction.core.headgesture.HeadPreviewUiState

@Composable
fun HeadPreviewMenuItem(onClick: () -> Unit) {
    SettingsBaseItem(
        title = stringResource(R.string.head_preview_title),
        subtitle = stringResource(R.string.head_preview_entry_description),
        icon = Icons.TwoTone.Headphones,
        onClick = onClick,
        modifier = Modifier.testTag("head_preview_entry"),
        trailingContent = { Icon(Icons.AutoMirrored.TwoTone.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
fun HeadPreviewScreenHost(profileId: String, vm: HeadPreviewViewModel = hiltViewModel()) {
    val state by vm.controller.preview.collectAsStateWithLifecycle()
    val navigation = LocalNavigationController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profileId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.stop() }
    }
    HeadPreviewScreen(state, { vm.stop(); navigation?.up() }, { vm.start(profileId) }, vm::stop,
        vm::recalibrate, vm::resetCounts, vm::setSound)
}

@Composable
fun HeadPreviewScreen(
    state: HeadPreviewUiState,
    onNavigateUp: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRecalibrate: () -> Unit,
    onResetCounts: () -> Unit,
    onSoundChange: (Boolean) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.head_preview_title)) }, navigationIcon = {
            IconButton(onClick = onNavigateUp, modifier = Modifier.testTag("head_preview_back")) {
                Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = stringResource(R.string.head_preview_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.head_preview_experimental), style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.head_preview_safe), style = MaterialTheme.typography.bodySmall) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.requested) OutlinedButton(onClick = onStop, modifier = Modifier.testTag("head_preview_stop")) {
                        Text(stringResource(R.string.head_preview_stop))
                    } else Button(onClick = onStart, modifier = Modifier.testTag("head_preview_start")) {
                        Text(stringResource(R.string.head_preview_start))
                    }
                    OutlinedButton(onClick = onRecalibrate, enabled = state.requested) { Text(stringResource(R.string.head_preview_recalibrate)) }
                }
            }
            item { state.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) } }
            item { Text(stringResource(R.string.head_preview_progress, state.state.calibrationCount)) }
            item {
                val pose = state.state.pose
                if (state.state.status == HeadPreviewStatus.LIVE && pose != null) {
                    val marker = MaterialTheme.colorScheme.primary
                    val ring = MaterialTheme.colorScheme.outline
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.size(100.dp).testTag("head_preview_pose_indicator")) {
                            drawCircle(ring, radius = size.minDimension * .44f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                            drawCircle(marker, radius = 6.dp.toPx(), center = Offset(
                                size.width / 2f + (pose.yaw / 30f).coerceIn(-1f, 1f) * size.width * .38f,
                                size.height / 2f + (pose.pitch / 30f).coerceIn(-1f, 1f) * size.height * .38f,
                            ))
                        }
                        Text(stringResource(R.string.head_preview_pitch, pose.pitch))
                        Text(stringResource(R.string.head_preview_yaw, pose.yaw))
                    }
                } else Text(stringResource(R.string.head_preview_no_measurement))
            }
            item { state.state.motion?.let { Text(stringResource(R.string.head_preview_motion, it.horizontal, it.vertical)) } }
            item { state.lastSampleAgeMs?.let { Text(stringResource(R.string.head_preview_age, it)) } }
            item { Text(stringResource(R.string.head_preview_counts, state.nods, state.shakes)) }
            item { OutlinedButton(onClick = onResetCounts, enabled = state.requested) { Text(stringResource(R.string.head_preview_reset_counts)) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.head_preview_sound), modifier = Modifier.weight(1f))
                    Switch(state.soundEnabled, onCheckedChange = onSoundChange, enabled = state.requested)
                }
            }
        }
    }
}
