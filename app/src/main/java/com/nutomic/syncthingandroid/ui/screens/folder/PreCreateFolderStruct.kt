package com.nutomic.syncthingandroid.ui.screens.folder

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.FileUtils
import java.io.File
import java.io.FileWriter

/**
 * Pre-creates the ".stfolder" marker and ".stversions" directory, ported from
 * FolderActivity.preCreateFolderStruct. Must be called on a background dispatcher.
 */
internal fun preCreateFolderStruct(context: Context, uriFolderRoot: Uri?, absolutePath: String) {
    val TAG = "FolderEditScreen"
    val folderMarkerDirName = Folder().markerName
    val strFolderMarkerPath = absolutePath + File.separator + folderMarkerDirName
    val doNotDeleteFileName = "DO_NOT_DELETE"
    val strDoNotDeleteFile = strFolderMarkerPath + File.separator + doNotDeleteFileName
    val strStVersionsPath = absolutePath + File.separator + Constants.FOLDER_NAME_STVERSIONS
    val strStVersionsNoMediaFile = strStVersionsPath + File.separator + ".nomedia"

    // Fall back to classic API if uriFolderRoot is missing.
    if (uriFolderRoot == null) {
        Log.w(TAG, "preCreateFolderStruct: uriFolderRoot == null. Using absolute path.")
        try {
            File(strFolderMarkerPath).mkdirs()
            if (File(strDoNotDeleteFile).createNewFile()) {
                FileWriter(strDoNotDeleteFile).use { it.write(doNotDeleteFileName) }
            }
            File(strStVersionsPath).mkdirs()
            File(strStVersionsNoMediaFile).createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e)
        }
        return
    }

    val dfFolder = DocumentFile.fromTreeUri(context, uriFolderRoot) ?: return

    val dfFolderMarkerDir = FileUtils.safCreateDirectory(dfFolder, folderMarkerDirName)
    if (dfFolderMarkerDir != null) {
        // The marker file is extensionless "DO_NOT_DELETE" on both the SAF and the
        // classic path, and safCreateFile checks existence by the SAME name it
        // creates under. The old ".txt" spelling checked for "DO_NOT_DELETE.txt"
        // while actually creating the extension-stripped "DO_NOT_DELETE", so every
        // folder add stacked another provider-renamed duplicate ("DO_NOT_DELETE (1)",
        // ...) inside the marker directory.
        FileUtils.safCreateFile(context, dfFolderMarkerDir, doNotDeleteFileName, doNotDeleteFileName)
    }
    val dfStVersionsDir = FileUtils.safCreateDirectory(dfFolder, Constants.FOLDER_NAME_STVERSIONS)
    if (dfStVersionsDir != null) {
        FileUtils.safCreateFile(context, dfStVersionsDir, ".nomedia", "")
    }
}
