package eu.darken.capod.main.ui.devicesettings.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.darken.capod.R
import eu.darken.capod.common.compose.Preview2
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.reaction.core.autoconnect.AutoConnectCondition

@Composable
internal fun AutoConnectConditionDialog(
    current: AutoConnectCondition,
    hasEarDetection: Boolean,
    hasCase: Boolean,
    onSelect: (AutoConnectCondition) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = AutoConnectCondition.entries.filter { condition ->
        when (condition) {
            AutoConnectCondition.IN_EAR -> hasEarDetection
            AutoConnectCondition.CASE_OPEN -> hasCase
            AutoConnectCondition.WHEN_SEEN -> true
        }
    }
    val descriptionRes = mapOf(
        AutoConnectCondition.WHEN_SEEN to R.string.autoconnect_guidance_when_seen,
        AutoConnectCondition.CASE_OPEN to R.string.autoconnect_guidance_case_open,
        AutoConnectCondition.IN_EAR to R.string.autoconnect_guidance_in_ear,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_autoconnect_condition_label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(
                    text = stringResource(R.string.autoconnect_guidance_background),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { condition ->
                    val isSelected = condition == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onSelect(condition) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = stringResource(condition.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (condition == AutoConnectCondition.WHEN_SEEN) {
                                Text(
                                    text = stringResource(R.string.autoconnect_guidance_recommended),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = stringResource(descriptionRes.getValue(condition)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Preview2
@Composable
private fun AutoConnectConditionDialogFullPreview() = PreviewWrapper {
    AutoConnectConditionDialog(
        current = AutoConnectCondition.IN_EAR,
        hasEarDetection = true,
        hasCase = true,
        onSelect = {},
        onDismiss = {},
    )
}

@Preview2
@Composable
private fun AutoConnectConditionDialogMinimalPreview() = PreviewWrapper {
    AutoConnectConditionDialog(
        current = AutoConnectCondition.WHEN_SEEN,
        hasEarDetection = false,
        hasCase = false,
        onSelect = {},
        onDismiss = {},
    )
}
