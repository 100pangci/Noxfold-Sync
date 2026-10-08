package com.nutomic.syncthingandroid.service

import android.content.SharedPreferences
import android.os.Environment
import android.util.Log

import com.nutomic.syncthingandroid.service.SyncthingService.State
import com.nutomic.syncthingandroid.util.ConfigXml

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

import net.lingala.zip4j.ZipFile
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
        Log.d(TAG, "exportConfig BEGIN")
        val targetZip = backupZipFile
        val password = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""
        var success = false
        try {
            if (service.currentState != State.DISABLED) {
                // Read a consistent view after the native process has exited.
                service.shutdownToStateBlocking(State.DISABLED)
            }

            success = ConfigBackupTransactions.writeValidatedAtomically(
                target = targetZip,
                write = { stagedZip ->
                    val workDir = File(service.cacheDir, "config-export-${UUID.randomUUID()}")
                    if (!workDir.mkdirs()) throw IOException("Could not create export staging directory")
                    try {
                        val prefsFile = File(workDir, Constants.SHARED_PREFS_FILE)
                        FileOutputStream(prefsFile).use { fileOutputStream ->
                            ObjectOutputStream(fileOutputStream).use { objectOutputStream ->
                                objectOutputStream.writeObject(preferences.all)
                                objectOutputStream.flush()
                            }
                            fileOutputStream.flush()
                            fileOutputStream.fd.sync()
                        }

                        val includePaths = listOf(
                            Constants.getConfigFile(service),
                            Constants.getPrivateKeyFile(service),
                            Constants.getPublicKeyFile(service),
                            Constants.getHttpsCertFile(service),
                            Constants.getHttpsKeyFile(service),
                        )
                        includePaths.forEach { file ->
                            if (!file.isFile || file.length() == 0L) {
                                throw IOException("Required export file is missing or empty: ${file.name}")
                            }
                        }

                        val zip = if (password.isEmpty()) {
                            ZipFile(stagedZip)
                        } else {
                            ZipFile(stagedZip, password.toCharArray())
                        }
                        val parameters = ZipParameters().apply {
                            compressionMethod = CompressionMethod.DEFLATE
                            compressionLevel = CompressionLevel.NORMAL
                            if (password.isNotEmpty()) {
                                isEncryptFiles = true
                                encryptionMethod = EncryptionMethod.AES
                                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                            } else {
                                isEncryptFiles = false
                            }
                        }
                        includePaths.forEach { zip.addFile(it, parameters) }
                        zip.addFile(prefsFile, parameters)
                        val database = Constants.getIndexDbFolder(service)
                        if (database.isDirectory) zip.addFolder(database, parameters)
                    } finally {
                        workDir.deleteRecursively()
                    }
                },
                validate = { stagedZip ->
                    val zip = if (password.isEmpty()) {
                        ZipFile(stagedZip)
                    } else {
                        ZipFile(stagedZip, password.toCharArray())
                    }
                    REQUIRED_BACKUP_FILES.forEach { name ->
                        if (zip.getFileHeader(name) == null) {
                            throw IOException("Generated backup is missing $name")
                        }
                    }
                    val validationDir = File(service.cacheDir, "config-export-check-${UUID.randomUUID()}")
                    try {
                        zip.extractAll(validationDir.absolutePath)
                        REQUIRED_BACKUP_FILES.forEach { name ->
                            val extracted = File(validationDir, name)
                            if (!extracted.isFile || extracted.length() == 0L) {
                                throw IOException("Generated backup failed validation for $name")
                            }
                        }
                        validateConfigXml(File(validationDir, Constants.CONFIG_FILE))
                    } finally {
                        validationDir.deleteRecursively()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "exportConfig: Failed to export config", e)
            success = false
        } finally {
            // Start Syncthing after export if run conditions apply, on success or failure.
            restartIfRunConditionsApply()
        }
        Log.d(TAG, "exportConfig END")
        return success
    }

    /**
     * Imports config and keys from the backup zip file.
     *
     * @return True if the import was successful, false otherwise (eg if files aren't found).
     */
    fun importConfig(): Boolean {
        Log.d(TAG, "importConfig PRECHECK")
        val zipFilePath = backupZipFile
        if (!zipFilePath.isFile) {
            Log.e(TAG, "importConfig: ZIP file is missing: ${zipFilePath.absolutePath}")
            return false
        }

        val importDir = File(service.filesDir, ".config-import-${UUID.randomUUID()}")
        val extracted = File(importDir, "extracted")
        var bridgesWereStarted: Boolean? = null
        var success = false
        var rollbackComplete = true
        var bridgesReady = true
        var preferenceCommitAttempted = false
        val oldPreferences = snapshotPreferences(preferences.all)
        try {
            if (!extracted.mkdirs()) throw IOException("Could not create import staging directory")
            val password = preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: ""
            val zipFile = openZip(zipFilePath, password)
            validateZipEntryNames(zipFile)
            REQUIRED_BACKUP_FILES.forEach { name ->
                if (zipFile.getFileHeader(name) == null) throw IOException("Required file missing from ZIP: $name")
            }
            zipFile.extractAll(extracted.absolutePath)
            ensureExtractedTreeIsContained(extracted)
            validateExtractedImport(extracted)

            val importedPreferences = readImportedPreferences(
                File(extracted, Constants.SHARED_PREFS_FILE),
                preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, DEFAULT_BACKUP_REL_PATH)
                    ?: DEFAULT_BACKUP_REL_PATH,
                preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: "",
            )

            Log.d(TAG, "importConfig BEGIN")
            bridgesWereStarted = service.pauseSafBridgesForConfigImport()
            if (service.currentState != State.DISABLED) {
                service.shutdownToStateBlocking(State.DISABLED)
            }

            val databaseStaged = File(extracted, INDEX_DB_FOLDER)
                .takeIf { it.isDirectory }
            val replacements = listOf(
                ConfigBackupTransactions.Replacement(File(extracted, Constants.CONFIG_FILE), Constants.getConfigFile(service)),
                ConfigBackupTransactions.Replacement(File(extracted, Constants.PRIVATE_KEY_FILE), Constants.getPrivateKeyFile(service)),
                ConfigBackupTransactions.Replacement(File(extracted, Constants.PUBLIC_KEY_FILE), Constants.getPublicKeyFile(service)),
                ConfigBackupTransactions.Replacement(File(extracted, Constants.HTTPS_CERT_FILE), Constants.getHttpsCertFile(service)),
                ConfigBackupTransactions.Replacement(File(extracted, Constants.HTTPS_KEY_FILE), Constants.getHttpsKeyFile(service)),
                ConfigBackupTransactions.Replacement(databaseStaged, Constants.getIndexDbFolder(service)),
            )
            success = ConfigBackupTransactions.replaceAll(
                replacements = replacements,
                backupDir = File(importDir, "previous"),
                commit = {
                    preferenceCommitAttempted = true
                    if (applyPreferences(importedPreferences)) {
                        true
                    } else {
                        if (!restorePreferences(oldPreferences)) rollbackComplete = false
                        false
                    }
                },
                onRollbackFailure = { rollbackComplete = false },
            )
            if (!success && preferenceCommitAttempted) {
                if (!restorePreferences(oldPreferences)) rollbackComplete = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "importConfig: Staging or applying backup failed", e)
            if (preferenceCommitAttempted && !restorePreferences(oldPreferences)) {
                rollbackComplete = false
            }
        } finally {
            // Resume from the final on-disk/preferences state: committed import on success,
            // restored old configuration after a rolled-back failure.
            if (rollbackComplete) {
                bridgesWereStarted?.let {
                    try {
                        service.resumeSafBridgesAfterConfigImport(it)
                    } catch (e: Exception) {
                        bridgesReady = false
                        Log.e(TAG, "importConfig: Failed to resume bridges from final import state", e)
                    }
                }
            } else {
                Log.e(TAG, "importConfig: Rollback incomplete; leaving bridges/core stopped to protect data")
            }
            val previousFiles = File(importDir, "previous")
            val mustKeepRollbackFiles = previousFiles.exists() &&
                previousFiles.list()?.isNotEmpty() != false
            extracted.deleteRecursively()
            if (mustKeepRollbackFiles) {
                Log.e(TAG, "importConfig: Preserving rollback files in ${previousFiles.absolutePath}")
            } else {
                importDir.deleteRecursively()
            }
            if (success) {
                try {
                    cleanupImportedFolderDatabases()
                } catch (e: Exception) {
                    Log.e(TAG, "importConfig: Failed to cleanup invalid folder databases", e)
                }
            }
            if (rollbackComplete && bridgesReady && bridgesWereStarted != null) {
                try {
                    restartIfRunConditionsApply()
                } catch (e: Exception) {
                    Log.e(TAG, "importConfig: Failed to restart core from final import state", e)
                }
            }
        }
        return success
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
            service.launchStartupTaskOnMainThread(SyncthingRunnable.Command.main)
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
                SyncthingRunnable(service, SyncthingRunnable.Command.resetdatabase)
                    .run(returnStdOut = false)
                break
            }
        }
    }

    private fun openZip(path: File, password: String): ZipFile {
        val zip = if (password.isEmpty()) ZipFile(path) else ZipFile(path, password.toCharArray())
        if (password.isNotEmpty() && !zip.isEncrypted) {
            throw IOException("The ZIP is not encrypted, but a backup password is configured")
        }
        return zip
    }

    private fun validateZipEntryNames(zip: ZipFile) {
        for (header in zip.fileHeaders) {
            val name = header.fileName.replace('\\', '/')
            val segments = name.split('/')
            if (name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name) ||
                segments.any { it == ".." }
            ) {
                throw IOException("Unsafe path in backup archive: ${header.fileName}")
            }
        }
    }

    private fun ensureExtractedTreeIsContained(root: File) {
        val canonicalRoot = root.canonicalFile.toPath()
        root.walkTopDown().forEach { entry ->
            if (!entry.canonicalFile.toPath().startsWith(canonicalRoot)) {
                throw IOException("Extracted path escaped staging directory: ${entry.name}")
            }
        }
    }

    private fun validateExtractedImport(extracted: File) {
        REQUIRED_BACKUP_FILES.forEach { name ->
            val file = File(extracted, name)
            if (!file.isFile || file.length() == 0L) {
                throw IOException("Required backup file is missing or empty: $name")
            }
        }
        val database = File(extracted, INDEX_DB_FOLDER)
        if (database.exists() && !database.isDirectory) {
            throw IOException("Backup index database is not a directory")
        }
        validateConfigXml(File(extracted, Constants.CONFIG_FILE))
        // Deserialize before touching any live file or preference.
        readImportedPreferences(
            File(extracted, Constants.SHARED_PREFS_FILE),
            preferences.getString(Constants.PREF_BACKUP_REL_PATH_TO_ZIP, DEFAULT_BACKUP_REL_PATH)
                ?: DEFAULT_BACKUP_REL_PATH,
            preferences.getString(Constants.PREF_BACKUP_PASSWORD, "") ?: "",
        )
    }

    private fun validateConfigXml(file: File) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val root = factory.newDocumentBuilder().parse(file).documentElement
        if (root.tagName != "configuration") {
            throw IOException("Backup config.xml has an unexpected root element")
        }
    }

    private fun readImportedPreferences(
        file: File,
        backupPath: String,
        backupPassword: String,
    ): Map<String, Any> {
        val raw = ObjectInputStream(FileInputStream(file)).use { it.readObject() as? Map<*, *> }
            ?: throw IOException("Invalid shared preferences backup")
        val result = LinkedHashMap<String, Any>()
        for ((rawKey, value) in raw) {
            val key = rawKey as? String ?: throw IOException("Invalid preference key in backup")
            if (key in DEPRECATED_PREFERENCES || key in CACHED_PREFERENCES) {
                logV("importConfig: Ignoring obsolete/cache pref '$key'.")
                continue
            }
            when (value) {
                is Boolean, is String, is Int, is Float, is Long -> result[key] = value
                is Set<*> -> {
                    val strings = value.map {
                        it as? String ?: throw IOException("Invalid string-set preference '$key'")
                    }.toSet()
                    result[key] = strings
                }
                else -> Log.w(TAG, "importConfig: Ignoring unsupported preference type for '$key'")
            }
        }
        // Keep the destination's backup location and password, not the values from the source.
        result[Constants.PREF_BACKUP_REL_PATH_TO_ZIP] = backupPath
        result[Constants.PREF_BACKUP_PASSWORD] = backupPassword
        return result
    }

    private fun snapshotPreferences(values: Map<String, *>): Map<String, Any> =
        values.mapNotNull { (key, value) ->
            when (value) {
                is Boolean, is String, is Int, is Float, is Long -> key to value
                is Set<*> -> key to value.filterIsInstance<String>().toSet()
                else -> null
            }
        }.toMap()

    private fun applyPreferences(values: Map<String, Any>): Boolean =
        writePreferences(preferences.edit().clear(), values)

    private fun restorePreferences(values: Map<String, Any>): Boolean {
        val restored = writePreferences(preferences.edit().clear(), values)
        if (!restored) {
            Log.e(TAG, "importConfig: Failed to restore pre-import preferences")
        }
        return restored
    }

    private fun writePreferences(
        editor: SharedPreferences.Editor,
        values: Map<String, Any>,
    ): Boolean {
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        return editor.commit()
    }

    private fun logV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "ConfigBackupManager"
        private const val INDEX_DB_FOLDER = "index-v2"

        private val REQUIRED_BACKUP_FILES = listOf(
            Constants.CONFIG_FILE,
            Constants.PRIVATE_KEY_FILE,
            Constants.PUBLIC_KEY_FILE,
            Constants.HTTPS_CERT_FILE,
            Constants.HTTPS_KEY_FILE,
            Constants.SHARED_PREFS_FILE,
        )

        private val DEPRECATED_PREFERENCES = setOf(
            "first_start", "advanced_folder_picker", "backup_folder_name", "bind_network",
            "log_to_file", "notification_type", "notify_crashes", "suggest_new_folder_root",
            "use_legacy_hashing", "pref_current_language", "restartOnWakeup",
            "wakelock_while_binary_running", "use_root", "important_news_shown_version",
        )

        private val CACHED_PREFERENCES = setOf(
            Constants.PREF_APP_START_COUNTER,
            Constants.PREF_BTNSTATE_FORCE_START_STOP,
            Constants.PREF_DEBUG_FACILITIES_AVAILABLE,
            Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID,
            Constants.PREF_LAST_BINARY_VERSION,
            Constants.PREF_LOCAL_DEVICE_ID,
            Constants.PREF_LAST_RUN_TIME,
        )

        /**
         * Default relative path of the backup zip below the external storage root.
         */
        private const val DEFAULT_BACKUP_REL_PATH = "backups/syncthing/config.zip"
    }
}
