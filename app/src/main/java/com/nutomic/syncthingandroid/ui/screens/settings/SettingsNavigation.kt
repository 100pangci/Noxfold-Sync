package com.nutomic.syncthingandroid.ui.screens.settings

import android.util.Log
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.adaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.rememberWindowSizeClass
import com.nutomic.syncthingandroid.ui.nav.BACK_PEEK_PAD_DP
import com.nutomic.syncthingandroid.ui.nav.backPopTransform
import com.nutomic.syncthingandroid.ui.nav.backPredictivePopTransform
import com.nutomic.syncthingandroid.ui.nav.sceneCrossFade
import com.nutomic.syncthingandroid.util.isTelevision
import kotlinx.serialization.Serializable

@Serializable
sealed interface SettingsRoute : NavKey {

    @Serializable
    data object Root : SettingsRoute

    @Serializable
    data object RunConditions : SettingsRoute
    @Serializable
    data object UserInterface : SettingsRoute
    @Serializable
    data object Behavior : SettingsRoute
    @Serializable
    data object SyncthingOptions : SettingsRoute
    @Serializable
    data object CustomCertificate : SettingsRoute
    @Serializable
    data object ImportExport : SettingsRoute
    @Serializable
    data object Troubleshooting : SettingsRoute
    @Serializable
    data object Experimental : SettingsRoute
    @Serializable
    data object About : SettingsRoute
    @Serializable
    data object Licenses : SettingsRoute


    companion object {
        private const val TAG = "SettingsRoute"

        // Use these strings to open particular screen directly
        fun fromString(route: String?): SettingsRoute = when (route) {
            "RunConditions" -> RunConditions
            "UserInterface" -> UserInterface
            "Behavior" -> Behavior
            "SyncthingOptions" -> SyncthingOptions
            "CustomCertificate" -> CustomCertificate
            "ImportExport" -> ImportExport
            "Troubleshooting" -> Troubleshooting
            "Experimental" -> Experimental
            "About" -> About
            "Licenses" -> Licenses
            "Root" -> Root
            else -> {
                Log.d(TAG, "Unknown settings path provided: $route. Defaulting to Root.")
                Root
            }
        }
    }
}

interface Navigator<T: NavKey> {
    fun navigateTo(route: T)
    fun navigateBack()
    fun navigateUp()
}

val LocalSettingsNavigator = staticCompositionLocalOf<Navigator<SettingsRoute>> {
    error("Navigator not provided")
}

@Composable
fun rememberSettingsNavBackStack(startDestination: SettingsRoute): NavBackStack<SettingsRoute> {
    return rememberSerializable(
        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer())
    ) {
        val initialRoute = listOfNotNull(
            SettingsRoute.Root,
            SettingsRoute.About.takeIf { startDestination == SettingsRoute.Licenses },
            startDestination.takeIf { it != SettingsRoute.Root }
        ).toMutableStateList()
        NavBackStack(initialRoute)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsNavDisplay(
    backStack: NavBackStack<SettingsRoute>
) {
    val navigator = LocalSettingsNavigator.current
    val peekPadPx = with(LocalDensity.current) { BACK_PEEK_PAD_DP.dp.roundToPx() }

    // On large windows the root list and the current sub-screen become a list-detail
    // pair; compact and medium windows keep the single-pane push navigation (and its
    // transitions), because ListDetailSceneStrategy yields to the chained
    // SinglePaneSceneStrategy whenever only one pane fits (its directive switches to two
    // panes at the expanded width breakpoint, 840dp). Televisions stay single-pane too.
    val useListDetail = !LocalConfiguration.current.isTelevision &&
        rememberWindowSizeClass().adaptiveWidthClass == AdaptiveWidthClass.Expanded
    val listDetailStrategy = rememberListDetailSceneStrategy<SettingsRoute>()
    val sceneStrategy: SceneStrategy<SettingsRoute> =
        if (useListDetail) {
            listDetailStrategy then SinglePaneSceneStrategy()
        } else {
            SinglePaneSceneStrategy()
        }

    // Scene changes on large screens are the detail pane appearing or disappearing;
    // cross-fading avoids sliding the whole list (and rendering it twice). Detail
    // switches themselves do not change the scene and are animated by the pane scaffold.
    NavDisplay(
        backStack = backStack,
        onBack = { navigator.navigateBack() },
        sceneStrategies = listOf(sceneStrategy),
        entryProvider = entryProvider {
            settingsRootEntry()
            settingsRunConditionsEntry()
            settingsUserInterfaceEntry()
            settingsBehaviorEntry()
            settingsSyncthingOptionsEntry()
            settingsCustomCertificateEntry()
            settingsImportExportEntry()
            settingsTroubleshootingEntry()
            settingsExperimentalEntry()
            settingsAboutEntry()
            licensesEntry()
        },
        transitionSpec = {
            if (useListDetail) {
                sceneCrossFade()
            } else {
                // Slide in from right when navigating forward
                slideInHorizontally(initialOffsetX = { it }) togetherWith
                        slideOutHorizontally(targetOffsetX = { -it })
            }
        },
        popTransitionSpec = {
            if (useListDetail) sceneCrossFade() else backPopTransform()
        },
        predictivePopTransitionSpec = { swipeEdge ->
            if (useListDetail) sceneCrossFade()
            else backPredictivePopTransform(swipeEdge, peekPadPx)
        },
        modifier = Modifier.onKeyEvent { keyEvent ->
            if (keyEvent.key == Key.DirectionLeft
                && keyEvent.type == KeyEventType.KeyDown) {
                navigator.navigateBack()
                true
            } else {
                false
            }
        }
    )
}

/**
 * Registers a settings destination as the detail pane of the list-detail scaffold. On
 * compact windows it behaves exactly like [EntryProviderScope.entry].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
internal inline fun <reified T : SettingsRoute> EntryProviderScope<SettingsRoute>.settingsDetailEntry(
    noinline content: @Composable (T) -> Unit,
) {
    entry<T>(metadata = ListDetailSceneStrategy.detailPane(), content = content)
}
