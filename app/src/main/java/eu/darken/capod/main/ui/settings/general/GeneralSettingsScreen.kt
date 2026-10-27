package eu.darken.capod.main.ui.settings.general

import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.AccountTree
import androidx.compose.material.icons.twotone.BatteryStd
import androidx.compose.material.icons.twotone.Contrast
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.FilterList
import androidx.compose.material.icons.automirrored.twotone.Message
import androidx.compose.material.icons.twotone.Notifications
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.Language
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import eu.darken.capod.common.locale.AppLanguage
import eu.darken.capod.common.locale.AppLanguages
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.capod.R
import eu.darken.capod.common.compose.Preview2
import eu.darken.capod.common.compose.PreviewWrapper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.capod.common.error.ErrorEventHandler
import eu.darken.capod.common.navigation.NavigationEventHandler
import eu.darken.capod.common.settings.SettingsBaseItem
import eu.darken.capod.common.settings.SettingsCategoryHeader
import eu.darken.capod.common.settings.SettingsListPreferenceItem
import eu.darken.capod.common.settings.ThemeColorSelectorDialog
import eu.darken.capod.common.theming.ThemeColor
import eu.darken.capod.common.theming.ThemeMode
import eu.darken.capod.common.theming.ThemeState
import eu.darken.capod.common.theming.ThemeStyle

@Composable
fun GeneralSettingsScreenHost(vm: GeneralSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguages.current(context)) }

    val state by vm.state.collectAsStateWithLifecycle(initialValue = null)
    state?.let {
        GeneralSettingsScreen(
            state = it,
            onNavigateUp = { vm.navUp() },
            onShowConnectedNotificationChanged = { enabled -> vm.setShowConnectedNotification(enabled) },
            onShowLiveBatteryInNotificationChanged = { vm.setShowLiveBatteryInNotification(it) },
            onKeepNotificationAfterDisconnectChanged = { enabled -> vm.setKeepNotificationAfterDisconnect(enabled) },
            onShowBatteryInStatusBarChanged = { enabled -> vm.setShowBatteryInStatusBar(enabled) },
            onOffloadedFilteringDisabledChanged = { disabled -> vm.setOffloadedFilteringDisabled(disabled) },
            onOffloadedBatchingDisabledChanged = { disabled -> vm.setOffloadedBatchingDisabled(disabled) },
            onUseIndirectScanResultCallbackChanged = { enabled -> vm.setUseIndirectScanResultCallback(enabled) },
            onHideUnmatchedDevicesChanged = { enabled -> vm.setHideUnmatchedDevices(enabled) },
            onShowBottomPopupChanged = { vm.setShowBottomPopup(it) },
            onShowTopPopupChanged = { vm.setShowTopPopup(it) },
            onThemeModeSelected = { mode -> vm.setThemeMode(mode) },
            onThemeStyleSelected = { style -> vm.setThemeStyle(style) },
            onThemeColorSelected = { color -> vm.setThemeColor(color) },
            onUpgrade = { vm.launchUpgrade() },
            onOpenAppDetails = { SettingsHelpLauncher.openAppDetails(context) },
            onOpenFindDevices = { SettingsHelpLauncher.openFindDevices(context) },
            selectedLanguage = language,
            onLanguageSelected = { selected -> AppLanguages.select(context, selected); language = selected },
        )
    }
}

@Composable
fun GeneralSettingsScreen(
    state: GeneralSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onShowConnectedNotificationChanged: (Boolean) -> Unit,
    onKeepNotificationAfterDisconnectChanged: (Boolean) -> Unit,
    onOffloadedFilteringDisabledChanged: (Boolean) -> Unit,
    onOffloadedBatchingDisabledChanged: (Boolean) -> Unit,
    onUseIndirectScanResultCallbackChanged: (Boolean) -> Unit,
    onHideUnmatchedDevicesChanged: (Boolean) -> Unit,
    onShowLiveBatteryInNotificationChanged: (Boolean) -> Unit = {},
    onShowBatteryInStatusBarChanged: (Boolean) -> Unit = {},
    onThemeModeSelected: (ThemeMode) -> Unit = {},
    onThemeStyleSelected: (ThemeStyle) -> Unit = {},
    onThemeColorSelected: (ThemeColor) -> Unit = {},
    onUpgrade: () -> Unit = {},
    modifier: Modifier = Modifier,
    selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onOpenAppDetails: () -> Boolean = { false },
    onOpenFindDevices: () -> Boolean = { false },
    onShowBottomPopupChanged: (Boolean) -> Unit = {},
    onShowTopPopupChanged: (Boolean) -> Unit = {},
) {
    var showColorDialog by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf<HelpDialog?>(null) }
    var launchFailed by remember { mutableStateOf(false) }

    val isMaterialYouActive = state.themeState.style == ThemeStyle.MATERIAL_YOU &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_general_label)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_appearance_label))
            }
            item {
                SettingsListPreferenceItem(
                    icon = Icons.TwoTone.Language,
                    title = stringResource(R.string.app_language_title),
                    entries = AppLanguage.entries,
                    selectedEntry = selectedLanguage,
                    onEntrySelected = onLanguageSelected,
                    entryLabel = { option -> stringResource(when (option) {
                        AppLanguage.SYSTEM -> R.string.app_language_system
                        AppLanguage.SIMPLIFIED_CHINESE -> R.string.app_language_chinese
                        AppLanguage.ENGLISH -> R.string.app_language_english
                    }) },
                )
            }
            item {
                if (!state.isUpgradeLocked) {
                    SettingsListPreferenceItem(
                        icon = Icons.TwoTone.DarkMode,
                        title = stringResource(R.string.ui_theme_mode_label),
                        entries = ThemeMode.entries,
                        selectedEntry = state.themeState.mode,
                        onEntrySelected = onThemeModeSelected,
                        entryLabel = { stringResource(it.labelRes) },
                    )
                } else {
                    SettingsBaseItem(
                        icon = Icons.TwoTone.DarkMode,
                        title = stringResource(R.string.ui_theme_mode_label),
                        subtitle = stringResource(state.themeState.mode.labelRes),
                        onClick = onUpgrade,
                        requiresUpgrade = true,
                    )
                }
            }
            item {
                if (!state.isUpgradeLocked) {
                    SettingsListPreferenceItem(
                        icon = Icons.TwoTone.Contrast,
                        title = stringResource(R.string.ui_theme_style_label),
                        entries = ThemeStyle.entries,
                        selectedEntry = state.themeState.style,
                        onEntrySelected = onThemeStyleSelected,
                        entryLabel = { stringResource(it.labelRes) },
                    )
                } else {
                    SettingsBaseItem(
                        icon = Icons.TwoTone.Contrast,
                        title = stringResource(R.string.ui_theme_style_label),
                        subtitle = stringResource(state.themeState.style.labelRes),
                        onClick = onUpgrade,
                        requiresUpgrade = true,
                    )
                }
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.ui_theme_color_label),
                    subtitle = if (isMaterialYouActive) {
                        stringResource(R.string.ui_theme_color_disabled_materialyou)
                    } else {
                        stringResource(state.themeState.color.labelRes)
                    },
                    icon = Icons.TwoTone.Palette,
                    onClick = {
                        if (state.isUpgradeLocked) onUpgrade() else showColorDialog = true
                    },
                    enabled = !isMaterialYouActive,
                    requiresUpgrade = state.isUpgradeLocked && !isMaterialYouActive,
                )
            }
            item {
                SettingsCategoryHeader(
                    text = stringResource(R.string.popup_styles_title),
                    subtitle = stringResource(R.string.popup_styles_summary),
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.popup_style_bottom),
                    icon = Icons.TwoTone.Notifications,
                    onClick = { onShowBottomPopupChanged(!state.showBottomPopup) },
                    trailingContent = {
                        Switch(checked = state.showBottomPopup, onCheckedChange = onShowBottomPopupChanged,
                            modifier = Modifier.padding(start = 16.dp))
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.popup_style_top),
                    icon = Icons.TwoTone.Notifications,
                    onClick = { onShowTopPopupChanged(!state.showTopPopup) },
                    trailingContent = {
                        Switch(checked = state.showTopPopup, onCheckedChange = onShowTopPopupChanged,
                            modifier = Modifier.padding(start = 16.dp))
                    },
                )
            }
            item {
                SettingsCategoryHeader(text = stringResource(R.string.settings_category_other_label))
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_background_help_title),
                    subtitle = stringResource(R.string.settings_background_help_summary),
                    icon = Icons.TwoTone.BatteryStd,
                    onClick = { helpDialog = HelpDialog.BACKGROUND; launchFailed = false },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_find_airpods_title),
                    subtitle = stringResource(R.string.settings_find_airpods_summary),
                    icon = Icons.TwoTone.Language,
                    onClick = { helpDialog = HelpDialog.FIND_AIRPODS; launchFailed = false },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_monitor_live_battery_label),
                    subtitle = stringResource(R.string.settings_monitor_live_battery_description),
                    icon = Icons.TwoTone.BatteryStd,
                    onClick = { onShowLiveBatteryInNotificationChanged(!state.showLiveBatteryInNotification) },
                    trailingContent = {
                        Switch(checked = state.showLiveBatteryInNotification,
                            onCheckedChange = onShowLiveBatteryInNotificationChanged,
                            modifier = Modifier.padding(start = 16.dp))
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_monitor_connected_notification_label),
                    subtitle = stringResource(R.string.settings_monitor_connected_notification_description),
                    icon = Icons.TwoTone.Notifications,
                    onClick = { onShowConnectedNotificationChanged(!state.showConnectedNotification) },
                    enabled = state.showLiveBatteryInNotification,
                    trailingContent = {
                        Switch(
                            checked = state.showConnectedNotification,
                            enabled = state.showLiveBatteryInNotification,
                            onCheckedChange = onShowConnectedNotificationChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_keep_notification_after_disconnect_label),
                    subtitle = stringResource(R.string.settings_keep_notification_after_disconnect_description),
                    icon = Icons.AutoMirrored.TwoTone.Message,
                    onClick = { onKeepNotificationAfterDisconnectChanged(!state.keepNotificationAfterDisconnect) },
                    enabled = state.showLiveBatteryInNotification && state.showConnectedNotification,
                    trailingContent = {
                        Switch(
                            checked = state.keepNotificationAfterDisconnect,
                            onCheckedChange = onKeepNotificationAfterDisconnectChanged,
                            enabled = state.showLiveBatteryInNotification && state.showConnectedNotification,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_monitor_statusbar_battery_label),
                    subtitle = stringResource(R.string.settings_monitor_statusbar_battery_description),
                    icon = Icons.TwoTone.BatteryStd,
                    onClick = { onShowBatteryInStatusBarChanged(!state.showBatteryInStatusBar) },
                    enabled = state.showLiveBatteryInNotification,
                    trailingContent = {
                        Switch(
                            checked = state.showBatteryInStatusBar,
                            enabled = state.showLiveBatteryInNotification,
                            onCheckedChange = onShowBatteryInStatusBarChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_overview_hide_unmatched_label),
                    subtitle = stringResource(R.string.settings_overview_hide_unmatched_description),
                    icon = Icons.TwoTone.VisibilityOff,
                    onClick = { onHideUnmatchedDevicesChanged(!state.hideUnmatchedDevices) },
                    trailingContent = {
                        Switch(
                            checked = state.hideUnmatchedDevices,
                            onCheckedChange = onHideUnmatchedDevicesChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsCategoryHeader(
                    text = stringResource(R.string.settings_category_compatibility_options_title),
                    subtitle = stringResource(R.string.settings_category_compatibility_options_description),
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_compat_offloaded_filtering_disabled_title),
                    subtitle = stringResource(R.string.settings_compat_offloaded_filtering_disabled_summary),
                    icon = Icons.TwoTone.FilterList,
                    onClick = { onOffloadedFilteringDisabledChanged(!state.isOffloadedFilteringDisabled) },
                    trailingContent = {
                        Switch(
                            checked = state.isOffloadedFilteringDisabled,
                            onCheckedChange = onOffloadedFilteringDisabledChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_compat_offloaded_batching_disabled_title),
                    subtitle = stringResource(R.string.settings_compat_offloaded_batching_disabled_summary),
                    icon = Icons.AutoMirrored.TwoTone.ViewList,
                    onClick = { onOffloadedBatchingDisabledChanged(!state.isOffloadedBatchingDisabled) },
                    trailingContent = {
                        Switch(
                            checked = state.isOffloadedBatchingDisabled,
                            onCheckedChange = onOffloadedBatchingDisabledChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_compat_indirectcallback_title),
                    subtitle = stringResource(R.string.settings_compat_indirectcallback_summary),
                    icon = Icons.TwoTone.AccountTree,
                    onClick = { onUseIndirectScanResultCallbackChanged(!state.useIndirectScanResultCallback) },
                    trailingContent = {
                        Switch(
                            checked = state.useIndirectScanResultCallback,
                            onCheckedChange = onUseIndirectScanResultCallbackChanged,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                )
            }
        }
    }

    if (showColorDialog) {
        ThemeColorSelectorDialog(
            selectedColor = state.themeState.color,
            onColorSelected = {
                onThemeColorSelected(it)
                showColorDialog = false
            },
            onDismiss = { showColorDialog = false },
        )
    }

    helpDialog?.let { dialog ->
        val background = dialog == HelpDialog.BACKGROUND
        AlertDialog(
            onDismissRequest = { helpDialog = null },
            title = {
                Text(stringResource(if (background) R.string.settings_background_help_title else R.string.settings_find_airpods_title))
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(if (background) R.string.settings_background_help_body else R.string.settings_find_airpods_body))
                    if (background) {
                        Text(
                            stringResource(R.string.settings_background_help_path),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (launchFailed) {
                        Text(
                            stringResource(if (background) R.string.settings_background_help_failure else R.string.settings_find_airpods_failure),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = {
                            launchFailed = !(if (background) onOpenAppDetails() else onOpenFindDevices())
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
                    ) {
                        Text(stringResource(if (background) R.string.settings_background_help_open else R.string.settings_find_airpods_open))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { helpDialog = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_help_close)) }
            },
        )
    }
}

private enum class HelpDialog { BACKGROUND, FIND_AIRPODS }

private fun previewGeneralState(isPro: Boolean) = GeneralSettingsViewModel.State(
    isUpgradeLocked = !isPro,
    showConnectedNotification = true,
    keepNotificationAfterDisconnect = false,
    showBatteryInStatusBar = false,
    isOffloadedFilteringDisabled = false,
    isOffloadedBatchingDisabled = false,
    useIndirectScanResultCallback = false,
    hideUnmatchedDevices = false,
    themeState = ThemeState(),
)

@Preview2
@Composable
private fun GeneralSettingsScreenProPreview() = PreviewWrapper {
    GeneralSettingsScreen(
        state = previewGeneralState(isPro = true),
        onNavigateUp = {},
        onShowConnectedNotificationChanged = {},
        onKeepNotificationAfterDisconnectChanged = {},
        onOffloadedFilteringDisabledChanged = {},
        onOffloadedBatchingDisabledChanged = {},
        onUseIndirectScanResultCallbackChanged = {},
        onHideUnmatchedDevicesChanged = {},
    )
}

@Preview2
@Composable
private fun GeneralSettingsScreenNonProPreview() = PreviewWrapper {
    GeneralSettingsScreen(
        state = previewGeneralState(isPro = false),
        onNavigateUp = {},
        onShowConnectedNotificationChanged = {},
        onKeepNotificationAfterDisconnectChanged = {},
        onOffloadedFilteringDisabledChanged = {},
        onOffloadedBatchingDisabledChanged = {},
        onUseIndirectScanResultCallbackChanged = {},
        onHideUnmatchedDevicesChanged = {},
    )
}
