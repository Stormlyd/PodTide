package eu.darken.capod.main.ui.heartrate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.capod.R
import eu.darken.capod.common.navigation.LocalNavigationController
import eu.darken.capod.common.settings.SettingsBaseItem
import eu.darken.capod.main.ui.devicesettings.cards.HeartRateCard
import eu.darken.capod.main.ui.devicesettings.cards.HeartRateViewModel
import eu.darken.capod.reaction.core.heartrate.HeartRateUiState
import java.text.DateFormat
import java.util.Date

@Composable
fun HeartRateMenuItem(onClick: () -> Unit) {
    SettingsBaseItem(
        title = stringResource(R.string.heart_rate_title),
        subtitle = stringResource(R.string.heart_rate_entry_description),
        icon = Icons.TwoTone.Favorite,
        onClick = onClick,
        modifier = Modifier.testTag("heart_rate_entry"),
        trailingContent = { Icon(Icons.AutoMirrored.TwoTone.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
fun HeartRateScreenHost(profileId: String, vm: HeartRateViewModel = hiltViewModel()) {
    val states by vm.controller.states.collectAsStateWithLifecycle()
    val state = states[profileId] ?: vm.controller.state(profileId)
    val navigation = LocalNavigationController.current
    HeartRateScreen(state, { navigation?.up() }, { vm.controller.start(profileId) },
        { vm.controller.stop(profileId) }, { vm.controller.clearHistory(profileId) })
}

@Composable
fun HeartRateScreen(state: HeartRateUiState, onNavigateUp: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.heart_rate_title)) }, navigationIcon = {
            IconButton(onClick = onNavigateUp, modifier = Modifier.testTag("heart_rate_back")) {
                Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = stringResource(R.string.heart_rate_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { HeartRateCard(state, onStart, onStop, onClear, showHistory = false) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.heart_rate_history_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.history.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.heart_rate_clear)) }
                }
            }
            item { Text(stringResource(R.string.heart_rate_history_description), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            if (state.history.isEmpty()) item { Text(stringResource(R.string.heart_rate_history_empty), Modifier.padding(horizontal = 16.dp)) }
            else {
                item { Text(stringResource(R.string.heart_rate_history_count, state.history.size), Modifier.padding(horizontal = 16.dp)) }
                items(state.history.asReversed()) { reading ->
                    Text(stringResource(R.string.heart_rate_history_row, timeFormat.format(Date(reading.atMillis)), reading.bpm), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }
}
