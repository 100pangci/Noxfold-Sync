package com.nutomic.syncthingandroid.ui.screens.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.ui.components.ClickRow
import com.nutomic.syncthingandroid.ui.components.FormCard
import com.nutomic.syncthingandroid.ui.components.GroupPickerRow
import com.nutomic.syncthingandroid.ui.components.ToggleRow

/**
 * Form content of the device add/edit screen. Every bound control mirrors its
 * value in compose state so edits are reflected immediately; the mutable Java
 * model is only written through for saving.
 */
@Composable
internal fun DeviceEditContent(
    device: Device,
    holder: DeviceEditStateHolder,
    isCreate: Boolean,
    prefExpertMode: Boolean,
    discoveredDevices: Map<String, DiscoveredDevice>?,
    onDeviceMutate: ((Device) -> Unit) -> Unit,
    onCompressionClick: () -> Unit,
    onScanQr: () -> Unit,
    onShowQr: () -> Unit,
    onOpenSyncConditions: () -> Unit,
    onOpenFolderEdit: () -> Unit,
    onRefreshDiscovery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        // ---- Device identity ----
        DeviceIdentityCard(
            device = device,
            holder = holder,
            isCreate = isCreate,
            prefExpertMode = prefExpertMode,
            discoveredDevices = discoveredDevices,
            onDeviceMutate = onDeviceMutate,
            onScanQr = onScanQr,
            onShowQr = onShowQr,
            onCompressionClick = onCompressionClick,
            onRefreshDiscovery = onRefreshDiscovery,
        )
        // ---- Toggles ----
        DeviceTogglesCard(
            device = device,
            onDeviceMutate = onDeviceMutate,
        )
        // ---- Custom sync conditions (edit mode only) ----
        if (!isCreate) {
            DeviceSyncConditionsCard(
                holder = holder,
                onOpenSyncConditions = onOpenSyncConditions,
            )
        }
        // ---- Folders shared with this device ----
        DeviceFoldersCard(
            holder = holder,
            onOpenFolderEdit = onOpenFolderEdit,
        )
    }
}

@Composable
private fun DeviceIdentityCard(
    device: Device,
    holder: DeviceEditStateHolder,
    isCreate: Boolean,
    prefExpertMode: Boolean,
    discoveredDevices: Map<String, DiscoveredDevice>?,
    onDeviceMutate: ((Device) -> Unit) -> Unit,
    onScanQr: () -> Unit,
    onShowQr: () -> Unit,
    onCompressionClick: () -> Unit,
    onRefreshDiscovery: () -> Unit,
) {
    var nameText by remember(device) { mutableStateOf(device.name ?: "") }
    var addressesText by remember(device) { mutableStateOf(displayableAddresses(device)) }
    FormCard(title = stringResource(R.string.device_id)) {
        if (isCreate) {
            OutlinedTextField(
                value = holder.deviceIdText,
                onValueChange = { value ->
                    holder.deviceIdText = value
                    onDeviceMutate { it.deviceID = value }
                },
                label = { Text(stringResource(R.string.device_id)) },
                leadingIcon = { Icon(Icons.Outlined.QrCode2, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                TextButton(onClick = onScanQr) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_qr_code_description))
                }
            }
        } else {
            // Device ID row: monospace, grouped in chunks of 7 characters for
            // readability, with quick copy and QR actions in the same row.
            val context = LocalContext.current
            val copiedToastText = stringResource(R.string.device_id_copied_to_clipboard)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.device_id),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatDeviceId(device.deviceID),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("deviceID", device.deviceID)
                    )
                    Toast.makeText(
                        context,
                        copiedToastText,
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy)
                    )
                }
                IconButton(onClick = onShowQr) {
                    Icon(
                        imageVector = Icons.Outlined.QrCode2,
                        contentDescription = stringResource(R.string.device_id_qr_code)
                    )
                }
            }
        }

        // ---- Discovered devices (create mode only) ----
        if (isCreate && holder.deviceIdText.isEmpty() && discoveredDevices != null) {
            if (discoveredDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.local_discovery_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.discovered_devices_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onRefreshDiscovery() }
                )
                discoveredDevices.forEach { (id, discoveredDevice) ->
                    val readableAddresses = discoveredDevice.addresses?.joinToString(", ") ?: ""
                    val caption = id + if (readableAddresses.isEmpty()) "" else " ($readableAddresses)"
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                holder.deviceIdText = id
                                onDeviceMutate { it.deviceID = id }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // ---- Name ----
        OutlinedTextField(
            value = nameText,
            onValueChange = { value ->
                nameText = value
                onDeviceMutate { it.name = value }
            },
            label = { Text(stringResource(R.string.device_name)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ---- Group ----
        GroupPickerRow(
            label = stringResource(R.string.device_group_label),
            currentGroup = device.group,
            groupOptions = holder.groupOptions,
            onGroupChanged = { value ->
                onDeviceMutate { it.group = value }
            }
        )

        // ---- Addresses ----
        OutlinedTextField(
            value = addressesText,
            onValueChange = { value ->
                addressesText = value
                onDeviceMutate { it.addresses = persistableAddresses(value) }
            },
            label = { Text(stringResource(R.string.addresses)) },
            leadingIcon = { Icon(Icons.Outlined.Lan, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ---- Compression (expert mode only) ----
        if (prefExpertMode) {
            ClickRow(
                title = stringResource(R.string.compression),
                value = compressionTitle(holder.compressionIndex),
                icon = Icons.Outlined.Compress,
                onClick = onCompressionClick
            )
        }
    }
}

@Composable
private fun DeviceTogglesCard(
    device: Device,
    onDeviceMutate: ((Device) -> Unit) -> Unit,
) {
    // Mirror the Java model toggles so the UI updates immediately.
    var introducer by remember(device) { mutableStateOf(device.introducer) }
    var autoAcceptFolders by remember(device) { mutableStateOf(device.autoAcceptFolders) }
    var paused by remember(device) { mutableStateOf(device.paused) }
    var untrusted by remember(device) { mutableStateOf(device.untrusted) }
    FormCard {
        ToggleRow(
            title = stringResource(R.string.introducer),
            icon = Icons.Outlined.RecordVoiceOver,
            checked = introducer,
            onCheckedChange = { checked ->
                introducer = checked
                onDeviceMutate { it.introducer = checked }
            }
        )
        ToggleRow(
            title = stringResource(R.string.autoAcceptFolders),
            icon = Icons.Outlined.MoveToInbox,
            checked = autoAcceptFolders,
            onCheckedChange = { checked ->
                autoAcceptFolders = checked
                onDeviceMutate { it.autoAcceptFolders = checked }
            }
        )
        ToggleRow(
            title = stringResource(R.string.pause_device),
            icon = Icons.Outlined.Pause,
            checked = paused,
            onCheckedChange = { checked ->
                paused = checked
                onDeviceMutate { it.paused = checked }
            }
        )
        ToggleRow(
            title = stringResource(R.string.untrusted_device),
            icon = Icons.Outlined.Shield,
            checked = untrusted,
            onCheckedChange = { checked ->
                untrusted = checked
                onDeviceMutate { it.untrusted = checked }
            }
        )
    }
}

@Composable
private fun DeviceSyncConditionsCard(
    holder: DeviceEditStateHolder,
    onOpenSyncConditions: () -> Unit,
) {
    FormCard {
        ToggleRow(
            title = stringResource(R.string.custom_sync_conditions_title),
            description = stringResource(R.string.custom_sync_conditions_description),
            icon = Icons.Outlined.Tune,
            checked = holder.customSyncConditions,
            onCheckedChange = { checked ->
                holder.customSyncConditions = checked
                holder.needsUpdate = true
            }
        )
        if (holder.customSyncConditions) {
            ClickRow(
                title = stringResource(R.string.custom_sync_conditions_dialog),
                value = stringResource(R.string.custom_sync_conditions_description),
                icon = Icons.Outlined.Schedule,
                onClick = onOpenSyncConditions
            )
        }
    }
}

@Composable
private fun DeviceFoldersCard(
    holder: DeviceEditStateHolder,
    onOpenFolderEdit: () -> Unit,
) {
    // Folders whose encryption password field is expanded. Collapsed by
    // default so long folder lists stay compact.
    var expandedPasswordFolders by remember { mutableStateOf(setOf<String>()) }
    FormCard(title = stringResource(R.string.folders)) {
        if (holder.folderStates.isEmpty()) {
            Text(
                text = stringResource(R.string.folders_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolderEdit() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            holder.folderStates.forEach { shareState ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = shareState.shared,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    holder.folderStates = holder.folderStates.map {
                                        if (it.folder.id == shareState.folder.id)
                                            it.copy(shared = checked)
                                        else it
                                    }
                                    holder.needsUpdate = true
                                }
                            )
                    ) {
                        Text(
                            text = shareState.folder.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (shareState.shared) {
                            // Key icon toggles the encryption password field;
                            // tinted primary when a password is configured.
                            IconButton(onClick = {
                                expandedPasswordFolders =
                                    if (shareState.folder.id in expandedPasswordFolders)
                                        expandedPasswordFolders - shareState.folder.id
                                    else
                                        expandedPasswordFolders + shareState.folder.id
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Key,
                                    contentDescription =
                                        stringResource(R.string.deviceEncryptionPasswordHint),
                                    tint = if (shareState.password.isNotEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Display-only switch: interaction is owned by the
                        // row's toggleable so hover/ripple bounds match the row.
                        Switch(checked = shareState.shared, onCheckedChange = null)
                    }
                    if (shareState.shared && shareState.folder.id in expandedPasswordFolders) {
                        OutlinedTextField(
                            value = shareState.password,
                            onValueChange = { value ->
                                holder.folderStates = holder.folderStates.map {
                                    if (it.folder.id == shareState.folder.id)
                                        it.copy(password = value)
                                    else it
                                }
                                holder.needsUpdate = true
                            },
                            label = { Text(stringResource(R.string.deviceEncryptionPasswordHint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun displayableAddresses(device: Device): String {
    val addresses = device.addresses ?: return ""
    return addresses.joinToString(", ")
}

/**
 * Formats the 52 character device ID the same way as the Syncthing web UI:
 * groups of 7 characters joined by dashes, 3 groups per line, so the text
 * never wraps mid-group even with large font scales.
 */
private fun formatDeviceId(deviceId: String): String =
    deviceId.replace("-", "")
        .chunked(7)
        .chunked(3)
        .joinToString("\n") { line -> line.joinToString("-") }

/**
 * Fault tolerant address list parsing, ported from DeviceActivity.persistableAddresses.
 */
private fun persistableAddresses(userInput: String): List<String> {
    if (userInput.isEmpty()) {
        return listOf("dynamic")
    }
    var input = userInput.replace(",", " ")
    input = input.replace(";", " ")
    input = input.replace("\\s+".toRegex(), ", ")
    return input.split(", ")
}

@Composable
private fun compressionTitle(index: Int): String {
    val entries = stringArrayResource(R.array.compress_entries)
    return entries.getOrElse(index) { "" }
}
