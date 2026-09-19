package com.nutomic.syncthingandroid.ui.screens.syncconditions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveContent
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.components.ClickRow
import com.nutomic.syncthingandroid.ui.components.ToggleRow
import com.nutomic.syncthingandroid.ui.appPreferences
import java.util.Arrays

/**
 * Per-object custom sync conditions screen, ported from the legacy SyncConditionsActivity.
 * Changes are saved when leaving the screen (mirrors onPause saving).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncConditionsScreen(
    objectPrefixAndId: String,
    objectReadableName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = context.appPreferences()

    // Global run conditions.
    val globalRunOnWifiEnabled = preferences.getBoolean(Constants.PREF_RUN_ON_WIFI, true)
    val globalWhitelistedSsid = preferences.getStringSet(Constants.PREF_WIFI_SSID_WHITELIST, emptySet()) ?: emptySet()
    val globalWhitelistEnabled = preferences.getBoolean(Constants.PREF_USE_WIFI_SSID_WHITELIST, false)
    val globalRunOnMeteredWifiEnabled = preferences.getBoolean(Constants.PREF_RUN_ON_METERED_WIFI, false)
    val globalRunOnMobileDataEnabled = preferences.getBoolean(Constants.PREF_RUN_ON_MOBILE_DATA, false)
    val globalRunOnPowerSource = preferences.getString(Constants.PREF_POWER_SOURCE, Constants.PowerSource.CHARGER_BATTERY)
        ?: Constants.PowerSource.CHARGER_BATTERY
    val globalRunOnAnyPowerSource = globalRunOnPowerSource == Constants.PowerSource.CHARGER_BATTERY

    // Custom object preference keys.
    val prefSyncOnWifi = Constants.DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId)
    val prefSyncOnWhitelistedWifi = Constants.DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId)
    val prefSelectedWhitelistSsid = Constants.DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId)
    val prefSyncOnMeteredWifi = Constants.DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId)
    val prefSyncOnMobileData = Constants.DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId)
    val prefSyncOnPowerSource = Constants.DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId)

    var syncOnWifi by remember {
        mutableStateOf(globalRunOnWifiEnabled && preferences.getBoolean(prefSyncOnWifi, globalRunOnWifiEnabled))
    }
    var syncOnWhitelistedWifi by remember {
        mutableStateOf(globalWhitelistEnabled && preferences.getBoolean(prefSyncOnWhitelistedWifi, globalWhitelistEnabled))
    }
    var syncOnMeteredWifi by remember {
        mutableStateOf(globalRunOnMeteredWifiEnabled && preferences.getBoolean(prefSyncOnMeteredWifi, globalRunOnMeteredWifiEnabled))
    }
    var syncOnMobileData by remember {
        mutableStateOf(globalRunOnMobileDataEnabled && preferences.getBoolean(prefSyncOnMobileData, globalRunOnMobileDataEnabled))
    }
    val powerSourceValues = context.resources.getStringArray(R.array.power_source_values)
    val powerSourceLabels = context.resources.getStringArray(R.array.power_source_entries)
    var powerSourceIndex by remember {
        val savedValue = if (globalRunOnAnyPowerSource)
            preferences.getString(prefSyncOnPowerSource, Constants.PowerSource.CHARGER_BATTERY)
        else
            globalRunOnPowerSource
        mutableStateOf(
            Arrays.asList(*powerSourceValues).indexOf(savedValue).coerceAtLeast(0)
        )
    }

    // Selected WiFi SSID whitelist items; strip any SSID no longer in the global whitelist.
    var selectedSsids by remember {
        val saved = preferences.getStringSet(prefSelectedWhitelistSsid, globalWhitelistedSsid) ?: emptySet()
        mutableStateOf(saved.toMutableSet().apply { retainAll(globalWhitelistedSsid) })
    }

    val saveState = rememberUpdatedState(Triple(syncOnWifi, syncOnWhitelistedWifi, powerSourceIndex))

    DisposableEffect(Unit) {
        onDispose {
            // Save custom object preferences when leaving the screen.
            val editor = preferences.edit()
            editor.putBoolean(prefSyncOnWifi, saveState.value.first)
            editor.putBoolean(prefSyncOnWhitelistedWifi, saveState.value.second)
            editor.putBoolean(prefSyncOnMeteredWifi, syncOnMeteredWifi)
            editor.putBoolean(prefSyncOnMobileData, syncOnMobileData)
            editor.putString(prefSyncOnPowerSource, powerSourceValues[saveState.value.third])
            val selected: MutableSet<String> = if (syncOnWhitelistedWifi) selectedSsids.toMutableSet() else mutableSetOf()
            editor.putStringSet(prefSelectedWhitelistSsid, selected)
            editor.apply()
        }
    }

    val wifiSsidList = globalWhitelistedSsid.toList().sorted()
    val ssidSwitchesEnabled = globalWhitelistEnabled && syncOnWifi && syncOnWhitelistedWifi

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_sync_conditions_dialog)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
                    }
                }
            )
        }
    ) { innerPadding ->
        AdaptiveContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = objectReadableName,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                ToggleRow(
                    title = stringResource(R.string.run_on_wifi_title),
                    checked = syncOnWifi,
                    enabled = globalRunOnWifiEnabled,
                    onCheckedChange = { checked ->
                        syncOnWifi = checked
                        if (!checked) syncOnWhitelistedWifi = false
                    }
                )
                ToggleRow(
                    title = stringResource(R.string.run_on_whitelisted_wifi_title),
                    checked = syncOnWhitelistedWifi,
                    enabled = globalWhitelistEnabled && syncOnWifi,
                    onCheckedChange = { checked -> syncOnWhitelistedWifi = checked }
                )
                if (globalWhitelistEnabled) {
                    if (wifiSsidList.isEmpty()) {
                        Text(
                            text = stringResource(R.string.custom_wifi_ssid_whitelist_empty),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        wifiSsidList.forEach { wifiSsid ->
                            val label = wifiSsid.replaceFirst("^\"".toRegex(), "").replaceFirst("\"$".toRegex(), "")
                            ToggleRow(
                                title = label,
                                checked = selectedSsids.contains(wifiSsid),
                                enabled = ssidSwitchesEnabled,
                                onCheckedChange = { checked ->
                                    selectedSsids = selectedSsids.toMutableSet().apply {
                                        if (checked) add(wifiSsid) else remove(wifiSsid)
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ToggleRow(
                    title = stringResource(R.string.run_on_metered_wifi_title),
                    checked = syncOnMeteredWifi,
                    enabled = globalRunOnMeteredWifiEnabled,
                    onCheckedChange = { checked -> syncOnMeteredWifi = checked }
                )
                ToggleRow(
                    title = stringResource(R.string.run_on_mobile_data_title),
                    checked = syncOnMobileData,
                    enabled = globalRunOnMobileDataEnabled,
                    onCheckedChange = { checked -> syncOnMobileData = checked }
                )
                ClickRow(
                    title = stringResource(R.string.power_source_title),
                    value = powerSourceLabels.getOrElse(powerSourceIndex) { "" },
                    enabled = globalRunOnAnyPowerSource
                )
                if (globalRunOnAnyPowerSource) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        powerSourceLabels.forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = powerSourceIndex == index,
                                onClick = { powerSourceIndex = index },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index, count = powerSourceLabels.size
                                )
                            ) {
                                Text(
                                    label,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
