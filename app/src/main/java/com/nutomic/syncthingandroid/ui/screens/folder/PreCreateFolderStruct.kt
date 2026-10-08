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
internal fun preCreateFolderStruct(context: Context, uriFolderRoot: Uri?, absolutePath: String): Boolean {
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
        return try {
            val markerDir = File(strFolderMarkerPath)
            if ((!markerDir.isDirectory && !markerDir.mkdirs()) || !markerDir.isDirectory) return false
            val marker = File(strDoNotDeleteFile)
            if (!marker.exists() && !marker.createNewFile()) return false
            if (!marker.isFile) return false
            FileWriter(strDoNotDeleteFile).use { it.write(doNotDeleteFileName) }
            val versionsDir = File(strStVersionsPath)
            if ((!versionsDir.isDirectory && !versionsDir.mkdirs()) || !versionsDir.isDirectory) return false
            val noMedia = File(strStVersionsNoMediaFile)
            if (!noMedia.exists() && !noMedia.createNewFile()) return false
            if (!noMedia.isFile) return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e)
            false
        }
    }

    val dfFolder = DocumentFile.fromTreeUri(context, uriFolderRoot) ?: return false

    val dfFolderMarkerDir = FileUtils.safCreateDirectory(dfFolder, folderMarkerDirName)
        ?: return false
    // The marker file is extensionless "DO_NOT_DELETE" on both the SAF and the
    // classic path, and safCreateFile checks existence by the SAME name it
    // creates under. The old ".txt" spelling checked for "DO_NOT_DELETE.txt"
    // while actually creating the extension-stripped "DO_NOT_DELETE", so every
    // folder add stacked another provider-renamed duplicate inside the marker directory.
    if (!FileUtils.safCreateFile(context, dfFolderMarkerDir, doNotDeleteFileName, doNotDeleteFileName)) {
        return false
    }
    val dfStVersionsDir = FileUtils.safCreateDirectory(dfFolder, Constants.FOLDER_NAME_STVERSIONS)
        ?: return false
    return FileUtils.safCreateFile(context, dfStVersionsDir, ".nomedia", "")
}
