package com.nutomic.syncthingandroid.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile

import com.nutomic.syncthingandroid.R

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Utils for dealing with Storage Access Framework URIs.
 */
object FileUtils {

    private const val TAG = "FileUtils"

    private const val PROC_MOUNTS_PATH = "/proc/mounts"
    private const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"
    private const val RECOMMENDED_FILES_APP_PACKAGE = "me.zhanghai.android.files"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_VOLUME_NAME = "downloads"
    private const val PRIMARY_VOLUME_NAME = "primary"
    private const val HOME_VOLUME_NAME = "home"

    enum class ExternalStorageDirType {
        DATA,
        EXT_MEDIA,
        INT_MEDIA
    }

    fun convertFromDocumentUriToTreeUri(documentUri: Uri): Uri {
        // IN: content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2F${applicationId}
        // OUT: content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2F${applicationId}
        val authority = documentUri.authority
        val documentId = DocumentsContract.getDocumentId(documentUri)
        return DocumentsContract.buildTreeDocumentUri(authority, documentId)
    }

    fun directoryUriExists(context: Context, documentUri: Uri?): Boolean {
        if (documentUri == null) {
            return false
        }
        val treeUri = convertFromDocumentUriToTreeUri(documentUri)
        val absPath = getAbsolutePathFromSAFUri(context, treeUri)
        // The Java original NPE'd on a null path here (File constructor).
        return File(absPath!!).exists()
    }

    fun getAbsolutePathFromSAFUri(context: Context, safResultUri: Uri?): String? {
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(
            safResultUri,
            DocumentsContract.getTreeDocumentId(safResultUri)
        )
        return getAbsolutePathFromTreeUri(context, treeUri)
    }

    fun getAbsolutePathFromTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: called with treeUri == null")
            return null
        }

        // Determine volumeId, e.g. "home", "documents"
        val volumeId = getVolumeIdFromTreeUri(treeUri) ?: return null

        // Handle Uri referring to internal or external storage.
        var volumePath = getVolumePath(volumeId, context)
        if (volumePath.endsWith(File.separator)) {
            volumePath = volumePath.substring(0, volumePath.length - 1)
        }
        var documentPath = getDocumentPathFromTreeUri(treeUri)
        if (documentPath.endsWith(File.separator)) {
            documentPath = documentPath.substring(0, documentPath.length - 1)
        }
        return if (documentPath.isNotEmpty()) {
            if (documentPath.startsWith(File.separator)) {
                volumePath + documentPath
            } else {
                volumePath + File.separator + documentPath
            }
        } else {
            volumePath
        }
    }

    private fun getMountedStoragePaths(): List<String> {
        val mountPaths = ArrayList<String>()
        try {
            BufferedReader(FileReader(PROC_MOUNTS_PATH)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("/storage/") || line.contains("/mnt/media_rw/")) {
                        val parts = line.split(" ".toRegex())
                        val mountPoint = parts[1]

                        // Filter
                        if ((mountPoint.startsWith("/storage/") || mountPoint.startsWith("/mnt/media_rw/")) &&
                            !mountPoint.contains("emulated") &&
                            File(mountPoint).isDirectory
                        ) {
                            mountPaths.add(mountPoint)
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "getMountedStoragePaths: Error reading /proc/mounts", e)
        }
        return mountPaths
    }

    fun getMountedStoragePathsAsFileArray(): Array<File> {
        val files = ArrayList<File>()
        for (path in getMountedStoragePaths()) {
            val f = File(path)
            if (f.canRead()) {
                files.add(f)
            }
        }
        return files.toTypedArray()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun getVolumePath(volumeId: String, context: Context): String {
        try {
            if (HOME_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isHomeVolume")
                // Reading the environment var avoids hard coding the case of the "documents" folder.
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
            }
            if (DOWNLOADS_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isDownloadsVolume")
                return getExternalStorageDownloadsDirectory()
            }

            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
            val getVolumeList = storageManager.javaClass.getMethod("getVolumeList")
            val getUuid = storageVolumeClazz.getMethod("getUuid")
            val getPath = storageVolumeClazz.getMethod("getPath")
            val isPrimary = storageVolumeClazz.getMethod("isPrimary")
            val result = getVolumeList.invoke(storageManager)

            val length = java.lang.reflect.Array.getLength(result)
            for (i in 0 until length) {
                val storageVolumeElement = java.lang.reflect.Array.get(result, i)
                val uuid = getUuid.invoke(storageVolumeElement) as String?
                val primary = isPrimary.invoke(storageVolumeElement) as Boolean
                val isPrimaryVolume = primary && PRIMARY_VOLUME_NAME == volumeId
                val isExternalVolume = uuid != null && uuid == volumeId
                Log.d(
                    TAG, "Found volume with uuid='$uuid" +
                        "', volumeId='$volumeId" +
                        "', primary=$primary" +
                        ", isPrimaryVolume=$isPrimaryVolume" +
                        ", isExternalVolume=$isExternalVolume"
                )
                if (isPrimaryVolume || isExternalVolume) {
                    Log.v(TAG, "getVolumePath: isPrimaryVolume || isExternalVolume")
                    // Return path if the correct volume corresponding to volumeId was found.
                    return getPath.invoke(storageVolumeElement) as String
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getVolumePath exception", e)
        }
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Log.w(TAG, "getVolumePath failed for volumeId='$volumeId'")
        if (volumeId == "primary") {
            Log.d(TAG, "volumeId == primary")
            return getInternalStorageRootAbsolutePath()
        }
        return "/storage/$volumeId"
    }

    fun getExternalFilesDir(context: Context, type: String?): File? {
        return getExternalFilesDir(context, ExternalStorageDirType.DATA, type)
    }

    fun getExternalFilesDir(context: Context, extDirType: ExternalStorageDirType, type: String?): File? {
        /**
         * Determine the app's private data folder on external storage if present.
         * e.g. "/storage/abcd-efgh/Android/data/[PACKAGE_NAME]/files"
         * e.g. "/storage/abcd-efgh/Android/media/[PACKAGE_NAME]"
         */
        val externalFilesDir = ArrayList<File?>()
        when (extDirType) {
            ExternalStorageDirType.DATA -> {
                externalFilesDir.addAll(ContextCompat.getExternalFilesDirs(context, null).toList())
                if (externalFilesDir.size > 1) {
                    // There is a bug on Huawei devices running Android 7, which returns the wrong external path.
                    // That's why we use ContextCompat here instead of context.
                    // See: https://stackoverflow.com/questions/39895579/fileprovider-error-onhuawei-devices
                    externalFilesDir.remove(context.getExternalFilesDir(null))
                }
            }
            ExternalStorageDirType.INT_MEDIA -> {
                externalFilesDir.add(File(Environment.getExternalStorageDirectory().toString() + "/Android/media/" + context.packageName))
            }
            ExternalStorageDirType.EXT_MEDIA -> {
                externalFilesDir.addAll(context.getExternalMediaDirs().toList())
                if (externalFilesDir.isNotEmpty()) {
                    externalFilesDir.remove(externalFilesDir[0])
                }
            }
        }
        externalFilesDir.remove(null)      // getExternalFilesDirs may return null for an ejected SDcard.
        if (externalFilesDir.isEmpty()) {
            Log.w(TAG, "Could not determine app's private files directory on external storage.")
            return null
        }
        if (type != null) {
            when (extDirType) {
                ExternalStorageDirType.EXT_MEDIA, ExternalStorageDirType.INT_MEDIA -> {
                    if (type == Environment.DIRECTORY_PICTURES) {
                        return File(externalFilesDir[0]!!, Environment.DIRECTORY_PICTURES)
                    }
                }
                ExternalStorageDirType.DATA -> {}
            }
        }
        return externalFilesDir[0]
    }

    /**
     * FileProvider does not support converting the absolute path from
     * getExternalFilesDir() to a "content://" Uri. As "file://" Uri
     * has been blocked since Android 7+, we need to build the Uri
     * manually after discovering the first external storage.
     * This is crucial to assist the user finding a writeable folder
     * to use Syncthing's two way sync feature.
     * API for getExternalFilesDirs(): 19+ (KITKAT+)
     * API for getExternalMediaDirs(): 21+ (LOLLIPOP+)
     */
    fun getExternalFilesDirUri(context: Context, extDirType: ExternalStorageDirType): Uri? {
        try {
            val externalFilesDir = getExternalFilesDir(context, extDirType, null)
            if (externalFilesDir == null) {
                Log.w(TAG, "Could not determine app's private files directory on external storage.")
                return null
            }
            val absPath = externalFilesDir.absolutePath

            val segments = absPath.split("/".toRegex())
            if (segments.size < 2) {
                Log.w(TAG, "Could not extract volumeId from app's private files path '$absPath'")
                return null
            }
            // Extract the volumeId, e.g. "abcd-efgh"
            val volumeId = segments[2]
            when (extDirType) {
                ExternalStorageDirType.DATA ->
                    // Build the content Uri for our private ".../data/[PKG_NAME]/files" folder.
                    return Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            volumeId + "%3AAndroid%2Fdata%2F" +
                            context.packageName + "%2Ffiles"
                    )
                ExternalStorageDirType.EXT_MEDIA ->
                    // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            volumeId + "%3AAndroid%2Fmedia%2F" +
                            context.packageName
                    )
                ExternalStorageDirType.INT_MEDIA ->
                    // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return Uri.parse(
                        "content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            "primary" + "%3AAndroid%2Fmedia%2F" +
                            context.packageName
                    )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getExternalFilesDirUri exception", e)
        }
        return null
    }

    /**
     * FileProvider does not support converting absolute paths
     * to a "content://" Uri. As "file://" Uri has been blocked
     * since Android 7+, we need to build the Uri manually.
     */
    fun getInternalStorageRootUri(): Uri {
        return Uri.parse("content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/primary%3A")
    }

    private fun getVolumeIdFromTreeUri(treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex())
        return if (split.isNotEmpty()) split[0] else null
    }

    private fun getDocumentPathFromTreeUri(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":".toRegex())
        return if (split.size >= 2) split[1] else File.separator
    }

    /**
     * Reading the environment var avoids hard coding the absolute path of the "/Download" folder.
     */
    fun getExternalStorageDownloadsDirectory(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    fun cutTrailingSlash(path: String): String? {
        return if (path.endsWith(File.separator)) {
            path.substring(0, path.length - 1)
        } else {
            path
        }
    }

    /**
     * Deletes a directory recursively.
     */
    @Throws(IOException::class)
    fun deleteDirectoryRecursively(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return false
        if (dir.isFile) return dir.delete()

        val entries = dir.listFiles()
        if (entries != null) {
            for (entry in entries) {
                deleteDirectoryRecursively(entry)
            }
        }
        return dir.delete()
    }

    /**
     * Expands the "~" path.
     * Equals SyncthingRunnable env "HOME"
     * Result: e.g. /storage/emulated/0/syncthing
     */
    fun getSyncthingTildeAbsolutePath(): String {
        return getInternalStorageRootAbsolutePath() + "/syncthing"
    }

    private fun getInternalStorageRootAbsolutePath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /**
     * Derives the mime type from file extension.
     */
    fun getMimeTypeFromFileExtension(fileExtension: String): String {
        val mimeTypes = MIME_TYPES
        val fileMimeType = mimeTypes[fileExtension.lowercase(Locale.ROOT)]
        return fileMimeType ?: ""
    }

    /**
     * Java original built this map on every call; the entries are 1:1 with it.
     */
    private val MIME_TYPES: Map<String, String> = HashMap<String, String>().apply {
        put("323", "text/h323")
        put("3g2", "video/3gpp2")
        put("3gp", "video/3gpp")
        put("3gp2", "video/3gpp2")
        put("3gpp", "video/3gpp")
        put("7z", "application/x-7z-compressed")
        put("aa", "audio/audible")
        put("aac", "audio/aac")
        put("aaf", "application/octet-stream")
        put("aax", "audio/vnd.audible.aax")
        put("ac3", "audio/ac3")
        put("aca", "application/octet-stream")
        put("accda", "application/msaccess.addin")
        put("accdb", "application/msaccess")
        put("accdc", "application/msaccess.cab")
        put("accde", "application/msaccess")
        put("accdr", "application/msaccess.runtime")
        put("accdt", "application/msaccess")
        put("accdw", "application/msaccess.webapplication")
        put("accft", "application/msaccess.ftemplate")
        put("acx", "application/internet-property-stream")
        put("addin", "text/xml")
        put("ade", "application/msaccess")
        put("adobebridge", "application/x-bridge-url")
        put("adp", "application/msaccess")
        put("adt", "audio/vnd.dlna.adts")
        put("adts", "audio/aac")
        put("afm", "application/octet-stream")
        put("ai", "application/postscript")
        put("aif", "audio/aiff")
        put("aifc", "audio/aiff")
        put("aiff", "audio/aiff")
        put("air", "application/vnd.adobe.air-application-installer-package+zip")
        put("amc", "application/mpeg")
        put("anx", "application/annodex")
        put("apk", "application/vnd.android.package-archive")
        put("application", "application/x-ms-application")
        put("art", "image/x-jg")
        put("asa", "application/xml")
        put("asax", "application/xml")
        put("ascx", "application/xml")
        put("asd", "application/octet-stream")
        put("asf", "video/x-ms-asf")
        put("ashx", "application/xml")
        put("asi", "application/octet-stream")
        put("asm", "text/plain")
        put("asmx", "application/xml")
        put("aspx", "application/xml")
        put("asr", "video/x-ms-asf")
        put("asx", "video/x-ms-asf")
        put("atom", "application/atom+xml")
        put("au", "audio/basic")
        put("avi", "video/x-msvideo")
        put("axa", "audio/annodex")
        put("axs", "application/olescript")
        put("axv", "video/annodex")
        put("bas", "text/plain")
        put("bcpio", "application/x-bcpio")
        put("bin", "application/octet-stream")
        put("bmp", "image/bmp")
        put("c", "text/plain")
        put("cab", "application/octet-stream")
        put("caf", "audio/x-caf")
        put("calx", "application/vnd.ms-office.calx")
        put("cat", "application/vnd.ms-pki.seccat")
        put("cc", "text/plain")
        put("cd", "text/plain")
        put("cdda", "audio/aiff")
        put("cdf", "application/x-cdf")
        put("cer", "application/x-x509-ca-cert")
        put("cfg", "text/plain")
        put("chm", "application/octet-stream")
        put("class", "application/x-java-applet")
        put("clp", "application/x-msclip")
        put("cmd", "text/plain")
        put("cmx", "image/x-cmx")
        put("cnf", "text/plain")
        put("cod", "image/cis-cod")
        put("config", "application/xml")
        put("contact", "text/x-ms-contact")
        put("coverage", "application/xml")
        put("cpio", "application/x-cpio")
        put("cpp", "text/plain")
        put("crd", "application/x-mscardfile")
        put("crl", "application/pkix-crl")
        put("crt", "application/x-x509-ca-cert")
        put("cs", "text/plain")
        put("csdproj", "text/plain")
        put("csh", "application/x-csh")
        put("csproj", "text/plain")
        put("css", "text/css")
        put("csv", "text/csv")
        put("cur", "application/octet-stream")
        put("cxx", "text/plain")
        put("dat", "application/octet-stream")
        put("datasource", "application/xml")
        put("dbproj", "text/plain")
        put("dcr", "application/x-director")
        put("def", "text/plain")
        put("deploy", "application/octet-stream")
        put("der", "application/x-x509-ca-cert")
        put("dgml", "application/xml")
        put("dib", "image/bmp")
        put("dif", "video/x-dv")
        put("dir", "application/x-director")
        put("disco", "text/xml")
        put("divx", "video/divx")
        put("dll", "application/x-msdownload")
        put("dll.config", "text/xml")
        put("dlm", "text/dlm")
        put("dng", "image/x-adobe-dng")
        put("doc", "application/msword")
        put("docm", "application/vnd.ms-word.document.macroEnabled.12")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("dot", "application/msword")
        put("dotm", "application/vnd.ms-word.template.macroEnabled.12")
        put("dotx", "application/vnd.openxmlformats-officedocument.wordprocessingml.template")
        put("dsp", "application/octet-stream")
        put("dsw", "text/plain")
        put("dtd", "text/xml")
        put("dtsconfig", "text/xml")
        put("dv", "video/x-dv")
        put("dvi", "application/x-dvi")
        put("dwf", "drawing/x-dwf")
        put("dwp", "application/octet-stream")
        put("dxr", "application/x-director")
        put("eml", "message/rfc822")
        put("emz", "application/octet-stream")
        put("eot", "application/vnd.ms-fontobject")
        put("eps", "application/postscript")
        put("etl", "application/etl")
        put("etx", "text/x-setext")
        put("evy", "application/envoy")
        put("exe", "application/octet-stream")
        put("exe.config", "text/xml")
        put("fdf", "application/vnd.fdf")
        put("fif", "application/fractals")
        put("filters", "application/xml")
        put("fla", "application/octet-stream")
        put("flac", "audio/flac")
        put("flr", "x-world/x-vrml")
        put("flv", "video/x-flv")
        put("fsscript", "application/fsharp-script")
        put("fsx", "application/fsharp-script")
        put("generictest", "application/xml")
        put("gif", "image/gif")
        put("group", "text/x-ms-group")
        put("gsm", "audio/x-gsm")
        put("gtar", "application/x-gtar")
        put("gz", "application/x-gzip")
        put("h", "text/plain")
        put("hdf", "application/x-hdf")
        put("hdml", "text/x-hdml")
        put("hhc", "application/x-oleobject")
        put("hhk", "application/octet-stream")
        put("hhp", "application/octet-stream")
        put("hlp", "application/winhlp")
        put("hpp", "text/plain")
        put("hqx", "application/mac-binhex40")
        put("hta", "application/hta")
        put("htc", "text/x-component")
        put("htm", "text/html")
        put("html", "text/html")
        put("htt", "text/webviewhtml")
        put("hxa", "application/xml")
        put("hxc", "application/xml")
        put("hxd", "application/octet-stream")
        put("hxe", "application/xml")
        put("hxf", "application/xml")
        put("hxh", "application/octet-stream")
        put("hxi", "application/octet-stream")
        put("hxk", "application/xml")
        put("hxq", "application/octet-stream")
        put("hxr", "application/octet-stream")
        put("hxs", "application/octet-stream")
        put("hxt", "text/html")
        put("hxv", "application/xml")
        put("hxw", "application/octet-stream")
        put("hxx", "text/plain")
        put("i", "text/plain")
        put("ico", "image/x-icon")
        put("ics", "text/calendar")
        put("idl", "text/plain")
        put("ief", "image/ief")
        put("iii", "application/x-iphone")
        put("inc", "text/plain")
        put("inf", "application/octet-stream")
        put("ini", "text/plain")
        put("inl", "text/plain")
        put("ins", "application/x-internet-signup")
        put("ipa", "application/x-itunes-ipa")
        put("ipg", "application/x-itunes-ipg")
        put("ipproj", "text/plain")
        put("ipsw", "application/x-itunes-ipsw")
        put("iqy", "text/x-ms-iqy")
        put("isp", "application/x-internet-signup")
        put("ite", "application/x-itunes-ite")
        put("itlp", "application/x-itunes-itlp")
        put("itms", "application/x-itunes-itms")
        put("itpc", "application/x-itunes-itpc")
        put("ivf", "video/x-ivf")
        put("jar", "application/java-archive")
        put("java", "application/octet-stream")
        put("jck", "application/liquidmotion")
        put("jcz", "application/liquidmotion")
        put("jfif", "image/pjpeg")
        put("jnlp", "application/x-java-jnlp-file")
        put("jpb", "application/octet-stream")
        put("jpe", "image/jpeg")
        put("jpeg", "image/jpeg")
        put("jpg", "image/jpeg")
        put("js", "application/javascript")
        put("json", "application/json")
        put("jsx", "text/jscript")
        put("jsxbin", "text/plain")
        put("latex", "application/x-latex")
        put("library-ms", "application/windows-library+xml")
        put("lit", "application/x-ms-reader")
        put("loadtest", "application/xml")
        put("lpk", "application/octet-stream")
        put("lsf", "video/x-la-asf")
        put("lst", "text/plain")
        put("lsx", "video/x-la-asf")
        put("lzh", "application/octet-stream")
        put("m13", "application/x-msmediaview")
        put("m14", "application/x-msmediaview")
        put("m1v", "video/mpeg")
        put("m2t", "video/vnd.dlna.mpeg-tts")
        put("m2ts", "video/vnd.dlna.mpeg-tts")
        put("m2v", "video/mpeg")
        put("m3u", "audio/x-mpegurl")
        put("m3u8", "audio/x-mpegurl")
        put("m4a", "audio/m4a")
        put("m4b", "audio/m4b")
        put("m4p", "audio/m4p")
        put("m4r", "audio/x-m4r")
        put("m4v", "video/x-m4v")
        put("mac", "image/x-macpaint")
        put("mak", "text/plain")
        put("man", "application/x-troff-man")
        put("manifest", "application/x-ms-manifest")
        put("map", "text/plain")
        put("master", "application/xml")
        put("mda", "application/msaccess")
        put("mdb", "application/x-msaccess")
        put("mde", "application/msaccess")
        put("mdp", "application/octet-stream")
        put("me", "application/x-troff-me")
        put("mfp", "application/x-shockwave-flash")
        put("mht", "message/rfc822")
        put("mhtml", "message/rfc822")
        put("mid", "audio/mid")
        put("midi", "audio/mid")
        put("mix", "application/octet-stream")
        put("mk", "text/plain")
        put("mkv", "video/x-matroska")
        put("mmf", "application/x-smaf")
        put("mno", "text/xml")
        put("mny", "application/x-msmoney")
        put("mod", "video/mpeg")
        put("mov", "video/quicktime")
        put("movie", "video/x-sgi-movie")
        put("mp2", "video/mpeg")
        put("mp2v", "video/mpeg")
        put("mp3", "audio/mpeg")
        put("mp4", "video/mp4")
        put("mp4v", "video/mp4")
        put("mpa", "video/mpeg")
        put("mpe", "video/mpeg")
        put("mpeg", "video/mpeg")
        put("mpf", "application/vnd.ms-mediapackage")
        put("mpg", "video/mpeg")
        put("mpp", "application/vnd.ms-project")
        put("mpv2", "video/mpeg")
        put("mqv", "video/quicktime")
        put("ms", "application/x-troff-ms")
        put("msi", "application/octet-stream")
        put("mso", "application/octet-stream")
        put("mts", "video/vnd.dlna.mpeg-tts")
        put("mtx", "application/xml")
        put("mvb", "application/x-msmediaview")
        put("mvc", "application/x-miva-compiled")
        put("mxp", "application/x-mmxp")
        put("nc", "application/x-netcdf")
        put("nomedia", "application/octet-stream")
        put("nsc", "video/x-ms-asf")
        put("nws", "message/rfc822")
        put("ocx", "application/octet-stream")
        put("oda", "application/oda")
        put("odb", "application/vnd.oasis.opendocument.database")
        put("odc", "application/vnd.oasis.opendocument.chart")
        put("odf", "application/vnd.oasis.opendocument.formula")
        put("odg", "application/vnd.oasis.opendocument.graphics")
        put("odh", "text/plain")
        put("odi", "application/vnd.oasis.opendocument.image")
        put("odl", "text/plain")
        put("odm", "application/vnd.oasis.opendocument.text-master")
        put("odp", "application/vnd.oasis.opendocument.presentation")
        put("ods", "application/vnd.oasis.opendocument.spreadsheet")
        put("odt", "application/vnd.oasis.opendocument.text")
        put("oga", "audio/ogg")
        put("ogg", "audio/ogg")
        put("ogv", "video/ogg")
        put("ogx", "application/ogg")
        put("one", "application/onenote")
        put("onea", "application/onenote")
        put("onepkg", "application/onenote")
        put("onetmp", "application/onenote")
        put("onetoc", "application/onenote")
        put("onetoc2", "application/onenote")
        put("opus", "audio/ogg")
        put("orderedtest", "application/xml")
        put("osdx", "application/opensearchdescription+xml")
        put("otf", "application/font-sfnt")
        put("otg", "application/vnd.oasis.opendocument.graphics-template")
        put("oth", "application/vnd.oasis.opendocument.text-web")
        put("otp", "application/vnd.oasis.opendocument.presentation-template")
        put("ots", "application/vnd.oasis.opendocument.spreadsheet-template")
        put("ott", "application/vnd.oasis.opendocument.text-template")
        put("oxt", "application/vnd.openofficeorg.extension")
        put("p10", "application/pkcs10")
        put("p12", "application/x-pkcs12")
        put("p7b", "application/x-pkcs7-certificates")
        put("p7c", "application/pkcs7-mime")
        put("p7m", "application/pkcs7-mime")
        put("p7r", "application/x-pkcs7-certreqresp")
        put("p7s", "application/pkcs7-signature")
        put("pbm", "image/x-portable-bitmap")
        put("pcast", "application/x-podcast")
        put("pct", "image/pict")
        put("pcx", "application/octet-stream")
        put("pcz", "application/octet-stream")
        put("pdf", "application/pdf")
        put("pfb", "application/octet-stream")
        put("pfm", "application/octet-stream")
        put("pfx", "application/x-pkcs12")
        put("pgm", "image/x-portable-graymap")
        put("php", "text/plain")
        put("pic", "image/pict")
        put("pict", "image/pict")
        put("pkgdef", "text/plain")
        put("pkgundef", "text/plain")
        put("pko", "application/vnd.ms-pki.pko")
        put("pls", "audio/scpls")
        put("pma", "application/x-perfmon")
        put("pmc", "application/x-perfmon")
        put("pml", "application/x-perfmon")
        put("pmr", "application/x-perfmon")
        put("pmw", "application/x-perfmon")
        put("png", "image/png")
        put("pnm", "image/x-portable-anymap")
        put("pnt", "image/x-macpaint")
        put("pntg", "image/x-macpaint")
        put("pnz", "image/png")
        put("pot", "application/vnd.ms-powerpoint")
        put("potm", "application/vnd.ms-powerpoint.template.macroEnabled.12")
        put("potx", "application/vnd.openxmlformats-officedocument.presentationml.template")
        put("ppa", "application/vnd.ms-powerpoint")
        put("ppam", "application/vnd.ms-powerpoint.addin.macroEnabled.12")
        put("ppm", "image/x-portable-pixmap")
        put("pps", "application/vnd.ms-powerpoint")
        put("ppsm", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12")
        put("ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptm", "application/vnd.ms-powerpoint.presentation.macroEnabled.12")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("prf", "application/pics-rules")
        put("prm", "application/octet-stream")
        put("prx", "application/octet-stream")
        put("ps", "application/postscript")
        put("psc1", "application/PowerShell")
        put("psd", "application/octet-stream")
        put("psess", "application/xml")
        put("psm", "application/octet-stream")
        put("psp", "application/octet-stream")
        put("pub", "application/x-mspublisher")
        put("pwz", "application/vnd.ms-powerpoint")
        put("py", "text/plain")
        put("qht", "text/x-html-insertion")
        put("qhtm", "text/x-html-insertion")
        put("qt", "video/quicktime")
        put("qti", "image/x-quicktime")
        put("qtif", "image/x-quicktime")
        put("qtl", "application/x-quicktimeplayer")
        put("qxd", "application/octet-stream")
        put("ra", "audio/x-pn-realaudio")
        put("ram", "audio/x-pn-realaudio")
        put("rar", "application/x-rar-compressed")
        put("ras", "image/x-cmu-raster")
        put("rat", "application/rat-file")
        put("rb", "text/plain")
        put("rc", "text/plain")
        put("rc2", "text/plain")
        put("rct", "text/plain")
        put("rdlc", "application/xml")
        put("reg", "text/plain")
        put("resx", "application/xml")
        put("rf", "image/vnd.rn-realflash")
        put("rgb", "image/x-rgb")
        put("rgs", "text/plain")
        put("rm", "application/vnd.rn-realmedia")
        put("rmi", "audio/mid")
        put("rmp", "application/vnd.rn-rn_music_package")
        put("roff", "application/x-troff")
        put("rpm", "audio/x-pn-realaudio-plugin")
        put("rqy", "text/x-ms-rqy")
        put("rtf", "application/rtf")
        put("rtx", "text/richtext")
        put("ruleset", "application/xml")
        put("s", "text/plain")
        put("safariextz", "application/x-safari-safariextz")
        put("scd", "application/x-msschedule")
        put("scr", "text/plain")
        put("sct", "text/scriptlet")
        put("sd2", "audio/x-sd2")
        put("sdp", "application/sdp")
        put("sea", "application/octet-stream")
        put("searchConnector-ms", "application/windows-search-connector+xml")
        put("setpay", "application/set-payment-initiation")
        put("setreg", "application/set-registration-initiation")
        put("settings", "application/xml")
        put("sgimb", "application/x-sgimb")
        put("sgml", "text/sgml")
        put("sh", "application/x-sh")
        put("shar", "application/x-shar")
        put("shtml", "text/html")
        put("sit", "application/x-stuffit")
        put("sitemap", "application/xml")
        put("skin", "application/xml")
        put("sldm", "application/vnd.ms-powerpoint.slide.macroEnabled.12")
        put("sldx", "application/vnd.openxmlformats-officedocument.presentationml.slide")
        put("slk", "application/vnd.ms-excel")
        put("sln", "text/plain")
        put("slupkg-ms", "application/x-ms-license")
        put("smd", "audio/x-smd")
        put("smi", "application/octet-stream")
        put("smx", "audio/x-smd")
        put("smz", "audio/x-smd")
        put("snd", "audio/basic")
        put("snippet", "application/xml")
        put("snp", "application/octet-stream")
        put("sol", "text/plain")
        put("sor", "text/plain")
        put("spc", "application/x-pkcs7-certificates")
        put("spl", "application/futuresplash")
        put("spx", "audio/ogg")
        put("src", "application/x-wais-source")
        put("srf", "text/plain")
        put("ssisdeploymentmanifest", "text/xml")
        put("ssm", "application/streamingmedia")
        put("sst", "application/vnd.ms-pki.certstore")
        put("stl", "application/vnd.ms-pki.stl")
        put("sv4cpio", "application/x-sv4cpio")
        put("sv4crc", "application/x-sv4crc")
        put("svc", "application/xml")
        put("svg", "image/svg+xml")
        put("swf", "application/x-shockwave-flash")
        put("t", "application/x-troff")
        put("tar", "application/x-tar")
        put("tcl", "application/x-tcl")
        put("testrunconfig", "application/xml")
        put("testsettings", "application/xml")
        put("tex", "application/x-tex")
        put("texi", "application/x-texinfo")
        put("texinfo", "application/x-texinfo")
        put("tgz", "application/x-compressed")
        put("thmx", "application/vnd.ms-officetheme")
        put("thn", "application/octet-stream")
        put("tif", "image/tiff")
        put("tiff", "image/tiff")
        put("tlh", "text/plain")
        put("tli", "text/plain")
        put("toc", "application/octet-stream")
        put("tr", "application/x-troff")
        put("trm", "application/x-msterminal")
        put("trx", "application/xml")
        put("ts", "video/vnd.dlna.mpeg-tts")
        put("tsv", "text/tab-separated-values")
        put("ttf", "application/font-sfnt")
        put("tts", "video/vnd.dlna.mpeg-tts")
        put("txt", "text/plain")
        put("u32", "application/octet-stream")
        put("uls", "text/iuls")
        put("user", "text/plain")
        put("ustar", "application/x-ustar")
        put("vb", "text/plain")
        put("vbdproj", "text/plain")
        put("vbk", "video/mpeg")
        put("vbproj", "text/plain")
        put("vbs", "text/vbscript")
        put("vcf", "text/x-vcard")
        put("vcproj", "application/xml")
        put("vcs", "text/plain")
        put("vcxproj", "application/xml")
        put("vddproj", "text/plain")
        put("vdp", "text/plain")
        put("vdproj", "text/plain")
        put("vdx", "application/vnd.ms-visio.viewer")
        put("vml", "text/xml")
        put("vscontent", "application/xml")
        put("vsct", "text/xml")
        put("vsd", "application/vnd.visio")
        put("vsi", "application/ms-vsi")
        put("vsix", "application/vsix")
        put("vsixlangpack", "text/xml")
        put("vsixmanifest", "text/xml")
        put("vsmdi", "application/xml")
        put("vspscc", "text/plain")
        put("vss", "application/vnd.visio")
        put("vsscc", "text/plain")
        put("vssettings", "text/xml")
        put("vssscc", "text/plain")
        put("vst", "application/vnd.visio")
        put("vstemplate", "text/xml")
        put("vsto", "application/x-ms-vsto")
        put("vsw", "application/vnd.visio")
        put("vsx", "application/vnd.visio")
        put("vtx", "application/vnd.visio")
        put("wav", "audio/wav")
        put("wave", "audio/wav")
        put("wax", "audio/x-ms-wax")
        put("wbk", "application/msword")
        put("wbmp", "image/vnd.wap.wbmp")
        put("wcm", "application/vnd.ms-works")
        put("wdb", "application/vnd.ms-works")
        put("wdp", "image/vnd.ms-photo")
        put("webarchive", "application/x-safari-webarchive")
        put("webm", "video/webm")
        put("webp", "image/webp")
        put("webtest", "application/xml")
        put("wiq", "application/xml")
        put("wiz", "application/msword")
        put("wks", "application/vnd.ms-works")
        put("wlmp", "application/wlmoviemaker")
        put("wlpginstall", "application/x-wlpg-detect")
        put("wlpginstall3", "application/x-wlpg3-detect")
        put("wm", "video/x-ms-wm")
        put("wma", "audio/x-ms-wma")
        put("wmd", "application/x-ms-wmd")
        put("wmf", "application/x-msmetafile")
        put("wml", "text/vnd.wap.wml")
        put("wmlc", "application/vnd.wap.wmlc")
        put("wmls", "text/vnd.wap.wmlscript")
        put("wmlsc", "application/vnd.wap.wmlscriptc")
        put("wmp", "video/x-ms-wmp")
        put("wmv", "video/x-ms-wmv")
        put("wmx", "video/x-ms-wmx")
        put("wmz", "application/x-ms-wmz")
        put("woff", "application/font-woff")
        put("wpl", "application/vnd.ms-wpl")
        put("wps", "application/vnd.ms-works")
        put("wri", "application/x-mswrite")
        put("wrl", "x-world/x-vrml")
        put("wrz", "x-world/x-vrml")
        put("wsc", "text/scriptlet")
        put("wsdl", "text/xml")
        put("wvx", "video/x-ms-wvx")
        put("x", "application/directx")
        put("xaf", "x-world/x-vrml")
        put("xaml", "application/xaml+xml")
        put("xap", "application/x-silverlight-app")
        put("xbap", "application/x-ms-xbap")
        put("xbm", "image/x-xbitmap")
        put("xdr", "text/plain")
        put("xht", "application/xhtml+xml")
        put("xhtml", "application/xhtml+xml")
        put("xla", "application/vnd.ms-excel")
        put("xlam", "application/vnd.ms-excel.addin.macroEnabled.12")
        put("xlc", "application/vnd.ms-excel")
        put("xld", "application/vnd.ms-excel")
        put("xlk", "application/vnd.ms-excel")
        put("xll", "application/vnd.ms-excel")
        put("xlm", "application/vnd.ms-excel")
        put("xls", "application/vnd.ms-excel")
        put("xlsb", "application/vnd.ms-excel.sheet.binary.macroEnabled.12")
        put("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("xlt", "application/vnd.ms-excel")
        put("xltm", "application/vnd.ms-excel.template.macroEnabled.12")
        put("xltx", "application/vnd.openxmlformats-officedocument.spreadsheetml.template")
        put("xlw", "application/vnd.ms-excel")
        put("xml", "text/xml")
        put("xmta", "application/xml")
        put("xof", "x-world/x-vrml")
        put("xoml", "text/plain")
        put("xpm", "image/x-xpixmap")
        put("xps", "application/vnd.ms-xpsdocument")
        put("xrm-ms", "text/xml")
        put("xsc", "application/xml")
        put("xsd", "text/xml")
        put("xsf", "text/xml")
        put("xsl", "text/xml")
        put("xslt", "text/xml")
        put("xsn", "application/octet-stream")
        put("xss", "application/xml")
        put("xspf", "application/xspf+xml")
        put("xtp", "application/octet-stream")
        put("xwd", "image/x-xwindowdump")
        put("z", "application/x-compress")
        put("zip", "application/zip")
    }

    fun safCreateDirectory(parentFolder: DocumentFile?, folderName: String): DocumentFile? {
        if (parentFolder == null) {
            Log.w(TAG, "safCreateDirectory: parentFolder == null")
            return null
        }
        for (file in parentFolder.listFiles()) {
            if (file.isDirectory && file.name == folderName) {
                Log.v(TAG, "safCreateDirectory: Directory already exists '$folderName'")
                return file
            }
        }
        val dfNewFolder = parentFolder.createDirectory(folderName)
        if (dfNewFolder == null) {
            Log.w(TAG, "safCreateDirectory: Failed to create directory '$folderName'")
            return null
        }
        Log.v(TAG, "safCreateDirectory: Created directory '$folderName'")
        return dfNewFolder
    }

    fun safCreateFile(context: Context, parentFolder: DocumentFile, fileNameAndExtension: String, content: String): Boolean {
        for (file in parentFolder.listFiles()) {
            if (file.isFile && file.name == fileNameAndExtension) {
                Log.v(TAG, "safCreateFile: File already exists '$fileNameAndExtension'")
                return true
            }
        }

        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileNameAndExtension)
        val fileMimeType = getMimeTypeFromFileExtension(fileExtension)

        // The display name passed to createDocument MUST equal the name the
        // existence check above compares against. Stripping a trailing extension
        // here would make every second call create another file (providers rename
        // duplicates), silently stacking files like "DO_NOT_DELETE (1)".
        val fileName = fileNameAndExtension

        var failSuccess = false
        var outputStream: OutputStream? = null
        try {
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentFolder.uri,
                fileMimeType,
                fileName
            )
            if (fileUri == null) {
                Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #1")
                return false
            }
            outputStream = context.contentResolver.openOutputStream(fileUri)
            if (content.isNotEmpty()) {
                outputStream!!.write(content.toByteArray(StandardCharsets.ISO_8859_1))
            }
            outputStream!!.flush()
            Log.v(TAG, "safCreateFile: Created file '$fileNameAndExtension', type '$fileMimeType'")
            failSuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #2", e)
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #3", e)
            }
        }
        return failSuccess
    }

    /**
     * Open file in compatible app.
     */
    fun openFile(context: Context, fullPathAndFilename: String) {
        var fileUri: Uri = Uri.parse(fullPathAndFilename)
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileUri.toString())
        val mimeType = getMimeTypeFromFileExtension(fileExtension)
        Log.v(TAG, "openFile: Detected mime type '$mimeType' for file '$fullPathAndFilename'")
        val intent: Intent = when (fileExtension) {
            "apk" ->
                // Requires permission in AndroidManifest.xml
                // We've dropped this as this is rarely used, mainly during development.
                // <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
                Intent(Intent.ACTION_INSTALL_PACKAGE)
            else -> Intent(Intent.ACTION_VIEW)
        }
        fileUri = FileProvider.getUriForFile(context, context.packageName + ".provider", File(fullPathAndFilename))
        intent.setDataAndType(fileUri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (anfe: ActivityNotFoundException) {
            Log.w(TAG, "openFile: ActivityNotFoundException. Falling back to app chooser...")
            intent.setDataAndType(Uri.parse(fullPathAndFilename), "application/*")
            val chooserIntent = Intent.createChooser(intent, context.getString(R.string.open_file_with))
            try {
                context.startActivity(chooserIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "openFile:", ex)
                Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Open folder in compatible file manager app.
     */
    fun openFolder(context: Context, folderPath: String) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Toast.makeText(
                context,
                context.getString(R.string.state_error_message, "Invalid folder path"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val folderUri = Uri.fromFile(folder)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(folderUri, "resource/folder")
        intent.putExtra("org.openintents.extra.ABSOLUTE_PATH", folderPath)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            // Launch file manager.
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)", e)
            openFolderOlderMimeType(context, intent, folderPath)
        } catch (e: SecurityException) {
            Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)", e)
            openFolderOlderMimeType(context, intent, folderPath)
        }
    }

    /**
     * Fallback to the older mime type; Java original used a multi-catch for this path.
     */
    private fun openFolderOlderMimeType(context: Context, intent: Intent, folderPath: String) {
        try {
            val documentId = getDocumentIdFromPath(folderPath)
            val documentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
            intent.setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #2)", e)
            // No compatible file manager app found.
            suggestFileManagerApp(context)
        } catch (e: SecurityException) {
            Log.e(TAG, "openFolder: No compatible file manager app not found or has insufficient permissions (stage #2)", e)
            // No compatible file manager app found.
            suggestFileManagerApp(context)
        }
    }

    private fun suggestFileManagerApp(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.suggest_file_manager_app_dialog_title)
            .setMessage(R.string.suggest_file_manager_app_dialog_text)
            .setPositiveButton(R.string.yes) { _, _ ->
                val appPackageName = RECOMMENDED_FILES_APP_PACKAGE
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")))
                } catch (anfe: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")))
                }
            }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }

    /**
     * Converts a raw file path into a Storage Access Framework Document ID,
     * including support for removable SD cards and USB OTG drives.
     */
    @SuppressLint("SdCardPath")
    private fun getDocumentIdFromPath(fullPath: String?): String? {
        if (fullPath == null || fullPath.isEmpty()) {
            return null
        }

        // Clean up trailing slashes for consistent parsing
        var path = fullPath
        if (path.endsWith("/") && path.length > 1) {
            path = path.substring(0, path.length - 1)
        }

        val internalStoragePath = INTERNAL_STORAGE_ROOT
        val sdcardPath = "/sdcard"
        val storagePrefix = "/storage/"
        val primaryAuthorityPrefix = "primary:"

        // 1. Handle Internal Storage
        return when {
            path == internalStoragePath || path == sdcardPath -> primaryAuthorityPrefix // Root of internal storage
            path.startsWith("$internalStoragePath/") -> primaryAuthorityPrefix + path.substring(internalStoragePath.length + 1)
            path.startsWith("$sdcardPath/") -> primaryAuthorityPrefix + path.substring(sdcardPath.length + 1)
            // 2. Handle Removable SD Cards and USB Drives
            path.startsWith(storagePrefix) -> {
                // Path looks something like: /storage/1A2B-3C4D/books/sync
                val withoutStorage = path.substring(storagePrefix.length) // "1A2B-3C4D/books/sync"
                val firstSlashIndex = withoutStorage.indexOf('/')
                if (firstSlashIndex != -1) {
                    // Extract the UUID and the relative path
                    withoutStorage.substring(0, firstSlashIndex) + ":" + withoutStorage.substring(firstSlashIndex + 1)
                } else {
                    // It's the absolute root of the SD card: /storage/1A2B-3C4D
                    "$withoutStorage:"
                }
            }
            // Path does not match known external storage patterns
            else -> null
        }
    }
}
