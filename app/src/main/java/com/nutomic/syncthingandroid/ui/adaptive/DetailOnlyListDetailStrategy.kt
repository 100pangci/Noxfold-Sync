package com.nutomic.syncthingandroid.ui.adaptive

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

private const val REAL_DETAIL_METADATA =
    "com.nutomic.syncthingandroid.adaptive.REAL_LIST_DETAIL"

/** Metadata for an actual detail entry (as opposed to the library's empty placeholder). */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun listDetailDetailPaneMetadata(sceneKey: Any = Unit): Map<String, Any> =
    ListDetailSceneStrategy.detailPane(sceneKey) + (REAL_DETAIL_METADATA to true)

/**
 * Material's strategy expands the detail placeholder on wide windows even when only a
 * list entry exists. This wrapper yields to Nav3's single-pane fallback unless the
 * current entry is one of our real details. It applies to previous-scene calculation as
 * well, preventing predictive Back from targeting an empty two-pane layout.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T : Any> rememberDetailOnlyListDetailStrategy(
    directive: PaneScaffoldDirective,
): SceneStrategy<T> {
    val delegate = rememberListDetailSceneStrategy<T>(directive = directive)
    return remember(delegate) { DetailOnlyListDetailStrategy(delegate) }
}

private class DetailOnlyListDetailStrategy<T : Any>(
    private val delegate: SceneStrategy<T>,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (entries.lastOrNull()?.metadata?.get(REAL_DETAIL_METADATA) != true) return null
        return with(delegate) { calculateScene(entries) }
    }
}
