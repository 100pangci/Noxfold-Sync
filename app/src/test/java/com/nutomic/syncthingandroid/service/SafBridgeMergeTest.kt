package com.nutomic.syncthingandroid.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the pure three-way merge of [MirrorMerge] and the bridge detection of
 * [SafBridge] (the forwarding layer for DocumentsProvider folders).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafBridgeMergeTest {

    private fun dir() = SafBridge.NodeInfo(isDir = true)

    private fun file(size: Long, mtime: Long, hash: String? = null) =
        SafBridge.NodeInfo(isDir = false, size = size, mtime = mtime, contentHash = hash)

    @Test
    fun plan_noopWhenBothSidesMatchSnapshot() {
        // The anti-oscillation guarantee: a state that was fully forwarded must not
        // produce any further work.
        val state = mapOf(
            "docs" to dir(),
            "docs/a.txt" to file(3, 1000)
        )
        val plan = MirrorMerge.plan(state, state, state)
        assertFalse(plan.hasWork())
        assertEquals(state, plan.result)
    }

    @Test
    fun plan_copiesNewProviderFileToForwardedDir() {
        val saf = mapOf("new.txt" to file(5, 100))
        val plan = MirrorMerge.plan(saf, emptyMap(), emptyMap())
        assertEquals(listOf("new.txt"), plan.copyToForwarded.map { it.first })
        assertTrue(plan.makeDirsInForwarded.isEmpty())
        assertEquals(saf, plan.result)
    }

    @Test
    fun plan_copiesNewForwardedFileToProvider() {
        val fwd = mapOf("out.bin" to file(9, 200))
        val plan = MirrorMerge.plan(emptyMap(), fwd, emptyMap())
        assertEquals(listOf("out.bin"), plan.copyToSaf)
        assertTrue(plan.makeDirsInSaf.isEmpty())
        // Pushed provider mtime is unknown -> snapshot records 0.
        assertEquals(mapOf("out.bin" to file(9, 0)), plan.result)
    }

    @Test
    fun plan_propagatesProviderDeletion() {
        val last = mapOf("gone.txt" to file(5, 100))
        val plan = MirrorMerge.plan(emptyMap(), last, last)
        assertEquals(listOf("gone.txt"), plan.deleteInForwarded)
        assertTrue(plan.deleteInSaf.isEmpty())
        assertTrue(plan.result.isEmpty())
    }

    @Test
    fun plan_forwardedDirWinsOnConflict() {
        // Changed on both sides: the forwarded (core-synced) side must win and be
        // pushed to the provider, never the other way around.
        val last = mapOf("c.txt" to file(1, 1))
        val saf = mapOf("c.txt" to file(2, 2))
        val fwd = mapOf("c.txt" to file(3, 3))
        val plan = MirrorMerge.plan(saf, fwd, last)
        assertEquals(listOf("c.txt"), plan.copyToSaf)
        assertTrue(plan.copyToForwarded.isEmpty())
        // The pushed file's provider mtime is unknown, so the snapshot records 0.
        assertEquals(mapOf("c.txt" to file(3, 0)), plan.result)
    }

    @Test
    fun plan_forwardedDeletionWinsOnConflict() {
        // Deleted in the forwarded dir while changed on the provider: the deletion wins.
        val last = mapOf("c.txt" to file(1, 1))
        val saf = mapOf("c.txt" to file(2, 2))
        val plan = MirrorMerge.plan(saf, emptyMap(), last)
        assertEquals(listOf("c.txt"), plan.deleteInSaf)
        assertTrue(plan.result.isEmpty())
    }

    @Test
    fun plan_createsDirsBeforeFiles() {
        val saf = mapOf(
            "deep/nested/dir" to dir(),
            "deep/nested/dir/f.txt" to file(1, 10),
            "deep" to dir(),
            "deep/nested" to dir()
        )
        val plan = MirrorMerge.plan(saf, emptyMap(), emptyMap())
        // Parents must come before children so the sequential apply can mkdir them.
        val dirs = plan.makeDirsInForwarded
        assertTrue(dirs.indexOf("deep") < dirs.indexOf("deep/nested"))
        assertTrue(dirs.indexOf("deep/nested") < dirs.indexOf("deep/nested/dir"))
        assertTrue(plan.copyToForwarded.map { it.first } == listOf("deep/nested/dir/f.txt"))
        assertEquals(saf, plan.result)
    }

    @Test
    fun plan_ignoresDirectoryMtimeDifferences() {
        // Providers rarely preserve dir mtimes; comparing them would oscillate.
        val saf = mapOf("docs" to SafBridge.NodeInfo(isDir = true, size = 0, mtime = 123))
        val fwd = mapOf("docs" to dir())
        val last = mapOf("docs" to SafBridge.NodeInfo(isDir = true, size = 0, mtime = 999))
        val plan = MirrorMerge.plan(saf, fwd, last)
        assertFalse(plan.hasWork())
    }

    @Test
    fun plan_toleratesZeroMtimeOnFiles() {
        // Providers may report mtime=0; equal size must then count as unchanged.
        val last = mapOf("f.bin" to file(10, 0))
        val saf = mapOf("f.bin" to file(10, 555))
        val fwd = mapOf("f.bin" to file(10, 777))
        val plan = MirrorMerge.plan(saf, fwd, last)
        // Both sides differ from the snapshot only by their (unreliable) mtimes.
        assertFalse(plan.hasWork())
    }

    @Test
    fun plan_sizeChangeWithZeroMtimeIsDetected() {
        val last = mapOf("f.bin" to file(10, 0))
        val saf = mapOf("f.bin" to file(20, 0))
        val plan = MirrorMerge.plan(saf, last, last)
        assertEquals(listOf("f.bin"), plan.copyToForwarded.map { it.first })
    }

    @Test
    fun plan_sameSizeAndUnreliableMtimeUsesContentHash() {
        val last = mapOf("f.bin" to file(4, 0, "old"))
        val saf = mapOf("f.bin" to file(4, 0, "new"))
        val fwd = mapOf("f.bin" to file(4, 0, "old"))

        val plan = MirrorMerge.plan(saf, fwd, last)

        assertEquals(listOf("f.bin"), plan.copyToForwarded.map { it.first })
        assertTrue(plan.copyToSaf.isEmpty())
    }

    @Test
    fun verifiedResult_dropsFailedCopyDownInsteadOfMistakingItForDeletion() {
        // Regression: a failed provider->forwarded copy must NOT advance the
        // snapshot; otherwise the next pass reads the empty forwarded dir as
        // "user deleted everything" and propagates deletions to the provider.
        val saf = mapOf("keep.txt" to file(5, 100))
        val plan = MirrorMerge.plan(saf, emptyMap(), emptyMap())
        assertEquals(listOf("keep.txt"), plan.copyToForwarded.map { it.first })

        // Copy-down FAILED (nothing applied): the entry is retried next pass.
        val failed = MirrorMerge.verifiedResult(plan, appliedFwd = emptySet(), appliedSaf = emptySet())
        assertFalse(failed.containsKey("keep.txt"))

        // Copy-down SUCCEEDED: the entry advances the snapshot.
        val succeeded = MirrorMerge.verifiedResult(plan, appliedFwd = setOf("keep.txt"), appliedSaf = emptySet())
        assertEquals(saf, succeeded)
    }

    @Test
    fun verifiedResult_dropsFailedPushAndFailedDelete() {
        // Push failure: retried next pass.
        val fwd = mapOf("out.bin" to file(9, 0))
        val pushPlan = MirrorMerge.plan(emptyMap(), fwd, emptyMap())
        assertFalse(
            MirrorMerge.verifiedResult(pushPlan, emptySet(), emptySet()).containsKey("out.bin")
        )
        assertTrue(
            MirrorMerge.verifiedResult(pushPlan, emptySet(), setOf("out.bin")).containsKey("out.bin")
        )

        // Provider-side deletion failure: retried next pass (entry stays absent from
        // the snapshot either way - deletions record themselves by absence).
        val last = mapOf("gone.txt" to file(5, 100))
        val deletePlan = MirrorMerge.plan(emptyMap(), last, last)
        assertEquals(listOf("gone.txt"), deletePlan.deleteInForwarded)
        assertTrue(MirrorMerge.verifiedResult(deletePlan, emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun verifiedResult_withBaseline_retainsFailedDeleteAndRetriesSameDirection() {
        val last = mapOf("gone.txt" to file(5, 100))
        val plan = MirrorMerge.plan(emptyMap(), last, last)

        val failed = MirrorMerge.verifiedResult(plan, emptySet(), emptySet(), last)
        assertEquals(last, failed)

        // The failed forwarded delete must not turn into a new forwarded file that gets
        // copied back into the provider on the following pass.
        val retry = MirrorMerge.plan(emptyMap(), last, failed)
        assertEquals(listOf("gone.txt"), retry.deleteInForwarded)
        assertTrue(retry.copyToSaf.isEmpty())
    }

    @Test
    fun verifiedResult_withBaseline_retainsFailedProviderDelete() {
        val last = mapOf("gone.txt" to file(5, 100))
        val plan = MirrorMerge.plan(emptyMap(), emptyMap(), last)

        val failed = MirrorMerge.verifiedResult(plan, emptySet(), emptySet(), last)
        assertEquals(last, failed)

        val retry = MirrorMerge.plan(emptyMap(), emptyMap(), failed)
        assertEquals(listOf("gone.txt"), retry.deleteInSaf)
    }

    @Test
    fun verifiedResult_keepsUntouchedPaths() {
        val state = mapOf(
            "docs" to dir(),
            "docs/a.txt" to file(3, 1000)
        )
        val plan = MirrorMerge.plan(state, state, state)
        assertEquals(state, MirrorMerge.verifiedResult(plan, emptySet(), emptySet()))
    }

    @Test
    fun forwardedScanFailure_isNotAnEmptyTree() {
        val file = File.createTempFile("saf-bridge", "not-a-directory")
        try {
            var failed = false
            try {
                scanForwardedDir(file)
            } catch (_: java.io.IOException) {
                failed = true
            }
            assertTrue("a local scan failure must not become an empty map", failed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun providerQueryFailure_isNotAnEmptyTree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tree = DocumentFileSafTree(
            context,
            Uri.parse("content://provider/tree/root"),
        )

        var failed = false
        try {
            tree.scan()
        } catch (_: java.io.IOException) {
            failed = true
        }
        assertTrue("a provider query failure must not become an empty map", failed)
    }

    @Test
    fun requiresBridge_onlyForThirdPartyProviders() {
        // The externalstorage provider maps to real paths and must not be bridged.
        assertFalse(
            SafBridge.requiresBridge(
                android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/tree/primary%3Atest"
                )
            )
        )
        assertTrue(
            SafBridge.requiresBridge(
                android.net.Uri.parse("content://org.fcitx.fcitx5.android.provider/tree/sync")
            )
        )
    }
}
