package eu.darken.capod.reaction.ui.popup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import kotlin.math.roundToInt

/** The production overlay card. Its surface is translucent; it does not sample or blur other apps. */
@Composable
fun PopUpContent(device: PodDevice, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(30.dp)
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        BoxWithConstraints(modifier.padding(8.dp)) {
            val compact = maxHeight < 430.dp
            Column(
                Modifier.fillMaxWidth().shadow(10.dp, shape).clip(shape)
                    .background(Brush.verticalGradient(listOf(colors.surface.copy(alpha = .98f), colors.surfaceContainer.copy(alpha = .96f))))
                    .border(1.dp, colors.outlineVariant.copy(alpha = .45f), shape)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(device.label?.takeIf { it.isNotBlank() } ?: device.getLabel(LocalContext.current),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(stringResource(if (device.isSystemConnected) R.string.popup_audio_connected else R.string.popup_device_detected),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(32.dp).background(colors.onSurface.copy(alpha = .06f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.general_close_action), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    PopupArtwork(device, compact)
                    when {
                        device.hasDualPods -> {
                            // At accessibility font sizes use roomy rows, keeping labels and percentages intact.
                            if (LocalDensity.current.fontScale > 1.5f) {
                                BatteryRing(stringResource(R.string.popup_left), device.batteryLeft, device.isLeftPodCharging == true, device.leftPodIcon, horizontal = true)
                                if (device.hasCase) BatteryRing(stringResource(R.string.popup_case), device.batteryCase, device.isCaseCharging == true, device.caseIcon, horizontal = true)
                                BatteryRing(stringResource(R.string.popup_right), device.batteryRight, device.isRightPodCharging == true, device.rightPodIcon, horizontal = true)
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    BatteryRing(stringResource(R.string.popup_left), device.batteryLeft, device.isLeftPodCharging == true, device.leftPodIcon, Modifier.weight(1f))
                                    if (device.hasCase) BatteryRing(stringResource(R.string.popup_case), device.batteryCase, device.isCaseCharging == true, device.caseIcon, Modifier.weight(1f))
                                    BatteryRing(stringResource(R.string.popup_right), device.batteryRight, device.isRightPodCharging == true, device.rightPodIcon, Modifier.weight(1f))
                                }
                            }
                        }
                        device.model != PodModel.UNKNOWN -> BatteryRing(stringResource(R.string.popup_headset), device.batteryHeadset, device.isHeadsetBeingCharged == true, device.iconRes)
                        else -> Text(stringResource(R.string.popup_unknown_device), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PopupArtwork(device: PodDevice, compact: Boolean) {
    val pro = device.model in setOf(PodModel.AIRPODS_PRO, PodModel.AIRPODS_PRO2, PodModel.AIRPODS_PRO2_USBC, PodModel.AIRPODS_PRO3)
    val height = if (compact) 100.dp else 182.dp
    if (pro) {
        Image(painterResource(R.drawable.popup_pro_hero), null, Modifier.fillMaxWidth().height(height), contentScale = ContentScale.Fit)
    } else if (device.hasDualPods) {
        Row(Modifier.fillMaxWidth().height(height).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            Image(painterResource(device.leftPodIcon), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Fit)
            if (device.hasCase) Image(painterResource(device.caseIcon), null, Modifier.weight(1.3f).fillMaxHeight(), contentScale = ContentScale.Fit)
            Image(painterResource(device.rightPodIcon), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Fit)
        }
    } else {
        Image(painterResource(device.iconRes), null, Modifier.fillMaxWidth().height(height).padding(20.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun BatteryRing(label: String, battery: Float, charging: Boolean, iconRes: Int, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val valid = battery.isFinite() && battery in 0f..1f
    val value = if (valid) "${(battery * 100).roundToInt()}%" else stringResource(R.string.popup_battery_missing)
    val chargingText = if (charging) stringResource(R.string.popup_charging) else ""
    val description = listOf(label, if (valid) value else stringResource(R.string.popup_battery_unknown), chargingText).filter { it.isNotEmpty() }.joinToString(", ")
    val colors = MaterialTheme.colorScheme
    val track = colors.onSurface.copy(alpha = .09f)
    val accent = if (valid && battery <= .2f) colors.error else Color(0xFF32B779)
    val ring: @Composable () -> Unit = {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val offset = Offset(stroke / 2, stroke / 2)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, offset, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (valid && battery > 0f) drawArc(accent, -90f, 360f * battery, false, offset, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (charging) Icon(Icons.Rounded.Bolt, null, Modifier.size(26.dp), tint = accent)
            else Image(painterResource(iconRes), null, Modifier.size(32.dp), contentScale = ContentScale.Fit)
        }
    }
    if (horizontal) {
        Row(modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
            ring()
            Text(label, Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
            ring()
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}
