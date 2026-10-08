package com.nutomic.syncthingandroid.util

import android.content.Context
import android.util.Log

import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.service.RestApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides a transparent access to the config if ...
 * a) Syncthing is running and REST API is available.
 * b) Syncthing is NOT running and config.xml is accessed.
 */
class ConfigRouter(context: Context) {

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    private val configXml: ConfigXml = ConfigXml(context)

    @Synchronized
    fun getFolders(restApi: RestApi?): List<Folder> {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            return configXml.folders
        }

        // Syncthing is running and REST API is available.
        return restApi.folders
    }

    @Synchronized
    fun getSharedFolders(deviceID: String): List<Folder> {
        val folders = getFolders(null)
        val sharedFolders = ArrayList<Folder>()

        for (folder in folders) {
            if (folder.getDevice(deviceID) != null) {
                // "device" is sharing "folder".
                sharedFolders.add(folder)
            }
        }

        return sharedFolders
    }

    @Synchronized
    fun addFolder(restApi: RestApi?, folder: Folder) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.addFolder(folder)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.addFolder(folder)       // This will send the config afterwards.
    }

    /** Adds a folder and waits for either the REST acknowledgement or durable config write. */
    suspend fun addFolderAndWait(restApi: RestApi?, folder: Folder) {
        if (restApi != null && restApi.isConfigLoaded) {
            restApi.addFolderAndWait(folder)
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(this@ConfigRouter) {
                configXml.loadConfig()
                configXml.addFolder(folder)
                if (!configXml.saveChanges()) throw IOException("Could not save folder config")
            }
        }
    }

    /** Replaces a folder and waits for durable completion. */
    suspend fun updateFolderAndWait(restApi: RestApi?, folder: Folder) {
        if (restApi != null && restApi.isConfigLoaded) {
            restApi.updateFolderAndWait(folder)
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(this@ConfigRouter) {
                configXml.loadConfig()
                configXml.updateFolder(folder)
                if (!configXml.saveChanges()) throw IOException("Could not save folder config")
            }
        }
    }

    @Synchronized
    fun ignoreFolder(restApi: RestApi?,
                            deviceId: String?,
                            folderId: String?,
                            folderLabel: String?) {
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(TAG, "ignoreFolder failed, Syncthing is not running or REST API is not (yet) available.")
            return
        }

        // The notification deep link always sets these extras; a missing id would have
        // written a null-id garbage entry in the Java version, so bail out instead.
        val deviceId = deviceId ?: return
        val folderId = folderId ?: return

        // Syncthing is running and REST API is available.
        restApi.ignoreFolder(
            deviceId,
            folderId,
            folderLabel
        )       // This will send the config afterwards.
    }

    @Synchronized
    fun updateFolder(restApi: RestApi?, folder: Folder) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.updateFolder(folder)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateFolder(folder)       // This will send the config afterwards.
    }

    @Synchronized
    fun removeFolder(restApi: RestApi?, folderId: String) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.removeFolder(folderId)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.removeFolder(folderId)       // This will send the config afterwards.
    }

    /**
     * Removes a folder only after the live config has been acknowledged/persisted. Callers
     * should perform destructive bridge cleanup only after this method returns successfully.
     */
    suspend fun removeFolderAndWait(restApi: RestApi?, folderId: String) {
        if (restApi != null && restApi.isConfigLoaded) {
            restApi.removeFolderAndWait(folderId)
            return
        }
        withContext(Dispatchers.IO) {
            synchronized(this@ConfigRouter) {
                configXml.loadConfig()
                configXml.removeFolder(folderId)
                if (!configXml.saveChanges()) throw IOException("Could not save folder removal")
            }
        }
    }

    /**
     * Gets ignore list for given folder.
     */
    @Synchronized
    fun getFolderIgnoreList(restApi: RestApi?, folder: Folder, listener: OnResultListener1<FolderIgnoreList>) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.getFolderIgnoreList(folder) { folderIgnoreList -> listener.onResult(folderIgnoreList) }
            return
        }

        // Syncthing is running and REST API is available.
        restApi.getFolderIgnoreList(folder.id) { folderIgnoreList -> listener.onResult(folderIgnoreList) }
    }

    /**
     * Stores ignore list for given folder.
     */
    @Synchronized
    fun postFolderIgnoreList(restApi: RestApi?, folder: Folder, ignore: Array<String>) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.postFolderIgnoreList(folder, ignore)
            return
        }

        // Syncthing is running and REST API is available.
        restApi.postFolderIgnoreList(folder.id, ignore)
    }

    /** Saves ignore rules before a new folder is allowed to scan/sync. */
    suspend fun postFolderIgnoreListAndWait(restApi: RestApi?, folder: Folder, ignore: Array<String>) {
        if (restApi != null && restApi.isConfigLoaded) {
            restApi.postFolderIgnoreListAndWait(folder.id, ignore)
            return
        }
        val saved = withContext(Dispatchers.IO) {
            synchronized(this@ConfigRouter) {
                configXml.postFolderIgnoreList(folder, ignore)
            }
        }
        if (!saved) throw IOException("Could not save folder ignore rules")
    }

    @Synchronized
    fun getDevices(restApi: RestApi?, includeLocal: Boolean): List<Device> {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            return configXml.getDevices(includeLocal)
        }

        // Syncthing is running and REST API is available.
        return restApi.getDevices(includeLocal)
    }

    @Synchronized
    fun updateDevice(restApi: RestApi?, device: Device) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.updateDevice(device)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateDevice(device)       // This will send the config afterwards.
    }

    @Synchronized
    fun removeDevice(restApi: RestApi?, deviceID: String?) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.removeDevice(deviceID ?: return)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.removeDevice(deviceID ?: return)       // This will send the config afterwards.
    }

    @Synchronized
    fun ignoreDevice(restApi: RestApi?,
                            deviceID: String?,
                            deviceName: String?,
                            deviceAddress: String?) {
        if (restApi == null || !restApi.isConfigLoaded) {
            Log.e(TAG, "ignoreDevice failed, Syncthing is not running or REST API is not (yet) available.")
            return
        }

        // The notification deep link always sets this extra; a missing id would have
        // written a null-id garbage entry in the Java version, so bail out instead.
        val deviceID = deviceID ?: return

        // Syncthing is running and REST API is available.
        restApi.ignoreDevice(
            deviceID,
            deviceName,
            deviceAddress
        )       // This will send the config afterwards.
    }

    @Synchronized
    fun getGui(restApi: RestApi?): Gui {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            return configXml.getGui()
        }

        // Syncthing is running and REST API is available.
        return restApi.gui
    }

    @Synchronized
    fun updateGui(restApi: RestApi?, gui: Gui) {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            configXml.updateGui(gui)
            configXml.saveChanges()
            return
        }

        // Syncthing is running and REST API is available.
        restApi.updateGui(gui)       // This will send the config afterwards.
    }

    @Synchronized
    fun getOptions(restApi: RestApi?): Options {
        if (restApi == null || !restApi.isConfigLoaded) {
            // Syncthing is not running or REST API is not (yet) available.
            configXml.loadConfig()
            return configXml.getOptions()
        }

        // Syncthing is running and REST API is available.
        return restApi.options
    }

    companion object {

        private const val TAG = "ConfigRouter"
    }
}
