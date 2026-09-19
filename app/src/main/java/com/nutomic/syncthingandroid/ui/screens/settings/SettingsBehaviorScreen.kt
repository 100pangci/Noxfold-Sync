package com.nutomic.syncthingandroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsBehaviorEntry() {
    settingsDetailEntry<SettingsRoute.Behavior> {
        SettingsBehaviorScreen()
    }
}


@Composable
fun SettingsBehaviorScreen() {

    val autoStart = rememberPreferenceState(Constants.PREF_START_SERVICE_ON_BOOT, false)
    val broadcast = rememberPreferenceState(Constants.PREF_BROADCAST_SERVICE_CONTROL, false)
    val overwrite = rememberPreferenceState(Constants.PREF_ALLOW_OVERWRITE_FILES, false)

    SettingsScaffold(
        title = stringResource(R.string.category_behaviour),
    ) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.behaviour_autostart_title)) },
                summary = { Text(stringResource(R.string.behaviour_autostart_summary)) },
                state = autoStart,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.broadcast_service_control_title))},
                summary = { Text(stringResource(R.string.broadcast_service_control_summary))},
                state = broadcast,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.allow_overwrite_files_title)) },
                summary = { Text(stringResource(R.string.allow_overwrite_files_summary))},
                state = overwrite,
            )
        }
        item {
            RootModePreference()
        }
    }
}

/**
 * The optional "run Syncthing as root" switch. Lives in the behaviour section: the
 * feature is hardened and tested, but toggling it restarts the core and switches the
 * privilege mode, so it keeps its confirmation dialog and a busy state that disables
 * the switch while a toggle is in flight.
 */
@Composable
private fun RootModePreference() {
    val context = LocalContext.current

    val runAsRoot = rememberPreferenceState(Constants.PREF_RUN_AS_ROOT, false)
    val rootSwitchBusy = remember { mutableStateOf(false) }
    // Non-null while the root-mode warning dialog is up: true = enabling, false = disabling.
    // The switch never writes the preference directly — a tap only opens this dialog, and
    // the preference is written (and the core restarted) only after the user confirms.
    val pendingRootWarning = remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    pendingRootWarning.value?.let { enabling ->
        AlertDialog(
            onDismissRequest = { pendingRootWarning.value = null },
            title = { Text(stringResource(R.string.root_warning_title)) },
            text = {
                Text(
                    stringResource(
                        if (enabling) {
                            R.string.root_enable_warning_message
                        } else {
                            R.string.root_disable_warning_message
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRootWarning.value = null
                    scope.launch {
                        rootSwitchBusy.value = true
                        try {
                            if (enabling) {
                                // Request the su grant here — this is where the Magisk
                                // dialog appears. Abort without writing the preference
                                // when su is unavailable or denied.
                                val granted = withContext(Dispatchers.IO) { RootAccess.isSuAvailable() }
                                if (!granted) {
                                    Toast.makeText(
                                        context, R.string.root_authorization_unavailable, Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }
                                runAsRoot.value = true
                            } else {
                                runAsRoot.value = false
                            }
                            // No storage handback here on disable: the root-uid core is
                            // still running and could rewrite config.xml (0600 root) after
                            // our chown, voiding it. The restart flow hands storage back
                            // AFTER the core has fully exited (launchStartupTask's root
                            // preflight / the non-root launch path).
                            context.startService(
                                Intent(context, SyncthingService::class.java)
                                    .setAction(SyncthingService.ACTION_RESTART)
                            )
                        } finally {
                            rootSwitchBusy.value = false
                        }
                    }
                }) {
                    Text(
                        stringResource(
                            if (enabling) {
                                R.string.root_enable_warning_continue
                            } else {
                                R.string.root_disable_warning_continue
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRootWarning.value = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    SwitchPreference(
        value = runAsRoot.value,
        onValueChange = { requested -> pendingRootWarning.value = requested },
        title = { Text(stringResource(R.string.run_as_root_title)) },
        summary = { Text(stringResource(R.string.run_as_root_summary)) },
        enabled = !rootSwitchBusy.value,
    )
}
