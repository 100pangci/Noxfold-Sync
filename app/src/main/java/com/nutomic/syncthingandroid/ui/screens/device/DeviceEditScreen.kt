package com.nutomic.syncthingandroid.ui.screens.device

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.TABLET_MIN_WIDTH_DP
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.contentWidthForTablet
import com.nutomic.syncthingandroid.ui.dialogs.CompressionDialog
import com.nutomic.syncthingandroid.ui.dialogs.ConfirmDialog
import com.nutomic.syncthingandroid.ui.dialogs.DeviceIdQrDialog
import com.nutomic.syncthingandroid.ui.nav.AppRoute
import com.nutomic.syncthingandroid.ui.nav.EditStateStore
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.util.Compression
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class FolderShareState(
    val folder: Folder,
    val shared: Boolean,
    val password: String,
)

/**
 * Per-field compose state for the device edit screen. Fields are kept
 * individually (instead of one data class holding the mutable Java model) so
 * that every change reliably triggers recomposition. The draft device model
 * lives here too (NOT in rememberSaveable): the whole holder is store-backed
 * so the draft survives being covered by another route, and is evicted when
 * the route leaves the back stack - avoiding the stale-draft resurrection a
 * SaveableStateHolder-backed value would risk.
 */
internal class DeviceEditStateHolder {
    var device by mutableStateOf<Device?>(null)
    var needsUpdate by mutableStateOf(false)
    var folderStates by mutableStateOf<List<FolderShareState>>(emptyList())
    var customSyncConditions by mutableStateOf(false)
    var compressionIndex by mutableStateOf(Compression.METADATA.index)
    var deviceIdText by mutableStateOf("")

    /**
     * Group names currently used by the configured devices, offered as
     * dropdown suggestions in the group picker. Filled once when the draft
     * is initialized.
     */
    var groupOptions by mutableStateOf<List<String>>(emptyList())
}

/**
 * Store providing [DeviceEditStateHolder] outside the Navigation 3 entry
 * composition, keyed by [deviceEditStateKey].
 */
internal val LocalDeviceEditStateStore =
    staticCompositionLocalOf<EditStateStore<DeviceEditStateHolder>> {
        error("DeviceEditStateStore not provided")
    }

/**
 * Stable identity of a device edit session; independent of the
 * notification/scan extras (same lifecycle contract as folderEditStateKey).
 */
internal fun deviceEditStateKey(deviceId: String?, isCreate: Boolean): String =
    if (isCreate || deviceId == null) "create" else "edit:$deviceId"

/**
 * Device add/edit screen, ported from the legacy DeviceActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditScreen(
    deviceId: String?,
    deviceName: String?,
    isCreate: Boolean,
    notificationId: Int,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val service = LocalSyncthingService.current
    val serviceState = LocalServiceState.current
    val api = service?.api
    val apiConfigLoaded = api?.isConfigLoaded ?: false
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val configRouter = remember { ConfigRouter(context) }
    val preferences = context.appPreferences()
    val prefExpertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false)

    // Draft state is store-backed (NOT remember/rememberSaveable): Nav3 disposes this
    // entry while the sync conditions route is on top, and the draft has to survive
    // that while staying evictable when the edit session ends. See EditStateStore.
    val holder = LocalDeviceEditStateStore.current.stateFor(deviceEditStateKey(deviceId, isCreate))
    var device by holder::device

    // Init the model once. Holder-keyed: see FolderEditScreen's init effect comment
    // on Nav3 composition reuse across a fast pop -> re-push of the same route.
    LaunchedEffect(holder) {
        if (device != null) return@LaunchedEffect
        val d = if (isCreate) {
            Device().apply {
                name = deviceName ?: ""
                deviceID = deviceId ?: ""
                addresses = listOf("dynamic")
                compression = Compression.METADATA.getValue(context)
            }
        } else {
            var found: Device? = null
            // config.xml DOM parse (api is deliberately null here): keep it off the
            // main thread so the enter transition stays smooth.
            for (current in withContext(Dispatchers.IO) { configRouter.getDevices(null, false) }) {
                if (current.deviceID == (deviceId ?: "")) {
                    found = current
                    break
                }
            }
            if (found == null) {
                // Device not found, maybe it was deleted.
                navigator.navigateBack()
                return@LaunchedEffect
            }
            found
        }
        device = d
        syncHolderFromDevice(holder, d, context, isCreate, preferences)
        // Group name suggestions = the groups of all configured remote
        // devices; a corrupt config.xml must not block the editor here.
        holder.groupOptions = try {
            withContext(Dispatchers.IO) { configRouter.getDevices(api, false) }
                .mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .sorted()
        } catch (e: ConfigXml.OpenConfigException) {
            emptyList()
        }
    }

    var discoveredDevices by remember { mutableStateOf<Map<String, DiscoveredDevice>?>(null) }
    // Folder list is a full-config Gson deep copy (or a config.xml DOM parse when the
    // api is down): load it off the main thread instead of blocking composition.
    var folders by remember(apiConfigLoaded) { mutableStateOf<List<Folder>?>(null) }
    LaunchedEffect(apiConfigLoaded) {
        folders = withContext(Dispatchers.IO) { configRouter.getFolders(api) }
    }

    // Refresh folder share states whenever the folders list or device id changes.
    // The delay keeps the state-write recomposition out of the enter transition.
    LaunchedEffect(folders, device?.deviceID) {
        delay(450)
        val currentFolders = folders ?: return@LaunchedEffect
        val d = device ?: return@LaunchedEffect
        holder.folderStates = currentFolders.map { folder ->
            val shared = folder.getDevice(d.deviceID) != null
            val password = folder.getDevice(d.deviceID)?.encryptionPassword ?: ""
            FolderShareState(folder, shared, password)
        }
    }

    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDialog by rememberSaveable { mutableStateOf(false) }
    var showCompressionDialog by rememberSaveable { mutableStateOf(false) }
    var discoveryRefresh by rememberSaveable { mutableStateOf(0) }

    val qrScanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val scanned = result.contents
        if (!scanned.isNullOrEmpty()) {
            device?.let { it.deviceID = scanned }
            holder.deviceIdText = scanned
            holder.needsUpdate = true
        }
    }

    // Cancel the consent notification once the service is connected. On a
    // cold start from the notification tap the service is not yet bound while
    // the init effect runs, so cancellation has to react to the service
    // becoming available instead.
    LaunchedEffect(service) {
        service?.notificationHandler?.cancelConsentNotification(notificationId)
    }

    // Query discovered devices in create mode (when the id field is still empty).
    LaunchedEffect(isCreate, apiConfigLoaded, holder.deviceIdText.isEmpty(), discoveryRefresh) {
        if (isCreate && apiConfigLoaded && holder.deviceIdText.isEmpty()) {
            api?.getDiscoveredDevices { result ->
                discoveredDevices = result
            }
        }
    }

    // Intercept back only while the draft is dirty: a clean draft lets the event reach
    // NavDisplay so the predictive pop transition (peek of the previous screen) plays,
    // a dirty draft shows the discard dialog instead (no peek, standard UX).
    BackHandler(enabled = holder.needsUpdate) {
        showDiscardDialog = true
    }

    fun save() {
        val d = device ?: return
        if (d.deviceID.isNullOrEmpty()) {
            Toast.makeText(context, R.string.device_id_required, Toast.LENGTH_LONG).show()
            return
        }
        if (!d.checkDeviceID()) {
            Toast.makeText(context, R.string.device_id_invalid, Toast.LENGTH_LONG).show()
            return
        }
        if (!d.checkDeviceAddresses()) {
            Toast.makeText(context, R.string.device_addresses_invalid, Toast.LENGTH_LONG).show()
            return
        }
        // Apply folder sharing + encryption passwords.
        for (state in holder.folderStates) {
            val folder = state.folder
            if (state.shared) {
                folder.addDevice(d)
                folder.getDevice(d.deviceID)?.let { it.encryptionPassword = state.password }
            } else {
                folder.removeDevice(d.deviceID)
            }
            configRouter.updateFolder(api, folder)
        }
        if (isCreate) {
            configRouter.updateDevice(api, d)
            navigator.navigateBack()
            return
        }
        if (!holder.needsUpdate) {
            navigator.navigateBack()
            return
        }
        preferences.edit().putBoolean(
            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                Constants.PREF_OBJECT_PREFIX_DEVICE + d.deviceID
            ),
            holder.customSyncConditions
        ).apply()
        configRouter.updateDevice(api, d)
        navigator.navigateBack()
    }

    val scanPrompt = stringResource(R.string.scan_qr_code_description)

    Surface(color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(if (isCreate) R.string.add_device else R.string.edit_device))
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (holder.needsUpdate) showDiscardDialog = true
                            else navigator.navigateBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
                        }
                    },
                    actions = {
                        if (!isCreate) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.delete_device))
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (device != null) {
                    FloatingActionButton(
                        onClick = { save() },
                        modifier = Modifier.imePadding()
                    ) {
                        Icon(
                            Icons.Outlined.Save,
                            stringResource(if (isCreate) R.string.create else R.string.save_title)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Keep the viewport above the IME (see FolderEditScreen comment):
                    // prevents system window pan + FAB imePadding double-counting.
                    .imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val d = device
                if (d != null) {
                    DeviceEditContent(
                        modifier = Modifier
                            .contentWidthForTablet(isTablet)
                            .fillMaxSize(),
                        device = d,
                        holder = holder,
                        isCreate = isCreate,
                        prefExpertMode = prefExpertMode,
                        discoveredDevices = discoveredDevices,
                        onDeviceMutate = { mutate ->
                            mutate(d)
                            holder.needsUpdate = true
                        },
                        onCompressionClick = { showCompressionDialog = true },
                        onScanQr = {
                            qrScanLauncher.launch(
                                com.journeyapps.barcodescanner.ScanOptions()
                                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                    .setPrompt(scanPrompt)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(true)
                            )
                        },
                        onShowQr = { showQrDialog = true },
                        onOpenSyncConditions = {
                            navigator.navigateTo(
                                AppRoute.SyncConditions(
                                    objectPrefixAndId = Constants.PREF_OBJECT_PREFIX_DEVICE + d.deviceID,
                                    objectReadableName = d.displayName
                                )
                            )
                        },
                        onOpenFolderEdit = { navigator.openFolderEdit(null, true) },
                        onRefreshDiscovery = { discoveryRefresh++ },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.syncthing_starting),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }

    if (showQrDialog) {
        val d = device
        if (d != null) {
            DeviceIdQrDialog(
                deviceName = d.displayName.trim(),
                deviceId = d.deviceID,
                isCurrentDevice = false,
                onDismiss = { showQrDialog = false }
            )
        } else {
            showQrDialog = false
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            message = stringResource(R.string.remove_device_confirm),
            onConfirm = {
                showDeleteDialog = false
                configRouter.removeDevice(api, device?.deviceID)
                navigator.navigateBack()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showDiscardDialog) {
        ConfirmDialog(
            message = stringResource(R.string.dialog_discard_changes),
            onConfirm = {
                showDiscardDialog = false
                navigator.navigateBack()
            },
            onDismiss = { showDiscardDialog = false }
        )
    }

    if (showCompressionDialog) {
        CompressionDialog(
            selectedIndex = holder.compressionIndex,
            onSelect = { index ->
                val compression = Compression.fromIndex(index)
                showCompressionDialog = false
                if (compression.index != holder.compressionIndex) {
                    device?.let { it.compression = compression.getValue(context) }
                    holder.compressionIndex = index
                    holder.needsUpdate = true
                }
            },
            onDismiss = { showCompressionDialog = false }
        )
    }
}

private fun syncHolderFromDevice(
    holder: DeviceEditStateHolder,
    device: Device,
    context: android.content.Context,
    isCreate: Boolean,
    preferences: android.content.SharedPreferences,
) {
    holder.deviceIdText = device.deviceID ?: ""
    holder.compressionIndex = Compression.fromValue(context, device.compression).index
    holder.needsUpdate = isCreate
    holder.customSyncConditions = if (isCreate) false else preferences.getBoolean(
        Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
            Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
        ), false
    )
}
