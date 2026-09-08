package com.nutomic.syncthingandroid.ui.dialogs

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.nav.LocalResultBus
import com.nutomic.syncthingandroid.util.ConfigRouter
import java.util.concurrent.TimeUnit

/**
 * Hosts the dialogs that are controlled from MainActivity:
 * the device id QR dialog and the anonymous usage reporting dialog.
 */
@Composable
fun MainActivityDialogsHost() {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val serviceState = LocalServiceState.current
    val resultBus = LocalResultBus.current
    val configRouter = remember { ConfigRouter(context) }
    val api = service?.api
    val apiConfigLoaded = api?.isConfigLoaded ?: false

    // ---- Device id QR dialog ----
    val showDeviceIdDialog by resultBus.showDeviceIdDialog.collectAsState()
    val deviceId = showDeviceIdDialog
    if (deviceId != null) {
        val deviceName = remember(deviceId) {
            var name = ""
            try {
                for (d in configRouter.getDevices(api, true)) {
                    if (d.deviceID == deviceId) {
                        name = d.displayName
                        break
                    }
                }
            } catch (e: Exception) {
                // Ignore - fall back to empty name.
            }
            name.trim()
        }
        DeviceIdQrDialog(
            deviceName = deviceName,
            deviceId = deviceId,
            isCurrentDevice = true,
            onDismiss = { resultBus.showDeviceIdDialog.value = null }
        )
    }

    // ---- Restart confirmation dialog ----
    val restartRequested by resultBus.restartRequested.collectAsState()
    if (restartRequested) {
        AlertDialog(
            onDismissRequest = { resultBus.restartRequested.value = false },
            title = { Text(stringResource(R.string.dialog_confirm_restart)) },
            confirmButton = {
                TextButton(onClick = {
                    resultBus.restartRequested.value = false
                    context.startService(
                        Intent(context, SyncthingService::class.java)
                            .setAction(SyncthingService.ACTION_RESTART)
                    )
                }) { Text(stringResource(android.R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = { resultBus.restartRequested.value = false }) {
                    Text(stringResource(android.R.string.no))
                }
            }
        )
    }

    // ---- Usage reporting dialog ----
    LaunchedEffect(serviceState, apiConfigLoaded) {
        if (serviceState != SyncthingService.State.ACTIVE || !apiConfigLoaded || api == null) {
            return@LaunchedEffect
        }
        if (!usageReportingDelayPassed(context)) {
            return@LaunchedEffect
        }
        if (api.isUsageReportingDecided()) {
            return@LaunchedEffect
        }
        api.getUsageReport { report ->
            resultBus.usageReport.value = report
        }
    }
    val usageReport by resultBus.usageReport.collectAsState()
    val report = usageReport
    if (report != null && api != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.usage_reporting_dialog_title)) },
            text = {
                // The usage report text is long; keep it in a small scrollable
                // block so the whole dialog always fits on screen.
                Text(
                    text = report,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resultBus.usageReport.value = null
                    api.setUsageReporting(true)
                    api.sendConfig()
                }) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    resultBus.usageReport.value = null
                    api.setUsageReporting(false)
                    api.sendConfig()
                }) { Text(stringResource(R.string.no)) }
            }
        )
    }
}

private fun usageReportingDelayPassed(context: android.content.Context): Boolean {
    return try {
        val firstInstallTime = context.packageManager
            .getPackageInfo(context.packageName, 0).firstInstallTime
        System.currentTimeMillis() > firstInstallTime + TimeUnit.DAYS.toMillis(3)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
