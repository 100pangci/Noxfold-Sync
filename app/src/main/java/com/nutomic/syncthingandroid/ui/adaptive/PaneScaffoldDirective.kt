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
fun rememberListDetailDirective(showDetail: Boolean): PaneScaffoldDirective {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    return remember(adaptiveInfo, showDetail) {
        calculatePaneScaffoldDirective(adaptiveInfo).withoutPaneSpacers(
            showDetail = showDetail,
        )
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun PaneScaffoldDirective.withoutPaneSpacers(showDetail: Boolean): PaneScaffoldDirective = PaneScaffoldDirective(
    // Keep the root list in the adaptive scaffold, but restrict it to a single
    // partition until there is an actual detail. This lets its state animate to two
    // panes instead of replacing the whole NavDisplay scene (the source of the flash).
    maxHorizontalPartitions = if (showDetail) maxHorizontalPartitions else 1,
    horizontalPartitionSpacerSize = 0.dp,
    maxVerticalPartitions = if (showDetail) maxVerticalPartitions else 1,
    verticalPartitionSpacerSize = 0.dp,
    defaultPanePreferredWidth = defaultPanePreferredWidth,
    defaultPanePreferredHeight = defaultPanePreferredHeight,
    excludedBounds = excludedBounds,
    shouldAutoFocusCurrentDestination = shouldAutoFocusCurrentDestination,
)
