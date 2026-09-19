package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * Material directive for the list-detail panes with the inter-pane spacers removed.
 *
 * The library's default leaves a 24dp strip of container background between the panes;
 * this app renders both panes on the same background, so that strip reads as a stray
 * gap rather than a deliberate separation.
 */
@Composable
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun rememberListDetailDirective(): PaneScaffoldDirective {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    return remember(adaptiveInfo) {
        calculatePaneScaffoldDirective(adaptiveInfo).withoutPaneSpacers()
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun PaneScaffoldDirective.withoutPaneSpacers(): PaneScaffoldDirective = PaneScaffoldDirective(
    maxHorizontalPartitions = maxHorizontalPartitions,
    horizontalPartitionSpacerSize = 0.dp,
    maxVerticalPartitions = maxVerticalPartitions,
    verticalPartitionSpacerSize = 0.dp,
    defaultPanePreferredWidth = defaultPanePreferredWidth,
    defaultPanePreferredHeight = defaultPanePreferredHeight,
    excludedBounds = excludedBounds,
    shouldAutoFocusCurrentDestination = shouldAutoFocusCurrentDestination,
)
