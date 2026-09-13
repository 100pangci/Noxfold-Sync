package com.nutomic.syncthingandroid.service

/** A coalesced set of directory scopes invalidated by observer hints. */
internal data class DirtySnapshot(
    val full: Boolean,
    val safDirs: Set<String>,
    val forwardedDirs: Set<String>,
) {
    val roots: Set<String>
        get() = safDirs + forwardedDirs

    fun isEmpty(): Boolean = !full && safDirs.isEmpty() && forwardedDirs.isEmpty()
}

/**
 * Tracks invalidation scopes, not file truth. A path event only narrows the next scan; the
 * provider and forwarded directory are still scanned and merged before any state is committed.
 */
internal class DirtyPathTracker {

    private val lock = Any()
    private val dirtySafDirs = LinkedHashSet<String>()
    private val dirtyForwardedDirs = LinkedHashSet<String>()
    private var fullDirty = false

    fun markFull() {
        synchronized(lock) {
            fullDirty = true
        }
    }

    /** Provider observers cannot reliably identify a changed document, so they dirty the root. */
    fun markSafRoot() {
        markSafDir("")
    }

    fun markSafDir(path: String?) {
        synchronized(lock) {
            addCoalesced(dirtySafDirs, normalize(path))
        }
    }

    /** Marks the directory containing an event path. Null means that the root is unknown. */
    fun markForwardedPath(path: String?) {
        val normalized = normalize(path)
        val directory = if (normalized.isEmpty()) {
            ""
        } else {
            normalized.substringBeforeLast('/', "")
        }
        markForwardedDir(directory)
    }

    fun markForwardedDir(path: String?) {
        synchronized(lock) {
            addCoalesced(dirtyForwardedDirs, normalize(path))
        }
    }

    fun take(): DirtySnapshot {
        synchronized(lock) {
            val result = DirtySnapshot(
                full = fullDirty,
                safDirs = LinkedHashSet(dirtySafDirs),
                forwardedDirs = LinkedHashSet(dirtyForwardedDirs),
            )
            fullDirty = false
            dirtySafDirs.clear()
            dirtyForwardedDirs.clear()
            return result
        }
    }

    /** Restores work after an unknown scan/apply failure; newer observer hints are preserved. */
    fun restore(snapshot: DirtySnapshot) {
        synchronized(lock) {
            if (snapshot.full) {
                fullDirty = true
            }
            snapshot.safDirs.forEach { addCoalesced(dirtySafDirs, it) }
            snapshot.forwardedDirs.forEach { addCoalesced(dirtyForwardedDirs, it) }
        }
    }

    private fun addCoalesced(target: MutableSet<String>, path: String) {
        if (path.isEmpty()) {
            target.clear()
            target.add("")
            return
        }
        if (target.contains("") || target.any { path == it || path.startsWith("$it/") }) {
            return
        }
        target.removeAll { it.startsWith("$path/") }
        target.add(path)
    }

    private fun normalize(path: String?): String {
        val value = path?.replace('\\', '/')?.trim('/') ?: return ""
        if (value.isEmpty() || value == ".") {
            return ""
        }
        if (value.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            // An observer path is untrusted metadata. Unknown or malformed paths must widen
            // the scan to the root rather than throwing from the observer callback.
            return ""
        }
        return value
    }
}
