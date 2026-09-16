package com.nutomic.syncthingandroid.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.service.SyncthingRunnable.Command
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Unit tests for the coroutines-based SyncthingRunnable (phase5).
 *
 * Each test runs the real JVM ProcessBuilder against a fake "libsyncthingnative.so"
 * shell script placed in the app's nativeLibraryDir, so the whole launch / stream
 * pumping / exit code handling path is exercised for real. The exit code is selected
 * by the script body, e.g. "exit 3".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SyncthingApp::class)
class SyncthingRunnableTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var libDir: File
    private lateinit var binary: File

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        libDir = Files.createTempDirectory("syncthing-phase5-test").toFile()
        // Point the app's native library dir at the fake binary location.
        // Constants.getSyncthingBinary() reads applicationInfo.nativeLibraryDir.
        context.applicationInfo.nativeLibraryDir = libDir.absolutePath
        binary = File(libDir, Constants.FILENAME_SYNCTHING_BINARY)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        libDir.deleteRecursively()
        File(context.filesDir, "syncthing.log").delete()
        File(context.filesDir, "syncthing.log.tmp").delete()
    }

    /**
     * Creates the fake native binary. Shell variables must be escaped for the test
     * source itself, e.g. "echo \"HOME=\\\$HOME\"" writes `echo "HOME=$HOME"` to the script.
     */
    private fun writeBinary(body: String) {
        binary.writeText("#!/bin/sh\n$body\n")
        binary.setExecutable(true)
    }

    private fun newRunnable(command: Command): SyncthingRunnable = SyncthingRunnable(context, command)

    private fun nextStartedService(): Intent? = shadowOf(context as Application).nextStartedService

    private fun notificationCount(): Int = shadowOf(notificationManager).allNotifications.size

    private fun lastNotificationTitle(): String {
        val notification = shadowOf(notificationManager).allNotifications.last()
        return notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
    }

    @Test
    fun exitDisposition_coversEveryLegacyBranch() {
        assertEquals(ExitOutcome.NORMAL, exitDisposition(0).outcome)
        assertEquals(ExitOutcome.RESTART, exitDisposition(3).outcome)
        for (code in intArrayOf(1, 2, 9, 64, 137)) {
            assertEquals("code $code", ExitOutcome.CRASH, exitDisposition(code).outcome)
        }
        // Unknown codes fall into the default branch.
        for (code in intArrayOf(4, 42, 255)) {
            assertEquals("code $code", ExitOutcome.CRASH, exitDisposition(code).outcome)
        }
        // Per-code log reasons preserved verbatim from the Java implementation.
        assertEquals(Log.INFO, exitDisposition(0).logLevel)
        assertEquals(Log.WARN, exitDisposition(1).logLevel)
        assertEquals(Log.INFO, exitDisposition(3).logLevel)
        assertTrue(exitDisposition(1).logMessage.contains("exitError"))
        assertTrue(exitDisposition(2).logMessage.contains("exitNoUpgradeAvailable"))
        assertTrue(exitDisposition(3).logMessage.contains("exitRestarting"))
        assertTrue(exitDisposition(9).logMessage.contains("exitForceKill"))
        assertTrue(exitDisposition(137).logMessage.contains("exitForceKill"))
        assertTrue(exitDisposition(64).logMessage.contains("exitInvalidCommandLine"))
        assertTrue(exitDisposition(42).logMessage.contains("exited unexpectedly"))
    }

    @Test
    fun coreEnvironment_isAppliedToNativeProcess() {
        writeBinary("echo \"STHOMEDIR=\$STHOMEDIR\"; echo \"STNOUPGRADE=\$STNOUPGRADE\"; " +
                "echo \"STMONITORED=\$STMONITORED\"; echo \"STVERSIONEXTRA=\$STVERSIONEXTRA\"; " +
                "echo \"GOGC=\$GOGC\"")
        val out = newRunnable(Command.main).run(true)
        val lines = out.lines()
        assertEquals("STHOMEDIR=${context.filesDir.path}", lines[0])
        assertEquals("STNOUPGRADE=1", lines[1])
        assertEquals("STMONITORED=1", lines[2])
        assertEquals("STVERSIONEXTRA=${context.getString(R.string.app_name)}", lines[3])
        assertEquals("GOGC=100", lines[4])   // SDK 34 >= O, so the GO default applies.
    }

    @Test
    fun debugFacilitiesPreference_isPassedViaSttrace() {
        prefs.edit().putStringSet(Constants.PREF_DEBUG_FACILITIES_ENABLED, setOf("syncthing", "model")).commit()
        writeBinary("echo \"STTRACE=\$STTRACE\"")
        val out = newRunnable(Command.main).run(true)
        val sttrace = out.removePrefix("STTRACE=").trim()
        assertTrue("STTRACE was [$sttrace]", sttrace.contains("syncthing"))
        assertTrue("STTRACE was [$sttrace]", sttrace.contains("model"))
    }

    @Test
    fun customEnvironmentVariables_areInjectedAndMalformedEntriesIgnored() {
        // " =emptykey" and "NOEQUALS" are malformed and must be ignored without breaking the launch.
        prefs.edit().putString(Constants.PREF_ENVIRONMENT_VARIABLES, "ST_TEST_VAR=hello  =emptykey NOEQUALS").commit()
        writeBinary("echo \"VAR=\$ST_TEST_VAR\"")
        val out = newRunnable(Command.main).run(true)
        assertEquals("VAR=hello\n", out)
    }

    @Test
    fun torPreference_setsSocksProxyEnvironment() {
        prefs.edit().putBoolean(Constants.PREF_USE_TOR, true).commit()
        writeBinary("echo \"PROXY=\$all_proxy\"; echo \"NOFALLBACK=\$ALL_PROXY_NO_FALLBACK\"")
        val out = newRunnable(Command.main).run(true)
        assertEquals("PROXY=${Constants.DEFAULT_TOR_SOCKS_PROXY_ADDRESS}\nNOFALLBACK=1\n", out)
    }

    @Test
    fun proxyPreferences_areAppliedWhenTorDisabled() {
        prefs.edit()
                .putString(Constants.PREF_SOCKS_PROXY_ADDRESS, "socks5://127.0.0.1:9050")
                .putString(Constants.PREF_HTTP_PROXY_ADDRESS, "http://127.0.0.1:8118")
                .commit()
        writeBinary("echo \"SOCKS=\$all_proxy\"; echo \"HTTP=\$http_proxy\"; echo \"HTTPS=\$https_proxy\"")
        val out = newRunnable(Command.main).run(true)
        assertEquals("SOCKS=socks5://127.0.0.1:9050\nHTTP=http://127.0.0.1:8118\nHTTPS=http://127.0.0.1:8118\n", out)
    }

    @Test
    fun normalExitCodes_takeNoServiceAction() {
        for (exitCode in intArrayOf(0)) {
            writeBinary("exit $exitCode")
            newRunnable(Command.main).run(returnStdOut = false)
            assertNull("exit $exitCode: unexpected service intent", nextStartedService())
            // No crash notification (id 9) is posted for normal exits.
            assertFalse("exit $exitCode: unexpected crash notification", notificationCount() > 0)
        }
    }

    @Test
    fun restartExitCode_requestsServiceRestart() {
        writeBinary("exit 3")
        newRunnable(Command.main).run(returnStdOut = false)
        val intent = nextStartedService()
        assertNotNull(intent)
        assertEquals(SyncthingService.ACTION_RESTART, intent!!.action)
        // Exactly one service intent was started.
        assertNull(nextStartedService())
    }

    @Test
    fun plannedShutdownSuppressesCrashReportingOnForcedExit() {
        // killProcess escalates to SIGKILL when a planned shutdown's graceful exit
        // exceeds the grace period; the resulting 137 must not surface as a crash.
        val runnable = newRunnable(Command.main)
        runnable.markPlannedShutdown()
        writeBinary("exit 137")
        runnable.run(returnStdOut = false)
        assertNull("planned shutdown: unexpected stop intent", nextStartedService())
        assertFalse("planned shutdown: unexpected crash notification", notificationCount() > 0)
    }

    @Test
    fun crashExitCodes_stopServiceWithExtraAndShowCrashNotification() {
        for (exitCode in intArrayOf(1, 2, 9, 64, 137, 7)) {   // 7 covers the default branch; 137 = SIGKILL.
            writeBinary("exit $exitCode")
            newRunnable(Command.main).run(returnStdOut = false)
            val intent = nextStartedService()
            assertNotNull("exit $exitCode: stop intent missing", intent)
            assertEquals(SyncthingService.ACTION_STOP, intent!!.action)
            assertTrue(
                    "exit $exitCode: stop intent must carry EXTRA_STOP_AFTER_CRASHED_NATIVE",
                    intent.getBooleanExtra(SyncthingService.EXTRA_STOP_AFTER_CRASHED_NATIVE, false)
            )
            // The crash notification (id 9) is replaced on every crash, so assert on the
            // latest posted notification instead of counting.
            assertTrue("exit $exitCode: crash notification missing", notificationCount() >= 1)
            assertTrue(
                    "exit $exitCode: notification title was [${lastNotificationTitle()}]",
                    lastNotificationTitle().contains(exitCode.toString())
            )
        }
        assertNull(nextStartedService())
    }

    @Test
    fun runWithStdOutCapture_returnsCapturedOutputAndTakesNoAction() {
        writeBinary("echo hello-stdout; echo noise >&2")
        val out = newRunnable(Command.deviceid).run(true)
        assertEquals("hello-stdout\n", out)
        assertNull(nextStartedService())
        // The native output is not written to the log file in capture mode (old behavior).
        assertFalse(File(context.filesDir, "syncthing.log").exists())
    }

    @Test
    fun serviceIntents_areSuppressedWhenStdOutCaptured() {
        // exitRestarting: no ACTION_RESTART while capturing stdout.
        writeBinary("exit 3")
        newRunnable(Command.main).run(true)
        assertNull(nextStartedService())

        // Crashed exit: the notification is not gated by returnStdOut (old behavior),
        // but the stop intent is.
        writeBinary("exit 1")
        newRunnable(Command.main).run(true)
        assertNull(nextStartedService())
        assertTrue(
                "crash notification missing: title [${lastNotificationTitle()}]",
                notificationCount() > 0 && lastNotificationTitle().contains("1")
        )
    }

    @Test
    fun missingBinary_throwsExecutableNotFoundExceptionWithBinaryPath() {
        try {
            newRunnable(Command.main).run(true)
            fail("Expected ExecutableNotFoundException")
        } catch (e: SyncthingRunnable.ExecutableNotFoundException) {
            assertEquals(binary.absolutePath, e.message)
        }
    }

    @Test
    fun nativeOutput_isAppendedToSyncthingLogFile() {
        writeBinary("echo out-line-abc; echo err-line-xyz >&2")
        newRunnable(Command.main).run(returnStdOut = false)
        val content = File(context.filesDir, "syncthing.log").readText()
        assertTrue("stdout line missing in [${content.take(200)}]", content.contains("out-line-abc"))
        assertTrue("stderr line missing in [${content.take(200)}]", content.contains("err-line-xyz"))
    }

    @Test
    fun trimLogFile_keepsOnlyLastAllowedLines() {
        val logFile = File(context.filesDir, "syncthing.log")
        logFile.bufferedWriter().use { writer ->
            for (i in 1..SyncthingRunnable.LOG_FILE_MAX_LINES + 5) {
                writer.write("line-$i\n")
            }
        }
        newRunnable(Command.main).trimSyncthingLogFile()
        val lines = logFile.readLines()
        assertEquals(SyncthingRunnable.LOG_FILE_MAX_LINES, lines.size)
        assertEquals("line-6", lines.first())
        assertEquals("line-${SyncthingRunnable.LOG_FILE_MAX_LINES + 5}", lines.last())
    }

    @Test
    fun trimLogFile_shortFileIsUntouched() {
        val logFile = File(context.filesDir, "syncthing.log")
        logFile.writeText("a\nb\nc\n")
        newRunnable(Command.main).trimSyncthingLogFile()
        assertEquals("a\nb\nc\n", logFile.readText())
    }

    @Test
    fun exitCode_isAlwaysLoggedRegardlessOfVerbosePreference() {
        // Phase5 fix for the "traceless death" gap: the exit code trail must not depend
        // on the verbose log preference.
        writeBinary("exit 7")
        newRunnable(Command.main).run(returnStdOut = false)
        val runnableLogs = ShadowLog.getLogs().filter { it.tag == "SyncthingRunnable" }
        assertTrue(
                "exit code log missing: ${runnableLogs.map { it.msg }}",
                runnableLogs.any { it.msg!!.contains("Syncthing exited with code 7") }
        )
        // The default-branch reason is logged as a warning with the legacy wording.
        assertTrue(
                runnableLogs.any { it.msg!!.contains("Syncthing exited unexpectedly. Exit code = 7") }
        )
    }

    @Test
    fun rootLaunchScript_exportsEnvSetsUmaskAndExecsQuotedArgs() {
        val script = buildRootLaunchScript(
                mapOf("HOME" to "/data/user/0/pkg/files", "STTRACE" to "a b"),
                arrayOf("/path/to/libsyncthingnative.so", "serve", "--no-browser"),
        )
        assertTrue(script.contains("export 'HOME'='/data/user/0/pkg/files'\n"))
        assertTrue(script.contains("export 'STTRACE'='a b'\n"))
        assertTrue(script.contains("umask 000\n"))
        assertTrue(script.contains("exec '/path/to/libsyncthingnative.so' 'serve' '--no-browser'"))
        // exec must be the last statement so signals and the exit code reach the binary.
        assertTrue(script.trimEnd().endsWith("exec '/path/to/libsyncthingnative.so' 'serve' '--no-browser'"))
    }

    @Test
    fun rootLaunchScript_escapesSingleQuotesInValues() {
        val script = buildRootLaunchScript(
                mapOf("STVERSIONEXTRA" to "it's a fork"),
                arrayOf("/bin"),
        )
        assertTrue(script.contains("export 'STVERSIONEXTRA'='it'\\''s a fork'"))
    }

    @Test
    fun rootLaunchScript_quotesKeysSoMalformedNamesStayInert() {
        // Parsing (putCustomEnvironmentVariables) is supposed to reject keys like this,
        // but the script must stay inert even if such a key ever reaches it: unquoted,
        // `export A;reboot;='x'` would execute `reboot` as root.
        val script = buildRootLaunchScript(
                mapOf("A;reboot;" to "x"),
                arrayOf("/bin"),
        )
        assertTrue(script.contains("export 'A;reboot;'='x'\n"))
    }

    @Test
    fun customEnvironmentVariables_rejectEntriesWithInvalidKeyNames() {
        // "A;reboot;=x" carries a shell-metacharacter variable name; it must be dropped
        // entirely (it would execute as root in the root-mode launch script) while the
        // valid entry still lands in the environment.
        prefs.edit().putString(
                Constants.PREF_ENVIRONMENT_VARIABLES, "ST_TEST_VAR=hello A;reboot;=x"
        ).commit()
        writeBinary("echo \"VAR=\$ST_TEST_VAR\"; echo \"A=\$A\"")
        val out = newRunnable(Command.main).run(true)
        assertEquals("VAR=hello\nA=\n", out)
    }
}
