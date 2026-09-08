package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import android.text.TextUtils
import android.util.Log

import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingRunnable
import com.nutomic.syncthingandroid.service.buildSyncthingCameraFolder

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import org.mindrot.jbcrypt.BCrypt
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * Provides direct access to the config.xml file in the file system.
 * This class should only be used if the syncthing API is not available (usually during startup).
 */
class ConfigXml(private val context: Context) {

    class OpenConfigException : RuntimeException()

    /**
     * Compares devices by name, uses the device ID as fallback if the name is empty
     */
    private val devicesComparator = Comparator<Device> { lhs, rhs ->
        val lhsName = if (lhs.name.isNotEmpty()) lhs.name else lhs.deviceID
        val rhsName = if (rhs.name.isNotEmpty()) rhs.name else rhs.deviceID
        lhsName.compareTo(rhsName)
    }

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    private val enableVerboseLog: Boolean = AppPrefs.getPrefVerboseLog(context)

    private val configFile: File = Constants.getConfigFile(context)

    private lateinit var config: Document

    fun loadConfig() {
        parseConfig()
        updateIfNeeded()
    }

    /**
     * This should run within an AsyncTask as it can cause a full CPU load
     * for more than 30 seconds on older phone hardware.
     */
    fun generateConfig() {
        // Create new secret keys and config.
        Log.i(TAG, "(Re)Generating keys and config.")
        SyncthingRunnable(context, SyncthingRunnable.Command.generate).run(true)
        parseConfig()
        var changed = false

        // Set local device name.
        Log.i(TAG, "Starting syncthing to retrieve local device id.")
        val localDeviceID = getLocalDeviceIDandStoreToPref()
        if (!TextUtils.isEmpty(localDeviceID)) {
            changed = changeLocalDeviceName(localDeviceID) || changed
        }

        // Change default folder section.
        val elementDefaults = config.documentElement
            .getElementsByTagName("defaults").item(0) as? Element
        if (elementDefaults != null) {
            val elementDefaultFolder = elementDefaults
                .getElementsByTagName("folder").item(0) as? Element
            if (elementDefaultFolder != null) {
                val elementVersioning = elementDefaultFolder.getElementsByTagName("versioning").item(0) as? Element
                if (elementVersioning != null) {
                    elementVersioning.setAttribute("type", "trashcan")
                    val nodeParam = config.createElement("param")
                    elementVersioning.appendChild(nodeParam)
                    val elementParam = nodeParam as Element
                    elementParam.setAttribute("key", "cleanoutDays")
                    elementParam.setAttribute("val", "14")
                    changed = true
                }
            }
        }

        /* Section - GUI */
        val gui = getGuiElement()
            ?: throw OpenConfigException()

        // Set user to "syncthing"
        changed = setConfigElement(gui, "user", "syncthing") || changed

        // Initialiaze password to the API key
        changed = setConfigElement(gui, "password", BCrypt.hashpw(apiKey, BCrypt.gensalt(4))) || changed
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Constants.PREF_WEBUI_PASSWORD, apiKey)
            .apply()

        //  Allow debug and release to run in parallel for testing purposes.
        if (Constants.isDebuggable(context)) {
            // Set alternative gui listen port.
            changed = setConfigElement(gui, "address", Constants.DEBUG_WEBGUI_BIND_ADDRESS) || changed

            // Set alternative data listen port.
            val elementOptions = config.documentElement.getElementsByTagName("options").item(0) as? Element
            if (elementOptions != null) {
                changed = setConfigElement(
                    elementOptions, "listenAddress", arrayOf(
                        Constants.DEBUG_DATA_LISTEN_ADDRESS,
                        "dynamic+https://relays.syncthing.net/endpoint"
                    )
                ) || changed
            }
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    private fun getLocalDeviceIDfromPref(): String {
        var localDeviceID = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.PREF_LOCAL_DEVICE_ID, "")
        if (TextUtils.isEmpty(localDeviceID)) {
            Log.d(TAG, "getLocalDeviceIDfromPref: Local device ID unavailable, trying to retrieve it from syncthing ...")
            try {
                localDeviceID = getLocalDeviceIDandStoreToPref()
            } catch (e: SyncthingRunnable.ExecutableNotFoundException) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Failed to execute syncthing core")
            }
            if (TextUtils.isEmpty(localDeviceID)) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Local device ID unavailable")
            }
        }
        return localDeviceID ?: ""
    }

    private fun getLocalDeviceIDandStoreToPref(): String {
        val logOutput = SyncthingRunnable(context, SyncthingRunnable.Command.deviceid).run(true)
        val localDeviceID = logOutput.replace("\n", "")

        // Verify that local device ID is correctly formatted.
        val localDevice = Device()
        localDevice.deviceID = localDeviceID
        if (!localDevice.checkDeviceID()) {
            Log.w(TAG, "getLocalDeviceIDandStoreToPref: Syncthing core returned a bad formatted device ID \"$localDeviceID\"")
            return ""
        }

        // Store local device ID to pref. This saves us expensive calls to the syncthing binary if we need it later.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Constants.PREF_LOCAL_DEVICE_ID, localDeviceID)
            .apply()
        Log.d(TAG, "getLocalDeviceIDandStoreToPref: Cached local device ID \"$localDeviceID\"")
        return localDeviceID
    }

    /**
     * Makes the core's security files readable/writable for the app again when a root-mode
     * session left them root-owned: syncthing writes config.xml and the key material with
     * explicit 0600 modes (which the launch-time umask cannot influence), and the app's
     * own config IO then fails until ownership is returned. Chowns only this small set of
     * files through the root shell — instant, no recursive walk over the index database.
     * Harmless no-op when the files are already app-owned or su is unavailable.
     */
    private fun ensureCoreFilesReadable(): Boolean {
        val uid = android.os.Process.myUid()
        var ok = true
        for (file in listOf(
            configFile,
            Constants.getPublicKeyFile(context),
            Constants.getPrivateKeyFile(context),
        )) {
            try {
                if (android.system.Os.stat(file.absolutePath).st_uid == uid) {
                    continue
                }
            } catch (e: Exception) {
                continue // missing file: nothing to fix here
            }
            val quoted = "'" + file.absolutePath.replace("'", "'\\''") + "'"
            if (RootAccess.code("chown ${uid}:${uid} $quoted") != 0) {
                ok = false
            }
        }
        return ok
    }

    private fun parseConfig() {
        if (!configFile.canRead() && !ensureCoreFilesReadable()) {
            Log.w(TAG, "Failed to open config file '$configFile'")
            throw OpenConfigException()
        }
        try {
            val inputStream = FileInputStream(configFile)
            val inputStreamReader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
            val inputSource = InputSource(inputStreamReader)
            inputSource.encoding = "UTF-8"
            val dbfactory = DocumentBuilderFactory.newInstance()
            val db = dbfactory.newDocumentBuilder()
            config = db.parse(inputSource)
            inputStream.close()
        } catch (e: SAXException) {
            Log.w(TAG, "Failed to parse config file '$configFile'", e)
            throw OpenConfigException()
        } catch (e: ParserConfigurationException) {
            Log.w(TAG, "Failed to parse config file '$configFile'", e)
            throw OpenConfigException()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to parse config file '$configFile'", e)
            throw OpenConfigException()
        }
    }

    val webGuiUrl: URL
        get() = Util.buildWebGuiUrl(getGuiElement()!!.getElementsByTagName("address").item(0).textContent)

    val webGuiBindPort: Int
        get() {
            return try {
                val gui = Gui()
                gui.address = getGuiElement()!!.getElementsByTagName("address").item(0).textContent
                Integer.parseInt(gui.bindPort)
            } catch (e: Exception) {
                Log.w(TAG, "getWebGuiBindPort: Failed with exception: ", e)
                Constants.DEFAULT_WEBGUI_TCP_PORT
            }
        }

    val apiKey: String
        get() = getGuiElement()!!.getElementsByTagName("apikey").item(0).textContent

    val webUIUsername: String
        get() {
            val userNode = getGuiElement()!!.getElementsByTagName("user").item(0)
            if (userNode != null) {
                val username = userNode.textContent
                return username ?: ""
            }
            return ""
        }

    val webUIPassword: String
        get() = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.PREF_WEBUI_PASSWORD, "") ?: ""

    /**
     * Updates the config file.
     * Sets ignorePerms flag to true on every folder, force enables TLS, sets the
     * username/password.
     */
    private fun updateIfNeeded() {
        var changed = false

        /* Perform one-time migration tasks on syncthing's config file when coming from an older config version. */
        changed = migrateSyncthingOptions() || changed

        /* Get refs to important config objects */
        val folders = config.documentElement.getElementsByTagName("folder")

        /* Section - folders */
        for (i in 0 until folders.length) {
            val r = folders.item(i) as Element
            // Set ignorePerms attribute.
            if (!r.hasAttribute("ignorePerms") ||
                !r.getAttribute("ignorePerms").toBoolean()
            ) {
                Log.i(TAG, "Set 'ignorePerms' on folder " + r.getAttribute("id"))
                r.setAttribute("ignorePerms", true.toString())
                changed = true
            }

            // Set 'hashers' on the given folder.
            changed = setConfigElement(r, "hashers", "1") || changed
        }

        /* Section - GUI */
        val gui = getGuiElement()
            ?: throw OpenConfigException()

        // Platform-specific: Force REST API and Web UI access to use TLS 1.2 or not.
        val forceHttps = Constants.osSupportsTLS12()
        if (!gui.hasAttribute("tls") ||
            gui.getAttribute("tls").toBoolean() != forceHttps
        ) {
            gui.setAttribute("tls", forceHttps.toString())
            changed = true
        }

        /* Section - options */
        val options = config.documentElement
            .getElementsByTagName("options").item(0) as? Element
            ?: throw OpenConfigException()

        /* Dismiss "fsWatcherNotification" according to https://github.com/syncthing/syncthing-android/pull/1051 */
        val childNodes = options.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "unackedNotificationID") {
                val notificationType = getContentOrDefault(node, "")
                when (notificationType) {
                    "authenticationUserAndPassword", "crAutoEnabled", "crAutoDisabled", "fsWatcherNotification" -> {
                        Log.i(TAG, "Remove found unackedNotificationID '$notificationType'.")
                        options.removeChild(node)
                        changed = true
                    }
                }
            }
        }

        // Disable "startBrowser" because it applies to desktop environments and cannot start a mobile browser app.
        val defaultOptions = Options()
        changed = setConfigElement(options, "startBrowser", defaultOptions.startBrowser) || changed

        /**
         * Disable Syncthing's NAT feature because it causes kernel oops on some buggy kernels.
         */
        if (Constants.osHasKernelBugIssue505()) {
            val natEnabledChanged = setConfigElement(options, "natEnabled", false)
            if (natEnabledChanged) {
                Log.d(TAG, "Disabling NAT option because a buggy kernel was detected.")
                changed = true
            }
        }

        // Add the "Syncthing Camera" folder if the user consented to use the feature.
        val prefEnableSyncthingCamera = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        if (prefEnableSyncthingCamera) {
            changed = addSyncthingCameraFolder() || changed
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    /**
     * Updates syncthing options to a version specific target setting in the config file.
     * Used for one-time config migration from a lower syncthing version to the current version.
     * Enables filesystem watcher.
     * Returns if changes to the config have been made.
     */
    private fun migrateSyncthingOptions(): Boolean {
        val defaultFolder = Folder()

        /* Read existing config version */
        var configVersion = getAttributeOrDefault(config.documentElement, "version", 0)
        val oldConfigVersion = configVersion

        /* Check if we have to do manual migration from version X to Y */
        if (configVersion == 27) {
            /* fsWatcher transition */
            Log.i(TAG, "Migrating config version $configVersion to 28 ...")

            /* Enable fsWatcher for all folders */
            val folders = config.documentElement.getElementsByTagName("folder")
            for (i in 0 until folders.length) {
                val r = folders.item(i) as Element

                // Enable "fsWatcherEnabled" attribute and set default delay.
                Log.i(TAG, "Set 'fsWatcherEnabled', 'fsWatcherDelayS' on folder " + r.getAttribute("id"))
                r.setAttribute("fsWatcherEnabled", defaultFolder.fsWatcherEnabled.toString())
                r.setAttribute("fsWatcherDelayS", defaultFolder.fsWatcherDelayS.toString())
            }

            /**
             * Set config version to 28 after manual config migration
             * This prevents "unackedNotificationID" getting populated
             * with the fsWatcher GUI notification.
             */
            configVersion = 28
        }

        if (configVersion == oldConfigVersion) {
            return false
        }
        config.documentElement.setAttribute("version", configVersion.toString())
        Log.i(TAG, "Old config version was $oldConfigVersion, new config version is $configVersion")
        return true
    }

    private fun getAttributeOrDefault(element: Element, attribute: String, defaultValue: Boolean): Boolean {
        return if (element.hasAttribute(attribute)) element.getAttribute(attribute).toBoolean() else defaultValue
    }

    private fun getAttributeOrDefault(element: Element, attribute: String, defaultValue: Int): Int {
        return try {
            if (element.hasAttribute(attribute)) Integer.parseInt(element.getAttribute(attribute)) else defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getAttributeOrDefault(element: Element, attribute: String, defaultValue: Float): Float {
        return try {
            if (element.hasAttribute(attribute)) element.getAttribute(attribute).toFloat() else defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getAttributeOrDefault(element: Element, attribute: String, defaultValue: String): String {
        return if (element.hasAttribute(attribute)) element.getAttribute(attribute) else defaultValue
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Boolean): Boolean {
        return if (node == null) defaultValue else node.textContent.toBoolean()
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Int): Int {
        return try {
            if (node == null) defaultValue else Integer.parseInt(node.textContent)
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getContentOrDefault(node: Node?, defaultValue: Float): Float {
        return try {
            if (node == null) defaultValue else node.textContent.toFloat()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    private fun getContentOrDefault(node: Node?, defaultValue: String): String {
        return node?.textContent ?: defaultValue
    }

    val folders: List<Folder>
        get() {
            val localDeviceID = getLocalDeviceIDfromPref()
            val folders = ArrayList<Folder>()

            // Prevent enumerating "<folder>" tags below "<default>" nodes by enumerating child nodes manually.
            val childNodes = config.documentElement.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeName != "folder") {
                    continue
                }
                val r = node as Element
                val folder = Folder()
                folder.id = getAttributeOrDefault(r, "id", "")
                folder.group = getAttributeOrDefault(r, "group", folder.group)
                folder.label = getAttributeOrDefault(r, "label", folder.label)

                folder.path = getAttributeOrDefault(r, "path", "")
                if (folder.path.startsWith("~/")) {
                    folder.path = folder.path.replaceFirst("^~".toRegex(), FileUtils.getSyncthingTildeAbsolutePath())
                }

                folder.type = getAttributeOrDefault(r, "type", Constants.FOLDER_TYPE_SEND_RECEIVE)
                folder.autoNormalize = getAttributeOrDefault(r, "autoNormalize", folder.autoNormalize)
                folder.fsWatcherDelayS = getAttributeOrDefault(r, "fsWatcherDelayS", folder.fsWatcherDelayS)
                folder.fsWatcherEnabled = getAttributeOrDefault(r, "fsWatcherEnabled", folder.fsWatcherEnabled)
                folder.ignorePerms = getAttributeOrDefault(r, "ignorePerms", folder.ignorePerms)
                folder.rescanIntervalS = getAttributeOrDefault(r, "rescanIntervalS", folder.rescanIntervalS)

                folder.copiers = getContentOrDefault(r.getElementsByTagName("copiers").item(0), folder.copiers)
                folder.hashers = getContentOrDefault(r.getElementsByTagName("hashers").item(0), folder.hashers)
                folder.order = getContentOrDefault(r.getElementsByTagName("order").item(0), folder.order)
                folder.paused = getContentOrDefault(r.getElementsByTagName("paused").item(0), folder.paused)
                folder.ignoreDelete = getContentOrDefault(r.getElementsByTagName("ignoreDelete").item(0), folder.ignoreDelete)
                folder.copyOwnershipFromParent = getContentOrDefault(r.getElementsByTagName("copyOwnershipFromParent").item(0), folder.copyOwnershipFromParent)
                folder.modTimeWindowS = getContentOrDefault(r.getElementsByTagName("modTimeWindowS").item(0), folder.modTimeWindowS)
                folder.blockPullOrder = getContentOrDefault(r.getElementsByTagName("blockPullOrder").item(0), folder.blockPullOrder)
                folder.disableFsync = getContentOrDefault(r.getElementsByTagName("disableFsync").item(0), folder.disableFsync)
                folder.maxConcurrentWrites = getContentOrDefault(r.getElementsByTagName("maxConcurrentWrites").item(0), folder.maxConcurrentWrites)
                folder.maxConflicts = getContentOrDefault(r.getElementsByTagName("maxConflicts").item(0), folder.maxConflicts)
                folder.copyRangeMethod = getContentOrDefault(r.getElementsByTagName("copyRangeMethod").item(0), folder.copyRangeMethod)
                folder.caseSensitiveFS = getContentOrDefault(r.getElementsByTagName("caseSensitiveFS").item(0), folder.caseSensitiveFS)
                folder.syncOwnership = getContentOrDefault(r.getElementsByTagName("syncOwnership").item(0), folder.syncOwnership)
                folder.sendOwnership = getContentOrDefault(r.getElementsByTagName("sendOwnership").item(0), folder.sendOwnership)
                folder.syncXattrs = getContentOrDefault(r.getElementsByTagName("syncXattrs").item(0), folder.syncXattrs)
                folder.sendXattrs = getContentOrDefault(r.getElementsByTagName("sendXattrs").item(0), folder.sendXattrs)
                folder.blockIndexing = getContentOrDefault(r.getElementsByTagName("blockIndexing").item(0), folder.blockIndexing)
                folder.filesystemType = getContentOrDefault(r.getElementsByTagName("filesystemType").item(0), folder.filesystemType)

                // Devices
                /*
                <device id="[DEVICE_ID]" introducedBy=""/>
                */
                val nodeDevices = r.getElementsByTagName("device")
                for (j in 0 until nodeDevices.length) {
                    val elementDevice = nodeDevices.item(j) as Element
                    val device = SharedWithDevice()
                    device.deviceID = getAttributeOrDefault(elementDevice, "id", "")

                    // Exclude self.
                    if (!TextUtils.isEmpty(device.deviceID) && device.deviceID != localDeviceID) {
                        device.introducedBy = getAttributeOrDefault(elementDevice, "introducedBy", device.introducedBy)
                        device.encryptionPassword = getContentOrDefault(elementDevice.getElementsByTagName("encryptionPassword").item(0), device.encryptionPassword)
                        folder.addDevice(device)
                    }
                }

                // MinDiskFree
                /*
                <minDiskFree unit="MB">5</minDiskFree>
                */
                val minDiskFree = Folder.MinDiskFree()
                val elementMinDiskFree = r.getElementsByTagName("minDiskFree").item(0) as? Element
                if (elementMinDiskFree != null) {
                    minDiskFree.unit = getAttributeOrDefault(elementMinDiskFree, "unit", minDiskFree.unit)
                    minDiskFree.value = getContentOrDefault(elementMinDiskFree, minDiskFree.value)
                }
                folder.minDiskFree = minDiskFree

                // Versioning
                /*
                <versioning></versioning>
                <versioning type="trashcan">
                    <param key="cleanoutDays" val="90"></param>
                    <cleanupIntervalS>3600</cleanupIntervalS>
                    <fsPath></fsPath>
                    <fsType>basic</fsType>
                </versioning>
                */
                val versioning = Folder.Versioning()
                val elementVersioning = r.getElementsByTagName("versioning").item(0) as? Element
                if (elementVersioning != null) {
                    versioning.type = getAttributeOrDefault(elementVersioning, "type", "")
                    versioning.cleanupIntervalS = getContentOrDefault(elementVersioning.getElementsByTagName("cleanupIntervalS").item(0), 3600)
                    versioning.fsPath = getContentOrDefault(elementVersioning.getElementsByTagName("fsPath").item(0), "")
                    versioning.fsType = getContentOrDefault(elementVersioning.getElementsByTagName("fsType").item(0), "basic")
                    val nodeVersioningParam = elementVersioning.getElementsByTagName("param")
                    for (j in 0 until nodeVersioningParam.length) {
                        val elementVersioningParam = nodeVersioningParam.item(j) as Element
                        versioning.params[getAttributeOrDefault(elementVersioningParam, "key", "")] =
                            getAttributeOrDefault(elementVersioningParam, "val", "")
                    }
                }
                folder.versioning = versioning

                folders.add(folder)
            }
            folders.sortWith(Folder.LABEL_COMPARATOR)
            return folders
        }

    fun addFolder(folder: Folder) {
        Log.d(TAG, "addFolder: folder.id=" + folder.id)
        // Replace an existing folder with the same id instead of adding a
        // duplicate (e.g. when the user saves twice while Syncthing is not
        // running and the config.xml fallback applies).
        removeFolder(folder.id)
        val nodeConfig = config.documentElement
        val nodeFolder = config.createElement("folder")
        nodeConfig.appendChild(nodeFolder)
        val elementFolder = nodeFolder as Element
        elementFolder.setAttribute("id", folder.id)
        updateFolder(folder)
    }

    fun updateFolder(folder: Folder) {
        val localDeviceID = getLocalDeviceIDfromPref()
        val nodeFolders = config.documentElement.getElementsByTagName("folder")
        for (i in 0 until nodeFolders.length) {
            val r = nodeFolders.item(i) as Element
            if (folder.id == getAttributeOrDefault(r, "id", "")) {
                // Found folder node to update.
                r.setAttribute("group", folder.group)
                r.setAttribute("label", folder.label)
                r.setAttribute("path", folder.path)
                r.setAttribute("type", folder.type)
                r.setAttribute("autoNormalize", folder.autoNormalize.toString())
                r.setAttribute("fsWatcherDelayS", folder.fsWatcherDelayS.toString())
                r.setAttribute("fsWatcherEnabled", folder.fsWatcherEnabled.toString())
                r.setAttribute("ignorePerms", folder.ignorePerms.toString())
                r.setAttribute("rescanIntervalS", folder.rescanIntervalS.toString())

                setConfigElement(r, "copiers", folder.copiers.toString())
                setConfigElement(r, "hashers", folder.hashers.toString())
                setConfigElement(r, "order", folder.order)
                setConfigElement(r, "paused", folder.paused)
                setConfigElement(r, "ignoreDelete", folder.ignoreDelete)
                setConfigElement(r, "copyOwnershipFromParent", folder.copyOwnershipFromParent)
                setConfigElement(r, "modTimeWindowS", folder.modTimeWindowS.toString())
                setConfigElement(r, "blockPullOrder", folder.blockPullOrder)
                setConfigElement(r, "disableFsync", folder.disableFsync)
                setConfigElement(r, "maxConcurrentWrites", folder.maxConcurrentWrites.toString())
                setConfigElement(r, "maxConflicts", folder.maxConflicts.toString())
                setConfigElement(r, "copyRangeMethod", folder.copyRangeMethod)
                setConfigElement(r, "caseSensitiveFS", folder.caseSensitiveFS)
                setConfigElement(r, "syncOwnership", folder.syncOwnership)
                setConfigElement(r, "sendOwnership", folder.sendOwnership)
                setConfigElement(r, "syncXattrs", folder.syncXattrs)
                setConfigElement(r, "sendXattrs", folder.sendXattrs)
                setConfigElement(r, "blockIndexing", folder.blockIndexing)
                setConfigElement(r, "filesystemType", folder.filesystemType)

                // Update devices that share this folder.
                // Pass 1: Remove all devices below that folder in XML except the local device.
                val nodeDevices = r.getElementsByTagName("device")
                for (j in nodeDevices.length - 1 downTo 0) {
                    val elementDevice = nodeDevices.item(j) as Element
                    if (getAttributeOrDefault(elementDevice, "id", "") != localDeviceID) {
                        Log.d(TAG, "updateFolder: nodeDevices: Removing deviceID=" + getAttributeOrDefault(elementDevice, "id", ""))
                        removeChildElementFromTextNode(r, elementDevice)
                    }
                }

                // Pass 2: Add devices below that folder from the POJO model.
                val devices = folder.getSharedWithDevices()
                for (device in devices) {
                    Log.d(TAG, "updateFolder: nodeDevices: Adding deviceID=" + device.deviceID)
                    val nodeDevice = config.createElement("device")
                    r.appendChild(nodeDevice)
                    val elementDevice = nodeDevice as Element
                    elementDevice.setAttribute("id", device.deviceID)
                    elementDevice.setAttribute("introducedBy", device.introducedBy)
                    setConfigElement(elementDevice, "encryptionPassword", device.encryptionPassword)
                }

                // minDiskFree
                folder.minDiskFree?.let { minDiskFree ->
                    // Pass 1: Remove all minDiskFree nodes from XML (usually one)
                    r.getElementsByTagName("minDiskFree").item(0)?.let { nodeMinDiskFree ->
                        Log.d(TAG, "updateFolder: nodeMinDiskFree: Removing minDiskFree node")
                        removeChildElementFromTextNode(r, nodeMinDiskFree as Element)
                    }

                    // Pass 2: Add minDiskFree node from the POJO model to XML.
                    val nodeNewMinDiskFree = config.createElement("minDiskFree")
                    r.appendChild(nodeNewMinDiskFree)
                    val elementNewMinDiskFree = nodeNewMinDiskFree as Element
                    elementNewMinDiskFree.setAttribute("unit", minDiskFree.unit)
                    setConfigElement(r, "minDiskFree", minDiskFree.value.toString())
                }

                // Versioning
                // Pass 1: Remove all versioning nodes from XML (usually one)
                r.getElementsByTagName("versioning").item(0)?.let { nodeVersioning ->
                    Log.d(TAG, "updateFolder: nodeVersioning: Removing versioning node")
                    removeChildElementFromTextNode(r, nodeVersioning as Element)
                }

                // Pass 2: Add versioning node from the POJO model to XML.
                val nodeNewVersioning = config.createElement("versioning")
                r.appendChild(nodeNewVersioning)
                val elementNewVersioning = nodeNewVersioning as Element
                val versioning = folder.versioning
                val versioningType = versioning?.type
                if (!versioningType.isNullOrEmpty()) {
                    elementNewVersioning.setAttribute("type", versioningType)
                    setConfigElement(elementNewVersioning, "cleanupIntervalS", versioning!!.cleanupIntervalS.toString())
                    setConfigElement(elementNewVersioning, "fsPath", versioning.fsPath)
                    setConfigElement(elementNewVersioning, "fsType", versioning.fsType)
                    for ((key, value) in versioning.params) {
                        Log.d(TAG, "updateFolder: nodeVersioning: Adding param key=$key, val=$value")
                        val nodeParam = config.createElement("param")
                        elementNewVersioning.appendChild(nodeParam)
                        val elementParam = nodeParam as Element
                        elementParam.setAttribute("key", key)
                        elementParam.setAttribute("val", value)
                    }
                }

                break
            }
        }
    }

    fun removeFolder(folderId: String) {
        val nodeFolders = config.documentElement.getElementsByTagName("folder")
        for (i in nodeFolders.length - 1 downTo 0) {
            val r = nodeFolders.item(i) as Element
            if (folderId == getAttributeOrDefault(r, "id", "")) {
                // Found folder node to remove. Remove every node with this id,
                // stale duplicates may exist from earlier double saves.
                Log.d(TAG, "removeFolder: Removing folder node, folderId=$folderId")
                removeChildElementFromTextNode(r.parentNode as Element, r)
            }
        }
    }

    fun setFolderPause(folderId: String, paused: Boolean) {
        val nodeFolders = config.documentElement.getElementsByTagName("folder")
        for (i in 0 until nodeFolders.length) {
            val r = nodeFolders.item(i) as Element
            if (getAttributeOrDefault(r, "id", "") == folderId) {
                setConfigElement(r, "paused", paused)
                break
            }
        }
    }

    /**
     * Gets ignore list for given folder.
     */
    fun getFolderIgnoreList(folder: Folder, listener: OnResultListener1<FolderIgnoreList>) {
        val folderIgnoreList = FolderIgnoreList()
        try {
            val file = File(folder.path, Constants.FILENAME_STIGNORE)
            if (file.exists()) {
                FileInputStream(file).use { fileInputStream ->
                    val data = ByteArray(file.length().toInt())
                    fileInputStream.read(data)
                    folderIgnoreList.ignore = String(data, StandardCharsets.UTF_8)
                        .split("\n".toRegex()).toTypedArray()
                }
            } else {
                // File not found.
                Log.w(TAG, "getFolderIgnoreList: File missing $file")
                /**
                 * Don't fail as the file might be expectedly missing when users didn't
                 * set ignores in the past storyline of that folder.
                 */
            }
        } catch (e: IOException) {
            Log.e(TAG, "getFolderIgnoreList: Failed to read '" + folder.path + "/" + Constants.FILENAME_STIGNORE + "' #1", e)
        }
        listener.onResult(folderIgnoreList)
    }

    /**
     * Stores ignore list for given folder.
     */
    fun postFolderIgnoreList(folder: Folder, ignore: Array<String>) {
        try {
            val file = File(folder.path, Constants.FILENAME_STIGNORE)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(file).use { fileOutputStream ->
                fileOutputStream.write(TextUtils.join("\n", ignore).toByteArray(StandardCharsets.UTF_8))
                fileOutputStream.flush()
            }
        } catch (e: IOException) {
            /**
             * This will happen on external storage folders which exist outside the
             * "/Android/data/[package_name]/files" folder on Android 5+.
             */
            Log.w(TAG, "postFolderIgnoreList: Failed to write '" + folder.path + "/" + Constants.FILENAME_STIGNORE + "' #1", e)
        }
    }

    fun getDevices(includeLocal: Boolean): List<Device> {
        val localDeviceID = getLocalDeviceIDfromPref()
        val devices = ArrayList<Device>()

        // Prevent enumerating "<device>" tags below "<defaults>", "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName != "device") {
                continue
            }
            val r = node as Element
            val device = Device()
            device.compression = getAttributeOrDefault(r, "compression", device.compression)
            device.deviceID = getAttributeOrDefault(r, "id", "")
            device.group = getAttributeOrDefault(r, "group", device.group)
            device.introducedBy = getAttributeOrDefault(r, "introducedBy", device.introducedBy)
            device.introducer = getAttributeOrDefault(r, "introducer", device.introducer)
            device.name = getAttributeOrDefault(r, "name", device.name)
            device.autoAcceptFolders = getContentOrDefault(r.getElementsByTagName("autoAcceptFolders").item(0), device.autoAcceptFolders)
            device.maxRecvKbps = getContentOrDefault(r.getElementsByTagName("maxRecvKbps").item(0), device.maxRecvKbps)
            device.maxSendKbps = getContentOrDefault(r.getElementsByTagName("maxSendKbps").item(0), device.maxSendKbps)
            device.paused = getContentOrDefault(r.getElementsByTagName("paused").item(0), device.paused)
            device.untrusted = getContentOrDefault(r.getElementsByTagName("untrusted").item(0), device.untrusted)
            device.numConnections = getContentOrDefault(r.getElementsByTagName("numConnections").item(0), device.numConnections)

            // Addresses
            /*
            <device ...>
                <address>dynamic</address>
                <address>tcp4://192.168.1.67:2222</address>
            </device>
            */
            val addresses = ArrayList<String>()
            val nodeAddresses = r.getElementsByTagName("address")
            for (j in 0 until nodeAddresses.length) {
                addresses.add(getContentOrDefault(nodeAddresses.item(j), ""))
            }
            device.addresses = addresses

            // Allowed Networks
            val allowedNetworks = ArrayList<String>()
            val nodeAllowedNetworks = r.getElementsByTagName("allowedNetwork")
            for (j in 0 until nodeAllowedNetworks.length) {
                allowedNetworks.add(getContentOrDefault(nodeAllowedNetworks.item(j), ""))
            }
            device.allowedNetworks = allowedNetworks

            // ignoredFolders
            val ignoredFolders = ArrayList<IgnoredFolder>()
            val nodeIgnoredFolders = r.getElementsByTagName("ignoredFolder")
            for (j in 0 until nodeIgnoredFolders.length) {
                val elementIgnoredFolder = nodeIgnoredFolders.item(j) as Element
                val ignoredFolder = IgnoredFolder()
                ignoredFolder.id = getAttributeOrDefault(elementIgnoredFolder, "id", ignoredFolder.id)
                ignoredFolder.label = getAttributeOrDefault(elementIgnoredFolder, "label", ignoredFolder.label)
                ignoredFolder.time = getAttributeOrDefault(elementIgnoredFolder, "time", ignoredFolder.time)
                ignoredFolders.add(ignoredFolder)
            }
            device.ignoredFolders = ignoredFolders

            // Exclude self if requested.
            val isLocalDevice = !TextUtils.isEmpty(device.deviceID) && device.deviceID == localDeviceID
            if (includeLocal || !isLocalDevice) {
                devices.add(device)
            }
        }
        devices.sortWith(devicesComparator)
        return devices
    }

    /**
     * Adds or updates a device identified by its device ID.
     */
    fun updateDevice(device: Device) {
        var deviceExists = false

        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        var childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "device") {
                val r = node as Element
                if (device.deviceID == getAttributeOrDefault(r, "id", "")) {
                    deviceExists = true
                    break
                }
            }
        }

        // If the device does not exist in config, add it.
        if (!deviceExists) {
            Log.d(TAG, "updateDevice: [addDevice] Adding deviceID='" + device.deviceID + "' to config ...")
            val nodeConfig = config.documentElement
            val nodeDevice = config.createElement("device")
            nodeConfig.appendChild(nodeDevice)
            val elementDevice = nodeDevice as Element
            elementDevice.setAttribute("id", device.deviceID)
        }

        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "device") {
                val r = node as Element
                if (device.deviceID == getAttributeOrDefault(r, "id", "")) {
                    // Found device to update.
                    r.setAttribute("compression", device.compression)
                    r.setAttribute("group", device.group)
                    r.setAttribute("introducedBy", device.introducedBy)
                    r.setAttribute("introducer", device.introducer.toString())
                    r.setAttribute("name", device.name)

                    setConfigElement(r, "autoAcceptFolders", device.autoAcceptFolders)
                    setConfigElement(r, "paused", device.paused)
                    setConfigElement(r, "untrusted", device.untrusted)
                    setConfigElement(r, "numConnections", device.numConnections.toString())
                    updateDeviceAddresses(r, device)
                    updateDeviceAllowedNetworks(r, device)
                    break
                }
            }
        }
    }

    private fun updateDeviceAddresses(r: Element, device: Device) {
        // Addresses
        // Pass 1: Remove all addresses in XML.
        val nodeAddresses = r.getElementsByTagName("address")
        for (j in nodeAddresses.length - 1 downTo 0) {
            val elementAddress = nodeAddresses.item(j) as Element
            Log.d(TAG, "updateDevice: nodeAddresses: Removing address=" + getContentOrDefault(elementAddress, ""))
            removeChildElementFromTextNode(r, elementAddress)
        }

        // Pass 2: Add addresses from the POJO model.
        device.addresses?.let { addresses ->
            for (address in addresses) {
                Log.d(TAG, "updateDevice: nodeAddresses: Adding address=$address")
                val nodeAddress = config.createElement("address")
                r.appendChild(nodeAddress)
                val elementAddress = nodeAddress as Element
                elementAddress.textContent = address
            }
        }
    }

    private fun updateDeviceAllowedNetworks(r: Element, device: Device) {
        // Allowed Networks
        // Pass 1: Remove all allowed networks in XML.
        val nodeAllowedNetworks = r.getElementsByTagName("allowedNetwork")
        for (j in nodeAllowedNetworks.length - 1 downTo 0) {
            val elementAllowedNetwork = nodeAllowedNetworks.item(j) as Element
            Log.d(TAG, "updateDevice: nodeAllowedNetworks: Removing allowedNetwork=" + getContentOrDefault(elementAllowedNetwork, ""))
            removeChildElementFromTextNode(r, elementAllowedNetwork)
        }

        // Pass 2: Add allowed networks from the POJO model.
        device.allowedNetworks?.let { allowedNetworks ->
            for (allowedNetwork in allowedNetworks) {
                Log.d(TAG, "updateDevice: nodeAllowedNetworks: Adding allowedNetwork=$allowedNetwork")
                val nodeAllowedNetwork = config.createElement("allowedNetwork")
                r.appendChild(nodeAllowedNetwork)
                val elementAllowedNetwork = nodeAllowedNetwork as Element
                elementAllowedNetwork.textContent = allowedNetwork
            }
        }
    }

    fun removeDevice(deviceID: String) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "device") {
                val r = node as Element
                if (deviceID == getAttributeOrDefault(r, "id", "")) {
                    // Found device to remove.
                    Log.d(TAG, "removeDevice: Removing device node, deviceID=$deviceID")
                    removeChildElementFromTextNode(r.parentNode as Element, r)
                    break
                }
            }
        }
    }

    fun getGui(): Gui {
        val elementGui = config.documentElement.getElementsByTagName("gui").item(0) as? Element
        val gui = Gui()
        if (elementGui == null) {
            Log.e(TAG, "getGui: elementGui == null. Returning defaults.")
            return gui
        }

        gui.enabled = getAttributeOrDefault(elementGui, "enabled", gui.enabled)
        gui.useTLS = getAttributeOrDefault(elementGui, "tls", gui.useTLS)

        // Equals getContentOrDefault(node, gui.address/gui.user) with a nullable default.
        gui.address = elementGui.getElementsByTagName("address").item(0)?.textContent ?: gui.address
        gui.user = elementGui.getElementsByTagName("user").item(0)?.textContent ?: gui.user
        gui.password = getContentOrDefault(elementGui.getElementsByTagName("password").item(0), "")
        gui.apiKey = getContentOrDefault(elementGui.getElementsByTagName("apikey").item(0), "")
        gui.theme = getContentOrDefault(elementGui.getElementsByTagName("theme").item(0), gui.theme)
        gui.insecureAdminAccess = getContentOrDefault(elementGui.getElementsByTagName("insecureAdminAccess").item(0), gui.insecureAdminAccess)
        gui.insecureAllowFrameLoading = getContentOrDefault(elementGui.getElementsByTagName("insecureAllowFrameLoading").item(0), gui.insecureAllowFrameLoading)
        gui.insecureSkipHostCheck = getContentOrDefault(elementGui.getElementsByTagName("insecureSkipHostCheck").item(0), gui.insecureSkipHostCheck)
        return gui
    }

    fun updateGui(gui: Gui) {
        val elementGui = config.documentElement.getElementsByTagName("gui").item(0) as? Element
        if (elementGui == null) {
            Log.e(TAG, "updateGui: elementGui == null")
            return
        }

        elementGui.setAttribute("enabled", gui.enabled.toString())
        elementGui.setAttribute("tls", gui.useTLS.toString())

        setConfigElement(elementGui, "address", gui.address)
        setConfigElement(elementGui, "user", gui.user)
        setConfigElement(elementGui, "password", gui.password)
        setConfigElement(elementGui, "apikey", gui.apiKey)
        setConfigElement(elementGui, "theme", gui.theme)
        setConfigElement(elementGui, "insecureAdminAccess", gui.insecureAdminAccess)
        setConfigElement(elementGui, "insecureAllowFrameLoading", gui.insecureAllowFrameLoading)
        setConfigElement(elementGui, "insecureSkipHostCheck", gui.insecureSkipHostCheck)
    }

    fun getOptions(): Options {
        val elementOptions = config.documentElement.getElementsByTagName("options").item(0) as? Element
        val options = Options()
        if (elementOptions == null) {
            Log.e(TAG, "getOptions: elementOptions == null. Returning defaults.")
            return options
        }

        // options.listenAddresses
        val listenAddressNodes = elementOptions.getElementsByTagName("listenAddress")
        val listenAddressesList = ArrayList<String>()
        for (i in 0 until listenAddressNodes.length) {
            val addressNode = listenAddressNodes.item(i)
            val addressText = addressNode.textContent.trim()
            if (addressText.isNotEmpty()) {
                listenAddressesList.add(addressText)
            }
        }
        options.listenAddresses = listenAddressesList.toTypedArray()

        // options.globalAnnounceServers
        options.globalAnnounceEnabled = getContentOrDefault(elementOptions.getElementsByTagName("globalAnnounceEnabled").item(0), options.globalAnnounceEnabled)
        options.localAnnounceEnabled = getContentOrDefault(elementOptions.getElementsByTagName("localAnnounceEnabled").item(0), options.localAnnounceEnabled)
        options.localAnnouncePort = getContentOrDefault(elementOptions.getElementsByTagName("localAnnouncePort").item(0), options.localAnnouncePort)
        options.localAnnounceMCAddr = getContentOrDefault(elementOptions.getElementsByTagName("localAnnounceMCAddr").item(0), "")
        options.maxSendKbps = getContentOrDefault(elementOptions.getElementsByTagName("maxSendKbps").item(0), options.maxSendKbps)
        options.maxRecvKbps = getContentOrDefault(elementOptions.getElementsByTagName("maxRecvKbps").item(0), options.maxRecvKbps)
        options.reconnectionIntervalS = getContentOrDefault(elementOptions.getElementsByTagName("reconnectionIntervalS").item(0), options.reconnectionIntervalS)
        options.relaysEnabled = getContentOrDefault(elementOptions.getElementsByTagName("relaysEnabled").item(0), options.relaysEnabled)
        options.relayReconnectIntervalM = getContentOrDefault(elementOptions.getElementsByTagName("relayReconnectIntervalM").item(0), options.relayReconnectIntervalM)
        options.startBrowser = getContentOrDefault(elementOptions.getElementsByTagName("startBrowser").item(0), options.startBrowser)
        options.natEnabled = getContentOrDefault(elementOptions.getElementsByTagName("natEnabled").item(0), options.natEnabled)
        options.natLeaseMinutes = getContentOrDefault(elementOptions.getElementsByTagName("natLeaseMinutes").item(0), options.natLeaseMinutes)
        options.natRenewalMinutes = getContentOrDefault(elementOptions.getElementsByTagName("natRenewalMinutes").item(0), options.natRenewalMinutes)
        options.natTimeoutSeconds = getContentOrDefault(elementOptions.getElementsByTagName("natTimeoutSeconds").item(0), options.natTimeoutSeconds)
        options.urAccepted = getContentOrDefault(elementOptions.getElementsByTagName("urAccepted").item(0), options.urAccepted)
        options.urUniqueId = getContentOrDefault(elementOptions.getElementsByTagName("urUniqueId").item(0), "")
        options.urURL = getContentOrDefault(elementOptions.getElementsByTagName("urURL").item(0), options.urURL)
        options.urPostInsecurely = getContentOrDefault(elementOptions.getElementsByTagName("urPostInsecurely").item(0), options.urPostInsecurely)
        options.urInitialDelayS = getContentOrDefault(elementOptions.getElementsByTagName("urInitialDelayS").item(0), options.urInitialDelayS)
        options.autoUpgradeIntervalH = getContentOrDefault(elementOptions.getElementsByTagName("autoUpgradeIntervalH").item(0), options.autoUpgradeIntervalH)
        options.upgradeToPreReleases = getContentOrDefault(elementOptions.getElementsByTagName("upgradeToPreReleases").item(0), options.upgradeToPreReleases)
        options.keepTemporariesH = getContentOrDefault(elementOptions.getElementsByTagName("keepTemporariesH").item(0), options.keepTemporariesH)
        options.cacheIgnoredFiles = getContentOrDefault(elementOptions.getElementsByTagName("cacheIgnoredFiles").item(0), options.cacheIgnoredFiles)
        options.progressUpdateIntervalS = getContentOrDefault(elementOptions.getElementsByTagName("progressUpdateIntervalS").item(0), options.progressUpdateIntervalS)
        options.limitBandwidthInLan = getContentOrDefault(elementOptions.getElementsByTagName("limitBandwidthInLan").item(0), options.limitBandwidthInLan)
        options.releasesURL = getContentOrDefault(elementOptions.getElementsByTagName("releasesURL").item(0), options.releasesURL)
        // alwaysLocalNets
        options.overwriteRemoteDeviceNamesOnConnect = getContentOrDefault(elementOptions.getElementsByTagName("overwriteRemoteDeviceNamesOnConnect").item(0), options.overwriteRemoteDeviceNamesOnConnect)
        options.tempIndexMinBlocks = getContentOrDefault(elementOptions.getElementsByTagName("tempIndexMinBlocks").item(0), options.tempIndexMinBlocks)
        options.setLowPriority = getContentOrDefault(elementOptions.getElementsByTagName("setLowPriority").item(0), options.setLowPriority)
        // minHomeDiskFree
        options.maxFolderConcurrency = getContentOrDefault(elementOptions.getElementsByTagName("maxFolderConcurrency").item(0), options.maxFolderConcurrency)
        options.unackedNotificationID = getContentOrDefault(elementOptions.getElementsByTagName("unackedNotificationID").item(0), options.unackedNotificationID)
        options.crURL = getContentOrDefault(elementOptions.getElementsByTagName("crashReportingURL").item(0), options.crURL)
        options.crashReportingEnabled = getContentOrDefault(elementOptions.getElementsByTagName("crashReportingEnabled").item(0), options.crashReportingEnabled)
        options.stunKeepaliveStartS = getContentOrDefault(elementOptions.getElementsByTagName("stunKeepaliveStartS").item(0), options.stunKeepaliveStartS)
        options.stunKeepaliveMinS = getContentOrDefault(elementOptions.getElementsByTagName("stunKeepaliveMinS").item(0), options.stunKeepaliveMinS)
        options.stunServer = getContentOrDefault(elementOptions.getElementsByTagName("stunServer").item(0), options.stunServer)
        options.maxConcurrentIncomingRequestKiB = getContentOrDefault(elementOptions.getElementsByTagName("maxConcurrentIncomingRequestKiB").item(0), options.maxConcurrentIncomingRequestKiB)
        options.announceLANAddresses = getContentOrDefault(elementOptions.getElementsByTagName("announceLANAddresses").item(0), options.announceLANAddresses)
        options.sendFullIndexOnUpgrade = getContentOrDefault(elementOptions.getElementsByTagName("sendFullIndexOnUpgrade").item(0), options.sendFullIndexOnUpgrade)
        options.featureFlag = getContentOrDefault(elementOptions.getElementsByTagName("featureFlag").item(0), options.featureFlag)
        options.connectionLimitEnough = getContentOrDefault(elementOptions.getElementsByTagName("connectionLimitEnough").item(0), options.connectionLimitEnough)
        options.connectionLimitMax = getContentOrDefault(elementOptions.getElementsByTagName("connectionLimitMax").item(0), options.connectionLimitMax)
        return options
    }

    fun setDevicePause(deviceId: String, paused: Boolean) {
        // Prevent enumerating "<device>" tags below "<folder>" nodes by enumerating child nodes manually.
        val childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "device") {
                val r = node as Element
                if (getAttributeOrDefault(r, "id", "") == deviceId) {
                    setConfigElement(r, "paused", paused)
                    break
                }
            }
        }
    }

    /**
     * If an indented child element is removed, whitespace and line break will be left by
     * Element.removeChild().
     * See https://stackoverflow.com/questions/14255064/removechild-how-to-remove-indent-too
     */
    private fun removeChildElementFromTextNode(parentElement: Element, childElement: Element) {
        val prev = childElement.previousSibling
        if (prev != null &&
            prev.nodeType == Node.TEXT_NODE &&
            prev.nodeValue.trim().isEmpty()
        ) {
            parentElement.removeChild(prev)
        }
        parentElement.removeChild(childElement)
    }

    private fun setConfigElement(parent: Element, tagName: String, newValue: Boolean): Boolean {
        return setConfigElement(parent, tagName, newValue.toString())
    }

    private fun setConfigElement(parent: Element, tagName: String, textContent: String?): Boolean {
        var element = parent.getElementsByTagName(tagName).item(0)
        if (element == null) {
            element = config.createElement(tagName)
            parent.appendChild(element)
        }
        if (textContent != element.textContent) {
            element.textContent = textContent
            return true
        }
        return false
    }

    private fun setConfigElement(parent: Element, tagName: String, textArray: Array<String>): Boolean {
        val existingNodes = parent.getElementsByTagName(tagName)
        val toRemove = ArrayList<Node>()
        for (i in 0 until existingNodes.length) {
            val node = existingNodes.item(i)
            if (node.parentNode === parent) {
                toRemove.add(node)
            }
        }
        for (node in toRemove) {
            parent.removeChild(node)
        }
        for (text in textArray) {
            val newElement = config.createElement(tagName)
            newElement.textContent = text
            parent.appendChild(newElement)
        }
        return toRemove.isNotEmpty() || textArray.isNotEmpty()
    }

    private fun getGuiElement(): Element? {
        return config.documentElement.getElementsByTagName("gui").item(0) as? Element
    }

    /**
     * Set device model name as device name for Syncthing.
     * We need to iterate through XML nodes manually, as config.getDocumentElement() will also
     * return nested elements inside folder element. We have to check that we only rename the
     * device corresponding to the local device ID.
     * Returns if changes to the config have been made.
     */
    private fun changeLocalDeviceName(localDeviceID: String): Boolean {
        val childNodes = config.documentElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeName == "device") {
                if ((node as Element).getAttribute("id") == localDeviceID) {
                    Log.i(TAG, "changeLocalDeviceName: Rename device ID $localDeviceID to ${Build.MODEL}")
                    node.setAttribute("name", Build.MODEL)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Adds a new folder pointing to the app-specific "Syncthing Camera"
     * directory if it hasn't been added to the config yet.
     * Returns if changes to the config have been made.
     */
    private fun addSyncthingCameraFolder(): Boolean {
        val nodeFolders = config.documentElement.getElementsByTagName("folder")
        var folderAlreadyPresentInConfig = false
        for (i in 0 until nodeFolders.length) {
            val r = nodeFolders.item(i) as Element
            val folderId = getAttributeOrDefault(r, "id", "")
            if (!TextUtils.isEmpty(folderId) && folderId == Constants.syncthingCameraFolderId) {
                folderAlreadyPresentInConfig = true
                break
            }
        }
        if (folderAlreadyPresentInConfig) {
            return false
        }

        // Shared with the RestApi live-enablement path (SyncthingService).
        val folder = buildSyncthingCameraFolder(context)
        if (folder == null) {
            Log.e(TAG, "addSyncthingCameraFolder: Could not determine storage dir")
            return false
        }

        // Add folder to config.
        LogV("addSyncthingCameraFolder: Adding folder to config [${folder.path}]")
        addFolder(folder)
        return true
    }

    /**
     * Writes updated config back to file.
     */
    fun saveChanges() {
        if (!configFile.canWrite() && !ensureCoreFilesReadable()) {
            Log.w(TAG, "Failed to save updated config. Cannot change the owner of the config file.")
            return
        }

        Log.i(TAG, "Saving config file")
        val configTempFile = Constants.getConfigTempFile(context)
        try {
            // Write XML header.
            val fileOutputStream = FileOutputStream(configTempFile)
            fileOutputStream.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray(StandardCharsets.UTF_8))

            // Prepare Object-to-XML transform.
            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            transformer.setOutputProperty(OutputKeys.METHOD, "xml")
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-16")
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")

            // Output XML body.
            val byteArrayOutputStream = ByteArrayOutputStream()
            val streamResult = StreamResult(OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8))
            transformer.transform(DOMSource(config), streamResult)
            val outputBytes = byteArrayOutputStream.toByteArray()
            fileOutputStream.write(outputBytes)
            fileOutputStream.close()
        } catch (e: TransformerException) {
            Log.w(TAG, "Failed to transform object to xml and save temporary config file", e)
            return
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Failed to save temporary config file, FileNotFoundException", e)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to save temporary config file, IOException", e)
        }
        try {
            configTempFile.renameTo(configFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rename temporary config file to original file", e)
        }
    }

    private fun LogV(logMessage: String) {
        if (enableVerboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {

        private const val TAG = "ConfigXml"
    }
}
