package com.nutomic.syncthingandroid.ui.screens.folder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import com.google.gson.Gson
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SafBridge
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.dialogs.ConfirmDialog
import com.nutomic.syncthingandroid.ui.nav.AppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalResultBus
import com.nutomic.syncthingandroid.ui.nav.ResultBus
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils

private const val TAG = "FolderEditScreen"

/**
 * Folder add/edit screen, ported from the legacy FolderActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderEditScreen(
    folderId: String?,
    folderLabel: String?,
    isCreate: Boolean,
    deviceId: String?,
    receiveEncrypted: Boolean,
    notificationId: Int,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val service = LocalSyncthingService.current
    val serviceState = LocalServiceState.current
    val api = service?.api
    val apiConfigLoaded = api?.isConfigLoaded ?: false
    val configRouter = remember { ConfigRouter(context) }
    val preferences = context.appPreferences()
    val scope = rememberCoroutineScope()
    val resultBus = LocalResultBus.current

    val prefExpertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false)
    // Draft state is store-backed (NOT remember): Nav3 disposes this entry while the
    // sync conditions / folder picker routes are on top, and the draft has to survive
    // that. See EditStateStore for the eviction lifecycle.
    val holder = LocalFolderEditStateStore.current.stateFor(folderEditStateKey(folderId, isCreate))
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showFolderTypeDialog by rememberSaveable { mutableStateOf(false) }
    var showPullOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showVersioningDialog by rememberSaveable { mutableStateOf(false) }

    // Init the model once. Keyed by the holder INSTANCE, not Unit: Nav3 can keep a
    // composition alive across a pop -> re-push of the same route (fast exit during
    // the enter transition), and a Unit-keyed effect that was consumed/cancelled in
    // that limbo state never runs again, leaving folder == null forever (black
    // screen). A fresh holder instance re-keys the effect and the init self-heals.
    LaunchedEffect(holder) {
        initFolderEditState(
            context, holder, isCreate, folderId, folderLabel, receiveEncrypted,
            deviceId, notificationId, api, configRouter, navigator, preferences,
        )
    }
    // Cancel the consent notification once the service is connected. On a
    // cold start from the notification tap the service is not yet bound while
    // the init effect runs, so cancellation has to react to the service
    // becoming available instead.
    LaunchedEffect(service) {
        service?.notificationHandler?.cancelConsentNotification(notificationId)
    }
    // Refresh device share states when the api becomes available or folder changes.
    // A draft that is still untouched also gets rebased onto the live REST config
    // once the api is up: init may have loaded it from the config.xml fallback
    // (core not started yet / config import in progress) and saving such a stale
    // snapshot would clobber newer share state with a whole-folder PUT.
    // The delay keeps the REST-callback recompositions out of the enter transition.
    LaunchedEffect(apiConfigLoaded, holder.folder?.id) {
        delay(450)
        if (!isCreate && apiConfigLoaded && holder.folder != null && !holder.needsUpdate) {
            resyncCleanDraftFromApi(context, api, configRouter, holder, navigator)
        }
        refreshDeviceShareStates(configRouter, api, holder)
    }
    // Collect folder picker result pushed by the FolderPicker route.
    // Holder-keyed: see the init effect comment on Nav3 composition reuse.
    LaunchedEffect(holder) {
        collectFolderPickerResults(resultBus, context, holder)
    }
    // Intercept back only while the draft is dirty: a clean draft lets the event reach
    // NavDisplay so the predictive pop transition (peek of the previous screen) plays,
    // a dirty draft shows the discard dialog instead (no peek, standard UX).
    BackHandler(enabled = holder.needsUpdate && !holder.isSaving) {
        showDiscardDialog = true
    }
    // SAF directory picker used when the system picker is available.
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Survive reboots: without the persisted grant the provider access is lost.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "takePersistableUriPermission failed for $uri", e)
            }
            if (SafBridge.requiresBridge(uri)) {
                // Third-party DocumentsProvider root: forward it into a real folder
                // the Syncthing core can sync.
                val safBridge = (context.applicationContext as SyncthingApp).safBridge
                val currentPath = holder.folder?.path
                val forwardedPath = if (currentPath != null && safBridge.isForwardedPath(currentPath)) {
                    // An existing forwarded folder being (re-)authorized: keep the
                    // configured path and reset the stale snapshot. Deciding by the
                    // path (NOT by grant state) is important: the fresh grant taken
                    // above would mask a lost-grant situation and skip the reset.
                    safBridge.reauthorize(currentPath, uri)
                    currentPath
                } else {
                    safBridge.register(uri)
                }
                holder.folderUri = uri
                onPickedPath(context, holder, forwardedPath)
                Toast.makeText(context, R.string.saf_bridge_folder_mapped, Toast.LENGTH_LONG).show()
            } else {
                holder.folderUri = uri
                val targetPath = FileUtils.getAbsolutePathFromSAFUri(context, uri)
                onPickedPath(context, holder, targetPath)
            }
        }
    }
    val f = holder.folder

    // A forwarded folder whose SAF grant was lost (fresh install + config import)
    // pops the SAF picker automatically on entry, one folder at a time. Once
    // re-authorized the path stays unchanged, so the picker is not offered again.
    LaunchedEffect(f?.path) {
        val path = f?.path ?: return@LaunchedEffect
        if ((context.applicationContext as SyncthingApp).safBridge.needsAuthorization(path)) {
            Toast.makeText(context, R.string.saf_bridge_needs_authorization, Toast.LENGTH_LONG).show()
            safLauncher.launch(null)
        }
    }

    fun saveFolder() {
        val folder = holder.folder ?: return
        FolderEditActions.save(
            scope = scope,
            context = context,
            navigator = navigator,
            configRouter = configRouter,
            api = api,
            preferences = preferences,
            folder = folder,
            folderUri = holder.folderUri,
            needsUpdate = holder.needsUpdate,
            ignoreListNeedsUpdate = holder.ignoreListNeedsUpdate,
            ignoreListText = holder.ignoreListText,
            deviceStates = holder.deviceStates,
            customSyncConditions = holder.customSyncConditions,
            runScript = holder.runScript,
            isCreate = isCreate,
            isSaving = holder.isSaving,
            setSaving = { holder.isSaving = it },
            onValidationError = { res ->
                Toast.makeText(context, res, Toast.LENGTH_LONG).show()
            },
        )
    }
    Scaffold(
        topBar = {
            FolderEditTopBar(
                holder = holder, isCreate = isCreate,
                onDiscardChanges = { showDiscardDialog = true },
                onDelete = { showDeleteDialog = true },
            )
        },
        floatingActionButton = {
            if (f != null) {
                FloatingActionButton(
                    onClick = { saveFolder() },
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
        FolderEditBody(
            holder = holder, folder = f, isCreate = isCreate,
            prefExpertMode = prefExpertMode, innerPadding = innerPadding,
            launchSafPicker = { safLauncher.launch(it) },
            onFolderTypeDialogApproved = { showFolderTypeDialog = true },
            onShowPullOrderDialog = { showPullOrderDialog = true },
            onShowVersioningDialog = { showVersioningDialog = true },
            onMarkDirty = { holder.needsUpdate = true },
        )
    }
    // ---- Dialogs ----
    FolderEditConfigDialogs(
        folder = f, isCreate = isCreate, holder = holder,
        showFolderTypeDialog = showFolderTypeDialog,
        showPullOrderDialog = showPullOrderDialog,
        showVersioningDialog = showVersioningDialog,
        onDismissFolderTypeDialog = { showFolderTypeDialog = false },
        onDismissPullOrderDialog = { showPullOrderDialog = false },
        onDismissVersioningDialog = { showVersioningDialog = false },
    )
    FolderEditConfirmDialogs(
        folder = f,
        showDeleteDialog = showDeleteDialog,
        showDiscardDialog = showDiscardDialog,
        configRouter = configRouter, api = api, preferences = preferences,
        onDismissDeleteDialog = { showDeleteDialog = false },
        onDismissDiscardDialog = { showDiscardDialog = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditTopBar(
    holder: FolderEditStateHolder,
    isCreate: Boolean,
    onDiscardChanges: () -> Unit,
    onDelete: () -> Unit,
) {
    val navigator = LocalAppNavigator.current
    TopAppBar(
        title = {
            Text(stringResource(if (isCreate) R.string.create_folder else R.string.edit_folder))
        },
        navigationIcon = {
            IconButton(onClick = {
                if (holder.isSaving) return@IconButton
                if (holder.needsUpdate) onDiscardChanges()
                else navigator.navigateBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
            }
        },
        actions = {
            if (!isCreate) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete_folder))
                }
            }
        }
    )
}

@Composable
private fun FolderEditBody(
    holder: FolderEditStateHolder,
    folder: Folder?,
    isCreate: Boolean,
    prefExpertMode: Boolean,
    innerPadding: PaddingValues,
    launchSafPicker: (Uri?) -> Unit,
    onFolderTypeDialogApproved: () -> Unit,
    onShowPullOrderDialog: () -> Unit,
    onShowVersioningDialog: () -> Unit,
    onMarkDirty: () -> Unit,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // Keep the scrollable viewport above the IME so a focused field at the
            // bottom (ignore patterns) is scrolled into view by Compose itself.
            // Without this the system pans the whole window as a fallback and the
            // FAB's own imePadding double-counts, pushing it far above the keyboard.
            .imePadding()
    ) {
        if (folder != null) {
            FolderEditContent(
                holder = holder,
                folder = folder,
                isCreate = isCreate,
                prefExpertMode = prefExpertMode,
                onMarkDirty = onMarkDirty,
                onIgnoreListChanged = {
                    holder.ignoreListText = it
                    holder.ignoreListNeedsUpdate = true
                    holder.needsUpdate = true
                },
                onPickPath = {
                    // Prefer the storage access framework picker with a sensible initial uri.
                    var initialUri: Uri? = null
                    val externalFilesDirUri =
                        FileUtils.getExternalFilesDirUri(context, FileUtils.ExternalStorageDirType.INT_MEDIA)
                    if (FileUtils.directoryUriExists(context, externalFilesDirUri)) {
                        initialUri = externalFilesDirUri
                    } else {
                        val internalFilesDirUri = FileUtils.getInternalStorageRootUri()
                        if (FileUtils.directoryUriExists(context, internalFilesDirUri)) {
                            initialUri = internalFilesDirUri
                        }
                    }
                    try {
                        launchSafPicker(initialUri)
                    } catch (e: android.content.ActivityNotFoundException) {
                        navigator.openFolderPicker(holder.folder?.path, null)
                    }
                },
                onPickAdvancedPath = { navigator.openFolderPicker(holder.folder?.path, null) },
                onShowFolderTypeDialog = {
                    onFolderTypeDialogRequest(context, holder) { ok ->
                        if (ok) onFolderTypeDialogApproved()
                    }
                },
                onShowPullOrderDialog = onShowPullOrderDialog,
                onShowVersioningDialog = onShowVersioningDialog,
                onOpenSyncConditions = {
                    navigator.navigateTo(
                        com.nutomic.syncthingandroid.ui.nav.AppRoute.SyncConditions(
                            objectPrefixAndId = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id,
                            objectReadableName = folder.label
                        )
                    )
                },
                onOpenDeviceEdit = { navigator.openDeviceEdit(null, true) },
            )
        }
        if (holder.isSaving) {
            SavingOverlay()
        }
    }
}

@Composable
private fun FolderEditConfigDialogs(
    folder: Folder?,
    isCreate: Boolean,
    holder: FolderEditStateHolder,
    showFolderTypeDialog: Boolean,
    showPullOrderDialog: Boolean,
    showVersioningDialog: Boolean,
    onDismissFolderTypeDialog: () -> Unit,
    onDismissPullOrderDialog: () -> Unit,
    onDismissVersioningDialog: () -> Unit,
) {
    val context = LocalContext.current
    if (showFolderTypeDialog && folder != null) {
        com.nutomic.syncthingandroid.ui.dialogs.FolderTypeDialog(
            currentType = folder.type,
            onSelect = { newType ->
                onDismissFolderTypeDialog()
                if (!isCreate && newType == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
                    Toast.makeText(
                        context,
                        R.string.folder_type_switch_to_receive_encrypted_not_allowed,
                        Toast.LENGTH_LONG
                    ).show()
                    return@FolderTypeDialog
                }
                folder.type = newType
                holder.configVersion++
                holder.needsUpdate = true
            },
            onDismiss = onDismissFolderTypeDialog
        )
    }
    if (showPullOrderDialog && folder != null) {
        com.nutomic.syncthingandroid.ui.dialogs.PullOrderDialog(
            currentOrder = folder.order,
            onSelect = { order ->
                onDismissPullOrderDialog()
                folder.order = order
                holder.configVersion++
                holder.needsUpdate = true
            },
            onDismiss = onDismissPullOrderDialog
        )
    }
    if (showVersioningDialog && folder != null) {
        com.nutomic.syncthingandroid.ui.dialogs.VersioningDialog(
            initialType = folder.versioning?.type ?: "none",
            initialParams = folder.versioning?.params ?: emptyMap(),
            onApply = { type, params ->
                onDismissVersioningDialog()
                applyVersioning(folder, type, params)
                holder.configVersion++
                holder.needsUpdate = true
            },
            onDismiss = onDismissVersioningDialog
        )
    }
}

@Composable
private fun FolderEditConfirmDialogs(
    folder: Folder?,
    showDeleteDialog: Boolean,
    showDiscardDialog: Boolean,
    configRouter: ConfigRouter,
    api: RestApi?,
    preferences: SharedPreferences,
    onDismissDeleteDialog: () -> Unit,
    onDismissDiscardDialog: () -> Unit,
) {
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    if (showDeleteDialog && folder != null) {
        ConfirmDialog(
            message = stringResource(R.string.remove_folder_confirm),
            onConfirm = {
                onDismissDeleteDialog()
                // Drop the SAF forwarding bridge if this folder had one (no-op otherwise).
                (context.applicationContext as SyncthingApp).safBridge.unregister(folder.path)
                FolderEditActions.delete(configRouter, api, preferences, folder.id, navigator)
            },
            onDismiss = onDismissDeleteDialog
        )
    }
    if (showDiscardDialog) {
        ConfirmDialog(
            message = stringResource(R.string.dialog_discard_changes),
            onConfirm = {
                onDismissDiscardDialog()
                navigator.navigateBack()
            },
            onDismiss = onDismissDiscardDialog
        )
    }
}

private suspend fun initFolderEditState(
    context: Context,
    holder: FolderEditStateHolder,
    isCreate: Boolean,
    folderId: String?,
    folderLabel: String?,
    receiveEncrypted: Boolean,
    deviceId: String?,
    notificationId: Int,
    api: RestApi?,
    configRouter: ConfigRouter,
    navigator: AppNavigator,
    preferences: SharedPreferences,
) {
    if (holder.folder != null) return
    // One folder snapshot serves both the edit lookup and the group name
    // suggestions; loaded off the main thread in either mode. A corrupt
    // config.xml must not block "create" (the draft does not need it) and
    // "edit" then simply falls back to its existing "not found" handling.
    val folderList = try {
        withContext(Dispatchers.IO) { configRouter.getFolders(api) }
    } catch (e: ConfigXml.OpenConfigException) {
        emptyList()
    }
    if (isCreate) {
        holder.folder = initNewFolder(folderId, folderLabel, receiveEncrypted)
        holder.needsUpdate = true
    } else {
        // Prefer the live REST config (deep copy) over the config.xml fallback:
        // ConfigRouter falls back to the XML file when the api is not loaded yet.
        // Saving a draft that was loaded from a stale XML snapshot would clobber
        // newer share state (e.g. right after a config import) - updateFolder PUTs
        // the whole folder object, not a diff.
        var found: Folder? = null
        for (current in folderList) {
            if (current.id == (folderId ?: "")) {
                found = current
                break
            }
        }
        if (found == null) {
            navigator.navigateBack()
            return
        }
        holder.folder = found
        // Defer past the enter transition; the callback's state write recomposes the editor.
        delay(450)
        configRouter.getFolderIgnoreList(api, found) { list ->
            holder.ignoreListText = list.ignore?.joinToString("\n") ?: ""
        }
    }
    holder.groupOptions = folderList
        .mapNotNull { it.group?.trim()?.takeIf { group -> group.isNotEmpty() } }
        .distinct()
        .sorted()
    holder.customSyncConditions = if (isCreate) false else preferences.getBoolean(
        Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
            Constants.PREF_OBJECT_PREFIX_FOLDER + holder.folder!!.id
        ), false
    )
    holder.runScript = preferences.getBoolean(
        Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(holder.folder!!.id), false
    )
    // Evaluate the write access of the folder path so "folder type" and the
    // ignore patterns are enabled for folders that are only being edited.
    // The write test forks two shell subprocesses - never on the main thread.
    withContext(Dispatchers.IO) { checkWriteAndUpdateUI(context, holder) }
    // Automatically share with the given device (e.g. when accepting a folder share).
    deviceId?.let { devId ->
        holder.folder?.addDevice(com.nutomic.syncthingandroid.model.SharedWithDevice().apply {
            deviceID = devId
        })
        holder.needsUpdate = true
    }
}

/**
 * Rebases a still-untouched edit draft onto the live REST config. Called when the
 * api becomes available after [initFolderEditState] had to fall back to the
 * config.xml file (core not started yet / import still settling). Never runs on a
 * dirty draft: user edits must not be discarded.
 */
private suspend fun resyncCleanDraftFromApi(
    context: Context,
    api: RestApi?,
    configRouter: ConfigRouter,
    holder: FolderEditStateHolder,
    navigator: AppNavigator,
) {
    val folderId = holder.folder?.id ?: return
    var live: Folder? = null
    for (current in withContext(Dispatchers.IO) { configRouter.getFolders(api) }) {
        if (current.id == folderId) {
            live = current
            break
        }
    }
    if (live == null) {
        // The folder vanished from the live config while the editor was open.
        navigator.navigateBack()
        return
    }
    // Swapping the folder object invalidates every reader of holder.folder and
    // recomposes the whole editor tree. Skip the swap when the fresh copy is
    // content-identical to the draft (the common case: init already loaded from
    // the REST config), otherwise the recomposition lands right after the
    // enter transition as a visible hitch.
    val unchanged = withContext(Dispatchers.IO) {
        Gson().toJson(live) == Gson().toJson(holder.folder)
    }
    if (!unchanged) {
        holder.folder = live
    }
    holder.folderUri = null
    holder.needsUpdate = false
    configRouter.getFolderIgnoreList(api, live) { list ->
        holder.ignoreListText = list.ignore?.joinToString("\n") ?: ""
    }
    // The write test forks two shell subprocesses - never on the main thread.
    withContext(Dispatchers.IO) { checkWriteAndUpdateUI(context, holder) }
}

private suspend fun refreshDeviceShareStates(
    configRouter: ConfigRouter,
    api: RestApi?,
    holder: FolderEditStateHolder,
) {
    val f = holder.folder ?: return
    val devices = withContext(Dispatchers.IO) { configRouter.getDevices(api, false) }
    holder.deviceStates = devices.map { device ->
        val shared = f.getDevice(device.deviceID) != null
        val password = f.getDevice(device.deviceID)?.encryptionPassword ?: ""
        DeviceShareState(device, shared, password)
    }
}

private suspend fun collectFolderPickerResults(
    resultBus: ResultBus,
    context: Context,
    holder: FolderEditStateHolder,
) {
    resultBus.folderPickerResult.collect { path ->
        if (path != null) {
            onPickedPath(context, holder, path)
        }
        resultBus.folderPickerResult.value = null
    }
}
