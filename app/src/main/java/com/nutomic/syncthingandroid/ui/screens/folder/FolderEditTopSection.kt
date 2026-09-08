package com.nutomic.syncthingandroid.ui.screens.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.components.ClickRow
import com.nutomic.syncthingandroid.ui.components.FormCard
import com.nutomic.syncthingandroid.ui.components.ToggleRow

/**
 * Top part of the folder form: label/id/path and the folder type row.
 */
@Composable
internal fun FolderEditTopSection(
    holder: FolderEditStateHolder,
    folder: com.nutomic.syncthingandroid.model.Folder,
    isCreate: Boolean,
    prefExpertMode: Boolean,
    onMarkDirty: () -> Unit,
    onPickPath: () -> Unit,
    onPickAdvancedPath: () -> Unit,
    onShowFolderTypeDialog: () -> Unit,
    configVersion: Int = 0,
) {
    var label by remember(folder) { mutableStateOf(folder.label ?: "") }
    var idText by remember(folder) { mutableStateOf(folder.id ?: "") }

    FormCard {
        OutlinedTextField(
            value = label,
            onValueChange = { value ->
                label = value
                folder.label = value.trim()
                onMarkDirty()
            },
            label = { Text(stringResource(R.string.folder_label)) },
            leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        com.nutomic.syncthingandroid.ui.components.GroupPickerRow(
            label = stringResource(R.string.folder_group_label),
            currentGroup = folder.group,
            groupOptions = holder.groupOptions,
            onGroupChanged = { value ->
                folder.group = value
                onMarkDirty()
            }
        )
        if (isCreate) {
            OutlinedTextField(
                value = idText,
                onValueChange = { value ->
                    idText = value
                    folder.id = value
                    onMarkDirty()
                },
                label = { Text(stringResource(R.string.folder_id)) },
                leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else {
            ClickRow(
                title = stringResource(R.string.folder_id),
                value = folder.id,
                icon = Icons.Outlined.Tag
            )
        }

        // Path row (edit mode: read only).
        if (isCreate) {
            ClickRow(
                title = stringResource(R.string.directory),
                value = folder.path,
                icon = Icons.Outlined.FolderOpen,
                onClick = onPickPath
            )
            ClickRow(
                title = stringResource(R.string.advanced_directory_selection),
                icon = Icons.Outlined.CreateNewFolder,
                onClick = onPickAdvancedPath
            )
        } else {
            ClickRow(
                title = stringResource(R.string.directory),
                value = folder.path,
                icon = Icons.Outlined.FolderOpen
            )
        }

        // Access level explanation.
        val accessText =
            if (!folder.path.isNullOrEmpty() && holder.canWriteToPath)
                stringResource(R.string.folder_path_readwrite)
            else if (!folder.path.isNullOrEmpty())
                stringResource(R.string.folder_path_readonly)
            else
                null
        if (accessText != null) {
            Text(
                text = accessText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Folder type row. A saved receive-encrypted folder is locked
        // (Syncthing forbids leaving that type); during creation the type
        // stays freely changeable.
        val typeEnabled = holder.canWriteToPath &&
                !folder.path.isNullOrEmpty() &&
                !(folder.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED && !isCreate)
        androidx.compose.runtime.key(configVersion) {
            FolderTypeRow(
                folderType = folder.type,
                enabled = typeEnabled,
                onClick = onShowFolderTypeDialog
            )
        }
    }
}

@Composable
private fun FolderTypeRow(
    folderType: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val (label, description, icon) = when (folderType) {
        Constants.FOLDER_TYPE_SEND_ONLY -> Triple(
            stringResource(R.string.folder_type_sendonly),
            stringResource(R.string.folder_type_sendonly_description),
            Icons.Outlined.Upload as ImageVector
        )
        Constants.FOLDER_TYPE_RECEIVE_ONLY -> Triple(
            stringResource(R.string.folder_type_receiveonly),
            stringResource(R.string.folder_type_receiveonly_description),
            Icons.Outlined.Download as ImageVector
        )
        Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Triple(
            stringResource(R.string.folder_type_receive_encrypted),
            stringResource(R.string.folder_type_receive_encrypted_description),
            Icons.Outlined.Lock as ImageVector
        )
        else -> Triple(
            stringResource(R.string.folder_type_sendreceive),
            stringResource(R.string.folder_type_sendreceive_description),
            Icons.Outlined.Folder as ImageVector
        )
    }
    ClickRow(
        title = stringResource(R.string.folder_type),
        value = label,
        description = description,
        icon = icon,
        enabled = enabled,
        onClick = onClick
    )
}
