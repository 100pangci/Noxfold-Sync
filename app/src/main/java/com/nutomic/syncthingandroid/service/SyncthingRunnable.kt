package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.RootAccess
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Runs the syncthing binary from command line, and prints its output to logcat.
 *
 * Coroutines model: SyncthingService drives the main binary through the suspend [execute]
 * (its job is joined after the native process is killed). The blocking [run] bridge is
 * reserved for synchronous one-shot commands. Stream pumping runs as coroutines on
 * Dispatchers.IO instead of raw threads; the pumps are joined right after waitFor,
 * preserving the old waitFor -> join ordering.
 *
 * Divergences from the old implementation (all intentional):
 *  - The exit code is always logged (Log.i), not only in verbose mode. This closes the old
 *    "traceless death" gap where a crashed native process left no exit code trail.
 *  - The when(exitCode) mapping is extracted into [exitDisposition] (unit-testable) and 137
 *    is no longer treated as a normal exit: a process killed with SIGKILL reports 137 and the
 *    old code swallowed that, leaving SyncthingService in a "fake ACTIVE" state while the
 *    native process was gone (silent sync stop, Web GUI unreachable). All normal shutdown
 *    paths exit with code 0, so 137 is now handled as a crash.
 *  - Guava Files/Charsets usages were replaced with kotlin stdlib file APIs.
 *  - Math.ceilDiv was replaced with a manual ceilDiv for the non-negative operands used
 *    here; Math.ceilDiv requires Android API 35+, which would break log trimming on older
 *    devices (latent issue, no core library desugaring is configured).
 */
class SyncthingRunnable(private val context: Context, command: Command) {

    lateinit var preferences: SharedPreferences

    lateinit var notificationHandler: NotificationHandler

    private val verboseLog: Boolean
    private val runAsRoot: Boolean
    private val logFile: File
    private val commandArgs: Array<String>

    init {
        val app = context.applicationContext as SyncthingApp
        preferences = app.preferences
        notificationHandler = app.notificationHandler
        verboseLog = AppPrefs.getPrefVerboseLog(preferences)
        runAsRoot = AppPrefs.getRunAsRoot(preferences)
        // Example: syncthingBinary="/data/app/${applicationId}-8HsN-IsVtZXc8GrE5-Hepw==/lib/x86/libsyncthingnative.so"
        val syncthingBinary = Constants.getSyncthingBinary(context)
        logFile = Constants.getSyncthingLogFile(context)

        // Get preferences relevant to starting syncthing core.
        commandArgs = when (command) {
            Command.deviceid -> arrayOf(syncthingBinary.path, "device-id")                  // Output the device ID to the command line.
            Command.generate -> arrayOf(syncthingBinary.path, "generate")                   // Generate keys, a config file and immediately exit.
            Command.main -> arrayOf(syncthingBinary.path, "serve", "--no-browser")          // Run the main Syncthing application.
            Command.resetdatabase -> arrayOf(syncthingBinary.path, "debug", "reset-database")   // Reset Syncthing's database
            Command.resetdeltas -> arrayOf(syncthingBinary.path, "serve", "--debug-reset-delta-idxs")   // Reset Syncthing's delta indexes
        }
    }

    enum class Command {
        deviceid,
        generate,
        main,
        resetdatabase,
        resetdeltas,
    }

    class ExecutableNotFoundException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, throwable: Throwable) : super(message, throwable)
    }

    /**
     * Set by [SyncthingService] while it kills this process as part of a planned
     * shutdown (stop or restart). A core whose graceful shutdown exceeds the kill
     * grace period is escalated to SIGKILL by our own killProcess and then exits with
     * 137 - which the exit mapping below would otherwise misreport as a crash
     * (notification + ERROR state) even though WE delivered the SIGKILL on purpose.
     */
    @Volatile
    var plannedShutdown = false
        private set

    fun markPlannedShutdown() {
        plannedShutdown = true
    }

    /**
     * Blocking one-shot command runner (generate, device-id, reset-database). The main
     * binary lifecycle uses the suspend [execute] on the service scope; this bridge only
     * exists for synchronous callers (ConfigXml, config import) and must therefore run on
     * a background thread.
     */
    @Throws(ExecutableNotFoundException::class)
    fun run(returnStdOut: Boolean): String = runBlocking { execute(returnStdOut) }

    /**
     * The suspend-based implementation shared by both blocking entry points.
     */
    @Throws(ExecutableNotFoundException::class)
    suspend fun execute(returnStdOut: Boolean): String {
        var sendStopToService = false
        var restartSyncthingNative = false
        var capturedStdOut = ""

        // Trim Syncthing log.
        trimSyncthingLogFile()

        var multicastLock: MulticastLock? = null
        var process: Process? = null
        try {
            // Android 11 blocks local discovery if we did not acquire MulticastLock.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("multicastLock")
            multicastLock.setReferenceCounted(true)
            multicastLock.acquire()

            /**
             * Setup and run a new syncthing instance
             */
            val launched = setupAndLaunch(buildEnvironment())
            process = launched
            syncthingProcess.set(launched)

            val exitCode = coroutineScope {
                val pumpJobs: List<Job> = if (returnStdOut) {
                    capturedStdOut = captureStdOut(launched)
                    emptyList()
                } else {
                    listOf(
                            launch(Dispatchers.IO) { pumpStream(launched.inputStream, Log.INFO) },
                            launch(Dispatchers.IO) { pumpStream(launched.errorStream, Log.WARN) },
                    )
                }
                val code = launched.waitFor()
                Log.i(TAG, "Syncthing exited with code $code")
                syncthingProcess.set(null)
                pumpJobs.forEach { it.join() }
                code
            }

            val disposition = exitDisposition(exitCode)
            if (disposition.logLevel == Log.INFO) {
                Log.i(TAG, disposition.logMessage)
            } else {
                Log.w(TAG, disposition.logMessage)
            }
            if (plannedShutdown && disposition.outcome == ExitOutcome.CRASH) {
                // Our own planned kill (SIGKILL escalation after the SIGINT grace
                // period) is not a crash: skip the crash notification and the
                // ACTION_STOP-with-crashed-extra, the shutdown flow already knows.
                Log.i(TAG, "Syncthing exited with code $exitCode during our planned " +
                        "shutdown; not treating it as a crash")
            } else when (disposition.outcome) {
                ExitOutcome.NORMAL -> {}
                ExitOutcome.RESTART -> {
                    // Restart was requested via Rest API call.
                    restartSyncthingNative = true
                }
                ExitOutcome.CRASH -> {
                    notificationHandler.showCrashedNotification(
                            R.string.notification_crash_title,
                            exitCode.toString()
                    )
                    sendStopToService = true
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
            // The binary never ran, so the service must not stay in State.ACTIVE.
            sendStopToService = true
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to execute syncthing binary or read output", e)
            // The binary never ran, so the service must not stay in State.ACTIVE.
            sendStopToService = true
        } finally {
            multicastLock?.release()
            process?.destroy()
        }

        // Restart syncthing if it exited unexpectedly while running on a separate thread.
        if (!returnStdOut && restartSyncthingNative) {
            context.startService(Intent(context, SyncthingService::class.java)
                    .setAction(SyncthingService.ACTION_RESTART))
        }

        // Notify SyncthingService that service state State.ACTIVE is no longer valid.
        if (!returnStdOut && sendStopToService) {
            val intent = Intent(context, SyncthingService::class.java)
            intent.action = SyncthingService.ACTION_STOP
            intent.putExtra(SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE, true)
            context.startService(intent)
        }

        // Return captured command line output.
        return capturedStdOut
    }

    private fun putCustomEnvironmentVariables(environment: MutableMap<String, String>, sp: SharedPreferences) {
        val customEnvironment = sp.getString(Constants.PREF_ENVIRONMENT_VARIABLES, null)
        if (customEnvironment.isNullOrEmpty()) {
            return
        }

        for (entry in customEnvironment.split(" ")) {
            val keyAndValue = entry.split("=", limit = 2)
            if (keyAndValue.size != 2 || keyAndValue[0].isEmpty()) {
                Log.w(TAG, "putCustomEnvironmentVariables: Ignoring malformed entry [$entry]")
                continue
            }
            if (!ENV_KEY_PATTERN.matches(keyAndValue[0])) {
                // POSIX env names only. A key like "A;reboot;" is inert in the app-UID
                // ProcessBuilder environment, but the root-mode launch script renders
                // entries as raw `export KEY=VALUE` lines where it would execute.
                Log.w(TAG, "putCustomEnvironmentVariables: Ignoring entry with invalid " +
                        "variable name [${keyAndValue[0]}]")
                continue
            }
            logV("Setting env var: [${keyAndValue[0]}]=[${keyAndValue[1]}]")
            environment[keyAndValue[0]] = keyAndValue[1]
        }
    }

    /**
     * Pumps one of the native process' streams into the syncthing log file. Always runs
     * regardless of the verbose preference, like the old implementation. The [priority]
     * parameter is kept for parity with the old (currently disabled) logcat output path.
     */
    private fun pumpStream(stream: InputStream, priority: Int) {
        var br: BufferedReader? = null
        try {
            br = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            while (true) {
                val line = br.readLine() ?: break
                // Always output SyncthingNative's output to "syncthing.log".
                logFile.appendText(line + "\n")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        } finally {
            try {
                br?.close()
            } catch (e: IOException) {
                Log.w(TAG, "log: Failed to close bufferedReader", e)
            }
        }
    }

    /**
     * Reads the process' stdout to the end (blocking, like the old implementation) and
     * returns the captured output. Only used for one-shot commands (device-id, generate).
     */
    private fun captureStdOut(process: Process): String {
        val captured = StringBuilder()
        var br: BufferedReader? = null
        try {
            br = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            while (true) {
                val line = br.readLine() ?: break
                Log.i(TAG_NATIVE, line)
                captured.append(line).append("\n")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Syncthing's command line output", e)
        } finally {
            // Deliberately unguarded like the old code: an IOException from close() must
            // reach the outer catch so the service is told the run did not finish cleanly.
            br?.close()
        }
        return captured.toString()
    }

    // If the nth last newline is found within this buffer, then the offset of that newline within
    // the buffer is returned. Otherwise, the negative of (nth - newlines consumed) is returned for
    // use with the new search.
    private fun findNthLastNewline(data: ByteArray, size: Int, nth: Int): Int {
        if (nth <= 0) {
            throw IllegalArgumentException("nth must be positive: $nth")
        }

        val newlineByte = '\n'.code.toByte()
        var newlinesRemaining = nth
        for (i in size - 1 downTo 0) {
            if (data[i] == newlineByte) {
                newlinesRemaining--

                if (newlinesRemaining == 0) {
                    return i
                }
            }
        }

        return -newlinesRemaining
    }

    /**
     * Only keep last [LOG_FILE_MAX_LINES] lines in log file, to avoid bloat.
     */
    internal fun trimSyncthingLogFile() {
        if (!logFile.exists()) {
            return
        }

        try {
            RandomAccessFile(logFile, "r").use { input ->
                // Find the offset of the (n + 1)th newline with constant memory. The last n lines
                // is everything after that point. This will read in block-aligned chunks if
                // LOG_FILE_BUFFER_SIZE is a multiple of the filesystem block size.
                val buf = ByteArray(LOG_FILE_BUFFER_SIZE)
                val length = input.length()
                val chunks = ceilDiv(length, buf.size.toLong())
                var newlinesRemaining = LOG_FILE_MAX_LINES + 1
                var truncationOffset = -1L

                for (chunk in chunks - 1 downTo 0) {
                    val offset = buf.size * chunk
                    input.seek(offset)

                    // Last chunk can be smaller than the whole buffer.
                    val n = min(length - offset, buf.size.toLong()).toInt()
                    input.readFully(buf, 0, n)

                    val ret = findNthLastNewline(buf, n, newlinesRemaining)
                    if (ret >= 0) {
                        truncationOffset = offset + ret + 1
                        break
                    } else {
                        newlinesRemaining = -ret
                    }
                }

                if (truncationOffset < 0) {
                    // The file already contains fewer than maximum lines.
                    return
                }

                input.seek(truncationOffset)

                val tempFile = File(context.filesDir.toString(), "syncthing.log.tmp")
                var remain = length - truncationOffset

                FileOutputStream(tempFile).use { output ->
                    while (remain > 0) {
                        val n = min(remain, buf.size.toLong()).toInt()

                        input.readFully(buf, 0, n)
                        output.write(buf, 0, n)

                        remain -= n
                    }
                }

                tempFile.renameTo(logFile)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to trim log file", e)
        }
    }

    /**
     * Replacement for Math.ceilDiv which requires Android API 35+; both operands are
     * non-negative here so the simple arithmetic form is equivalent.
     */
    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    private fun buildEnvironment(): HashMap<String, String> {
        val targetEnv = HashMap<String, String>()

        // Set home directory to data folder for web GUI folder picker.
        targetEnv["HOME"] = FileUtils.getSyncthingTildeAbsolutePath()

        // Set config, key and database directory.
        targetEnv["STHOMEDIR"] = context.filesDir.toString()
        targetEnv["STTRACE"] = preferences.getStringSet(
                Constants.PREF_DEBUG_FACILITIES_ENABLED,
                emptySet()
        )?.joinToString(" ").orEmpty()
        targetEnv["STMONITORED"] = "1"
        targetEnv["STNOUPGRADE"] = "1"
        targetEnv["STVERSIONEXTRA"] = context.getString(R.string.app_name)

        // Database tuning against slowness.
        targetEnv["SQLITE_TMPDIR"] = context.cacheDir.absolutePath

        // Workaround SyncthingNativeCode denied to read gatewayIP by Android 14+ restriction.
        getGatewayIpV4(context)?.let { gatewayIpV4 ->
            targetEnv["FALLBACK_NET_GATEWAY_IPV4"] = gatewayIpV4
        }

        if (preferences.getBoolean(Constants.PREF_USE_TOR, false)) {
            targetEnv["all_proxy"] = Constants.DEFAULT_TOR_SOCKS_PROXY_ADDRESS
            targetEnv["ALL_PROXY_NO_FALLBACK"] = "1"
        } else {
            val socksProxyAddress = preferences.getString(Constants.PREF_SOCKS_PROXY_ADDRESS, "")
            if (!socksProxyAddress.isNullOrEmpty()) {
                targetEnv["all_proxy"] = socksProxyAddress
            }

            val httpProxyAddress = preferences.getString(Constants.PREF_HTTP_PROXY_ADDRESS, "")
            if (!httpProxyAddress.isNullOrEmpty()) {
                targetEnv["http_proxy"] = httpProxyAddress
                targetEnv["https_proxy"] = httpProxyAddress
            }
        }

        // Go's default GC target percentage.
        logV("Setting env var: [GOGC]=[100]")
        targetEnv["GOGC"] = "100"

        putCustomEnvironmentVariables(targetEnv, preferences)
        return targetEnv
    }

    private fun setupAndLaunch(env: Map<String, String>): Process {
        // Check if "libsyncthingnative.so" exists.
        if (commandArgs.isNotEmpty()) {
            val libSyncthing = File(commandArgs[0])
            if (!libSyncthing.exists()) {
                Log.e(TAG, "CRITICAL - Syncthing core binary is missing in APK package location ${commandArgs[0]}")
                throw ExecutableNotFoundException(commandArgs[0])
            }
        }
        var launchedAsRoot = false
        val pb: ProcessBuilder = if (runAsRoot) {
            val suBinary = RootAccess.suBinaryPath()
            if (suBinary != null && RootAccess.isSuAvailable()) {
                // The umask 000 wrapper is load-bearing: the root-uid core creates app-shared
                // files (config.xml, cert.pem, logs, SAF-bridge staging dirs) that the
                // unprivileged app must stay able to read, write and delete.
                Log.i(TAG, "Root mode: launching syncthing core via root shell (umask 000)")
                launchedAsRoot = true
                ProcessBuilder(suBinary, "-c", buildRootLaunchScript(env, commandArgs))
            } else {
                Log.w(TAG, "Root mode enabled but su is unavailable or denied; " +
                        "falling back to an app-uid launch")
                ProcessBuilder(*commandArgs).also { it.environment().putAll(env) }
            }
        } else {
            // Hand root-session files back to the app UID before the unprivileged core
            // (or the app itself, e.g. key generation) needs them: root-written config
            // and key material carry explicit 0600 modes that umask cannot influence.
            if (RootAccess.appStorageOwnedByRoot(context)) {
                Log.i(TAG, "Previous core ran as root; returning app storage to app ownership")
                RootAccess.handBackStorage(context)
            }
            ProcessBuilder(*commandArgs).also { it.environment().putAll(env) }
        }
        val process = pb.start()
        // Record the privilege mode the running core actually uses: shutdown paths must
        // keep being able to kill and inspect it even after the user toggles the
        // preference while the core is up.
        AppPrefs.setLastCoreRunAsRoot(preferences, launchedAsRoot)
        return process
    }

    private fun logV(logMessage: String) {
        if (verboseLog) {
            Log.v(TAG, logMessage)
        }
    }

    companion object {
        private const val TAG = "SyncthingRunnable"
        private const val TAG_NATIVE = "SyncthingNativeCode"

        internal const val LOG_FILE_MAX_LINES = 200000
        private const val LOG_FILE_BUFFER_SIZE = 1024 * 1024

        private val syncthingProcess = AtomicReference<Process?>(null)

        fun getGatewayIpV4(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return null
            val props = cm.getLinkProperties(activeNetwork) ?: return null

            for (route in props.routes) {
                val gateway = route.gateway
                if (route.isDefaultRoute && gateway is Inet4Address) {
                    return gateway.hostAddress
                }
            }
            return null
        }
    }
}

/**
 * Valid POSIX environment variable names. Keys are validated against this before they
 * reach either the ProcessBuilder environment or the root-mode launch script.
 */
private val ENV_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Builds the shell script executed by `su -c` for a root-mode core launch.
 *
 * Environment variables are re-exported inside the script (instead of relying on su
 * passing the parent environment through), then umask 000 is set so that every file the
 * root-uid core creates stays writable for the unprivileged app, and finally the binary
 * is exec'd (replacing the shell so signals and the exit code reach it directly).
 */
internal fun buildRootLaunchScript(env: Map<String, String>, commandArgs: Array<String>): String {
    val singleQuoted = { value: String -> "'" + value.replace("'", "'\\''") + "'" }
    // Keys are quoted too (export 'FOO'=... is valid shell): parsing is supposed to have
    // validated them against ENV_KEY_PATTERN, but the script must stay inert even for a
    // key like "A;reboot;" so a parsing gap cannot turn into a root shell injection.
    val exports = env.entries.joinToString("") { (key, value) ->
        "export ${singleQuoted(key)}=${singleQuoted(value)}\n"
    }
    val execLine = commandArgs.joinToString(" ") { arg -> singleQuoted(arg) }
    return "${exports}umask 000\nexec $execLine"
}

/**
 * Outcome of the when(exitCode) mapping, adapted from the Java implementation.
 */
internal enum class ExitOutcome {
    NORMAL,     // 0: shut down normally via API or graceful SIGINT.
    CRASH,      // 1 / 2 / 9 / 64 / 137 / default: crash notification + stop the service.
    RESTART,    // 3: exitRestarting -> ACTION_RESTART self-restart.
}

internal data class ExitDisposition(
        val logLevel: Int,
        val logMessage: String,
        val outcome: ExitOutcome,
)

/**
 * when(exitCode) mapping, adapted from the Java implementation.
 *
 * Divergence from the old code: 137 is no longer treated as a normal exit. A process killed
 * with SIGKILL reports 137 (128 + 9); the old code swallowed that and left SyncthingService
 * in a "fake ACTIVE" state while the native process was gone (silent sync stop, Web GUI
 * unreachable). All normal shutdown paths (Service Util.killProcess sends SIGINT, RestApi
 * shutdown goes through the REST API) exit with code 0, so 137 can only mean the process was
 * force-killed and is now treated as a crash: crash notification + ACTION_STOP with
 * EXTRA_STOP_AFTER_CRASHED_NATIVE, which tears the service down to State.DISABLED.
 */
internal fun exitDisposition(exitCode: Int): ExitDisposition = when (exitCode) {
    0 -> ExitDisposition(
            Log.INFO,
            "Syncthing was shut down normally via API or SIGKILL. Exit code = $exitCode",
            ExitOutcome.NORMAL
    )
    1 -> ExitDisposition(
            Log.WARN,
            "exit reason = exitError. Another Syncthing instance may be already running.",
            ExitOutcome.CRASH
    )
    2 -> ExitDisposition(
            Log.WARN,
            "exit reason = exitNoUpgradeAvailable. Another Syncthing instance may be already running.",
            ExitOutcome.CRASH
    )
    3 -> ExitDisposition(
            Log.INFO,
            "exit reason = exitRestarting. Restarting syncthing.",
            ExitOutcome.RESTART
    )
    9, 137 -> ExitDisposition(
            Log.WARN,
            "exit reason = exitForceKill. Syncthing was force killed (SIGKILL).",
            ExitOutcome.CRASH
    )
    64 -> ExitDisposition(
            Log.WARN,
            "exit reason = exitInvalidCommandLine.",
            ExitOutcome.CRASH
    )
    else -> ExitDisposition(
            Log.WARN,
            "Syncthing exited unexpectedly. Exit code = $exitCode",
            ExitOutcome.CRASH
    )
}
