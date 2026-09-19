package com.nutomic.syncthingandroid.ui.screens.photoshoot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveContent
import com.nutomic.syncthingandroid.util.PermissionUtil

/**
 * "Syncthing Camera" intro screen, ported from the legacy PhotoShootActivity intro UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoShootIntroScreen(
    onGo: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasStoragePermission by remember {
        mutableStateOf(PermissionUtil.haveStoragePermission(context))
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_shoot_intro_welcome_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.photo_shoot_intro_permission_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!hasStoragePermission) {
                    Button(
                        onClick = {
                            PermissionUtil.requestStoragePermission(
                                context as android.app.Activity, 142
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.grant_storage_permission))
                    }
                }
                if (!hasCameraPermission) {
                    Button(
                        onClick = {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.grant_camera_permission))
                    }
                }
                Button(
                    onClick = {
                        val havePermissions = PermissionUtil.haveStoragePermission(context) &&
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!havePermissions) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.photo_shoot_intro_missing_permissions,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@Button
                        }
                        context.appPreferences()
                            .edit()
                            .putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true)
                            .apply()
                        onGo()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.cont))
                }
            }
        }
    }
}
