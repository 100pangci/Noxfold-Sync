package com.nutomic.syncthingandroid.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.adaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.rememberWindowSizeClass
import com.nutomic.syncthingandroid.ui.theme.AMOLED_CARD_BORDER_ALPHA
import com.nutomic.syncthingandroid.ui.theme.LocalAmoledTheme

/**
 * Device ID QR dialog, ported from the legacy DeviceIdDialogFragment.
 */
@Composable
fun DeviceIdQrDialog(
    deviceName: String,
    deviceId: String,
    isCurrentDevice: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val qrCode = remember(deviceId) { generateQrCode(deviceId) }
    val deviceIdLabel = stringResource(R.string.device_id)
    val chooserLabel = stringResource(R.string.share_device_id_chooser)

    val onCopy: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(deviceIdLabel, deviceId))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.device_id_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }
    val onShare: () -> Unit = {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, deviceId)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, chooserLabel)
        )
    }

    val sideBySide = useSideBySideLayout()

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = if (sideBySide) 840.dp else 460.dp)
            .fillMaxWidth(0.9f),
        onDismissRequest = onDismiss,
        title = {
            if (sideBySide) {
                Row(Modifier.fillMaxWidth()) {
                    DialogTitle(deviceName, isCurrentDevice, Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.close_device_id))
                    }
                }
            } else {
                DialogTitle(deviceName, isCurrentDevice)
            }
        },
        text = {
            if (sideBySide) {
                LandscapeDialogContent(deviceId, qrCode, onCopy, onShare)
            } else {
                PortraitDialogContent(deviceId, qrCode, onCopy, onShare)
            }
        },
        confirmButton = {
            if (!sideBySide) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.finish))
                }
            }
        },
    )
}

/**
 * Side-by-side layout from medium window width on, so the QR code and the device ID
 * are not crammed into a narrow column on wide screens (including portrait tablets).
 */
@Composable
private fun useSideBySideLayout(): Boolean =
    rememberWindowSizeClass().adaptiveWidthClass != AdaptiveWidthClass.Compact

@Composable
private fun DialogTitle(
    deviceName: String,
    isCurrentDevice: Boolean,
    modifier: Modifier = Modifier
) {
    val thisDeviceText = stringResource(R.string.this_device)

    Column(modifier) {
        Text(stringResource(R.string.device_id))
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrentDevice) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = false,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = thisDeviceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PortraitDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val containerHeightDp = windowInfo.containerDpSize.height
    val qrMaxHeight = minOf(containerHeightDp * 0.30f, 280.dp)

    Column {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.heightIn(min = 160.dp, max = qrMaxHeight).fillMaxWidth()
        ) {
            Image(
                bitmap = qrCode.asImageBitmap(),
                contentDescription = stringResource(R.string.device_id),
                Modifier.fillMaxHeight()
            )
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = amoledSurfaceBorder(),
        ) {
            Text(deviceId, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(16.dp))
        CopyShareButtons(onCopy, onShare)
    }
}

@Composable
private fun LandscapeDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxHeight().weight(1f)
        ) {
            Image(
                bitmap = qrCode.asImageBitmap(),
                contentDescription = stringResource(R.string.device_id),
                Modifier.fillMaxHeight()
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = amoledSurfaceBorder(),
            ) {
                Text(deviceId, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            CopyShareButtons(onCopy, onShare)
        }
    }
}

@Composable
private fun CopyShareButtons(
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onCopy, Modifier.weight(1f)) {
            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.copy))
        }
        FilledTonalButton(onShare, Modifier.weight(1f)) {
            Icon(imageVector = Icons.Outlined.Share, contentDescription = stringResource(R.string.share_title))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_title))
        }
    }
}

/**
 * Faint outline for container-toned surfaces (device ID blocks) in the pure AMOLED
 * theme, matching the card treatment; null in the regular themes.
 */
@Composable
private fun amoledSurfaceBorder(): BorderStroke? =
    if (LocalAmoledTheme.current) {
        BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = AMOLED_CARD_BORDER_ALPHA)
        )
    } else {
        null
    }

private fun generateQrCode(deviceId: String): Bitmap {
    val qrSize = 232
    val black = 0xFF000000
    val white = 0x00000000

    val bitMatrix = MultiFormatWriter()
        .encode(deviceId, BarcodeFormat.QR_CODE, qrSize, qrSize)
    val bitMap = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)

    for (x in 0 until qrSize) {
        for (y in 0 until qrSize) {
            val pixel = if (bitMatrix[x, y]) black else white
            bitMap[x, y] = pixel.toInt()
        }
    }

    return bitMap
}
