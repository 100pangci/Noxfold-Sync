package com.nutomic.syncthingandroid.util

import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.util.deepCopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.util.ArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilTest {

    @Test
    fun shellQuote_wrapsInSingleQuotes() {
        assertEquals("'abc'", Util.shellQuote("abc"))
    }

    @Test
    fun shellQuote_escapesEmbeddedSingleQuotes() {
        // POSIX style: close quote, escaped quote, reopen quote.
        assertEquals("'it'\\''s'", Util.shellQuote("it's"))
    }

    @Test
    fun shellQuote_neutralizesShellMetacharacters() {
        val malicious = "\$(rm -rf /); `whoami` \"quoted\""
        val quoted = Util.shellQuote(malicious)
        // The quoted form must differ from the raw input and contain no unquoted $ or backtick.
        assertNotEquals(malicious, quoted)
        assertEquals("'$malicious'", quoted)
    }

    @Test
    fun deepCopy_returnsEqualButIndependentCopy() {
        val folder = Folder()
        folder.id = "folder-a"
        folder.label = "Alpha"
        folder.path = "/data/folder-a"

        val copy = deepCopy(folder)

        assertEquals(folder.id, copy.id)
        assertEquals(folder.label, copy.label)
        assertEquals(folder.path, copy.path)

        // Mutating the copy must not affect the original.
        copy.label = "Changed"
        assertEquals("Alpha", folder.label)
    }

    @Test
    fun deepCopy_copiesLists() {
        val folders = ArrayList<Folder>()
        val folder = Folder()
        folder.id = "folder-a"
        folders.add(folder)

        val copy = deepCopy(folders)

        assertEquals(1, copy.size)
        assertEquals("folder-a", copy[0].id)

        copy[0].id = "changed"
        assertEquals("folder-a", folders[0].id)
    }

    @Test
    fun parsePsOutput_rootMatchesFullPathOnly() {
        val output = """
            PID ARGS
              111 /data/app/~~ours==/lib/arm64/libsyncthingnative.so serve --no-browser
              222 /data/app/~~theirs==/lib/arm64/libsyncthingnative.so serve --no-browser
        """.trimIndent()
        val pids = parsePsOutput(
            output,
            "/data/app/~~ours==/lib/arm64/libsyncthingnative.so",
            asRoot = true,
        )
        assertEquals(listOf("111"), pids)
    }

    @Test
    fun parsePsOutput_rootSkipsSuWrapperContainingThePath() {
        // The su wrapper's command line embeds the launch script, which contains the same
        // binary path. Killing it instead of only the core reports a spurious 130 crash.
        val output = """
            PID ARGS
              111 /data/app/~~ours==/lib/arm64/libsyncthingnative.so serve --no-browser
              555 su -c export HOME='/data/user/0/pkg/files'
              777 /data/app/~~ours==/lib/arm64/libsyncthingnative.soX
        """.trimIndent()
        val pids = parsePsOutput(
            output,
            "/data/app/~~ours==/lib/arm64/libsyncthingnative.so",
            asRoot = true,
        )
        assertEquals(listOf("111"), pids)
    }

    @Test
    fun parsePsOutput_rootArgFiltersNarrowGenericProcessNameToOurFolders() {
        // A bare "find" name match under root would signal every find process on the
        // system; only helpers operating on OUR folder paths may match.
        val output = """
            PID ARGS
              111 find /storage/emulated/0/Sync -type f -cmin +1440 -print0
              222 find / -name lost+found
              333 find /data/otherapp/stuff -type f
              444 grep find
        """.trimIndent()
        val pids = parsePsOutput(
            output, "find", asRoot = true,
            argFilters = listOf("/storage/emulated/0/Sync"),
        )
        assertEquals(listOf("111"), pids)
    }

    @Test
    fun parsePsOutput_rootArgFiltersExcludeBareFindWithoutArgs() {
        val output = """
            PID ARGS
              111 find
              222 find /storage/emulated/0/Sync -type f
        """.trimIndent()
        val pids = parsePsOutput(
            output, "find", asRoot = true,
            argFilters = listOf("/storage/emulated/0/Sync"),
        )
        assertEquals(listOf("222"), pids)
    }

    @Test
    fun parsePsOutput_rootEmptyArgFiltersKeepBareNameMatching() {
        val output = """
            PID ARGS
              111 find
              222 find /anything
        """.trimIndent()
        val pids = parsePsOutput(output, "find", asRoot = true)
        assertEquals(listOf("111", "222"), pids)
    }

    @Test
    fun parsePsOutput_nonRootIgnoresArgFilters() {
        // Non-root lookups are UID-scoped; the filter must not change the results.
        val output = """
            USER      PID   PPID  VSIZE  RSS   WCHAN    NAME        S
            u0_a123   5678  999   1234   567   0        find        S
        """.trimIndent()
        val pids = parsePsOutput(
            output, "find", asRoot = false,
            argFilters = listOf("/nonexistent/path"),
        )
        assertEquals(listOf("5678"), pids)
    }

    @Test
    fun parseRootListenerPids_matchesOurListenerOnThePort() {
        val output = """
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program
            tcp   0   0 127.0.0.1:8384          0.0.0.0:*               LISTEN      1234/libsyncthingnative.so
            tcp   0   0 127.0.0.1:8080          0.0.0.0:*               LISTEN      555/someapp
        """.trimIndent()
        val pids = parseRootListenerPids(output, 8384, "libsyncthingnative.so")
        assertEquals(listOf("1234"), pids)
    }

    @Test
    fun parseRootListenerPids_handlesIpv6AndExtraColumns() {
        val output = """
            tcp6   0   0 :::8384   :::*   LISTEN   0   12345   777/libsyncthingnative.so
            tcp   0   0 0.0.0.0:8384   0.0.0.0:*   LISTEN   888/libsyncthingnative.so
        """.trimIndent()
        val pids = parseRootListenerPids(output, 8384, "libsyncthingnative.so")
        assertEquals(listOf("777", "888"), pids)
    }

    @Test
    fun parseRootListenerPids_ignoresOtherPortsStatesAndPrograms() {
        val output = """
            tcp   0   0 127.0.0.1:9999   0.0.0.0:*   LISTEN   1234/libsyncthingnative.so
            tcp   0   0 127.0.0.1:8384   1.2.3.4:5678   ESTABLISHED   1235/libsyncthingnative.so
            tcp   0   0 127.0.0.1:8384   0.0.0.0:*   LISTEN   1236/other-binary
        """.trimIndent()
        val pids = parseRootListenerPids(output, 8384, "libsyncthingnative.so")
        assertEquals(emptyList<String>(), pids)
    }

    @Test
    fun isTcpPortListening_detectsListeningAndClosedPorts() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            assertTrue(
                "probe must find the listening socket",
                Util.isTcpPortListening(server.localPort)
            )
        }
        // A closed listening socket (no accepted connections) releases its port
        // immediately, so connecting must be refused.
        val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val closedPort = closed.localPort
        closed.close()
        assertFalse("closed port must refuse connects", Util.isTcpPortListening(closedPort))
    }

    @Test
    fun parsePsOutput_nonRootReadsPidFromSecondToken() {
        val output = """
            USER      PID   PPID  VSIZE  RSS   WCHAN    NAME        S
            u0_a123   5678  999   1234   567   0        libsyncthingnative.so S
        """.trimIndent()
        val pids = parsePsOutput(output, "libsyncthingnative.so", asRoot = false)
        assertEquals(listOf("5678"), pids)
    }
}
