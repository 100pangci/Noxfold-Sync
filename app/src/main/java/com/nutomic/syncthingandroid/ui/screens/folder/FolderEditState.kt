package com.nutomic.syncthingandroid.ui.screens.folder

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.ui.nav.EditStateStore

/**
 * Remembers the folder model, the SAF uri and the dirty flags.
 */
internal class FolderEditStateHolder {
    var folder by mutableStateOf<Folder?>(null)
    var folderUri by mutableStateOf<Uri?>(null)
    var needsUpdate by mutableStateOf(false)
    var ignoreListNeedsUpdate by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var canWriteToPath by mutableStateOf(false)
    var ignoreListText by mutableStateOf("")
    var deviceStates by mutableStateOf<List<DeviceShareState>>(emptyList())
    var customSyncConditions by mutableStateOf(false)
    var runScript by mutableStateOf(false)

    /**
     * Group names currently used by the configured folders, offered as
     * dropdown suggestions in the group picker. Filled once when the draft
     * is initialized.
     */
    var groupOptions by mutableStateOf<List<String>>(emptyList())

    /**
     * Bumped whenever the folder model is changed from a dialog (type, pull
     * order, versioning). Sections reading the Java model directly are keyed
     * on this so they recompose reliably.
     */
    var configVersion by mutableStateOf(0)
}

/**
 * Holds the unsaved draft state of [FolderEditScreen] OUTSIDE the Navigation 3
 * entry composition, keyed by [folderEditStateKey] (see [EditStateStore] for
 * the lifecycle and why plain remember/rememberSaveable are not enough).
 */
internal val LocalFolderEditStateStore =
    staticCompositionLocalOf<EditStateStore<FolderEditStateHolder>> {
        error("FolderEditStateStore not provided")
    }

/**
 * Stable identity of a folder edit session. Deliberately independent of the
 * share/notification extras: re-entering the same folder's editor (from home,
 * or via a new share notification) continues the same draft while the route is
 * still on the stack, and starts fresh once it has been evicted.
 */
internal fun folderEditStateKey(folderId: String?, isCreate: Boolean): String =
    if (isCreate || folderId == null) "create" else "edit:$folderId"

