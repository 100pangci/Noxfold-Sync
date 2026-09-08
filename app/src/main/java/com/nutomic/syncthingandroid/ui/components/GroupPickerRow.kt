package com.nutomic.syncthingandroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R

/**
 * Group selection row used by the folder and device forms: a read-only
 * dropdown listing the ungrouped option and the group names already used by
 * the other entries of the same kind, plus a "+" button that creates a new
 * group by typing its name. The group value is a plain config attribute
 * ("" = ungrouped), so "creating" a group merely assigns the new name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerRow(
    label: String,
    currentGroup: String,
    groupOptions: List<String>,
    onGroupChanged: (String) -> Unit,
) {
    var groupText by remember(currentGroup) { mutableStateOf(currentGroup) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }

    fun selectGroup(value: String) {
        groupText = value
        menuExpanded = false
        onGroupChanged(value)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier.weight(1f)
        ) {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it }
            ) {
                OutlinedTextField(
                    value = groupText,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(label) },
                    placeholder = { Text(stringResource(R.string.folder_group_ungrouped)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    // Ungrouped first, then the group names of the other
                    // entries plus the current value (a group whose only
                    // member is this entry is still selectable, and a freshly
                    // typed name stays selectable until it is saved).
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_group_ungrouped)) },
                        onClick = { selectGroup("") }
                    )
                    (groupOptions + groupText)
                        .distinct()
                        .filter { it.isNotEmpty() }
                        .forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = { selectGroup(name) }
                            )
                        }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { showNewGroupDialog = true }) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.folder_group_new_group)
            )
        }
    }
    if (showNewGroupDialog) {
        NewGroupNameDialog(
            onConfirm = { name ->
                showNewGroupDialog = false
                selectGroup(name)
            },
            onDismiss = { showNewGroupDialog = false }
        )
    }
}

@Composable
private fun NewGroupNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_group_new_group)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_group_new_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty(), onClick = { onConfirm(trimmed) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
