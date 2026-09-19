package com.nutomic.syncthingandroid.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.adaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.rememberListDetailDirective
import com.nutomic.syncthingandroid.ui.adaptive.rememberWindowSizeClass
import com.nutomic.syncthingandroid.util.isTelevision

// Back-transition motion parameters, referenced from PiliNara's predictive back
// implementation (Flutter material_ui 1.1.0, shared element + fade forwards):
// 450ms transitions on the M3 emphasized ease-in-out cubic curve
// (Curves.easeInOutCubicEmphasized); fades are linear, the top screen fades out
// within the first quarter of the timeline and the revealed screen is fully
// opaque by 75%; the predictive peek offset is width/20 - 8dp toward the swipe
// edge (_kDivisionFactor = 20, _kMargin = 8).
internal const val BACK_TRANSITION_MILLIS = 450
internal val BACK_TRANSITION_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val BACK_FADE_OUT_MILLIS = BACK_TRANSITION_MILLIS / 4
internal const val BACK_FADE_IN_MILLIS = BACK_TRANSITION_MILLIS * 3 / 4
internal const val BACK_PEEK_DIVISOR = 20
internal const val BACK_PEEK_PAD_DP = 8
internal const val BACK_TOP_DRIFT_DIVISOR = 10

// A back call that leaves the whole stack (moveTaskToBack / finish) is ignored
// while a pop transition is still settling, see BackPressGuard.
internal const val BACK_POP_GUARD_MILLIS = BACK_TRANSITION_MILLIS + 50

/**
 * Android 16 "fade forwards" back transform: the previous screen slides in from
 * the leading quarter while fading in, the top screen fades out fast while
 * drifting a quarter width to the trailing edge. Tweens keep the gesture-seeked
 * transition smooth (spring defaults feel stiff when seeked by the back gesture).
 */
internal fun backPopTransform(): ContentTransform =
    (fadeIn(tween(BACK_FADE_IN_MILLIS, easing = LinearEasing)) +
            slideInHorizontally(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING)) { -it / 4 }) togetherWith
            (fadeOut(tween(BACK_FADE_OUT_MILLIS, easing = LinearEasing)) +
                    slideOutHorizontally(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING)) { it / 4 })

/**
 * Predictive back "shared element" transform: the revealed screen peeks from
 * width/20 - 8dp toward the swipe edge while scaling 0.9 -> 1; the top screen
 * dissolves in place (fast fade + shrink toward 0.9 + short drift) instead of
 * sliding the full width. [swipeEdge] is androidx.navigationevent.NavigationEvent's
 * edge constant (EDGE_LEFT = 0, EDGE_RIGHT = 1, EDGE_NONE = 2).
 */
internal fun backPredictivePopTransform(swipeEdge: Int, peekPadPx: Int): ContentTransform {
    val direction = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1
    return (fadeIn(tween(BACK_FADE_IN_MILLIS, easing = LinearEasing)) +
            scaleIn(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING), initialScale = 0.9f) +
            slideInHorizontally(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING)) {
                direction * (it / BACK_PEEK_DIVISOR - peekPadPx)
            }) togetherWith
            (fadeOut(tween(BACK_FADE_OUT_MILLIS, easing = LinearEasing)) +
                    scaleOut(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING), targetScale = 0.9f) +
                    slideOutHorizontally(tween(BACK_TRANSITION_MILLIS, easing = BACK_TRANSITION_EASING)) {
                        direction * (it / BACK_TOP_DRIFT_DIVISOR)
                    })
}

/**
 * Cross-fade for the appearance or disappearance of a list-detail scene on large
 * windows. Sliding would move the whole list pane (and render it twice); switching
 * between details does not change the scene and is animated by the pane scaffold.
 */
internal const val SCENE_CROSS_FADE_IN_MILLIS = 220
internal const val SCENE_CROSS_FADE_OUT_MILLIS = 90

internal fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.sceneCrossFade(): ContentTransform =
    fadeIn(tween(SCENE_CROSS_FADE_IN_MILLIS, easing = LinearEasing)) togetherWith
            fadeOut(tween(SCENE_CROSS_FADE_OUT_MILLIS, easing = LinearEasing))

/**
 * Thin wrapper around the Navigation 3 NavDisplay with shared transition specs.
 *
 * On expanded, non-television windows the list-detail scene strategy takes over when
 * both panes fit: routes marked as list/detail panes (see MainActivity) are shown side
 * by side. Compact windows and televisions keep the single-pane navigation.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : NavKey> AppNavDisplay(
    backStack: NavBackStack<T>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entryProvider: EntryProviderScope<T>.() -> Unit,
) {
    val peekPadPx = with(LocalDensity.current) { BACK_PEEK_PAD_DP.dp.roundToPx() }

    val useListDetail = !LocalConfiguration.current.isTelevision &&
        rememberWindowSizeClass().adaptiveWidthClass == AdaptiveWidthClass.Expanded
    val listDetailStrategy = rememberListDetailSceneStrategy<T>(
        directive = rememberListDetailDirective(),
    )
    val sceneStrategy: SceneStrategy<T> =
        if (useListDetail) {
            listDetailStrategy then SinglePaneSceneStrategy()
        } else {
            SinglePaneSceneStrategy()
        }

    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        sceneStrategies = listOf(sceneStrategy),
        entryProvider = entryProvider(builder = entryProvider),
        transitionSpec = {
            if (useListDetail) {
                sceneCrossFade()
            } else {
                slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { it } togetherWith
                        slideOutHorizontally(tween(350, easing = FastOutSlowInEasing)) { -it }
            }
        },
        popTransitionSpec = {
            if (useListDetail) sceneCrossFade() else backPopTransform()
        },
        predictivePopTransitionSpec = { swipeEdge ->
            if (useListDetail) sceneCrossFade()
            else backPredictivePopTransform(swipeEdge, peekPadPx)
        },
        modifier = modifier,
    )
}
