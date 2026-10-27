package eu.darken.capod.main.ui.devicesettings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import eu.darken.capod.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.capod.reaction.core.heartrate.HeartRateController
import eu.darken.capod.reaction.core.heartrate.HeartRateStatus
import eu.darken.capod.reaction.core.heartrate.HeartRateUiState
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class HeartRateViewModel @Inject constructor(val controller: HeartRateController) : ViewModel()

@Composable
fun HeartRateCard(state: HeartRateUiState, onStart: () -> Unit, onStop: () -> Unit, onClear: () -> Unit, showHistory: Boolean = true) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.heart_rate_device_title), style = MaterialTheme.typography.titleMedium)
            Text(state.reading.bpm?.let { stringResource(R.string.heart_rate_value, it) } ?: stringResource(R.string.heart_rate_value_unavailable), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(state.messageRes ?: when (state.reading.status) {
                HeartRateStatus.STOPPED -> R.string.heart_rate_stopped
                HeartRateStatus.WAITING -> R.string.heart_rate_waiting
                HeartRateStatus.ACTIVE -> R.string.heart_rate_active
                HeartRateStatus.STALE -> R.string.heart_rate_stale
            }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (state.requested) onStop else onStart) { Text(stringResource(if (state.requested) R.string.heart_rate_stop else R.string.heart_rate_start)) }
                if (showHistory && state.history.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.heart_rate_clear)) }
            }
            if (showHistory && state.history.isNotEmpty()) {
                Text(stringResource(R.string.heart_rate_history_description), style = MaterialTheme.typography.labelMedium)
                state.history.takeLast(5).asReversed().forEach { sample ->
                    Text(stringResource(R.string.heart_rate_history_row, DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(Date(sample.atMillis)), sample.bpm))
                }
            }
        }
    }
}
