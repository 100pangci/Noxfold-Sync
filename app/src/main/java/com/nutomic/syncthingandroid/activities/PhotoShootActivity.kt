package com.nutomic.syncthingandroid.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.PermissionChecker
import com.nutomic.syncthingandroid.R
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.ui.theme.ApplicationTheme
import com.nutomic.syncthingandroid.ui.screens.photoshoot.PhotoShootIntroScreen
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.FileUtils.ExternalStorageDirType
import com.nutomic.syncthingandroid.util.PermissionUtil
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Syncthing Camera" activity host, ported from the legacy PhotoShootActivity.
 * The intro UI now lives in
 * [com.nutomic.syncthingandroid.ui.screens.photoshoot.PhotoShootIntroScreen].
 */
class PhotoShootActivity : ThemedAppCompatActivity() {

    private var lastPhotoUri: Uri? by androidx.compose.runtime.mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check if required camera hardware is present.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            android.widget.Toast.makeText(
                this, getString(R.string.photo_shoot_intro_no_camera), android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Check if user granted permissions before and consented to use this feature.
        val prefEnableSyncthingCamera =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(com.nutomic.syncthingandroid.service.Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        val haveRequiredPermissions = PermissionUtil.haveStoragePermission(this) && haveCameraPermission()
        if (prefEnableSyncthingCamera && haveRequiredPermissions) {
            // Take a shortcut and offer to take a picture instantly.
            openCameraIntent()
            return
        }

        setContent {
            ApplicationTheme {
                PhotoShootIntroScreen(
                    onGo = { openCameraIntent() },
                    onBack = { finish() },
                )
            }
        }
    }

    private fun haveCameraPermission(): Boolean =
        PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PermissionChecker.PERMISSION_GRANTED

    private fun openCameraIntent() {
        val pictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (pictureIntent.resolveActivity(packageManager) == null) {
            Log.e(TAG, "This system does not support the ACTION_IMAGE_CAPTURE intent.")
            finish()
            return
        }

        // Create a file to store the image.
        val photoFile: File
        try {
            val created = createImageFile()
            if (created == null) {
                Log.e(TAG, "openCameraIntent: photoFile == null")
                return
            }
            photoFile = created
        } catch (ex: IOException) {
            Log.e(TAG, "Error occurred while creating the temp image file")
            return
        }

        val photoUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)
        pictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        lastPhotoUri = photoUri

        cameraLauncher.launch(pictureIntent)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = lastPhotoUri
        lastPhotoUri = null
        if (result.resultCode == RESULT_OK && uri != null) {
            try {
                MediaStore.Images.Media.getBitmap(contentResolver, uri).width
                android.widget.Toast.makeText(
                    this, R.string.photo_shoot_take_picture_success, android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return@registerForActivityResult
            } catch (e: Exception) {
                Log.d(TAG, "User cancelled or failed to take a picture.")
            }
        }
        // Failure: clean up the temp file.
        if (uri != null) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Delete temporary file FAILED", e)
            }
        }
        android.widget.Toast.makeText(
            this, R.string.photo_shoot_take_picture_failure, android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun createImageFile(): File? {
        val timeStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(LocalDateTime.now())
        val imageFileName = "IMG_$timeStamp" + "_"
        val storageDir = FileUtils.getExternalFilesDir(this, ExternalStorageDirType.INT_MEDIA, Environment.DIRECTORY_PICTURES)
            ?: return null
        storageDir.mkdirs()
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    companion object {
        private const val TAG = "PhotoShootActivity"
    }
}
