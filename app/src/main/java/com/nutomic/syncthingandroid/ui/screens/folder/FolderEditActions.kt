package com.nutomic.syncthingandroid.ui.screens.folder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionEvents
import com.nutomic.syncthingandroid.ui.nav.AppNavigator
import com.nutomic.syncthingandroid.util.ConfigRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Save/delete actions of the folder edit screen, ported from FolderActivity.onSave/showDeleteDialog.
 */
internal object FolderEditActions {

    private const val TAG = "FolderEditScreen"

    fun save(
        scope: CoroutineScope,
        context: Context,
        navigator: AppNavigator,
        configRouter: ConfigRouter,
        api: RestApi?,
        preferences: SharedPreferences,
        folder: Folder,
        folderUri: Uri?,
        needsUpdate: Boolean,
        ignoreListNeedsUpdate: Boolean,
        ignoreListText: String,
        deviceStates: List<DeviceShareState>,
        customSyncConditions: Boolean,
        runScript: Boolean,
        isCreate: Boolean,
        isSaving: Boolean,
        setSaving: (Boolean) -> Unit,
        onValidationError: (Int) -> Unit,
    ) {
        if (isSaving) {
            Log.v(TAG, "onSave: save already in progress")
            return
        }

        // Validate fields.
        if (folder.id.isNullOrEmpty()) {
            onValidationError(com.nutomic.syncthingandroid.R.string.folder_id_required)
            return
        }
        if (folder.label.isNullOrEmpty()) {
            onValidationError(com.nutomic.syncthingandroid.R.string.folder_label_required)
            return
        }
        if (folder.path.isNullOrEmpty()) {
            onValidationError(com.nutomic.syncthingandroid.R.string.folder_path_required)
            return
        }

        setSaving(true)

        preferences.edit().putBoolean(
            Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folder.id),
            runScript
        ).apply()

        if (isCreate) {
            Log.v(TAG, "onSave: Adding folder with ID = '" + folder.id + "'")
            val capturedUri = folderUri
            val capturedPath = folder.path
            scope.launch {
                withContext(Dispatchers.IO) {
                    preCreateFolderStruct(context, capturedUri, capturedPath)
                }
                configRouter.addFolder(api, folder)

                // Push ignore patterns entered during creation; the folder must
                // exist in Syncthing first, so give the config POST a moment.
                if (ignoreListText.isNotBlank()) {
                    kotlinx.coroutines.delay(1000)
                    configRouter.postFolderIgnoreList(
                        api, folder, ignoreListText.split("\n").toTypedArray()
                    )
                }

                // Start sync after adding a folder.
                RunConditionEvents.fireSyncTrigger(beginActiveTimeWindow = true)
                setSaving(false)
                navigator.navigateBack()
            }
            return
        }

        // Edit mode.
        if (!needsUpdate) {
            navigator.navigateBack()
            return
        }

        Log.v(TAG, "onSave: Updating folder with ID = '" + folder.id + "'")
        preferences.edit().putBoolean(
            Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(
                Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
            ),
            customSyncConditions
        ).apply()

        if (ignoreListNeedsUpdate) {
            configRouter.postFolderIgnoreList(api, folder, ignoreListText.split("\n").toTypedArray())
        }

        // Apply device sharing + encryption passwords.
        for (state in deviceStates) {
            val device = state.device
            if (state.shared) {
                folder.addDevice(com.nutomic.syncthingandroid.model.SharedWithDevice().apply {
                    deviceID = device.deviceID
                    introducedBy = device.introducedBy
                })
                folder.getDevice(device.deviceID)?.let { it.encryptionPassword = state.password }
            } else {
                folder.removeDevice(device.deviceID)
            }
        }

        configRouter.updateFolder(api, folder)
        navigator.navigateBack()
    }

    fun delete(
        configRouter: ConfigRouter,
        api: RestApi?,
        preferences: SharedPreferences,
        folderId: String,
        navigator: AppNavigator,
    ) {
        configRouter.removeFolder(api, folderId)
        if (folderId == Constants.syncthingCameraFolderId) {
            preferences.edit().putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false).apply()
        }
        navigator.navigateBack()
    }
}
