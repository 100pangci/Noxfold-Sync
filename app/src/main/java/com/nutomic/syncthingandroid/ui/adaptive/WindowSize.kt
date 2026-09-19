package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * Width breakpoints mirroring the Material 3 window size classes.
 *
 * The window library deprecates `WindowSizeClass.windowWidthSizeClass` in favour of
 * breakpoint checks, so the buckets are modelled here instead of using the deprecated
 * accessor.
 */
enum class AdaptiveWidthClass {
    Compact,
    Medium,
    Expanded,
}

/** Height breakpoints mirroring the Material 3 window size classes. */
enum class AdaptiveHeightClass {
    Compact,
    Medium,
    Expanded,
}

/**
 * The window size class of the current container, re-evaluated on rotation, split
 * screen, freeform window resizes and foldable posture changes.
 *
 * This describes the window, not the device: a phone in split screen can be
 * [AdaptiveWidthClass.Compact] while a tablet is [AdaptiveWidthClass.Medium] in portrait
 * and [AdaptiveWidthClass.Expanded] in landscape. Televisions must not be detected with
 * this - use `Configuration.isTelevision` for that.
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val containerSize = LocalWindowInfo.current.containerDpSize
    return remember(containerSize) {
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
            widthDp = containerSize.width.value,
            heightDp = containerSize.height.value,
        )
    }
}

/** Material 3 width class for this window size class. */
val WindowSizeClass.adaptiveWidthClass: AdaptiveWidthClass
    get() = when {
        isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
            AdaptiveWidthClass.Expanded
        isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            AdaptiveWidthClass.Medium
        else -> AdaptiveWidthClass.Compact
    }

/** Material 3 height class for this window size class. */
val WindowSizeClass.adaptiveHeightClass: AdaptiveHeightClass
    get() = when {
        isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND) ->
            AdaptiveHeightClass.Expanded
        isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) ->
            AdaptiveHeightClass.Medium
        else -> AdaptiveHeightClass.Compact
    }
