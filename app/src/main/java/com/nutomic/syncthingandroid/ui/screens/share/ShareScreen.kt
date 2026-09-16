package com.nutomic.syncthingandroid.ui.screens.share

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionEvents
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share-into-folder screen, ported from the legacy ShareActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    files: Map<Uri, String>,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = context.appPreferences()
    val configRouter = remember { ConfigRouter(context) }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<Folder>?>(null) }
    var selectedFolderIndex by remember { mutableStateOf(0) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var subDirectory by remember { mutableStateOf("") }
    var isCopying by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf(files.values.joinToString("\n")) }
    var showProgress by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            folders = configRouter.getFolders(null)
        } catch (e: ConfigXml.OpenConfigException) {
            android.widget.Toast.makeText(
                context, R.string.complete_welcome_wizard_first, android.widget.Toast.LENGTH_LONG
            ).show()
            onDone()
            return@LaunchedEffect
        }
        // Restore previously selected folder.
        val savedFolderId = preferences.getString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, "") ?: ""
        val list = folders
        if (list != null) {
            val index = list.indexOfFirst { it.id == savedFolderId }
            if (index >= 0) selectedFolderIndex = index
        }
    }

    // Refresh sub directory display when the selection changes.
    LaunchedEffect(selectedFolderIndex, folders) {
        val list = folders ?: return@LaunchedEffect
        if (selectedFolderIndex < list.size) {
            val folder = list[selectedFolderIndex]
            subDirectory = preferences.getString(com.nutomic.syncthingandroid.activities.ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + folder.id, "") ?: ""
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val list = folders ?: return@rememberLauncherForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK &&
            selectedFolderIndex < list.size
        ) {
            val folder = list[selectedFolderIndex]
            val folderDirectory = Util.formatPath(folder.path)
            val picked = result.data?.getStringExtra(com.nutomic.syncthingandroid.activities.FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
            if (picked != null) {
                val sub = picked.replace(folderDirectory, "")
                subDirectory = sub
                preferences.edit()
                    .putString(com.nutomic.syncthingandroid.activities.ShareActivity.PREF_FOLDER_SAVED_SUBDIRECTORY + folder.id, sub)
                    .apply()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ShareFileNameSection(files.size, nameText, onNameTextChange = { nameText = it })
        ShareFolderSection(
            folders = folders, selectedFolderIndex = selectedFolderIndex,
            subDirectory = subDirectory, dropdownExpanded = dropdownExpanded,
            onDropdownExpandedChange = { dropdownExpanded = it },
            onFolderSelected = { index, folder ->
                selectedFolderIndex = index
                preferences.edit().putString(
                    PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, folder.id
                ).apply()
                dropdownExpanded = false
            },
            onPickSubDirectory = { folderPickerLauncher.launch(it) },
        )
        ShareActionsRow(
            folders = folders,
            isCopying = isCopying,
            onShare = {
                shareSelectedFiles(
                    context, scope, preferences, files, nameText, folders,
                    selectedFolderIndex, subDirectory,
                    { isCopying = it }, { showProgress = it }, onDone,
                )
            },
            onDone = onDone,
        )
    }

    ShareProgressDialog(visible = showProgress)
}

@Composable
private fun ShareFileNameSection(
    fileCount: Int,
    nameText: String,
    onNameTextChange: (String) -> Unit,
) {
    Text(
        text = pluralStringResource(
            R.plurals.file_name_title,
            if (fileCount > 1) 2 else 1
        ),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedTextField(
        value = nameText,
        onValueChange = onNameTextChange,
        enabled = fileCount == 1,
        readOnly = fileCount > 1,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareFolderSection(
    folders: List<Folder>?,
    selectedFolderIndex: Int,
    subDirectory: String,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit,
    onFolderSelected: (Int, Folder) -> Unit,
    onPickSubDirectory: (Intent) -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.folders),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    if (folders != null && folders.isNotEmpty() && selectedFolderIndex < folders.size) {
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = onDropdownExpandedChange
        ) {
            OutlinedTextField(
                value = folders[selectedFolderIndex].toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.folders)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { onDropdownExpandedChange(false) }
            ) {
                folders.forEachIndexed { index, folder ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                folder.toString(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = { onFolderSelected(index, folder) }
                    )
                }
            }
        }
        Text(
            text = subDirectory,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedButton(
            onClick = {
                val folder = folders[selectedFolderIndex]
                val initialDir = File(folder.path, subDirectory)
                onPickSubDirectory(
                    com.nutomic.syncthingandroid.activities.FolderPickerActivity.createIntent(
                        context, initialDir.absolutePath, folder.path
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text(
                stringResource(R.string.advanced_directory_selection),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    } else {
        Text(
            text = stringResource(R.string.folder_list_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShareActionsRow(
    folders: List<Folder>?,
    isCopying: Boolean,
    onShare: () -> Unit,
    onDone: () -> Unit,
) {
    Row(modifier = Modifier.padding(top = 16.dp)) {
        Button(
            onClick = onShare,
            enabled = !isCopying && folders != null && folders.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.share_activity_title))
        }
        OutlinedButton(
            onClick = onDone,
            enabled = !isCopying,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}

@Composable
private fun ShareProgressDialog(visible: Boolean) {
    if (visible) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Column {
                    Text(stringResource(R.string.copy_progress))
                    CircularProgressIndicator(Modifier.padding(top = 12.dp))
                }
            }
        )
    }
}

private fun shareSelectedFiles(
    context: Context,
    scope: CoroutineScope,
    preferences: SharedPreferences,
    files: Map<Uri, String>,
    nameText: String,
    folders: List<Folder>?,
    selectedFolderIndex: Int,
    subDirectory: String,
    onCopyingChanged: (Boolean) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val list = folders ?: return
    if (selectedFolderIndex >= list.size) return
    val folder = list[selectedFolderIndex]
    if (folder.path == null) return
    val effectiveFiles = if (files.size == 1) {
        files.entries.associate { it.key to nameText }
    } else {
        files
    }
    val directory = File(folder.path, subDirectory)
    val allowOverwrite = preferences.getBoolean(Constants.PREF_ALLOW_OVERWRITE_FILES, false)
    onCopyingChanged(true)
    onShowProgressChanged(true)
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            ShareFilesHelper.copyFiles(context, effectiveFiles, directory, allowOverwrite)
        }
        onCopyingChanged(false)
        onShowProgressChanged(false)
        if (result.isError) {
            android.widget.Toast.makeText(
                context, R.string.copy_exception, android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (result.ignored > 0) {
            android.widget.Toast.makeText(
                context,
                context.resources.getQuantityString(
                    R.plurals.copy_success_partially,
                    result.copied.coerceAtLeast(1),
                    result.copied, folder.label, result.ignored
                ),
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(
                context,
                context.resources.getQuantityString(
                    R.plurals.copy_success,
                    result.copied.coerceAtLeast(1),
                    result.copied, folder.label
                ),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        // Notify RunConditionMonitor when time schedule is enabled.
        val prefRunOnTimeSchedule = preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)
        if (prefRunOnTimeSchedule) {
            RunConditionEvents.fireSyncTrigger(beginActiveTimeWindow = true)
        }
        onDone()
    }
}

internal const val PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER = "previously_selected_syncthing_folder"
