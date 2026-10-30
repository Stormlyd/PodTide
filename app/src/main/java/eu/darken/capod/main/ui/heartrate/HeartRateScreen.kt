package eu.darken.capod.main.ui.heartrate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.capod.R
import eu.darken.capod.common.navigation.LocalNavigationController
import eu.darken.capod.common.settings.SettingsBaseItem
import eu.darken.capod.main.ui.devicesettings.cards.HeartRateCard
import eu.darken.capod.main.ui.devicesettings.cards.HeartRateViewModel
import eu.darken.capod.reaction.core.heartrate.HeartRateUiState

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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(profileId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                vm.controller.start(profileId)
                vm.controller.monitor().collect { }
            } finally {
                vm.controller.stop(profileId)
            }
        }
    }
    val states by vm.controller.states.collectAsStateWithLifecycle()
    val state = states[profileId] ?: vm.controller.state(profileId)
    val navigation = LocalNavigationController.current
    HeartRateScreen(state, { navigation?.up() }, { vm.controller.start(profileId) },
        { vm.controller.stop(profileId) }, { vm.controller.clearHistory(profileId) })
}

@Composable
fun HeartRateScreen(state: HeartRateUiState, onNavigateUp: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.heart_rate_title)) }, navigationIcon = {
            IconButton(onClick = onNavigateUp, modifier = Modifier.testTag("heart_rate_back")) {
                Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = stringResource(R.string.heart_rate_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { HeartRateCard(state, onStart, onStop, onClear, showHistory = false, showControls = false) }
            item { HeartRateHistoryChart(state.history, onClear) }
        }
    }
}
