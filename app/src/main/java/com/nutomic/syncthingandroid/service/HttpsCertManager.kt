package com.nutomic.syncthingandroid.service

import android.util.Log

import com.nutomic.syncthingandroid.service.SyncthingService.HttpsCertReplaceResult
import com.nutomic.syncthingandroid.service.SyncthingService.OnHttpsCertReplaceResultListener
import com.nutomic.syncthingandroid.service.SyncthingService.OnServiceStateChangeListener
import com.nutomic.syncthingandroid.service.SyncthingService.State

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the Web GUI HTTPS certificate replacement/reset logic:
 * stopping the binary, swapping or deleting the cert/key files, verifying that
 * Syncthing comes back online and rolling back to the previous certificate on failure.
 *
 * All lifecycle operations run on the main thread, marshalled via [replaceHttpsCertificate]
 * onto the service scope (Dispatchers.Main.immediate).
 */
class HttpsCertManager(private val service: SyncthingService, private val scope: CoroutineScope) {

    /**
     * Replaces the Web GUI HTTPS certificate and key with the supplied PEM bytes, then restarts
     * Syncthing so the new files take effect. If the restart fails to bring the Web GUI back online,
     * the previous certificate and key are restored automatically.
     *
     * The bytes are expected to already be validated (see [com.nutomic.syncthingandroid.util.CertificateValidator]).
     * The whole start/stop lifecycle is marshalled onto the main thread because the binary
     * orchestration fields are only safe to touch there.
     */
    fun replaceHttpsCertificate(certPem: ByteArray, keyPem: ByteArray,
                                listener: OnHttpsCertReplaceResultListener) {
        scope.launch { doReplaceHttpsCertificate(certPem, keyPem, listener) }
    }

    /**
     * Deletes the user-supplied HTTPS certificate/key so Syncthing regenerates a fresh self-signed
     * certificate at the next start, then restarts (with rollback on failure).
     */
    fun resetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        scope.launch { doResetHttpsCertificate(listener) }
    }

    private fun doReplaceHttpsCertificate(certPem: ByteArray, keyPem: ByteArray,
                                          listener: OnHttpsCertReplaceResultListener) {
        // shutdown() defers while STARTING; wait it out so our file writes don't race the binary.
        if (service.currentState == State.STARTING) {
            scope.launch {
                delay(DEFERRED_RETRY_DELAY_MS)
                doReplaceHttpsCertificate(certPem, keyPem, listener)
            }
            return
        }

        val certFile = Constants.getHttpsCertFile(service)
        val keyFile = Constants.getHttpsKeyFile(service)

        // Stop the binary so it releases the cert/key before we overwrite them. The binary's
        // kill/join runs on the service's shutdown executor; continue once it has exited.
        val continueReplace = {
            backupWriteAndVerify(certFile, keyFile, certPem, keyPem, listener)
        }
        if (service.currentState != State.DISABLED) {
            service.shutdownToState(State.DISABLED) { continueReplace() }
        } else {
            continueReplace()
        }
    }

    private fun backupWriteAndVerify(certFile: File, keyFile: File, certPem: ByteArray, keyPem: ByteArray,
                                     listener: OnHttpsCertReplaceResultListener) {
        val certBak = backupFile(certFile)
        val keyBak = backupFile(keyFile)

        try {
            writeBytesAtomic(certFile, certPem)
            writeBytesAtomic(keyFile, keyPem)
            restrictToOwner(keyFile)
        } catch (e: IOException) {
            Log.e(TAG, "doReplaceHttpsCertificate: Failed to write new cert/key", e)
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            if (service.shouldRunAfterRestart()) {
                service.launchStartupTask(SyncthingRunnable.Command.main)
            }
            listener.onResult(HttpsCertReplaceResult.FAILED, e.message)
            return
        }

        applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, listener)
    }

    private fun doResetHttpsCertificate(listener: OnHttpsCertReplaceResultListener) {
        if (service.currentState == State.STARTING) {
            scope.launch {
                delay(DEFERRED_RETRY_DELAY_MS)
                doResetHttpsCertificate(listener)
            }
            return
        }

        val certFile = Constants.getHttpsCertFile(service)
        val keyFile = Constants.getHttpsKeyFile(service)

        val continueReset = {
            val certBak = backupFile(certFile)
            val keyBak = backupFile(keyFile)
            // Removing the files makes syncthing generate a fresh self-signed certificate at startup.
            deleteQuietly(certFile)
            deleteQuietly(keyFile)

            applyCertChangeWithVerify(certFile, keyFile, certBak, keyBak, listener)
        }
        if (service.currentState != State.DISABLED) {
            service.shutdownToState(State.DISABLED) { continueReset() }
        } else {
            continueReset()
        }
    }

    private fun applyCertChangeWithVerify(certFile: File, keyFile: File,
                                          certBak: File?, keyBak: File?,
                                          listener: OnHttpsCertReplaceResultListener) {
        if (service.shouldRunAfterRestart()) {
            verifyRestartAndRollback(certFile, keyFile, certBak, keyBak, listener)
        } else {
            // Not currently meant to run; the new files will take effect on next start.
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
            listener.onResult(HttpsCertReplaceResult.SUCCESS_PENDING_START, null)
        }
    }

    /**
     * Restarts the binary and watches the service state: success on reaching ACTIVE, failure on
     * ERROR / an abnormal STARTING&rarr;DISABLED transition (crashed binary) / a watchdog timeout.
     * On failure the backed-up cert/key are restored and a known-good instance is brought back up.
     */
    private fun verifyRestartAndRollback(certFile: File, keyFile: File,
                                         certBak: File?, keyBak: File?,
                                         listener: OnHttpsCertReplaceResultListener) {
        var resolved = false
        var sawStarting = false
        var verifyListener: OnServiceStateChangeListener? = null
        var watchdogJob: Job? = null

        val finishSuccess = {
            deleteQuietly(certBak)
            deleteQuietly(keyBak)
            listener.onResult(HttpsCertReplaceResult.SUCCESS, null)
            Unit
        }
        val finishFailure = {
            restoreFile(certBak, certFile)
            restoreFile(keyBak, keyFile)
            // Bring the previous, known-good certificate back online.
            val restartWithPreviousCertificate = {
                service.launchStartupTask(SyncthingRunnable.Command.main)
                listener.onResult(HttpsCertReplaceResult.FAILED,
                    "Syncthing did not come online with the new certificate.")
            }
            if (service.currentState != State.DISABLED && service.currentState != State.INIT) {
                service.shutdownToState(State.INIT) { restartWithPreviousCertificate() }
            } else {
                restartWithPreviousCertificate()
            }
            Unit
        }

        verifyListener = OnServiceStateChangeListener { state ->
            if (resolved) {
                return@OnServiceStateChangeListener
            }
            if (state == State.STARTING) {
                sawStarting = true
                return@OnServiceStateChangeListener
            }
            val success = state == State.ACTIVE
            val failure = state == State.ERROR || (sawStarting && state == State.DISABLED)
            if (!success && !failure) {
                return@OnServiceStateChangeListener
            }
            resolved = true
            watchdogJob?.cancel()
            // Defer unregister + lifecycle work out of onServiceStateChange's listener iteration.
            scope.launch {
                service.unregisterOnServiceStateChangeListener(verifyListener!!)
                if (success) {
                    finishSuccess()
                } else {
                    finishFailure()
                }
            }
        }

        // registerOnServiceStateChangeListener replays the current state (DISABLED) synchronously;
        // that is ignored because sawStarting is still false.
        service.registerOnServiceStateChangeListener(verifyListener)
        watchdogJob = scope.launch {
            delay(HTTPS_CERT_VERIFY_TIMEOUT_MS)
            if (resolved) {
                return@launch
            }
            resolved = true
            service.unregisterOnServiceStateChangeListener(verifyListener!!)
            if (service.currentState == State.ACTIVE) {
                finishSuccess()
            } else {
                finishFailure()
            }
        }
        service.launchStartupTask(SyncthingRunnable.Command.main)
    }

    private fun backupFile(file: File): File? {
        if (!file.exists()) {
            return null
        }
        val bak = File(file.parentFile, file.name + ".bak")
        deleteQuietly(bak)
        if (file.renameTo(bak)) {
            return bak
        }
        Log.w(TAG, "backupFile: Failed to back up " + file.name)
        return null
    }

    private fun restoreFile(bak: File?, target: File) {
        if (bak == null || !bak.exists()) {
            return
        }
        deleteQuietly(target)
        if (!bak.renameTo(target)) {
            Log.w(TAG, "restoreFile: Failed to restore " + target.name)
        }
    }

    private fun deleteQuietly(file: File?) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "deleteQuietly: Failed to delete " + file.name)
        }
    }

    private fun writeBytesAtomic(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(data)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            deleteQuietly(tmp)
            throw IOException("Failed to rename " + tmp.name + " to " + target.name)
        }
    }

    private fun restrictToOwner(file: File) {
        // Mirror syncthing core, which writes the HTTPS key with 0600 permissions.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    companion object {
        private const val TAG = "HttpsCertManager"

        /**
         * Backstop timeout for [verifyRestartAndRollback] in case the state machine never reaches
         * a terminal state (e.g. the binary crashed via a path that doesn't transition to ERROR).
         */
        private const val HTTPS_CERT_VERIFY_TIMEOUT_MS = 30000L

        private const val DEFERRED_RETRY_DELAY_MS = 1000L
    }
}
