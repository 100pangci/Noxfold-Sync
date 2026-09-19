package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.navigation3.scene.SceneStrategy

/** Metadata for an actual detail entry. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun listDetailDetailPaneMetadata(sceneKey: Any = Unit): Map<String, Any> =
    ListDetailSceneStrategy.detailPane(sceneKey)

/**
 * Keeps the root list in the same adaptive scene that later hosts its detail. The
 * directive constrains root-only content to one pane; when a detail appears, the pane
 * scaffold animates its own bounds instead of NavDisplay replacing the whole scene.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> rememberStableListDetailStrategy(
    directive: PaneScaffoldDirective,
): SceneStrategy<T> = rememberListDetailSceneStrategy(
    shouldHandleSinglePaneLayout = true,
    directive = directive,
)
