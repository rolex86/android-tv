@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.core.diagnostics.RemoteDebugConsole
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme

/** The Nuvio Reshaped settings category: everything the fork adds on top of NuvioTV, in one place. */
@Composable
internal fun NuvioReshapedSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val remoteContext = LocalContext.current
    var remoteOn by remember { mutableStateOf(RemoteDebugConsole.isEnabled(remoteContext)) }
    val remoteKey = remember { RemoteDebugConsole.accessKey(remoteContext) }
    val remoteIp = remember(remoteOn) {
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .asSequence().flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
        }.getOrNull() ?: "IP_SHIELDU"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        item(key = "remote_console_switch") {
            DebugToggleCard(
                title = "Vzdálená diagnostika – živá konzole",
                subtitle = if (remoteOn) "Zapnuto • http://$remoteIp:8765/?key=$remoteKey" else "Zapnout živé protokoly přehrávání dostupné z telefonu",
                checked = remoteOn,
                onToggle = {
                    RemoteDebugConsole.setEnabled(remoteContext, it)
                    remoteOn = it
                }
            )
        }
        if (remoteOn && remoteIp != "IP_SHIELDU") {
            item(key = "remote_console_qr") {
                RemoteConsoleQrCode("http://$remoteIp:8765/?key=$remoteKey")
            }
        }
        item(key = "nuvio_reshaped_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_nuvio_reshaped),
                subtitle = stringResource(R.string.settings_nuvio_reshaped_description),
            )
        }
        autoSyncSettingsItems(
            enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL,
            firstItemModifier = if (initialFocusRequester != null) {
                Modifier.focusRequester(initialFocusRequester)
            } else {
                Modifier
            },
        )
        autoSyncBubbleSettingsItems(enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL) // Nuvio RS hook: AutoSync bubble
        aiSubtitleSettingsItems(enabled = playerSettings.playerPreference != PlayerPreference.EXTERNAL)
        autoSourceRecoverySettingsItems(playerSettings)
        reshapedFormatsSettingsItems(playerSettings) // Nuvio RS: Arc sound and picture formats
        seekrKeySettingsItems()
        seekPreviewSettingsItems()
        subtitleFontSettingsItems()
        connectionSpeedSettingsItems()
        pillNavSettingsItems()
        netflixUiSettingsItems()
        liveTvSettingsItems()

        item(key = "plus_home_focus_diagnostics") {
            PlusHomeFocusDiagnosticsSettingsSection()
        }

        reshapedSyncSettingsItems()
    }
}
