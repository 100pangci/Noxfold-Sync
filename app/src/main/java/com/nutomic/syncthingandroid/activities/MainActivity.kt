package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import com.nutomic.syncthingandroid.ui.nav.AppNavDisplay
import com.nutomic.syncthingandroid.ui.nav.AppRoute
import com.nutomic.syncthingandroid.ui.nav.BackPressGuard
import com.nutomic.syncthingandroid.ui.nav.EditStateStore
import com.nutomic.syncthingandroid.ui.nav.IntentAppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalResultBus
import com.nutomic.syncthingandroid.ui.nav.ResultBus
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
                val navigator = remember(backStack) {
                    object : IntentAppNavigator(this@MainActivity) {
                        private val backGuard = BackPressGuard()

                        override fun navigateTo(route: AppRoute) {
                            backStack.add(route)
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
                val folderEditStateStore = remember { EditStateStore { FolderEditStateHolder() } }
                val deviceEditStateStore = remember { EditStateStore { DeviceEditStateHolder() } }
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
                                entry<AppRoute.Home> {
                                    HomeScreen(onExitApp = { doExit() })
                                }
                            entry<AppRoute.Log> {
                                LogScreen(onBack = { navigator.navigateBack() })
                            }
                            entry<AppRoute.WebView> { route ->
                                WebViewScreen(webPageUrl = route.url, onBack = { navigator.navigateBack() })
                            }
                            entry<AppRoute.SyncConditions> { route ->
                                SyncConditionsScreen(
                                    objectPrefixAndId = route.objectPrefixAndId,
                                    objectReadableName = route.objectReadableName,
                                    onBack = { navigator.navigateBack() },
                                )
                            }
                            entry<AppRoute.FolderPicker> { route ->
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
                            entry<AppRoute.DeviceEdit> { route ->
                                com.nutomic.syncthingandroid.ui.screens.device.DeviceEditScreen(
                                    deviceId = route.deviceId,
                                    deviceName = route.deviceName,
                                    isCreate = route.isCreate,
                                    notificationId = route.notificationId,
                                )
                            }
                            entry<AppRoute.FolderEdit> { route ->
                                com.nutomic.syncthingandroid.ui.screens.folder.FolderEditScreen(
                                    folderId = route.folderId,
                                    folderLabel = route.folderLabel,
                                    isCreate = route.isCreate,
                                    deviceId = route.deviceId,
                                    receiveEncrypted = route.receiveEncrypted,
                                    notificationId = route.notificationId,
                                )
                            }
                        },
                    )
                    }
                    com.nutomic.syncthingandroid.ui.dialogs.MainActivityDialogsHost()
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
