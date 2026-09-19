package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder
import com.nutomic.syncthingandroid.ui.theme.ApplicationTheme
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.adaptive.ListDetailPaneContent
import com.nutomic.syncthingandroid.ui.adaptive.ListDetailPaneRole
import com.nutomic.syncthingandroid.ui.adaptive.listDetailDetailPaneMetadata
import com.nutomic.syncthingandroid.ui.nav.AppNavDisplay
import com.nutomic.syncthingandroid.ui.nav.AppRoute
import com.nutomic.syncthingandroid.ui.nav.BackPressGuard
import com.nutomic.syncthingandroid.ui.nav.clearAfterLast
import com.nutomic.syncthingandroid.ui.nav.EditStateStore
import com.nutomic.syncthingandroid.ui.nav.IntentAppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalResultBus
import com.nutomic.syncthingandroid.ui.nav.ResultBus
import com.nutomic.syncthingandroid.ui.nav.replaceAfterLast
import com.nutomic.syncthingandroid.ui.dialogs.ConfirmDialog
import com.nutomic.syncthingandroid.ui.screens.device.DeviceEditStateHolder
import com.nutomic.syncthingandroid.ui.screens.device.LocalDeviceEditStateStore
import com.nutomic.syncthingandroid.ui.screens.device.deviceEditStateKey
import com.nutomic.syncthingandroid.ui.screens.home.HomeDataHost
import com.nutomic.syncthingandroid.ui.screens.home.HomeScreen
import com.nutomic.syncthingandroid.ui.screens.folder.FolderEditStateHolder
import com.nutomic.syncthingandroid.ui.screens.folder.LocalFolderEditStateStore
import com.nutomic.syncthingandroid.ui.screens.folder.folderEditStateKey
import com.nutomic.syncthingandroid.ui.screens.log.LogScreen
import com.nutomic.syncthingandroid.ui.screens.syncconditions.SyncConditionsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.nutomic.syncthingandroid.ui.screens.webview.WebViewScreen
import com.nutomic.syncthingandroid.util.PermissionUtil

/**
 * Single activity app shell: hosts the Compose navigation with folders/devices/status.
 * Ported from the legacy View based MainActivity.
 */
class MainActivity : SyncthingActivity(), OnServiceStateChangeListener {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Intent action to exit app.
         */
        const val ACTION_EXIT = ".MainActivity.EXIT"
    }

    lateinit var preferences: SharedPreferences

    private var serviceState by mutableStateOf(SyncthingService.State.INIT)
    private val resultBus = ResultBus()

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        serviceState = currentState
    }

    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = (application as SyncthingApp).preferences
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // SyncthingService needs to be started from this activity as the user
        // can directly launch this activity from the recent activity switcher.
        val serviceIntent = Intent(this, SyncthingService::class.java)
        startForegroundService(serviceIntent)

        handleExitIntent(intent)

        setContent {
            ApplicationTheme {
                val backStack = rememberSerializable(
                    serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer())
                ) {
                    NavBackStack(listOfNotNull<AppRoute>(AppRoute.Home).toMutableStateList())
                }
                // Edit states live outside NavDisplay so covered entries keep their drafts.
                // They are also consulted by the persistent Home pane before replacing a
                // detail: otherwise tapping another row silently discards unsaved edits.
                val folderEditStateStore = remember { EditStateStore { FolderEditStateHolder() } }
                val deviceEditStateStore = remember { EditStateStore { DeviceEditStateHolder() } }
                val pendingHomeAction = remember { mutableStateOf<PendingHomeAction?>(null) }
                val backGuard = remember { BackPressGuard() }
                val navigator = remember(
                    backStack,
                    folderEditStateStore,
                    deviceEditStateStore,
                    pendingHomeAction,
                    backGuard,
                ) {
                    object : IntentAppNavigator(this@MainActivity) {
                        override fun navigateTo(route: AppRoute) {
                            // Ignore taps that would push the destination that is already
                            // open (double taps, or tapping the item again while its editor
                            // sits in the detail pane): back would return to the same
                            // screen again instead of moving on.
                            if (backStack.lastOrNull() == route) return
                            backStack.add(route)
                        }

                        override fun navigateToHomeDetail(route: AppRoute) {
                            requestHomeAction(PendingHomeAction.Navigate(route))
                        }

                        override fun clearHomeDetail() {
                            requestHomeAction(PendingHomeAction.Clear)
                        }

                        private fun requestHomeAction(action: PendingHomeAction) {
                            if (pendingHomeAction.value != null) return
                            when (
                                currentEditorExitState(
                                    backStack,
                                    folderEditStateStore,
                                    deviceEditStateStore,
                                    retainedRoute = (action as? PendingHomeAction.Navigate)?.route,
                                )
                            ) {
                                EditorExitState.Saving -> return
                                EditorExitState.Dirty -> pendingHomeAction.value = action
                                EditorExitState.Clean -> performHomeAction(
                                    action = action,
                                    backStack = backStack,
                                    backGuard = backGuard,
                                )
                            }
                        }

                        override fun navigateBack() {
                            if (backStack.size > 1) {
                                backGuard.recordPop()
                                backStack.removeAt(backStack.lastIndex)
                            } else if (backGuard.mayLeaveStack()) {
                                // Leave MainActivity in its state as the home button was pressed.
                                moveTaskToBack(true)
                            }
                        }

                        override fun showDeviceIdDialog() = showQrCodeDialog()

                        override fun confirmRestart() {
                            resultBus.restartRequested.value = true
                        }
                    }
                }
                // Unsaved edit drafts must survive being covered by another route
                // (Nav3 disposes non-top entries), but must NOT survive the route
                // leaving the back stack - see EditStateStore.
                LaunchedEffect(backStack, folderEditStateStore, deviceEditStateStore) {
                    snapshotFlow { backStack.toList() }
                        .map { stack ->
                            Pair(
                                stack.filterIsInstance<AppRoute.FolderEdit>()
                                    .map { folderEditStateKey(it.folderId, it.isCreate) }.toSet(),
                                stack.filterIsInstance<AppRoute.DeviceEdit>()
                                    .map { deviceEditStateKey(it.deviceId, it.isCreate) }.toSet(),
                            )
                        }
                        .distinctUntilChanged()
                        .collect { (liveFolderKeys, liveDeviceKeys) ->
                            folderEditStateStore.retainAll(liveFolderKeys)
                            deviceEditStateStore.retainAll(liveDeviceKeys)
                        }
                }

                CompositionLocalProvider(
                    LocalSyncthingService provides service,
                    LocalServiceState provides serviceState,
                    LocalAppNavigator provides navigator,
                    LocalResultBus provides resultBus,
                    LocalFolderEditStateStore provides folderEditStateStore,
                    LocalDeviceEditStateStore provides deviceEditStateStore,
                ) {
                    // Hoists the home list polling above the NavDisplay so the lists
                    // survive entry transitions (see HomeDataHost).
                    HomeDataHost {
                        AppNavDisplay(
                            backStack = backStack,
                            onBack = { navigator.navigateBack() },
                            entryProvider = {
                                entry<AppRoute.Home>(
                                    // Wide windows show the home list and the editor side
                                    // by side; the pane must fit the rail plus the list.
                                    metadata = ListDetailSceneStrategy.listPane() +
                                        ListDetailSceneStrategy.preferredPaneSize(width = 440.dp),
                                ) {
                                    ListDetailPaneContent(ListDetailPaneRole.List) {
                                        val homeIndex = backStack.indexOfLast { it == AppRoute.Home }
                                        val selectedDetail = backStack.getOrNull(homeIndex + 1)
                                        HomeScreen(
                                            onExitApp = { doExit() },
                                            selectedFolderId =
                                                (selectedDetail as? AppRoute.FolderEdit)?.folderId,
                                            selectedDeviceId =
                                                (selectedDetail as? AppRoute.DeviceEdit)?.deviceId,
                                        )
                                    }
                                }
                            entry<AppRoute.Log> {
                                LogScreen(onBack = { navigator.navigateBack() })
                            }
                            entry<AppRoute.WebView> { route ->
                                WebViewScreen(webPageUrl = route.url, onBack = { navigator.navigateBack() })
                            }
                            entry<AppRoute.SyncConditions>(
                                metadata = listDetailDetailPaneMetadata(),
                            ) { route ->
                                ListDetailPaneContent(ListDetailPaneRole.Detail) {
                                    SyncConditionsScreen(
                                        objectPrefixAndId = route.objectPrefixAndId,
                                        objectReadableName = route.objectReadableName,
                                        onBack = { navigator.navigateBack() },
                                    )
                                }
                            }
                            entry<AppRoute.FolderPicker>(
                                metadata = listDetailDetailPaneMetadata(),
                            ) { route ->
                                ListDetailPaneContent(ListDetailPaneRole.Detail) {
                                    com.nutomic.syncthingandroid.ui.screens.folderpicker.FolderPickerScreen(
                                        initialDirectory = route.initialDirectory,
                                        rootDirectory = route.rootDirectory,
                                        onResult = { path ->
                                            if (path != null) {
                                                resultBus.folderPickerResult.value = path
                                            }
                                            navigator.navigateBack()
                                        }
                                    )
                                }
                            }
                            entry<AppRoute.DeviceEdit>(
                                metadata = listDetailDetailPaneMetadata(),
                            ) { route ->
                                ListDetailPaneContent(ListDetailPaneRole.Detail) {
                                    com.nutomic.syncthingandroid.ui.screens.device.DeviceEditScreen(
                                        deviceId = route.deviceId,
                                        deviceName = route.deviceName,
                                        isCreate = route.isCreate,
                                        notificationId = route.notificationId,
                                    )
                                }
                            }
                            entry<AppRoute.FolderEdit>(
                                metadata = listDetailDetailPaneMetadata(),
                            ) { route ->
                                ListDetailPaneContent(ListDetailPaneRole.Detail) {
                                    com.nutomic.syncthingandroid.ui.screens.folder.FolderEditScreen(
                                        folderId = route.folderId,
                                        folderLabel = route.folderLabel,
                                        isCreate = route.isCreate,
                                        deviceId = route.deviceId,
                                        receiveEncrypted = route.receiveEncrypted,
                                        notificationId = route.notificationId,
                                    )
                                }
                            }
                        },
                    )
                    }
                    com.nutomic.syncthingandroid.ui.dialogs.MainActivityDialogsHost()
                }
                pendingHomeAction.value?.let { action ->
                    ConfirmDialog(
                        message = stringResource(R.string.dialog_discard_changes),
                        onConfirm = {
                            pendingHomeAction.value = null
                            performHomeAction(action, backStack, backGuard)
                        },
                        onDismiss = { pendingHomeAction.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExitIntent(intent)
    }

    private fun handleExitIntent(intent: Intent?) {
        val action = intent?.action
        if (ACTION_EXIT == action) {
            Log.i(TAG, "Exit app requested by notification action")
            stopService(Intent(this, SyncthingService::class.java))
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if storage permission has been revoked at runtime.
        if (!PermissionUtil.haveStoragePermission(this)) {
            startActivity(Intent(this, com.nutomic.syncthingandroid.activities.OnboardingActivity::class.java))
            finish()
            return
        }
        // Evaluate run conditions to detect changes made to the metered wifi flags.
        service?.evaluateRunConditions()
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        val binder = iBinder as SyncthingServiceBinder
        binder.service.registerOnServiceStateChangeListener(this)
    }

    override fun onDestroy() {
        service?.unregisterOnServiceStateChangeListener(this)
        super.onDestroy()
    }

    /**
     * Exits the application by stopping the service and finishing the activity.
     */
    fun doExit() {
        if (isFinishing) {
            return
        }
        Log.i(TAG, "Exiting app on user request")
        stopService(Intent(this, SyncthingService::class.java))
        finishAndRemoveTask()
    }

    private fun showQrCodeDialog() {
        val deviceId = preferences.getString(Constants.PREF_LOCAL_DEVICE_ID, "") ?: ""
        if (deviceId.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.could_not_access_deviceid, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // The dialog itself is rendered by MainActivityDialogsHost inside the composition.
        resultBus.showDeviceIdDialog.value = deviceId
    }
}

private sealed interface PendingHomeAction {
    data class Navigate(val route: AppRoute) : PendingHomeAction
    data object Clear : PendingHomeAction
}

internal enum class EditorExitState { Clean, Dirty, Saving }

/** Whether replacing the persistent Home detail is currently safe. */
internal fun currentEditorExitState(
    backStack: List<AppRoute>,
    folderStore: EditStateStore<FolderEditStateHolder>,
    deviceStore: EditStateStore<DeviceEditStateHolder>,
    retainedRoute: AppRoute?,
): EditorExitState {
    var dirty = false
    for (route in backStack) {
        val state = when (route) {
            is AppRoute.FolderEdit -> {
                val key = folderEditStateKey(route.folderId, route.isCreate)
                val retained = (retainedRoute as? AppRoute.FolderEdit)?.let {
                    folderEditStateKey(it.folderId, it.isCreate)
                } == key
                if (retained) null else folderStore.stateOrNull(key)
                    ?.let { it.needsUpdate to it.isSaving }
            }
            is AppRoute.DeviceEdit -> {
                val key = deviceEditStateKey(route.deviceId, route.isCreate)
                val retained = (retainedRoute as? AppRoute.DeviceEdit)?.let {
                    deviceEditStateKey(it.deviceId, it.isCreate)
                } == key
                if (retained) null else deviceStore.stateOrNull(key)
                    ?.let { it.needsUpdate to false }
            }
            else -> null
        } ?: continue
        if (state.second) return EditorExitState.Saving
        dirty = dirty || state.first
    }
    return if (dirty) EditorExitState.Dirty else EditorExitState.Clean
}

private fun performHomeAction(
    action: PendingHomeAction,
    backStack: MutableList<AppRoute>,
    backGuard: BackPressGuard,
) {
    when (action) {
        is PendingHomeAction.Navigate -> backStack.replaceAfterLast(
            isAnchor = { it == AppRoute.Home },
            route = action.route,
        )
        PendingHomeAction.Clear -> {
            if (backStack.lastOrNull() != AppRoute.Home) backGuard.recordPop()
            backStack.clearAfterLast { it == AppRoute.Home }
        }
    }
}
