package eu.darken.capod.reaction.ui.popup

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import kotlin.math.roundToInt

/** Compact native status card. Only observed audio connection and individual batteries are shown. */
@Composable
fun TopPopUpContent(device: PodDevice, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val proArtwork = device.model in setOf(PodModel.AIRPODS_PRO, PodModel.AIRPODS_PRO2, PodModel.AIRPODS_PRO2_USBC, PodModel.AIRPODS_PRO3)
    Surface(modifier.widthIn(max = 360.dp), shape = RoundedCornerShape(28.dp),
        color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .45f)), shadowElevation = 6.dp) {
        Row(Modifier.background(Brush.verticalGradient(listOf(colors.surface.copy(alpha = .98f), colors.surfaceContainer.copy(alpha = .96f))))
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            // The complete content can scroll on short displays; the close affordance remains fixed.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(if (proArtwork) R.drawable.popup_pro_hero else device.iconRes), null,
                        Modifier.size(if (proArtwork) 56.dp else 44.dp), contentScale = ContentScale.Fit)
                    Text(device.label?.takeIf { it.isNotBlank() } ?: device.getLabel(LocalContext.current),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(if (device.isSystemConnected) R.string.popup_audio_connected else R.string.popup_device_detected),
                    Modifier.padding(top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when {
                        device.hasDualPods -> {
                            TopBattery(stringResource(R.string.popup_left), device.batteryLeft, device.isLeftPodCharging == true)
                            TopBattery(stringResource(R.string.popup_right), device.batteryRight, device.isRightPodCharging == true)
                            if (device.hasCase) TopBattery(stringResource(R.string.popup_case), device.batteryCase, device.isCaseCharging == true)
                        }
                        device.model != PodModel.UNKNOWN -> TopBattery(stringResource(R.string.popup_headset), device.batteryHeadset, device.isHeadsetBeingCharged == true)
                        else -> Text(stringResource(R.string.popup_unknown_device), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.general_close_action), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TopBattery(label: String, battery: Float, charging: Boolean) {
    val valid = battery.isFinite() && battery in 0f..1f
    val value = if (valid) "${(battery * 100).roundToInt()}%" else stringResource(R.string.popup_battery_missing)
    val chargingText = if (charging) " · ${stringResource(R.string.popup_charging)}" else ""
    Text("$label: $value$chargingText", style = MaterialTheme.typography.bodyMedium,
        color = if (valid && battery <= .2f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}
