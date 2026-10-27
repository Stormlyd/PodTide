package eu.darken.capod.main.ui.devicesettings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import eu.darken.capod.common.settings.SettingsSection
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import kotlin.math.roundToInt

@Composable
internal fun CustomEqCard(
    device: PodDevice,
    enabled: Boolean,
    onApply: (AapSetting.CustomEq) -> Unit,
) {
    val model = remember(device.profileId) { CustomEqViewModel(device.customEq) }
    val report = device.customEq
    val confirmationRevision = device.aap?.customEqConfirmationRevision ?: 0L
    LaunchedEffect(report, confirmationRevision) { model.observe(report, confirmationRevision) }
    val draft = model.draft
    val pending = device.pendingCustomEq

    SettingsSection(title = stringResource(R.string.custom_eq_title)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.custom_eq_description), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !draft.isEnabled,
                    onClick = { model.selectCustom(false) },
                    label = { Text(stringResource(R.string.custom_eq_recommended)) },
                )
                FilterChip(
                    selected = draft.isEnabled,
                    onClick = { model.selectCustom(true) },
                    label = { Text(stringResource(R.string.custom_eq_custom)) },
                )
            }
            if (draft.isEnabled) {
                EqBandSlider(stringResource(R.string.custom_eq_low), draft.low, model::setLow)
                EqBandSlider(stringResource(R.string.custom_eq_mid), draft.mid, model::setMid)
                EqBandSlider(stringResource(R.string.custom_eq_high), draft.high, model::setHigh)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::reset) { Text(stringResource(R.string.custom_eq_reset)) }
                Button(onClick = { onApply(model.markApplying(confirmationRevision)) }, enabled = enabled && pending == null) {
                    Text(stringResource(R.string.custom_eq_apply))
                }
            }
            Text(
                text = when {
                    pending != null && device.aap?.isEitherPodInEar == false -> stringResource(R.string.custom_eq_queued)
                    pending != null -> stringResource(R.string.custom_eq_pending)
                    report == null -> stringResource(R.string.custom_eq_no_report)
                    report.isEnabled -> stringResource(R.string.custom_eq_device_custom, report.low, report.mid, report.high)
                    else -> stringResource(R.string.custom_eq_device_recommended)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EqBandSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 99,
        )
    }
}
