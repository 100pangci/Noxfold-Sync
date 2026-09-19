package com.nutomic.syncthingandroid.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.ui.LocalServiceTick
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.adaptive.ListDetailPaneContent
import com.nutomic.syncthingandroid.ui.adaptive.ListDetailPaneRole
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import me.zhanghai.compose.preference.Preference

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<SettingsRoute>.settingsRootEntry() {
    entry<SettingsRoute.Root>(metadata = ListDetailSceneStrategy.listPane()) {
        ListDetailPaneContent(ListDetailPaneRole.List) {
            SettingsRootScreen()
        }
    }
}

@Composable
fun SettingsRootScreen() {
    val navigator = LocalSettingsNavigator.current
    val stService = LocalSyncthingService.current
    val stServiceTick = LocalServiceTick.current

    val isSyncthingOptionsEnabled by remember(stService, stServiceTick) {
        derivedStateOf { stService != null && stService.currentState == SyncthingService.State.ACTIVE }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
    ) {
        item {
            SettingsRootPreference(
                route = SettingsRoute.RunConditions,
                title = stringResource(R.string.run_conditions_title),
                summary = stringResource(R.string.run_conditions_summary),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.RunConditions) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.UserInterface,
                title = stringResource(R.string.category_user_interface),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.UserInterface) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.Behavior,
                title = stringResource(R.string.category_behaviour),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.Behavior) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.SyncthingOptions,
                title = stringResource(R.string.category_syncthing_options),
                summary = stringResource(R.string.category_syncthing_options_summary),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.SyncthingOptions) },
                enabled = isSyncthingOptionsEnabled,
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.ImportExport,
                title = stringResource(R.string.category_backup),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.ImportExport) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.Troubleshooting,
                title = stringResource(R.string.category_debug),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.Troubleshooting) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.Experimental,
                title = stringResource(R.string.category_experimental),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.Experimental) },
            )
        }
        item {
            SettingsRootPreference(
                route = SettingsRoute.About,
                title = stringResource(R.string.category_about),
                onClick = { navigator.navigateToRootDetail(SettingsRoute.About) },
            )
        }
    }
}

/** A root settings row with a list-detail-only selected state. */
@Composable
private fun SettingsRootPreference(
    route: SettingsRoute,
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val selected = LocalSelectedSettingsRoot.current == route
    val selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    Preference(
        title = {
            Text(
                text = title,
                color = if (selected) selectedContentColor else Color.Unspecified,
            )
        },
        summary = summary?.let {
            {
                Text(
                    text = it,
                    color = if (selected) selectedContentColor else Color.Unspecified,
                )
            }
        },
        modifier = if (selected) {
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
        } else {
            Modifier
        },
        enabled = enabled,
        onClick = onClick,
    )
}
