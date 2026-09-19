package com.nutomic.syncthingandroid.activities

import com.nutomic.syncthingandroid.ui.nav.AppRoute
import com.nutomic.syncthingandroid.ui.nav.EditStateStore
import com.nutomic.syncthingandroid.ui.screens.device.DeviceEditStateHolder
import com.nutomic.syncthingandroid.ui.screens.folder.FolderEditStateHolder
import com.nutomic.syncthingandroid.ui.screens.folder.folderEditStateKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityTabletNavigationTest {

    private val folderStore = EditStateStore { FolderEditStateHolder() }
    private val deviceStore = EditStateStore { DeviceEditStateHolder() }

    @Test
    fun selectingAnotherDetail_detectsDirtyDraft() {
        val current = AppRoute.FolderEdit(folderId = "a")
        folderStore.stateFor(folderEditStateKey("a", false)).needsUpdate = true

        val state = currentEditorExitState(
            backStack = listOf(AppRoute.Home, current),
            folderStore = folderStore,
            deviceStore = deviceStore,
            retainedRoute = AppRoute.FolderEdit(folderId = "b"),
        )

        assertEquals(EditorExitState.Dirty, state)
    }

    @Test
    fun selectingSameDetail_retainsDirtyDraftWithoutPrompt() {
        val current = AppRoute.FolderEdit(folderId = "a")
        folderStore.stateFor(folderEditStateKey("a", false)).needsUpdate = true

        val state = currentEditorExitState(
            backStack = listOf(AppRoute.Home, current, AppRoute.FolderPicker()),
            folderStore = folderStore,
            deviceStore = deviceStore,
            retainedRoute = current,
        )

        assertEquals(EditorExitState.Clean, state)
    }

    @Test
    fun leavingSavingFolder_isBlocked() {
        val current = AppRoute.FolderEdit(folderId = "a")
        folderStore.stateFor(folderEditStateKey("a", false)).apply {
            needsUpdate = true
            isSaving = true
        }

        val state = currentEditorExitState(
            backStack = listOf(AppRoute.Home, current),
            folderStore = folderStore,
            deviceStore = deviceStore,
            retainedRoute = AppRoute.DeviceEdit(deviceId = "device"),
        )

        assertEquals(EditorExitState.Saving, state)
    }
}
