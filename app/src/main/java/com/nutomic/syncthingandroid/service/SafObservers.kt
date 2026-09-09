package com.nutomic.syncthingandroid.service

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

import java.io.File

import android.os.FileObserver

/**
 * Registers one provider observer for a tree URI. DocumentsProvider notifications are not
 * assumed to be complete or precise; every callback is only a reconcile hint.
 */
internal class ProviderObserver(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
    private val onChangeHint: () -> Unit,
) {

    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) {
            return
        }
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChangeHint()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChangeHint()
            }
        }
        try {
            // SAF does not provide reliable, precise recursive filesystem notifications
            // comparable to inotify. Provider observers are treated as change hints, while
            // reconciliation remains the source of truth.
            contentResolver.registerContentObserver(treeUri, true, newObserver)
            observer = newObserver
        } catch (e: Exception) {
            throw e
        }
    }

    fun stop() {
        val current = observer ?: return
        observer = null
        try {
            contentResolver.unregisterContentObserver(current)
        } catch (_: Exception) {
            // The provider or resolver may already be gone during service shutdown.
        }
    }
}

/** Small testable abstraction over an Android [FileObserver]. */
internal interface DirectoryWatch {
    fun start()
    fun stop()
}

internal fun interface DirectoryWatchFactory {
    fun create(directory: File, onEvent: (event: Int, path: String?) -> Unit): DirectoryWatch
}

private object AndroidDirectoryWatchFactory : DirectoryWatchFactory {
    private const val WATCH_MASK = FileObserver.CREATE or
        FileObserver.DELETE or
        FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or
        FileObserver.CLOSE_WRITE or
        FileObserver.MODIFY or
        FileObserver.ATTRIB or
        FileObserver.DELETE_SELF or
        FileObserver.MOVE_SELF

    override fun create(
        directory: File,
        onEvent: (event: Int, path: String?) -> Unit,
    ): DirectoryWatch {
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(directory.absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                onEvent(event, path)
            }
        }
        return object : DirectoryWatch {
            override fun start() = observer.startWatching()

            override fun stop() = observer.stopWatching()
        }
    }
}

/**
 * Maintains one FileObserver per directory. Android FileObserver is not relied on to recurse;
 * the registry is refreshed after directory topology events and can be rebuilt after a restart.
 */
internal class ForwardedTreeObserver(
    private val root: File,
    private val onChangeHint: (relativePath: String?) -> Unit,
    private val watchFactory: DirectoryWatchFactory = AndroidDirectoryWatchFactory,
) {

    private val lock = Any()
    private val watches = LinkedHashMap<String, DirectoryWatch>()
    private var running = false

    fun start() {
        synchronized(lock) {
            if (running) {
                return
            }
            running = true
        }
        refresh()
    }

    fun stop() {
        val toStop: List<DirectoryWatch>
        synchronized(lock) {
            running = false
            toStop = watches.values.toList()
            watches.clear()
        }
        toStop.forEach { it.stop() }
    }

    private fun refresh() {
        val directories = collectDirectories(root)
        val directoryKeys = directories.map { it.absolutePath }.toSet()
        val stale: List<Pair<String, DirectoryWatch>>
        val added = ArrayList<Pair<String, DirectoryWatch>>()
        synchronized(lock) {
            if (!running) {
                return
            }
            stale = watches.filterKeys { it !in directoryKeys }.toList()
            stale.forEach { watches.remove(it.first) }
            for (directory in directories) {
                val key = directory.absolutePath
                if (key in watches) {
                    continue
                }
                val watch = try {
                    watchFactory.create(directory) { event, path ->
                        onFileEvent(directory, event, path)
                    }
                } catch (_: Exception) {
                    // Full reconciliation remains the fallback if a watch cannot be opened.
                    continue
                }
                watches[key] = watch
                added.add(key to watch)
            }
        }
        stale.forEach { it.second.stop() }
        added.forEach {
            try {
                it.second.start()
            } catch (_: Exception) {
                synchronized(lock) {
                    if (watches[it.first] === it.second) {
                        watches.remove(it.first)
                    }
                }
            }
        }
    }

    private fun onFileEvent(watchedDirectory: File, event: Int, path: String?) {
        if (!running()) {
            return
        }
        // The event path is deliberately not trusted for correctness. It is only useful for
        // refreshing the per-directory registry; P1 may also use it as a dirty subtree hint.
        onChangeHint(relativePath(watchedDirectory, path))
        val child = path?.let { File(watchedDirectory, it) }
        val kind = event and FileObserver.ALL_EVENTS
        if (kind and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 && child?.isDirectory == true) {
            refresh()
        } else if (kind and (
                FileObserver.DELETE or FileObserver.MOVED_FROM or
                    FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
            ) != 0
        ) {
            refresh()
        }
    }

    private fun running(): Boolean = synchronized(lock) { running }

    private fun relativePath(watchedDirectory: File, path: String?): String? {
        if (path == null) {
            return null
        }
        val base = if (watchedDirectory == root) {
            ""
        } else {
            watchedDirectory.relativeTo(root).path.replace(File.separatorChar, '/')
        }
        val child = path.replace(File.separatorChar, '/').trim('/')
        return listOf(base, child).filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun collectDirectories(directory: File): Set<File> {
        if (!directory.isDirectory) {
            return emptySet()
        }
        val result = LinkedHashSet<File>()
        fun walk(current: File) {
            if (!current.isDirectory || !result.add(current)) {
                return
            }
            current.listFiles()?.forEach { child ->
                if (child.isDirectory && !isSyncthingInternalName(child.name)) {
                    walk(child)
                }
            }
        }
        walk(directory)
        return result
    }
}
