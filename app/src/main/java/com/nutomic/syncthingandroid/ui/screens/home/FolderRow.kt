package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.theme.StatusBadge
import com.nutomic.syncthingandroid.ui.theme.statusWarning
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util

/**
 * One folder row inside a [HomeGroupCard] (pure renderer; all data is
 * precomputed in [FolderUiModel]). Tapping the row opens the folder settings,
 * the trailing icon opens the folder in the system file manager.
 *
 * When [FolderUiModel.needsSafAuthorization] is true the row shows an amber
 * "Authorization required" pill in the sync-status area and routes the tap to
 * [onReauthorize] (re-launches the SAF picker) instead of [onEdit]; the folder
 * name stays visible so the user can tell which folder is affected.
 */
@Composable
internal fun FolderRowContent(
    model: FolderUiModel,
    onEdit: (FolderUiModel) -> Unit,
    onOverride: (FolderUiModel) -> Unit,
    onRevert: (FolderUiModel) -> Unit,
    onReauthorize: (FolderUiModel) -> Unit = {},
) {
    val context = LocalContext.current
    var showOverrideConfirm by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }
    var showConflicts by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (model.needsSafAuthorization) onReauthorize(model) else onEdit(model) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when (model.typeTag) {
                Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Icons.Outlined.Lock
                Constants.FOLDER_TYPE_RECEIVE_ONLY -> Icons.Outlined.Download
                Constants.FOLDER_TYPE_SEND_ONLY -> Icons.Outlined.Upload
                else -> Icons.Outlined.Folder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = model.pathShort,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { FileUtils.openFolder(context, model.path) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.open_file_manager),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (model.overrideVisible) {
            TextButton(onClick = { showOverrideConfirm = true }) {
                Text(stringResource(R.string.override_changes))
            }
        }
        if (model.revertVisible) {
            TextButton(onClick = { showRevertConfirm = true }) {
                Text(stringResource(model.revertLabelRes))
            }
        }

        if (model.conflictCount > 0) {
            // Single-line warning pill; the long file names only appear in
            // the dialog so the card height stays uniform.
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { showConflicts = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.conflicts, model.conflictCount, model.conflictCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (model.lastItemText != null && model.lastItemTimeText != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = model.lastItemText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.lastItemTimeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        if (model.needsSafAuthorization) {
            // Amber warning pill in the sync-status area. Fixed amber
            // (statusWarning, #F9A825) on both light and dark themes so it
            // stands out in the list at a glance; StatusBadge's own
            // container-alpha (0.14) keeps the MD3 tonal-chip look
            // consistent with the other status pills.
            Surface(
                shape = RoundedCornerShape(50),
                color = statusWarning.copy(alpha = 0.14f),
                contentColor = statusWarning,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onReauthorize(model) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.saf_bridge_reauthorize_card),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (model.statusText != null) {
                StatusBadge(text = model.statusText, kind = model.statusKind)
                Spacer(Modifier.width(8.dp))
            }
            if (model.itemsAndSize != null) {
                Text(
                    text = model.itemsAndSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (model.statusText != null) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        model.invalidText?.let {
            if (it.isNotEmpty()) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (model.isSyncing) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { model.completion / 100f },
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showConflicts) {
        AlertDialog(
            onDismissRequest = { showConflicts = false },
            title = { Text(stringResource(R.string.conflict_files_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    model.conflictFiles.forEach { file ->
                        Text(
                            text = file,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConflicts = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showOverrideConfirm) {
        AlertDialog(
            onDismissRequest = { showOverrideConfirm = false },
            title = { Text(stringResource(R.string.override_changes)) },
            text = { Text(stringResource(R.string.override_changes_question)) },
            confirmButton = {
                TextButton(onClick = {
                    showOverrideConfirm = false
                    onOverride(model)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showOverrideConfirm = false }) {
                    Text(stringResource(android.R.string.no))
                }
            }
        )
    }
    if (showRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = { Text(stringResource(R.string.revert_local_changes)) },
            text = { Text(stringResource(R.string.revert_local_changes_question)) },
            confirmButton = {
                TextButton(onClick = {
                    showRevertConfirm = false
                    onRevert(model)
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirm = false }) {
                    Text(stringResource(android.R.string.no))
                }
            }
        )
    }
}
