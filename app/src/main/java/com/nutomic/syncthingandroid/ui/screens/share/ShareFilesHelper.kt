package com.nutomic.syncthingandroid.ui.screens.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.nutomic.syncthingandroid.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * File naming + copying helpers, ported from the legacy ShareActivity.
 */
internal object ShareFilesHelper {

    private const val TAG = "ShareScreen"

    data class CopyResult(val isError: Boolean, val copied: Int, val ignored: Int)

    /**
     * Generate file name for new file.
     */
    fun generateDisplayName(context: Context): String {
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        return String.format(context.resources.getString(R.string.file_name_template), dateTime)
    }

    /**
     * Get file name from uri (taken from ownCloud Android originally).
     */
    fun getDisplayNameForUri(context: Context, uri: Uri): String? {
        var displayName: String?
        if (ContentResolver.SCHEME_CONTENT != uri.scheme) {
            displayName = uri.lastPathSegment
        } else {
            displayName = getDisplayNameFromContentResolver(context, uri)
            if (displayName == null) {
                // last chance to have a name
                displayName = uri.lastPathSegment?.replace("\\s".toRegex(), "")
            }
            // Add best possible extension
            val index = displayName?.lastIndexOf(".") ?: -1
            if (index == -1 || MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(displayName!!.substring(index + 1)) == null
            ) {
                val mimeType = context.contentResolver.getType(uri)
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (extension != null) {
                    displayName += "." + extension
                }
            }
        }
        // Replace path separator characters to avoid inconsistent paths
        return displayName?.replace("/".toRegex(), "-")
    }

    private fun getDisplayNameFromContentResolver(context: Context, uri: Uri): String? {
        var displayName: String? = null
        val mimeType = context.contentResolver.getType(uri) ?: return null
        val displayNameColumn = when {
            mimeType.startsWith("image/") -> MediaStore.Images.ImageColumns.DISPLAY_NAME
            mimeType.startsWith("video/") -> MediaStore.Video.VideoColumns.DISPLAY_NAME
            mimeType.startsWith("audio/") -> MediaStore.Audio.AudioColumns.DISPLAY_NAME
            else -> MediaStore.Files.FileColumns.DISPLAY_NAME
        }
        var cursor: android.database.Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, arrayOf(displayNameColumn), null, null, null)
            if (cursor != null) {
                cursor.moveToFirst()
                displayName = cursor.getString(cursor.getColumnIndexOrThrow(displayNameColumn))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not retrieve display name for " + uri.toString())
        } finally {
            cursor?.close()
        }
        return displayName
    }

    /**
     * Copies the given files into the target directory. Blocking - call from IO dispatcher.
     */
    fun copyFiles(
        context: Context,
        files: Map<Uri, String>,
        directory: File,
        allowOverwrite: Boolean,
    ): CopyResult {
        var copied = 0
        var ignored = 0
        var isError = false
        for ((sourceUri, displayName) in files) {
            var inputStream: InputStream? = null
            try {
                val outFile = File(directory, displayName)
                if (outFile.isFile && !allowOverwrite) {
                    ignored++
                    continue
                }
                inputStream = context.contentResolver.openInputStream(sourceUri)
                outFile.outputStream().use { output -> inputStream!!.copyTo(output) }
                copied++
            } catch (e: FileNotFoundException) {
                Log.e(TAG, String.format("Can't find input file \"%s\" to copy", sourceUri), e)
                isError = true
            } catch (e: IOException) {
                Log.e(TAG, String.format("IO exception during file \"%s\" sharing", sourceUri), e)
                isError = true
            } finally {
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Exception on input/output stream close", e)
                }
            }
        }
        return CopyResult(isError, copied, ignored)
    }
}
