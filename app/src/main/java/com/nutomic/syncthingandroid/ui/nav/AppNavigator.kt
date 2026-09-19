package com.nutomic.syncthingandroid.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * High level navigation callbacks provided to all screens. Implemented by MainActivity.
 */
interface AppNavigator {
    /** Push a route onto the navigation 3 back stack. */
    fun navigateTo(route: AppRoute)

    /**
     * Select a detail directly from the persistent Home pane.
     *
     * MainActivity replaces the current detail branch instead of stacking another
     * sibling detail. Standalone activity hosts have no persistent Home pane, so the
     * default implementation is the regular push navigation.
     */
    fun navigateToHomeDetail(route: AppRoute) = navigateTo(route)

    /** Close every detail route after Home (used when changing the Home tab). */
    fun clearHomeDetail() = Unit

    /** Pop the current route. */
    fun navigateBack()

    fun openDeviceEdit(deviceId: String?, isCreate: Boolean)
    fun openFolderEdit(folderId: String?, isCreate: Boolean)
    fun openSyncConditions(objectPrefixAndId: String, objectReadableName: String)
    fun openFolderPicker(initialDirectory: String?, rootDirectory: String?)
    fun openLog()
    fun openWebView(url: String)
    fun openSettings(startDestination: String? = null)
    fun openRecentChanges()
    fun openWebGui()
    fun showDeviceIdDialog()
    fun confirmRestart()
}

/**
 * Replaces everything after the last matching anchor with [route]. This is the
 * canonical list-detail selection operation: selecting B after A must produce
 * `Root, B`, not `Root, A, B` (which makes Back revisit every previous selection).
 */
internal fun <T> MutableList<T>.replaceAfterLast(
    isAnchor: (T) -> Boolean,
    route: T,
) {
    val anchorIndex = indexOfLast(isAnchor)
    if (anchorIndex < 0) {
        if (lastOrNull() != route) add(route)
        return
    }
    // If the requested detail is already directly beside the list pane, keep that
    // route object (and therefore its draft identity) and only close routes nested
    // above it, such as a picker or sync-condition editor.
    val detailIndex = anchorIndex + 1
    if (detailIndex <= lastIndex && this[detailIndex] == route) {
        while (lastIndex > detailIndex) removeAt(lastIndex)
        return
    }
    while (lastIndex > anchorIndex) removeAt(lastIndex)
    add(route)
}

/** Removes every route after the last matching list-pane anchor. */
internal fun <T> MutableList<T>.clearAfterLast(isAnchor: (T) -> Boolean) {
    val anchorIndex = indexOfLast(isAnchor)
    if (anchorIndex < 0) return
    while (lastIndex > anchorIndex) removeAt(lastIndex)
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator not provided")
}
