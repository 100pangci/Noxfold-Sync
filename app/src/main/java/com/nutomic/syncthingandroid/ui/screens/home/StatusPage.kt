package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.TABLET_MIN_WIDTH_DP
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.components.AppCard
import com.nutomic.syncthingandroid.ui.contentWidthForTablet
import com.nutomic.syncthingandroid.util.Util
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "StatusPage"

/**
 * Status tab: why syncthing is (not) running plus system statistics.
 * Ported from the legacy StatusFragment and SegmentedButton.
 *
 * [visible] gates the polling loop: since the pager keeps this page composed
 * off-screen, polling here would otherwise recompose an invisible page every
 * interval and steal main-thread time from the list tabs.
 */
@Composable
fun StatusPage(
    serviceState: SyncthingService.State,
    visible: Boolean,
) {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val api = service?.api
    val preferences = context.appPreferences()
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP

    var forceStartStopState by remember {
        mutableStateOf(
            preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP)
        )
    }

    var ramUsage by remember { mutableStateOf("") }
    var download by remember { mutableStateOf("") }
    var upload by remember { mutableStateOf("") }
    var announceServer by remember { mutableStateOf("") }
    var uptime by remember { mutableStateOf("") }

    // Overall sync completion is event-driven inside RestApi and cached as a
    // StateFlow (phase6b): collect it instead of polling the cache here.
    val totalSyncCompletion = api?.totalSyncCompletion?.collectAsState()?.value ?: -1

    LaunchedEffect(serviceState, visible) {
        if (!visible) return@LaunchedEffect
        while (isActive) {
            if (serviceState == SyncthingService.State.ACTIVE && api != null && api.isConfigLoaded) {
                try {
                    api.getRemoteDeviceStatus("")

                    val systemStatus = api.fetchSystemStatus()
                    val announceTotal = systemStatus.discoveryMethods
                    val announceConnected =
                        announceTotal - (systemStatus.discoveryErrors?.size ?: 0)
                    ramUsage = Util.readableFileSize(context, systemStatus.sys.toDouble())
                    announceServer =
                        if (announceTotal == 0) ""
                        else String.format(Locale.getDefault(), "%1\$d/%2\$d", announceConnected, announceTotal)

                    val uptimeDays = TimeUnit.SECONDS.toDays(systemStatus.uptime)
                    val uptimeHours = TimeUnit.SECONDS.toHours(systemStatus.uptime) - TimeUnit.DAYS.toHours(uptimeDays)
                    val uptimeMinutes = TimeUnit.SECONDS.toMinutes(systemStatus.uptime) -
                            TimeUnit.HOURS.toMinutes(uptimeHours) - TimeUnit.DAYS.toMinutes(uptimeDays)
                    uptime = when {
                        uptimeDays > 0 -> String.format(Locale.getDefault(), "%dd %02dh %02dm", uptimeDays, uptimeHours, uptimeMinutes)
                        uptimeHours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", uptimeHours, uptimeMinutes)
                        else -> String.format(Locale.getDefault(), "%dm", uptimeMinutes)
                    }

                    val total: Connection =
                        if (api.isConfigLoaded) api.totalConnectionStatistic else Connection()
                    // "Hide" rates below 1 KiB/s to avoid bothering the user with idle traffic.
                    download = (if (total.inBits / 8 < 1024) "0 B/s" else Util.readableTransferRate(context, total.inBits)) +
                            " (" + Util.readableFileSize(context, total.inBytesTotal.toDouble()) + ")"
                    upload = (if (total.outBits / 8 < 1024) "0 B/s" else Util.readableTransferRate(context, total.outBits)) +
                            " (" + Util.readableFileSize(context, total.outBytesTotal.toDouble()) + ")"
                } catch (e: Exception) {
                    // Transient transport/parse failure - keep the old callback API's
                    // fire-and-forget tolerance and retry on the next cycle.
                    Log.w(TAG, "StatusPage: system status fetch failed", e)
                }
            }
            delay(Constants.REST_UPDATE_INTERVAL)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .contentWidthForTablet(isTablet)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // ---- Run state card: sync progress + state + reasons ----
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val stateTitle = when (serviceState) {
                    SyncthingService.State.INIT, SyncthingService.State.STARTING ->
                        stringResource(R.string.syncthing_starting)
                    SyncthingService.State.ACTIVE ->
                        if (totalSyncCompletion != -1)
                            stringResource(R.string.state_syncing, totalSyncCompletion)
                        else
                            stringResource(R.string.syncthing_running)
                    SyncthingService.State.DISABLED ->
                        stringResource(R.string.syncthing_not_running)
                    SyncthingService.State.ERROR ->
                        stringResource(R.string.syncthing_has_crashed)
                }
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (serviceState == SyncthingService.State.ACTIVE && totalSyncCompletion != -1) {
                    LinearProgressIndicator(
                        progress = { totalSyncCompletion / 100f },
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (serviceState == SyncthingService.State.ACTIVE || serviceState == SyncthingService.State.DISABLED) {
                    val explanation = service?.runDecisionExplanation?.trim()?.replace("\n", "\n- ") ?: ""
                    Text(
                        text = stringResource(R.string.reason),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "- " + explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- Transfer & resources card: two-column key/value grid ----
        if (serviceState == SyncthingService.State.ACTIVE) {
            val stats = buildList {
                if (uptime.isNotEmpty()) add(stringResource(R.string.uptime) to uptime)
                if (ramUsage.isNotEmpty()) add(stringResource(R.string.ram_usage) to ramUsage)
                if (download.isNotEmpty()) add(stringResource(R.string.download_title) to download)
                if (upload.isNotEmpty()) add(stringResource(R.string.upload_title) to upload)
                if (announceServer.isNotEmpty()) add(stringResource(R.string.announce_server) to announceServer)
            }
            if (stats.isNotEmpty()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.status_transfer_resources),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        stats.chunked(2).forEach { rowItems ->
                            Row {
                                StatCell(
                                    label = rowItems[0].first,
                                    value = rowItems[0].second,
                                    modifier = Modifier.weight(1f)
                                )
                                if (rowItems.size > 1) {
                                    androidx.compose.material3.VerticalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    StatCell(
                                        label = rowItems[1].first,
                                        value = rowItems[1].second,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Run decision picker: segmented buttons. All segments share a fixed
        // two-line height so long labels cannot stretch individual segments
        // out of alignment, and the check mark gets a small inset so it does
        // not hug the leading edge.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val labels = listOf(
                stringResource(R.string.button_follow_run_conditions),
                stringResource(R.string.button_force_start),
                stringResource(R.string.button_force_stop)
            )
            labels.forEachIndexed { index, rawLabel ->
                // Split "MAIN\nSUB" resources so every segment renders a
                // uniform title + subtitle pair (or title only).
                val labelLines = rawLabel.split("\n")
                SegmentedButton(
                    selected = forceStartStopState == index,
                    onClick = {
                        forceStartStopState = index
                        preferences.edit()
                            .putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, index)
                            .apply()
                        // Notify RunConditionMonitor that the decision changed.
                        androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(context)
                            .sendBroadcast(
                                android.content.Intent(
                                    com.nutomic.syncthingandroid.service.RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION
                                )
                            )
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    // Custom icon slot: draw the check mark (with a small
                    // inset so it clears the leading edge) only when this
                    // segment is selected; nothing otherwise.
                    icon = {
                        if (forceStartStopState == index) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(16.dp)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Text(
                            text = labelLines[0],
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (labelLines.size > 1) {
                            Text(
                                text = labelLines[1],
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * One key/value cell of the transfer & resources grid.
 */
@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
