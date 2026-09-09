package com.nutomic.syncthingandroid.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Root (su) availability gate for the optional "run Syncthing as root" feature.
 *
 * The first call spawns the su shell, which may surface the Magisk grant dialog; always
 * call from a background thread. The result is intentionally NOT cached: users can grant
 * or revoke the authorization between calls, and the probing cost only matters on
 * explicit user actions (toggling the setting, browsing folders, starting the core).
 */
object RootAccess {

    private const val TAG = "RootAccess"

    /**
     * Returns true if a root shell could be obtained and granted. On non-rooted devices
     * libsu falls back to an unprivileged shell, which yields false here.
     */
    fun isSuAvailable(): Boolean {
        Shell.getShell()
        return Shell.isAppGrantedRoot() == true
    }

    /**
     * Absolute path of the su binary (via the root shell's PATH), or null if su could not
     * be resolved. ProcessBuilder cannot rely on the app's PATH covering mounts like
     * /product/bin or /sbin, so the launch path resolves it explicitly.
     */
    fun suBinaryPath(): String? {
        return Shell.cmd("command -v su").exec().out
            .firstOrNull { it.isNotBlank() }
    }

    /** Runs the command in the shared root shell; returns its exit code. */
    fun code(cmd: String): Int {
        return Shell.cmd(cmd).exec().code
    }

    /** Runs the command in the shared root shell; returns its stdout lines. */
    fun out(cmd: String): List<String> {
        return Shell.cmd(cmd).exec().out
    }

    /**
     * Hands the app's private storage back to the app UID after a root-mode session.
     *
     * The root-uid core writes security-sensitive files with explicit restrictive modes
     * (syncthing saves config.xml and the key material as 0600), which the umask 000
     * wrapper cannot influence. Without this, the unprivileged app and the non-root core
     * can no longer read their own config and key material ("config read failed" /
     * "key generation failed" after switching root off).
     */
    fun handBackStorage(context: Context): Boolean {
        val uid = android.os.Process.myUid()
        var ok = true
        for (dir in listOf(context.filesDir, context.cacheDir)) {
            val quoted = "'" + dir.absolutePath.replace("'", "'\\''") + "'"
            if (code("chown -R ${uid}:${uid} $quoted") != 0) {
                ok = false
            }
            // Best-effort SELinux relabel: some su environments (SELinux type transitions in
            // the root daemon's policy) leave files created by the root-uid core with a type
            // the app domain cannot read, which ownership alone cannot fix. restorecon reverts
            // them to the policy default for the path (app_data_file). It is a no-op where the
            // type is already correct, and failures are ignored: the binary is not present in
            // every su environment, so it must never fail the ownership handback.
            if (code("restorecon -R $quoted") != 0) {
                Log.w(TAG, "handBackStorage: restorecon failed for ${dir.absolutePath} (ignored)")
            }
        }
        return ok
    }

    /**
     * True when app-private files are owned by another UID (i.e. the root-uid core left
     * them behind), so [handBackStorage] is needed. Uses the config file's actual owner
     * rather than any preference marker: a failed non-root launch must not mask files
     * that a previous root session left behind. stat() metadata is readable by the app
     * even for root-owned 0600 files inside its own data directory.
     */
    fun appStorageOwnedByRoot(context: Context): Boolean {
        val config = java.io.File(context.filesDir, "config.xml")
        if (!config.exists()) {
            return false
        }
        return try {
            android.system.Os.stat(config.absolutePath).st_uid != android.os.Process.myUid()
        } catch (e: Exception) {
            false
        }
    }
}
