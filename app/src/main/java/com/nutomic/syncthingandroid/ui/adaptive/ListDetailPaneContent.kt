package com.nutomic.syncthingandroid.ui.adaptive

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
import androidx.compose.ui.Modifier

enum class ListDetailPaneRole { List, Detail }

/**
 * Prevents child Scaffolds from applying window-edge insets on an internal pane edge.
 *
 * Every pane still touches the top and bottom. Only the list pane touches the window's
 * start edge and only the detail pane touches its end edge. Without consuming the
 * opposite horizontal inset here, a landscape navigation bar/cutout can create an
 * apparent blank strip between otherwise gapless panes.
 *
 * When the entry falls back to a single-pane Nav3 scene the local scope is null, so no
 * inset is consumed and the screen retains normal full-window behavior.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ListDetailPaneContent(
    role: ListDetailPaneRole,
    content: @Composable () -> Unit,
) {
    val inListDetailScene = LocalListDetailSceneScope.current != null
    val internalEdge = when (role) {
        ListDetailPaneRole.List -> WindowInsetsSides.End
        ListDetailPaneRole.Detail -> WindowInsetsSides.Start
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .let { modifier ->
                if (inListDetailScene) {
                    modifier.consumeWindowInsets(WindowInsets.systemBars.only(internalEdge))
                } else {
                    modifier
                }
            }
    ) {
        content()
    }
}
