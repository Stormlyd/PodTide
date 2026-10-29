package eu.darken.capod.main.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.automirrored.twotone.MenuBook
import androidx.compose.material.icons.twotone.DevicesOther
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material.icons.twotone.SupportAgent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.capod.R
import eu.darken.capod.common.compose.Preview2
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.BuildConfigWrap
import eu.darken.capod.common.PrivacyPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.darken.capod.common.error.ErrorEventHandler
import eu.darken.capod.common.navigation.Nav
import eu.darken.capod.common.navigation.NavigationEventHandler
import eu.darken.capod.common.settings.SettingsBaseItem
import eu.darken.capod.common.settings.SettingsCategoryHeader
import eu.darken.capod.common.upgrade.ui.settingsUpgradeStatusTitle

@Composable
fun SettingsScreenHost(vm: SettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsStateWithLifecycle(initialValue = null)
    state?.let {
        SettingsScreen(
            state = it,
            onNavigateUp = { vm.navUp() },
            onGeneralSettings = { vm.navTo(Nav.Settings.General) },
            onDeviceManager = { vm.navTo(Nav.Main.DeviceManager) },
            onUpgradeStatus = { vm.navTo(Nav.Main.Upgrade(manage = true)) },
            onSupport = { vm.navTo(Nav.Settings.Support) },
            onWiki = { vm.openUrl("https://github.com/Stormlyd/PodTide") },
            onChangelog = { vm.openUrl("https://github.com/Stormlyd/PodTide/releases") },
            onAcknowledgements = { vm.navTo(Nav.Settings.Acknowledgements) },
            onPrivacyPolicy = { vm.openUrl(PrivacyPolicy.URL) },
            onSponsor = { url -> vm.openUrl(url) },
        )
    }
}

@Composable
fun SettingsScreen(
    state: SettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onGeneralSettings: () -> Unit,
    onDeviceManager: () -> Unit,
    onUpgradeStatus: () -> Unit,
    onSupport: () -> Unit,
    onWiki: () -> Unit,
    onChangelog: () -> Unit,
    onAcknowledgements: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onSponsor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings_label))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    val sponsorUrl = state.sponsorUrl
                    if (state.isPro && sponsorUrl != null) {
                        IconButton(onClick = { onSponsor(sponsorUrl) }) {
                            Icon(
                                imageVector = Icons.TwoTone.Favorite,
                                contentDescription = null,
                            )
                        }
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
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text(
                            text = stringResource(R.string.podtide_settings_intro_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.podtide_settings_intro_subtitle),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsMenuGroup {
                    SettingsMenuLink(stringResource(R.string.settings_general_label), stringResource(R.string.settings_general_description), onGeneralSettings, icon = Icons.TwoTone.Settings)
                    SettingsMenuDivider()
                    SettingsMenuLink(stringResource(R.string.settings_devices_label), stringResource(R.string.settings_devices_description), onDeviceManager, icon = Icons.TwoTone.DevicesOther)
                }
            }
            item { SettingsCategoryHeader(text = stringResource(R.string.settings_category_other_label)) }
            item {
                SettingsMenuGroup {
                    SettingsMenuLink(stringResource(R.string.settings_support_label), stringResource(R.string.settings_support_description), onSupport, icon = Icons.TwoTone.SupportAgent)
                    SettingsMenuDivider()
                    SettingsMenuLink(stringResource(R.string.settings_wiki_label), stringResource(R.string.settings_wiki_description), onWiki, icon = Icons.AutoMirrored.TwoTone.MenuBook)
                    SettingsMenuDivider()
                    SettingsMenuLink(stringResource(R.string.changelog_label), BuildConfigWrap.VERSION_DESCRIPTION, onChangelog, iconPainter = painterResource(R.drawable.ic_changelog_onsurface))
                    SettingsMenuDivider()
                    SettingsMenuLink(stringResource(R.string.settings_acknowledgements_label), stringResource(R.string.general_thank_you_label), onAcknowledgements, icon = Icons.TwoTone.Favorite)
                    SettingsMenuDivider()
                    SettingsMenuLink(stringResource(R.string.settings_privacy_policy_label), stringResource(R.string.settings_privacy_policy_desc), onPrivacyPolicy, icon = Icons.TwoTone.Book)
                }
            }
        }
    }
}


@Composable
private fun SettingsMenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { Column(content = content) }
}

@Composable
private fun SettingsMenuDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

@Composable
private fun SettingsMenuLink(title: String, subtitle: String, onClick: () -> Unit, icon: ImageVector? = null, iconPainter: Painter? = null) {
    SettingsBaseItem(title = title, subtitle = subtitle, onClick = onClick, icon = icon, iconPainter = iconPainter, grouped = true,
        trailingContent = { Icon(Icons.AutoMirrored.TwoTone.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}

@Preview2
@Composable
private fun SettingsScreenPreview() = PreviewWrapper {
    SettingsScreen(
        state = SettingsViewModel.State(isPro = true, sponsorUrl = "https://example.com"),
        onNavigateUp = {},
        onGeneralSettings = {},
        onDeviceManager = {},
        onUpgradeStatus = {},
        onSupport = {},
        onWiki = {},
        onChangelog = {},
        onAcknowledgements = {},
        onPrivacyPolicy = {},
        onSponsor = {},
    )
}
