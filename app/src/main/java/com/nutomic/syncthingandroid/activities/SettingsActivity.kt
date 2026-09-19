package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.activities.SyncthingActivity
import com.nutomic.syncthingandroid.service.NotificationHandler
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.theme.ApplicationTheme
import com.nutomic.syncthingandroid.ui.LocalServiceTick
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.nav.BackPressGuard
import com.nutomic.syncthingandroid.util.LocalActivityScope
import kotlinx.coroutines.flow.MutableStateFlow
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import com.nutomic.syncthingandroid.ui.screens.settings.SettingsNavDisplay
import com.nutomic.syncthingandroid.ui.screens.settings.SettingsRoute
import com.nutomic.syncthingandroid.ui.screens.settings.LocalSettingsNavigator
import com.nutomic.syncthingandroid.ui.screens.settings.Navigator
import com.nutomic.syncthingandroid.ui.screens.settings.createPreferenceFlow
import com.nutomic.syncthingandroid.ui.screens.settings.rememberSettingsNavBackStack
import com.nutomic.syncthingandroid.ui.nav.replaceAfterLast

class SettingsActivity : SyncthingActivity() {

    lateinit var sharedPreferences: SharedPreferences
    private lateinit var prefFlow: MutableStateFlow<Preferences>
    lateinit var notificationHandler: NotificationHandler

    private var syncthingServiceState by mutableStateOf<SyncthingService?>(service)

    // The ticker will help update the ui whenever the state of syncthing service updates
    private var serviceUpdateTick by mutableIntStateOf(0)
    val stateChangeListener = SyncthingService.OnServiceStateChangeListener {
        serviceUpdateTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as SyncthingApp
        sharedPreferences = app.preferences
        notificationHandler = app.notificationHandler
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activityScope = this.lifecycleScope
        prefFlow = createPreferenceFlow(sharedPreferences, activityScope)

        val routeStr = intent.getStringExtra(EXTRA_START_DESTINATION)
        val startDestination: SettingsRoute = SettingsRoute.fromString(routeStr)

        setContent {
            val backStack = rememberSettingsNavBackStack(startDestination)
            val navigator = remember(backStack) {
                object : Navigator<SettingsRoute> {
                    private val backGuard = BackPressGuard()

                    override fun navigateTo(route: SettingsRoute) {
                        // Ignore taps that would push the destination that is already open.
                        if (backStack.lastOrNull() == route) return
                        backStack.add(route)
                    }
                    override fun navigateToRootDetail(route: SettingsRoute) {
                        backStack.replaceAfterLast(
                            isAnchor = { it == SettingsRoute.Root },
                            route = route,
                        )
                    }
                    override fun navigateBack() {
                        if (backStack.size > 1) {
                            backGuard.recordPop()
                            backStack.removeLastOrNull()
                        } else if (backGuard.mayLeaveStack()) {
                            finish()
                        }
                    }
                    override fun navigateUp() {
                        finish()
                    }
                }
            }

            ApplicationTheme {
                CompositionLocalProvider(
                    LocalActivityScope provides activityScope,
                    LocalSettingsNavigator provides navigator,
                    LocalSyncthingService provides syncthingServiceState,
                    LocalServiceTick provides serviceUpdateTick,
                ) {
                    ProvidePreferenceLocals(flow = prefFlow) {
                        SettingsNavDisplay(
                            backStack = backStack,
                        )
                    }
                }
            }
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        super.onServiceConnected(name, service)
        syncthingServiceState = this.service
        syncthingServiceState?.registerOnServiceStateChangeListener(stateChangeListener)
        serviceUpdateTick++
    }

    override fun onServiceDisconnected(name: ComponentName) {
        syncthingServiceState?.unregisterOnServiceStateChangeListener(stateChangeListener)
        super.onServiceDisconnected(name)
        syncthingServiceState = null
        serviceUpdateTick++
    }

    override fun onStop() {
        syncthingServiceState?.let {
            notificationHandler.updatePersistentNotification(it)
        }
        super.onStop()
    }

    override fun onDestroy() {
        syncthingServiceState?.unregisterOnServiceStateChangeListener(stateChangeListener)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_DESTINATION = "com.nutomic.syncthingandroid.settings.EXTRA_START_DESTINATION"
    }
}
