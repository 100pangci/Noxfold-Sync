package com.nutomic.syncthingandroid.ui.screens.log

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveContent
import java.io.File

/**
 * Log viewer screen, ported from the legacy LogActivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showSyncthingLog by rememberSaveable { mutableStateOf(false) }
    var logText by rememberSaveable { mutableStateOf("") }
    var isLoading by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(showSyncthingLog) {
        isLoading = true
        logText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Refresh the Android log file and read the SyncthingNative log file.
            val androidLogContent = LogContent.getAndroidLog()
            LogContent.writeLogFile(LogContent.getAndroidLogFile(context), androidLogContent)
            val syncthingLogContent = LogContent.readLogFile(LogContent.getSyncthingLogFile(context))
            if (showSyncthingLog) syncthingLogContent else androidLogContent
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (showSyncthingLog) R.string.syncthing_log_title
                            else R.string.android_log_title
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { showSyncthingLog = !showSyncthingLog }) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            stringResource(
                                if (showSyncthingLog) R.string.view_android_log
                                else R.string.view_syncthing_log
                            )
                        )
                    }
                    val shareLabel = stringResource(R.string.share_log_file)
                    IconButton(onClick = {
                        val logFile: File =
                            if (showSyncthingLog) LogContent.getSyncthingLogFile(context)
                            else LogContent.getAndroidLogFile(context)
                        if (!logFile.exists()) {
                            android.widget.Toast.makeText(
                                context, R.string.share_log_file_missing, android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@IconButton
                        }
                        val contentUri = FileProvider.getUriForFile(
                            context, context.packageName + ".provider", logFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, contentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, shareLabel)
                        )
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.share_log_file))
                    }
                }
            )
        }
    ) { innerPadding ->
        AdaptiveContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isLoading) {
                Text(
                    text = stringResource(R.string.retrieving_logs),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            } else {
                SelectionContainer {
                    Text(
                        text = logText,
                        fontFamily = FontFamily.Monospace,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

