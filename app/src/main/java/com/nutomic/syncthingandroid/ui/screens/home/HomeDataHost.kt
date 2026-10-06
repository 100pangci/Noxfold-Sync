package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Home screen list data, provided by [HomeDataHost].
 *
 * The lists are `null` while the first poll has not completed (loading state);
 * an empty list means the config really has no folders/devices.
 */
// These values change frequently. Track readers instead of invalidating the
// entire navigation subtree whenever a list's sync status changes.
val LocalHomeFolderModels = compositionLocalOf<List<FolderUiModel>?> { null }
val LocalHomeDeviceModels = compositionLocalOf<List<DeviceUiModel>?> { null }

/**
 * Hosts the home screen folder/device polling loops.
 *
 * The state must live outside the Navigation 3 NavDisplay: navigating to an
 * edit screen disposes the Home entry (and its remember state), so polling
 * hosted inside HomeScreen restarted from zero on every back navigation and
 * flashed the "no folders/devices" empty hint for one poll cycle.
 * Hoisting the loops here keeps the lists alive across entry transitions.
 */
@Composable
fun HomeDataHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val serviceState = LocalServiceState.current
    val api = service?.api
    val apiConfigLoaded = api?.isConfigLoaded ?: false

    val configRouter = remember { ConfigRouter(context) }

    var folders by remember { mutableStateOf<List<FolderUiModel>?>(null) }
    var devices by remember { mutableStateOf<List<DeviceUiModel>?>(null) }
    var sharedFoldersByDevice by remember { mutableStateOf<Map<String, List<Folder>>>(emptyMap()) }

    // Folders poll at the legacy GUI_UPDATE_INTERVAL cadence. The card data is
    // precomputed on the polling dispatcher; data class equality ensures rows
    // whose visible content did not change are skipped by composition.
    LaunchedEffect(service, serviceState, apiConfigLoaded) {
        while (isActive) {
            try {
                val newModels = withContext(Dispatchers.IO) {
                    // distinctBy guards the LazyColumn keys against duplicate
                    // folder ids that may exist in stale config.xml files.
                    buildFolderUiModels(context, api, apiConfigLoaded, configRouter.getFolders(api).distinctBy { it.id })
                }
                if (newModels != folders) {
                    folders = newModels
                }
            } catch (e: ConfigXml.OpenConfigException) {
                folders = null
            }
            delay(Constants.GUI_UPDATE_INTERVAL)
        }
    }

    // Devices poll at the legacy REST_UPDATE_INTERVAL cadence (3s on O+),
    // including the forced remote status refresh. Shared folder lists are
    // derived here in memory; the legacy ConfigRouter.getSharedFolders would
    // re-parse config.xml per call.
    LaunchedEffect(service, serviceState, apiConfigLoaded) {
        while (isActive) {
            try {
                val (rawDevices, newSharedFolders) = withContext(Dispatchers.IO) {
                    if (serviceState == SyncthingService.State.ACTIVE && api != null && apiConfigLoaded) {
                        api.getRemoteDeviceStatus("")
                    }
                    val d = configRouter.getDevices(api, false).distinctBy { it.deviceID }
                    // Derive sharing in memory; the legacy ConfigRouter helper
                    // re-parses config.xml on every call.
                    val map = HashMap<String, MutableList<Folder>>()
                    for (folder in configRouter.getFolders(api).distinctBy { it.id }) {
                        for (shared in folder.getSharedWithDevices()) {
                            map.getOrPut(shared.deviceID) { mutableListOf() }.add(folder)
                        }
                    }
                    d to map
                }
                val newModels = withContext(Dispatchers.IO) {
                    buildDeviceUiModels(context, api, apiConfigLoaded, rawDevices, newSharedFolders)
                }
                if (newModels != devices) {
                    devices = newModels
                    sharedFoldersByDevice = newSharedFolders
                }
            } catch (e: ConfigXml.OpenConfigException) {
                devices = null
            }
            delay(Constants.REST_UPDATE_INTERVAL)
        }
    }

    CompositionLocalProvider(
        LocalHomeFolderModels provides folders,
        LocalHomeDeviceModels provides devices,
        content = content,
    )
}
