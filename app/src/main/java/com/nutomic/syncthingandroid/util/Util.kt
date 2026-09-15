package com.nutomic.syncthingandroid.util

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.os.SystemClock
import android.util.Log

import androidx.appcompat.app.AppCompatActivity

import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.service.Constants

import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object Util {

    private const val TAG = "Util"

    /** Grace period after SIGINT before [killProcess] escalates to SIGKILL. */
    private const val KILL_SIGINT_GRACE_MS = 5000L

    /** Grace period after SIGKILL before [killProcess] gives up waiting. */
    private const val KILL_SIGKILL_GRACE_MS = 2000L

    /**
     * Converts a number of bytes to a human readable file size (eg 3.5 GiB).
     *
     * Based on http://stackoverflow.com/a/5599842
     */
    fun readableFileSize(context: Context, bytes: Double): String {
        val units = context.resources.getStringArray(R.array.file_size_units)
        if (bytes <= 0) return "0 " + units[0]
        val digitGroups = (Math.log10(bytes) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#")
            .format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Converts a number of bytes to a human readable transfer rate in bytes per second
     * (eg 100 KiB/s).
     *
     * Based on http://stackoverflow.com/a/5599842
     */
    fun readableTransferRate(context: Context, bits: Long): String {
        val units = context.resources.getStringArray(R.array.transfer_rate_units)
        val bytes = bits / 8
        if (bytes <= 0) return "0 " + units[0]
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#")
            .format(bytes.toDouble() / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Returns if the syncthing binary would be able to write a file into
     * the given folder given the configured access level.
     *
     * With [asRoot] the probe runs through the root shell, which is what the syncthing
     * core's effective access looks like when the "run as root" mode is enabled: the app
     * UID's own EACCES would otherwise wrongly reject root-only folders.
     */
    fun nativeBinaryCanWriteToPath(
        context: Context,
        absoluteFolderPath: String,
        asRoot: Boolean = false,
    ): Boolean {
        val touchFileName = ".stwritetest"

        // Write permission test file.
        val touchFile = "$absoluteFolderPath/$touchFileName"
        // The path is user-editable (folder edit screen) and may contain single quotes and
        // other shell metacharacters - always quote it, otherwise a path like "x';reboot;'"
        // would escape the quoting and run arbitrary commands through the root shell.
        val quotedTouchFile = shellQuote(touchFile)
        val exitCode = if (asRoot) {
            RootAccess.code("touch $quotedTouchFile")
        } else {
            runShellCommand("echo \"\" > $quotedTouchFile\n")
        }
        if (exitCode != 0) {
            val error = when (exitCode) {
                1 -> "Permission denied"
                else -> "Shell execution failed"
            }
            Log.i(TAG, "Failed to write test file '$touchFile', $error")
            return false
        }

        // Detected we have write permission.
        Log.i(TAG, "Successfully wrote test file '$touchFile'")

        // Remove test file.
        val rmExitCode = if (asRoot) {
            RootAccess.code("rm $quotedTouchFile")
        } else {
            runShellCommand("rm $quotedTouchFile\n")
        }
        if (rmExitCode != 0) {
            // This is very unlikely to happen, so we have less error handling.
            Log.i(TAG, "Failed to remove test file")
        }
        return true
    }

    /**
     * Look for running processes and return a list
     * containing the PIDs of found instances.
     *
     * With [asRoot] the listing runs through the root shell: the root-uid syncthing core
     * is invisible to the app's own `ps` (Android procfs only exposes same-UID processes).
     * Root listing matches the FIRST argument exactly against [processName] (pass the
     * full binary path) — a substring match would also hit the `su` wrapper whose
     * command line embeds the launch script containing that same path, and killing the
     * wrapper reports a spurious crash exit code (130) for the watched process.
     */
    fun getProcessPIDs(
        processName: String,
        asRoot: Boolean = false,
        argFilters: List<String> = emptyList(),
    ): List<String> {
        val output = if (asRoot) {
            RootAccess.out("ps -A -o pid,args").joinToString("\n")
        } else {
            runShellCommandGetOutput("ps\n")
        }
        if (output.isEmpty()) {
            Log.w(TAG, "getProcessPIDs: Failed to list processes. ps command returned empty.")
            return emptyList()
        }
        return parsePsOutput(output, processName, asRoot, argFilters)
    }

    /**
     * Look for running processes and end them gracefully.
     *
     * With [asRoot] both the lookup and the SIGINT go through the root shell: an app-UID
     * `kill` cannot signal the root-uid syncthing core (EPERM), and `ps` cannot see it.
     *
     * With [argFilters] (root mode only), a process is only matched when its argument
     * string contains at least one of the given substrings. Used for helpers like `find`
     * whose bare name is far too generic to kill under root: `ps -A` lists every process
     * on the system, and other users' or apps' `find` instances must not be signaled.
     * Non-root lookups are UID-scoped and ignore the filter.
     */
    fun killProcess(
        processName: String,
        asRoot: Boolean = false,
        argFilters: List<String> = emptyList(),
    ) {
        val processPIDs = getProcessPIDs(processName, asRoot, argFilters)
        if (processPIDs.isEmpty()) {
            Log.v(TAG, "killProcess: Found no running instances of [$processName]")
            return
        }
        for (processPID in processPIDs) {
            val exitCode = if (asRoot) {
                RootAccess.code("kill -SIGINT $processPID")
            } else {
                runShellCommand("kill -SIGINT $processPID\n")
            }
            if (exitCode != 0) {
                Log.w(TAG, "killProcess: Failed to send kill SIGINT to process [$processPID" +
                        "] exit code $exitCode")
            }
        }

        // Wait for the process to end. SIGINT is only a request: a process with blocked
        // signals or uninterruptible IO can ignore it indefinitely, which used to hang
        // this loop (and with it the whole shutdown path) forever. Escalate to SIGKILL
        // after a grace period, then give up with a warning.
        if (!awaitProcessExit(processName, asRoot, argFilters, KILL_SIGINT_GRACE_MS)) {
            Log.w(TAG, "killProcess: [$processName] still running after SIGINT grace " +
                    "period, escalating to SIGKILL")
            for (processPID in getProcessPIDs(processName, asRoot, argFilters)) {
                val exitCode = if (asRoot) {
                    RootAccess.code("kill -SIGKILL $processPID")
                } else {
                    runShellCommand("kill -SIGKILL $processPID\n")
                }
                if (exitCode != 0) {
                    Log.w(TAG, "killProcess: Failed to send kill SIGKILL to process [$processPID" +
                            "] exit code $exitCode")
                }
            }
            if (!awaitProcessExit(processName, asRoot, argFilters, KILL_SIGKILL_GRACE_MS)) {
                Log.w(TAG, "killProcess: Giving up, [$processName] still running after SIGKILL")
                return
            }
        }
        Log.d(TAG, "killProcess: No more instances of [$processName] running")
    }

    /**
     * Polls until no instance of [processName] matches anymore, or the timeout in
     * [timeoutMs] elapses. Returns true when all instances exited.
     */
    private fun awaitProcessExit(
        processName: String,
        asRoot: Boolean,
        argFilters: List<String>,
        timeoutMs: Long,
    ): Boolean {
        // Root lookups go through a su shell round-trip per poll; poll less often there.
        val pollIntervalMs = if (asRoot) 250L else 50L
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (getProcessPIDs(processName, asRoot, argFilters).isNotEmpty()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            SystemClock.sleep(pollIntervalMs)
        }
        return true
    }

    /**
     * Builds the web GUI URL from the given gui address (e.g. "127.0.0.1:8384").
     */
    fun buildWebGuiUrl(guiAddress: String): URL {
        try {
            return URL("https://$guiAddress")
        } catch (e: MalformedURLException) {
            throw RuntimeException("Failed to parse web interface URL", e)
        }
    }

    /**
     * Run command in a shell and return the exit code.
     */
    fun runShellCommand(cmd: String): Int {
        return runShellCommandInternal("runShellCommand", cmd, null)
    }

    /**
     * Run command in a shell and return the captured standard output.
     */
    fun runShellCommandGetOutput(cmd: String): String {
        val capturedStdOut = StringBuilder()
        runShellCommandInternal("runShellCommandGetOutput", cmd, capturedStdOut)
        return capturedStdOut.toString()
    }

    /**
     * Run command in a shell, optionally capturing its standard output.
     */
    private fun runShellCommandInternal(logTag: String, cmd: String, capturedStdOut: StringBuilder?): Int {
        // Assume "failure" exit code if an error is caught.
        // Note: redirectErrorStream(true); System.getProperty("line.separator");
        var exitCode = 255
        var shellProc: Process? = null
        var shellOut: DataOutputStream? = null
        try {
            shellProc = Runtime.getRuntime().exec("sh")
            shellOut = DataOutputStream(shellProc.getOutputStream())
            val bufferedWriter = BufferedWriter(OutputStreamWriter(shellOut))
            Log.d(TAG, "$logTag: $cmd")
            bufferedWriter.write(cmd)
            bufferedWriter.flush()
            shellOut.close()
            shellOut = null
            try {
                BufferedReader(InputStreamReader(shellProc.inputStream, StandardCharsets.UTF_8)).use { bufferedReader ->
                    while (true) {
                        val line = bufferedReader.readLine() ?: break
                        if (capturedStdOut == null) {
                            Log.v(TAG, "$logTag: $line")
                        } else {
                            capturedStdOut.append(line).append("\n")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "$logTag: Failed to read output", e)
            }
            exitCode = shellProc.waitFor()
            if (capturedStdOut != null && exitCode != 0) {
                Log.i(TAG, "$logTag: Exited with code $exitCode")
            }
        } catch (e: IOException) {
            Log.w(TAG, "$logTag: Exception", e)
        } catch (e: InterruptedException) {
            Log.w(TAG, "$logTag: Exception", e)
        } finally {
            try {
                shellOut?.close()
            } catch (e: IOException) {
                Log.w(TAG, "$logTag: Failed to close stream", e)
            }
            shellProc?.destroy()
        }
        return exitCode
    }

    /**
     * Returns true if some process is listening on the given TCP port on the loopback
     * interface.
     *
     * Probes with an actual localhost TCP connect instead of parsing /proc/net/tcp
     * (netstat): reading /proc/net/tcp is SELinux-denied for untrusted apps on many
     * devices, which made the old netstat-based check always report "port free" and
     * let a stale syncthing instance go unnoticed.
     *
     * Must not be called from the main thread (network op).
     */
    fun isTcpPortListening(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                true
            }
        } catch (e: IOException) {
            // Connection refused or timeout: nothing (reachable) is listening.
            false
        }
    }

    /**
     * Grace period after SIGINT before [killPortListenerAsRoot] escalates to SIGKILL.
     */
    private const val PORT_KILL_SIGINT_GRACE_MS = 3000L

    /**
     * Grace period after SIGKILL before [killPortListenerAsRoot] gives up waiting.
     */
    private const val PORT_KILL_SIGKILL_GRACE_MS = 2000L

    /**
     * Kills the process listening on [port] through the root shell - but only when it
     * is an instance of our own syncthing binary ([binaryName]).
     *
     * Last-resort cleanup for a stale root-uid core that survived an app force-stop and
     * that ps-based matching failed to find: whoever holds our WebUI port is almost
     * certainly that core, and the program name check makes sure a foreign app on the
     * same port is left alone. Returns true when a matching listener was found.
     * Must not be called from the main thread.
     */
    fun killPortListenerAsRoot(port: Int, binaryName: String): Boolean {
        if (!RootAccess.isSuAvailable()) {
            Log.i(TAG, "killPortListenerAsRoot: su unavailable, skipping port-owner kill")
            return false
        }
        val pids = parseRootListenerPids(
            RootAccess.out("netstat -tlnp").joinToString("\n"), port, binaryName
        )
        if (pids.isEmpty()) {
            Log.i(TAG, "killPortListenerAsRoot: no listener of our binary on port $port")
            return false
        }
        Log.w(TAG, "killPortListenerAsRoot: stale listener(s) $pids on port $port, sending SIGINT")
        for (pid in pids) {
            RootAccess.code("kill -SIGINT $pid")
        }
        if (!awaitTcpPortClosed(port, PORT_KILL_SIGINT_GRACE_MS)) {
            Log.w(TAG, "killPortListenerAsRoot: port $port still listening, escalating to SIGKILL")
            for (pid in parseRootListenerPids(
                RootAccess.out("netstat -tlnp").joinToString("\n"), port, binaryName
            )) {
                RootAccess.code("kill -SIGKILL $pid")
            }
            awaitTcpPortClosed(port, PORT_KILL_SIGKILL_GRACE_MS)
        }
        return true
    }

    /**
     * Polls until nothing listens on [port] anymore, or the timeout elapses.
     * Returns true when the port is free. Must not be called from the main thread.
     */
    private fun awaitTcpPortClosed(port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (isTcpPortListening(port)) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false
            }
            SystemClock.sleep(250)
        }
        return true
    }

    /**
     * Format a path properly.
     *
     * @param path String containing the path that needs formatting.
     * @return formatted file path as a string.
     */
    fun formatPath(path: String): String {
        return File(path).toURI().normalize().path
    }

    /**
     * Shorten a path using ellipsis to display it on UI
     * where we have little space to display it.
     */
    fun getPathEllipsis(fullFN: String): String {
        val MAX_CHARS_SUBDIR = 15
        val MAX_CHARS_FILENAME = MAX_CHARS_SUBDIR * 2

        var workIn = fullFN
        var workOut = ""
        while (true) {
            val index = workIn.indexOf('/')
            if (index < 0) {
                // Last part is the filename.
                if (workIn.length > MAX_CHARS_FILENAME) {
                    val indexFileExt = workIn.lastIndexOf(".")
                    if (indexFileExt > 0) {
                        // Filename with extension.
                        var fileName = workIn.substring(0, indexFileExt)
                        if (fileName.length > MAX_CHARS_FILENAME) {
                            fileName = fileName.substring(0, MAX_CHARS_FILENAME) + "\u22ef"
                        }
                        workIn = fileName + workIn.substring(indexFileExt)
                    } else {
                        // Filename without extension
                        workIn = workIn.substring(0, MAX_CHARS_FILENAME) + "\u22ef"
                    }
                }
                workOut += workIn
                break
            }
            // Handle one directory from the path.
            var part = workIn.substring(0, index)
            if (part.length > MAX_CHARS_SUBDIR) {
                part = part.substring(0, MAX_CHARS_SUBDIR) + "\u22ef"
            }
            workOut += "$part/"
            workIn = workIn.substring(index + 1)
        }
        return workOut
    }

    fun isRunningOnTV(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Converts dateTime to readable localized string.
     */
    fun formatDateTime(dateTime: String): String {
        // Convert dateTime to readable localized string.
        val parsedDateTime = ZonedDateTime.parse(dateTime)
        val zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
        return formatter.format(zonedDateTime)
    }

    fun formatTime(dateTime: String): String {
        // Convert dateTime to readable localized string.
        val parsedDateTime = ZonedDateTime.parse(dateTime)
        val zonedDateTime = parsedDateTime.withZoneSameInstant(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
        return formatter.format(zonedDateTime)
    }

    /**
     * Converts local time to ZonedDateTime.
     */
    fun getLocalZonedDateTime(): String {
        return ZonedDateTime.ofLocal(LocalDateTime.now(), ZoneId.of("UTC"), ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /**
     * Returns true if the given service class is currently running.
     * Note: getRunningServices() is deprecated and only returns a cached
     * snapshot on recent Android versions, which is fine for this check.
     */
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in am.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Quotes a string for safe use as a single shell argument.
     */
    fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     */
    fun runScriptSet(absPath: String, scriptArgs: Array<String>?) {
        val scriptFolder = File(absPath)
        if (!scriptFolder.exists() || !scriptFolder.isDirectory) {
            Log.w(TAG, "runScriptSet: Folder does not exist or is not of type folder: $absPath")
            return
        }

        // Find all script files within given folder path.
        val scriptFiles = scriptFolder.listFiles { _, name ->
            name.lowercase(Locale.ROOT).endsWith(".sh")
        }
        if (scriptFiles == null || scriptFiles.isEmpty()) {
            Log.v(TAG, "runScriptSet: No script files found within folder: $absPath")
            return
        }
        for (scriptFile in scriptFiles) {
            // Build arguments using shell escape.
            val cmdBuilder = StringBuilder()
            cmdBuilder.append("cd ").append(shellQuote("$absPath/..")).append(";")
            cmdBuilder.append("sh ").append(shellQuote(scriptFile.absolutePath))
            if (scriptArgs != null) {
                for (arg in scriptArgs) {
                    cmdBuilder.append(" ").append(shellQuote(arg))
                }
            }

            // Execute script.
            val command = cmdBuilder.toString()
            Log.v(TAG, "runScriptSet: Exec result [" + runShellCommandGetOutput(command) + "]")
        }
    }

    /**
     * Called by RestApi/setRemoteCompletionInfo after folder completed.
     */
    fun getSyncConflictFiles(absPath: String): Array<String> {
        val cmdBuilder = StringBuilder()
        cmdBuilder.append("cd ").append(shellQuote("$absPath/")).append(";")
        // Unescaped:
        //  find -type f -name "*\.sync-conflict-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]-[a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9]*" -not -path "\.\/\.stversions\/*" -print | sed "s~\.\/~~"
        cmdBuilder.append("find -type f -name \"*\\.sync-conflict-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]-[a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9][a-zA-Z0-9]*\" -not -path \"\\.\\/\\" + Constants.FOLDER_NAME_STVERSIONS + "\\/*\" -print | sed \"s~\\\\.\\/~~\"")
        val command = cmdBuilder.toString()
        val output = runShellCommandGetOutput(command)
        if (output.isEmpty()) {
            return arrayOf()
        }
        return output.split("\n".toRegex()).toTypedArray()
    }

    /**
     * Cached [X509TrustManager] backed by the Android OS trust store ("AndroidCAStore"),
     * which aggregates both the system CAs and the CAs the user manually installed. Built lazily.
     */
    @Volatile
    private var osTrustManager: X509TrustManager? = null

    @Volatile
    private var osTrustManagerInitialized = false

    /**
     * Returns an [X509TrustManager] that validates against the Android OS trust store,
     * including user-installed CAs. Unlike a `TrustManagerFactory.init((KeyStore) null)`,
     * the "AndroidCAStore" keystore exposes user-added certificates on API 24+.
     *
     * Used as a fallback so the app can talk to a local Syncthing instance whose HTTPS certificate
     * was replaced with one signed by a CA the user trusts at the OS level (see
     * https://github.com/researchxxl/syncthing-android/issues/222).
     *
     * @return the OS-backed trust manager, or `null` if it could not be built.
     */
    fun getOsTrustManager(): X509TrustManager? {
        if (!osTrustManagerInitialized) {
            synchronized(this) {
                if (!osTrustManagerInitialized) {
                    try {
                        val caStore = KeyStore.getInstance("AndroidCAStore")
                        caStore.load(null)
                        val tmf = TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm()
                        )
                        tmf.init(caStore)
                        for (tm in tmf.trustManagers) {
                            if (tm is X509TrustManager) {
                                osTrustManager = tm
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "getOsTrustManager: Failed to build OS trust manager", e)
                    }
                    osTrustManagerInitialized = true
                }
            }
        }
        return osTrustManager
    }
}

/**
 * Parses `ps` output into PIDs whose line contains [processName].
 *
 * Two column layouts exist: the app-shell `ps` (NAME column only, PID is the second
 * token, substring match) and the root-shell `ps -A -o pid,args` (PID first, exact match
 * on the first argument). The exact root match pairs with full-binary-path filters and
 * deliberately skips lines like `su -c <script>` whose arguments merely CONTAIN the
 * path — killing that wrapper instead of only the core reports a spurious 130 crash.
 *
 * With [argFilters] (root mode only), a matched line must additionally contain at least
 * one filter in its argument string - used to narrow generic process names like `find`
 * down to instances spawned for our own folders. An empty filter list disables the
 * narrowing (non-root lookups are UID-scoped anyway and always ignore the filter).
 */
internal fun parsePsOutput(
    output: String,
    processName: String,
    asRoot: Boolean,
    argFilters: List<String> = emptyList(),
): List<String> {
    val pidTokenIndex = if (asRoot) 0 else 1
    val processPIDs = mutableListOf<String>()
    for (line in output.split("\n".toRegex())) {
        if (asRoot) {
            val tokens = line.trim().split("\\s+".toRegex())
            if (tokens.size > 1 && tokens[1] == processName) {
                if (argFilters.isEmpty() ||
                    tokens.drop(2).joinToString(" ").let { args -> argFilters.any { it in args } }
                ) {
                    processPIDs.add(tokens[0])
                }
            }
        } else if (line.contains(processName)) {
            val tokens = line.trim().split("\\s+".toRegex())
            if (tokens.size > pidTokenIndex) {
                processPIDs.add(tokens[pidTokenIndex])
            }
        }
    }
    return processPIDs
}

/**
 * Extracts the PIDs of LISTEN sockets on [port] whose program name matches
 * [binaryName], from `netstat -tlnp` (root) output. Lines look like:
 *
 * `tcp   0   0 127.0.0.1:8384   0.0.0.0:*   LISTEN   1234/libsyncthingnative.so`
 *
 * Column layouts vary between netstat implementations (some insert User/Inode
 * columns before PID/Program), so the parser locates the LISTEN state token, takes
 * the local address two columns before it, and the PID/Program from the last column.
 */
internal fun parseRootListenerPids(netstatOutput: String, port: Int, binaryName: String): List<String> {
    val pids = mutableListOf<String>()
    for (line in netstatOutput.split("\n")) {
        val tokens = line.trim().split("\\s+".toRegex())
        val listenIdx = tokens.indexOf("LISTEN")
        if (listenIdx < 2) {
            continue
        }
        if (!tokens[listenIdx - 2].endsWith(":$port")) {
            continue
        }
        val pidAndProgram = tokens.last()
        if (!pidAndProgram.endsWith("/$binaryName")) {
            continue
        }
        val pid = pidAndProgram.substringBefore('/')
        if (pid.isNotEmpty()) {
            pids.add(pid)
        }
    }
    return pids
}
