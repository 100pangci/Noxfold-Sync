package com.nutomic.syncthingandroid.service

import android.os.Looper
import com.nutomic.syncthingandroid.SyncthingApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the shutdown completion contract of [SyncthingService.shutdownToState]:
 * when there is no binary to kill, the completion callback is queued for the next main
 * loop turn instead of being invoked inline (the previous implementation used
 * `Handler.post()`).
 */
@RunWith(RobolectricTestRunner::class)
// sdk 29 keeps PermissionUtil on its pre-R branch: Robolectric's
// Environment.isExternalStorageManager() is not usable in unit tests.
@Config(sdk = [29], application = SyncthingApp::class)
class SyncthingServiceShutdownTest {

    @Test
    fun completionCallback_isQueuedNotInline_whenNoBinaryIsRunning() {
        val controller = Robolectric.buildService(SyncthingService::class.java)
        val service = controller.create().get()

        var invoked = false
        service.shutdownToState(SyncthingService.State.DISABLED) { invoked = true }

        assertFalse("completion callback must not run inline in shutdownToState()", invoked)

        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("completion callback must run on a later main loop turn", invoked)
        controller.destroy()
    }
}
