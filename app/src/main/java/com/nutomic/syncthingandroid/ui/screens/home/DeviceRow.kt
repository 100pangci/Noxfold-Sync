package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.theme.StatusBadge

/**
 * Max folder names shown before the list collapses behind a "+N" expander.
 */
private const val FOLDER_LIST_COLLAPSE_THRESHOLD = 4

/**
 * One device row inside a [HomeGroupCard] (pure renderer; all data is
 * precomputed in [DeviceUiModel]). Tapping the row opens the device settings.
 */
@Composable
internal fun DeviceRowContent(
    model: DeviceUiModel,
    onEdit: (DeviceUiModel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(model) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.lastSeenText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (model.sharedFolderNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.device_state_unused),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.shared_folders_title_colon),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val expanded = remember(model.id) { mutableStateOf(false) }
                val hiddenCount = model.sharedFolderNames.size - FOLDER_LIST_COLLAPSE_THRESHOLD
                val visibleNames =
                    if (!expanded.value && hiddenCount > 0)
                        model.sharedFolderNames.take(FOLDER_LIST_COLLAPSE_THRESHOLD)
                    else
                        model.sharedFolderNames
                visibleNames.forEach { name ->
                    Text(
                        text = "\u2022 $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hiddenCount > 0) {
                    Text(
                        text = if (expanded.value)
                            stringResource(R.string.device_folders_show_less)
                        else
                            stringResource(R.string.device_folders_show_more, hiddenCount),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { expanded.value = !expanded.value }
                    )
                }
            }

            model.rateText?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            StatusBadge(text = model.statusText, kind = model.statusKind)
            if (model.isSyncing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { model.completion / 100f },
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth()
                )
            }
    }
}
