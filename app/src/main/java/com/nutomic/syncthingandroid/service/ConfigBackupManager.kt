package com.nutomic.syncthingandroid.service

import android.content.SharedPreferences
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.nutomic.syncthingandroid.service.SyncthingService.State
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/**
 * Owns the configuration backup/restore logic: exporting config, keys, index
 * database and shared preferences into an (optionally encrypted) ZIP archive
 * below the path configured by [Constants.PREF_BACKUP_REL_PATH_TO_ZIP],
 * and importing them back.
 */
class ConfigBackupManager(private val service: SyncthingService,
                          private val preferences: SharedPreferences,
                          private val enableVerboseLog: Boolean) {

    /**
     * Exports the local config and keys to the backup zip file.
     */
    fun exportConfig(): Boolean {
        var failSuccess = true
        Log.d(TAG, "exportConfig BEGIN")

        if (service.currentState != State.DISABLED) {
            // Synchronous shutdown on this background thread: the backup must only read
            // files after the binary has fully exited.
            service.shutdownToStateBlocking(State.DISABLED)
        }

        // Create export dir if non-existant.
        val targetZip = backupZipFile
        targetZip.parentFile?.mkdirs()

        // Export SharedPreferences.
        var sharedPreferencesFile: File? = null
        try {
            val prefsFile = Constants.getSharedPrefsFile(service)
            FileOutputStream(prefsFile).use { fileOutputStream ->
                ObjectOutputStream(fileOutputStream).use { objectOutputStream ->
                    objectOutputStream.writeObject(preferences.all)
                    objectOutputStream.flush()
                }
                fileOutputStream.flush()
            }
            sharedPreferencesFile = prefsFile
        } catch (e: IOException) {
            Log.e(TAG, "exportConfig: Failed to export SharedPreferences #1", e)
            failSuccess = false
        }

        // Make a list of files to backup.
        val includePaths = listOf(
            Constants.getConfigFile(service),

            Constants.getPrivateKeyFile(service),
            Constants.getPublicKeyFile(service),

            Constants.getHttpsCertFile(service),
            Constants.getHttpsKeyFile(service),

            Constants.getSharedPrefsFile(service),

            Constants.getIndexDbFolder(service)
        )

        // If user set one, apply a password and encrypt the zip file.
        val zipEncryptionPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""

        // Compress files to zip file.
        try {
            // Delete existing ZIP file to ensure we create a fresh archive instead of appending
            if (targetZip.exists()) {
                targetZip.delete()
            }

            val parameters = ZipParameters()
            parameters.compressionMethod = CompressionMethod.DEFLATE
            parameters.compressionLevel = CompressionLevel.NORMAL

            val zipFile: ZipFile
            if (zipEncryptionPassword.isEmpty()) {
                zipFile = ZipFile(targetZip)
                parameters.isEncryptFiles = false
            } else {
                zipFile = ZipFile(targetZip, zipEncryptionPassword.toCharArray())
                parameters.isEncryptFiles = true
                parameters.encryptionMethod = EncryptionMethod.AES
                parameters.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }

            // Add files.
            for (includePath in includePaths) {
                if (includePath.exists()) {
                    if (includePath.isFile) {
                        zipFile.addFile(includePath, parameters)
                    } else if (includePath.isDirectory) {
                        zipFile.addFolder(includePath, parameters)
                    }
                }
            }

            if (sharedPreferencesFile != null && sharedPreferencesFile.exists()) {
                sharedPreferencesFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "exportConfig: Failed to export config", e)
            failSuccess = false
        }
        Log.d(TAG, "exportConfig END")

        // Start syncthing after export if run conditions apply.
        restartIfRunConditionsApply()
        return failSuccess
    }

    /**
     * Imports config and keys from the backup zip file.
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    fun importConfig(): Boolean {
        Log.d(TAG, "importConfig PRECHECK")

        // Check if ZIP exists.
        val zipFilePath = backupZipFile
        if (!zipFilePath.exists()) {
            Log.e(TAG, "importConfig: ZIP file is missing. Please check if it is present at '" + zipFilePath.absolutePath + "' as specified in the settings screen.")
            return false
        }

        // Open ZIP file.
        val zipFile: ZipFile
        try {
            // If user set one, get password to decrypt the zip file.
            val zipEncryptionPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""
            if (zipEncryptionPassword.isEmpty()) {
                zipFile = ZipFile(zipFilePath)
            } else {
                val encryptedZipFile = ZipFile(zipFilePath, zipEncryptionPassword.toCharArray())
                if (!encryptedZipFile.isEncrypted) {
                    Log.e(TAG, "importConfig: ZIP file is not encrypted, but password was specified in settings screen. Try to specify an empty password temporarily.")
                    return false
                }
                zipFile = encryptedZipFile
            }

            // Check if ZIP archive contains required files.
            val checkFiles = listOf(
                Constants.CONFIG_FILE,

                Constants.PRIVATE_KEY_FILE,
                Constants.PUBLIC_KEY_FILE
            )
            for (checkFile in checkFiles) {
                if (zipFile.getFileHeader(checkFile) == null) {
                    Log.e(TAG, "importConfig: Required file not found inside zip [$checkFile]")
                    return false
                }
            }

            // Test if supplied encryption password is correct.
            val cacheDir = service.cacheDir.absolutePath
            zipFile.extractFile(Constants.PUBLIC_KEY_FILE, cacheDir)
            File(cacheDir, Constants.PUBLIC_KEY_FILE).delete()
        } catch (e: ZipException) {
            Log.e(TAG, "importConfig: Failed to open zip, " + e.message)
            return false
        }

        // Shutdown SyncthingNative.
        var failSuccess = true
        Log.d(TAG, "importConfig BEGIN")
        val safBridgesWereStarted = service.pauseSafBridgesForConfigImport()
        try {
            if (service.currentState != State.DISABLED) {
                // Synchronous shutdown on this background thread: the backup must only read
                // files after the binary has fully exited.
                service.shutdownToStateBlocking(State.DISABLED)
            }

            // Remove database folder if it exists.
            val databasePath = Constants.getIndexDbFolder(service)
            if (databasePath.exists()) {
                Log.d(TAG, "importConfig: Clearing index database")
                try {
                    FileUtils.deleteDirectoryRecursively(databasePath)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to delete directory '" + databasePath.absolutePath + "'" + e)
                }
            }

            // Decompress zip file.
            try {
                zipFile.extractAll(service.filesDir.absolutePath)
            } catch (e: ZipException) {
                Log.e(TAG, "importConfig: Failed to extract zip, " + e.message)
                failSuccess = false
            }

            // Check if necessary files are present after extraction.
            val checkPaths = listOf(
                Constants.getConfigFile(service),

                Constants.getPrivateKeyFile(service),
                Constants.getPublicKeyFile(service),

                Constants.getHttpsCertFile(service),
                Constants.getHttpsKeyFile(service),

                Constants.getSharedPrefsFile(service)
            )
            for (checkPath in checkPaths) {
                if (!checkPath.exists()) {
                    Log.e(TAG, "importConfig: Missing file after extraction [" + checkPath.name + "]")
                    failSuccess = false
                }
            }

            // Import shared preferences.
            val sharedPreferencesFile = Constants.getSharedPrefsFile(service)
            if (sharedPreferencesFile.exists()) {
                Log.d(TAG, "importConfig: Importing shared preferences")
                failSuccess = failSuccess && importConfigSharedPrefs(sharedPreferencesFile)
                sharedPreferencesFile.delete()
            }
        } finally {
            // The imported mappings and snapshots are now on disk, or the import failed. In
            // either case no pre-import observer/tree may remain active.
            service.resumeSafBridgesAfterConfigImport(safBridgesWereStarted)
        }

        try {
            cleanupImportedFolderDatabases()
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
        }

        // Start syncthing after import if run conditions apply.
        restartIfRunConditionsApply()
        return failSuccess
    }

    /**
     * Get backup zip file.
     * Default: /storage/emulated/0/backups/syncthing/config.zip
     */
    private val backupZipFile: File
        get() {
            var relPathToZip = preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, DEFAULT_BACKUP_REL_PATH) ?: DEFAULT_BACKUP_REL_PATH
            // NOTE: somehow we get empty string from the prefs, which crashes the app, use default when that happens
            // TODO: figure out where the empty string is coming from and fix that
            if (relPathToZip.isEmpty()) {
                relPathToZip = DEFAULT_BACKUP_REL_PATH
            }
            return File(Environment.getExternalStorageDirectory(), relPathToZip)
        }

    private fun restartIfRunConditionsApply() {
        if (service.shouldRunAfterRestart()) {
            val mainLooper = Handler(Looper.getMainLooper())
            mainLooper.post { service.launchStartupTask(SyncthingRunnable.Command.main) }
        }
    }

    private fun cleanupImportedFolderDatabases() {
        val configXml = ConfigXml(service)
        try {
            configXml.loadConfig()
        } catch (e: ConfigXml.OpenConfigException) {
            Log.w(TAG, "importConfig: Unable to parse imported config for DB cleanup")
            return
        }

        val folders = configXml.folders
        if (folders.isEmpty()) {
            return
        }

        for (folder in folders) {
            if (folder.id.isNullOrEmpty()) {
                continue
            }

            val folderPath: File? = folder.path?.takeIf { it.isNotEmpty() }?.let { File(it) }
            val folderPathMissing = folderPath == null || !folderPath.isDirectory

            val markerName = folder.markerName.ifEmpty { Constants.FILENAME_STFOLDER }
            val markerMissing = folderPathMissing ||
                    (folderPath != null && !File(folderPath, markerName).exists())

            if (folderPathMissing || markerMissing) {
                Log.i(TAG, "importConfig: Folder path or marker missing for folder id \"" + folder.id + "\". Resetting Syncthing database.")
                SyncthingRunnable(service, SyncthingRunnable.Command.resetdatabase).run()
                break
            }
        }
    }

    private fun importConfigSharedPrefs(file: File): Boolean {
        var failSuccess = true
        try {
            // Read, deserialize shared preferences.
            ObjectInputStream(FileInputStream(file)).use { objectInputStream ->
                val objectFromInputStream = objectInputStream.readObject()
                val sharedPrefsMap = objectFromInputStream as? Map<*, *>
                if (sharedPrefsMap != null) {

                    // Store backup folder to restore it back later in the process.
                    val relPathToZip = preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, "")
                    val backupPassword = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "")

                    // Prepare a SharedPreferences commit.
                    val editor = preferences.edit()
                    editor.clear()
                    for (entry in sharedPrefsMap.entries) {
                        val prefKey = entry.key as String
                        when (prefKey) {
                            // Preferences that are no longer used and left-overs from previous versions of the app.
                            "first_start",
                            "advanced_folder_picker",
                            "backup_folder_name",
                            "bind_network",
                            "log_to_file",
                            "notification_type",
                            "notify_crashes",
                            "suggest_new_folder_root",
                            "use_legacy_hashing",
                            "pref_current_language",
                            "restartOnWakeup",
                            "wakelock_while_binary_running",
                            "use_root",
                            "important_news_shown_version" -> {
                                logV("importConfig: Ignoring deprecated pref \"$prefKey\".")
                            }
                            // Cached information which is not available on SettingsActivity.
                            Constants.PREF_APP_START_COUNTER,
                            Constants.PREF_BTNSTATE_FORCE_START_STOP,
                            Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
                            Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID,
                            Constants.PREF_LAST_BINARY_VERSION,
                            Constants.PREF_LOCAL_DEVICE_ID,
                            Constants.PREF_LAST_RUN_TIME -> {
                                logV("importConfig: Ignoring cache pref \"$prefKey\".")
                            }
                            else -> {
                                Log.i(TAG, "importConfig: Adding pref \"$prefKey\" to commit ...")

                                // The editor only provides typed setters.
                                val prefValue = entry.value
                                when (prefValue) {
                                    is Boolean -> editor.putBoolean(prefKey, prefValue)
                                    is String -> editor.putString(prefKey, prefValue)
                                    is Int -> editor.putInt(prefKey, prefValue)
                                    is Float -> editor.putFloat(prefKey, prefValue)
                                    is Long -> editor.putLong(prefKey, prefValue)
                                    is Set<*> -> editor.putStringSet(prefKey, asSet(prefValue, String::class.java))
                                    else -> Log.w(TAG, "importConfig: SharedPref type " + prefValue?.javaClass?.name + " is unknown")
                                }
                            }
                        }
                    }
                    editor.putString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, relPathToZip)
                    editor.putString(Constants.PREF_BACKUP_PASSWORD, backupPassword)

                    /**
                     * If all shared preferences have been added to the commit successfully,
                     * apply the commit.
                     */
                    failSuccess = failSuccess && editor.commit()
                } else {
                    Log.e(TAG, "importConfig: Invalid object stream")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e)
            failSuccess = false
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "importConfig: Failed to import SharedPreferences #1", e)
            failSuccess = false
        }
        return failSuccess
    }

    private fun <T> asSet(c: Set<*>?, type: Class<out T>): Set<T>? {
        if (c == null) {
            return null
        }
        val set = HashSet<T>()
        for (o in c) {
            set.add(type.cast(o))
        }
        return set
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "ConfigBackupManager"

        /**
         * Default relative path of the backup zip below the external storage root.
         */
        private const val DEFAULT_BACKUP_REL_PATH = "backups/syncthing/config.zip"
    }
}
