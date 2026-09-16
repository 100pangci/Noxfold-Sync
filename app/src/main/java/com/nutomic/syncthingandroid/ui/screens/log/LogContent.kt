package com.nutomic.syncthingandroid.ui.screens.log

import android.content.Context
import android.util.Log
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Log fetching/writing helpers, ported from the legacy LogActivity's UpdateLogTask.
 */
internal object LogContent {

    private const val TAG = "LogScreen"
    private const val ANDROID_LOG_FILE_MAX_LINES = 2000

    fun getAndroidLog(): String {
        var output = Util.runShellCommandGetOutput(
            "/system/bin/logcat -t " + ANDROID_LOG_FILE_MAX_LINES + " -v time *:i ps:s art:s"
        )
        output = output.replace("I/SyncthingNativeCode", "")
        output = output.replace("\\(\\s?[0-9]+\\):".toRegex(), "")
        val lines = output.split("\n")
        val result = ArrayList<String>(lines.size)
        for (logline in lines) {
            if (isNoiseLine(logline)) continue
            // Remove date and milliseconds.
            var line = logline.replaceFirst("^[0-9]{2}-[0-9]{2}\\s".toRegex(), "")
            line = line.replaceFirst("^([0-9]{2}:[0-9]{2}:[0-9]{2})\\.[0-9]{3}\\s".toRegex(), "$1")
            result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun isNoiseLine(logline: String): Boolean {
        return logline.contains("--- beginning of ") ||
                logline.contains("/AbsListViewStubImpl") ||
                logline.contains("W/ActionBarDrawerToggle") ||
                logline.contains("/ActivityThread") ||
                logline.contains("/Choreographer") ||
                logline.contains("I/chatty") ||
                logline.contains("W/chmod") ||
                logline.contains("/chromium") ||
                logline.contains("/DecorView") ||
                logline.contains("/EGL") ||
                logline.contains("E/FileUtils err") ||
                logline.contains("/HWUI") ||
                logline.contains("/IInputConnectionWrapper") ||
                logline.contains("/InputMethodManager") ||
                logline.contains("W/Looper") ||
                logline.contains("/libEGL") ||
                logline.contains("E/libc") ||
                logline.contains("W/libc") ||
                logline.contains("/OpenGLRenderer") ||
                logline.contains("/Repluralization") ||
                logline.contains("/RenderThread") ||
                logline.contains("/ResourceType") ||
                logline.contains("W/sh") ||
                logline.contains("W/Settings") ||
                logline.contains("/StrictMode") ||
                logline.contains("I/System.out") ||
                logline.contains("W/TextView") ||
                logline.contains("I/Timeline") ||
                logline.contains("I/VRI") ||
                logline.contains("/ViewRootImpl") ||
                logline.contains("I/WebViewFactory") ||
                logline.contains("WindowOnBackDispatcher") ||
                logline.contains("I/X509Util") ||
                logline.contains("/ziparchive") ||
                logline.contains("/zygote")
    }

    fun readLogFile(file: File): String {
        var content = ""
        var fileInputStream: FileInputStream? = null
        try {
            if (file.exists()) {
                fileInputStream = FileInputStream(file)
                val data = ByteArray(file.length().toInt())
                fileInputStream.read(data)
                content = String(data, StandardCharsets.UTF_8)
            } else {
                Log.e(TAG, "readLogFile: File missing '" + file.toString() + "'")
            }
        } catch (e: IOException) {
            Log.e(TAG, "readLogFile: Failed to read '" + file.toString() + "' #1", e)
        } finally {
            try {
                fileInputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "readLogFile: Failed to read '" + file.toString() + "' #2", e)
            }
        }
        return content
    }

    fun writeLogFile(file: File, logContent: String) {
        var fileOutputStream: FileOutputStream? = null
        try {
            if (!file.exists()) {
                file.createNewFile()
            }
            fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(logContent.toByteArray(StandardCharsets.UTF_8))
            fileOutputStream.flush()
        } catch (e: IOException) {
            Log.w(TAG, "writeLogFile: Failed to write '" + file.toString() + "' #1", e)
        } finally {
            try {
                fileOutputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "writeLogFile: Failed to write '" + file.toString() + "' #2", e)
            }
        }
    }

    fun getAndroidLogFile(context: Context): File = Constants.getAndroidLogFile(context)

    fun getSyncthingLogFile(context: Context): File = Constants.getSyncthingLogFile(context)
}
