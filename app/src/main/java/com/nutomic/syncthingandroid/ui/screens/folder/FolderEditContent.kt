package com.nutomic.syncthingandroid.ui.screens.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Assembles the folder edit form from its top and bottom sections.
 */
@Composable
internal fun FolderEditContent(
    modifier: Modifier = Modifier,
    holder: FolderEditStateHolder,
    folder: com.nutomic.syncthingandroid.model.Folder,
    isCreate: Boolean,
    prefExpertMode: Boolean,
    onMarkDirty: () -> Unit,
    onIgnoreListChanged: (String) -> Unit,
    onPickPath: () -> Unit,
    onPickAdvancedPath: () -> Unit,
    onShowFolderTypeDialog: () -> Unit,
    onShowPullOrderDialog: () -> Unit,
    onShowVersioningDialog: () -> Unit,
    onOpenSyncConditions: () -> Unit,
    onOpenDeviceEdit: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            FolderEditTopSection(
                holder = holder,
                folder = folder,
                isCreate = isCreate,
                prefExpertMode = prefExpertMode,
                onMarkDirty = onMarkDirty,
                onPickPath = onPickPath,
                onPickAdvancedPath = onPickAdvancedPath,
                onShowFolderTypeDialog = onShowFolderTypeDialog,
                configVersion = holder.configVersion,
            )
            FolderEditBottomSection(
                holder = holder,
                folder = folder,
                isCreate = isCreate,
                prefExpertMode = prefExpertMode,
                onMarkDirty = onMarkDirty,
                onIgnoreListChanged = onIgnoreListChanged,
                onShowPullOrderDialog = onShowPullOrderDialog,
                onShowVersioningDialog = onShowVersioningDialog,
                onOpenSyncConditions = onOpenSyncConditions,
                onOpenDeviceEdit = onOpenDeviceEdit,
                configVersion = holder.configVersion,
            )
        }
    }
}
