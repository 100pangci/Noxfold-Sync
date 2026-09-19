package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

enum class ListDetailPaneRole { List, Detail }

/** Whether the current adaptive scene has a real detail pane beside its list. */
val LocalListDetailHasDetail = staticCompositionLocalOf { false }

/**
 * Prevents child Scaffolds from applying window-edge insets on an internal pane edge.
 *
 * Every pane still touches the top and bottom. Only the list pane touches the window's
 * start edge and only the detail pane touches its end edge. Without consuming the
 * opposite horizontal inset here, a landscape navigation bar/cutout can create an
 * apparent blank strip between otherwise gapless panes.
 *
 * Root-only adaptive scenes deliberately retain a single pane for a smooth subsequent
 * expansion; [LocalListDetailHasDetail] prevents those scenes from consuming their own
 * window-edge inset as if a sibling pane were already present. Compact single-pane
 * fallbacks keep their normal full-window behavior.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ListDetailPaneContent(
    role: ListDetailPaneRole,
    content: @Composable () -> Unit,
) {
    val hasInternalPaneEdge = LocalListDetailSceneScope.current != null &&
        LocalListDetailHasDetail.current
    val internalEdge = when (role) {
        ListDetailPaneRole.List -> WindowInsetsSides.End
        ListDetailPaneRole.Detail -> WindowInsetsSides.Start
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .let { modifier ->
                if (hasInternalPaneEdge) {
                    modifier.consumeWindowInsets(WindowInsets.systemBars.only(internalEdge))
                } else {
                    modifier
                }
            }
    ) {
        if (role == ListDetailPaneRole.Detail && hasInternalPaneEdge) {
            DetailContentEnterTransition(content = content)
        } else {
            content()
        }
    }
}

private const val DETAIL_ENTER_MILLIS = 240

/**
 * Switching between two details does not change the adaptive scene, so the pane
 * scaffold does not animate it (it only animates the pane itself). Fade and slide the
 * incoming detail content in instead of swapping it instantly; the outgoing content is
 * disposed by Navigation 3.
 */
@Composable
private fun DetailContentEnterTransition(content: @Composable () -> Unit) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(DETAIL_ENTER_MILLIS, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(DETAIL_ENTER_MILLIS, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 14 },
                ),
        // The pane collapse is owned by the scaffold; an exit animation here would keep
        // a stale screen alive while the list is already expanding again.
        exit = ExitTransition.None,
    ) {
        content()
    }
}
