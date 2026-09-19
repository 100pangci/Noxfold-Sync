package com.nutomic.syncthingandroid.ui.screens.folderpicker

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.ui.components.EmptyListHint
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.RootAccess
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.util.isTelevision
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single browsable directory entry. The indirection exists because root-only paths
 * cannot be stat'ed by the app's own UID — in root browse mode the listing (including
 * the is-directory flag) comes from the root shell instead of java.io.File.
 */
internal data class PickerEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

/** Single-quotes a path for safe use inside a root shell command. */
internal fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Parses `ls -Ap` output (directories carry a trailing slash) into entries of [parentPath].
 */
internal fun parseLsApOutput(lines: List<String>, parentPath: String): List<PickerEntry> {
    return lines.filter { it.isNotBlank() }.map { line ->
        val isDirectory = line.endsWith("/")
        val name = line.removeSuffix("/")
        PickerEntry(name, File(parentPath, name).absolutePath, isDirectory)
    }
}

/**
 * Built-in file system directory picker, ported from the legacy FolderPickerActivity.
 * With "run as root" enabled (and su granted) a root browse mode becomes available that
 * lists and creates directories through the root shell, so folders outside the app's
 * own reach (e.g. other apps' data) can be picked for syncing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    initialDirectory: String?,
    rootDirectory: String?,
    onResult: (String?) -> Unit,
) {
    val context = LocalContext.current
    var roots by remember { mutableStateOf<Set<File>>(emptySet()) }
    var location by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<PickerEntry>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var rootBrowse by rememberSaveable { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = (context.applicationContext as SyncthingApp).preferences
        if (AppPrefs.getRunAsRoot(prefs)) {
            // Blocking su spawn (may show the grant dialog) — keep it off the main thread.
            rootAvailable = withContext(Dispatchers.IO) { RootAccess.isSuAvailable() }
        }
    }

    LaunchedEffect(rootDirectory) {
        roots = populateRoots(context, rootDirectory)
        if (!initialDirectory.isNullOrEmpty()) {
            location = File(initialDirectory)
        } else if (roots.size == 1) {
            location = roots.first()
        } else {
            location = null
        }
    }

    // Refresh the entry list whenever the location or the browse mode changes.
    LaunchedEffect(location, rootBrowse) {
        val loc = location
        entries = if (loc == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { listEntries(loc, rootBrowse) }
        }
    }

    val isRootView = location == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isRootView) {
                            stringResource(R.string.advanced_storage_path_overview)
                        } else {
                            stringResource(R.string.current_path, location!!.absolutePath)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onResult(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(android.R.string.cancel))
                    }
                },
                actions = {
                    if (rootAvailable) {
                        IconButton(onClick = { rootBrowse = !rootBrowse }) {
                            Icon(
                                Icons.Outlined.Terminal,
                                stringResource(R.string.folder_picker_root_browse),
                                tint = if (rootBrowse) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (!isRootView) {
                        val canGoUp = canGoUpToSubDir(location, roots) || canGoUpToRootDir(location, roots)
                        IconButton(
                            enabled = canGoUp,
                            onClick = {
                                if (canGoUpToSubDir(location, roots)) {
                                    location = location!!.parentFile
                                } else if (canGoUpToRootDir(location, roots)) {
                                    location = null
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.folder_go_up))
                        }
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.create_folder))
                        }
                        IconButton(onClick = {
                            location?.let { onResult(Util.formatPath(it.absolutePath)) }
                        }) {
                            Icon(Icons.Outlined.Done, stringResource(R.string.save_title))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        BoxWithConstraints(contentModifier) {
            // Use this pane's actual constraints, not the activity window size. In
            // list-detail mode an 840dp window leaves only ~400dp for this detail
            // pane; treating that as expanded would squeeze the entries into 120dp.
            val useSideBySidePanes = useTwoPaneFolderPicker(
                isRootView = isRootView,
                isTelevision = LocalConfiguration.current.isTelevision,
                availableWidth = maxWidth,
            )

            if (useSideBySidePanes) {
                val activeRoot = findContainingRoot(roots, location)
                Row(Modifier.fillMaxSize()) {
                    RootsList(
                        roots = roots.toList(),
                        selectedRoot = activeRoot,
                        onSelect = { location = it },
                        modifier = Modifier
                            .width(ROOTS_PANE_WIDTH)
                            .fillMaxHeight(),
                    )
                    VerticalDivider()
                    EntriesList(
                        entries = entries,
                        onOpen = { entry ->
                            if (entry.isDirectory) {
                                location = File(entry.path)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else if (isRootView) {
                RootsList(
                    roots = roots.toList(),
                    onSelect = { location = it },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EntriesList(
                    entries = entries,
                    onOpen = { entry ->
                        if (entry.isDirectory) {
                            location = File(entry.path)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showCreateDialog = false
                    val loc = location
                    if (loc != null && name.isNotBlank()) {
                        val created = if (rootBrowse) {
                            RootAccess.code("mkdir ${shellQuote(File(loc, name.trim()).absolutePath)}") == 0
                        } else {
                            File(loc, name.trim()).mkdir()
                        }
                        if (created) {
                            location = File(loc, name.trim())
                        } else {
                            android.widget.Toast.makeText(
                                context, R.string.create_folder_failed, android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/**
 * Width of the storage-roots sidebar in the two-pane picker.
 */
private val ROOTS_PANE_WIDTH = 280.dp

/** Root pane plus a useful (rather than sliver-sized) directory pane. */
private val TWO_PANE_MIN_WIDTH = 720.dp

internal fun useTwoPaneFolderPicker(
    isRootView: Boolean,
    isTelevision: Boolean,
    availableWidth: androidx.compose.ui.unit.Dp,
): Boolean = !isRootView && !isTelevision && availableWidth >= TWO_PANE_MIN_WIDTH

/** The deepest configured root containing [location], so only one root is highlighted. */
internal fun findContainingRoot(roots: Set<File>, location: File?): File? {
    val path = location?.absolutePath ?: return null
    return roots
        .filter { root ->
            val rootPath = root.absolutePath
            path == rootPath || path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
        }
        .maxByOrNull { it.absolutePath.length }
}

/**
 * The storage roots, used both as the full-screen root overview and as the sidebar next
 * to a browsed directory. [selectedRoot] is the root containing the current location.
 */
@Composable
private fun RootsList(
    roots: List<File>,
    onSelect: (File) -> Unit,
    modifier: Modifier = Modifier,
    selectedRoot: File? = null,
) {
    LazyColumn(modifier = modifier) {
        items(roots, key = { it.absolutePath }) { root ->
            val selected = selectedRoot == root
            Text(
                text = root.absolutePath,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(root) }
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/** Directory entries of the current location, or a hint when it is empty. */
@Composable
private fun EntriesList(
    entries: List<PickerEntry>,
    onOpen: (PickerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyListHint(stringResource(R.string.folder_picker_title), modifier)
    } else {
        LazyColumn(modifier = modifier) {
            items(entries, key = { it.path }) { entry ->
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (entry.isDirectory) FontStyle.Normal else FontStyle.Italic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * Lists the entries of [location], sorted directories-first. In root browse mode the
 * listing comes from the root shell (`ls -Ap`), which also works for directories the
 * app UID cannot stat itself.
 */
internal fun listEntries(location: File, rootBrowse: Boolean): List<PickerEntry> {
    val comparator = compareByDescending<PickerEntry> { it.isDirectory }
        .thenBy { it.name.lowercase() }
    return if (rootBrowse) {
        val out = RootAccess.out("ls -Ap ${shellQuote(location.absolutePath)}")
        parseLsApOutput(out, location.absolutePath).sortedWith(comparator)
    } else {
        location.listFiles()
            ?.map { PickerEntry(it.name, it.absolutePath, it.isDirectory) }
            ?.sortedWith(comparator)
            ?: emptyList()
    }
}

private fun canGoUpToSubDir(location: File?, roots: Set<File>): Boolean =
    location != null && !roots.contains(location)

private fun canGoUpToRootDir(location: File?, roots: Set<File>): Boolean =
    roots.contains(location) && roots.size > 1

/**
 * Builds the list of storage roots, ported from FolderPickerActivity.populateRoots.
 */
private fun populateRoots(context: android.content.Context, rootDirectory: String?): Set<File> {
    val roots = ArrayList<File>()
    if (!rootDirectory.isNullOrEmpty()) {
        roots.add(File(rootDirectory))
    } else {
        roots.addAll(context.getExternalFilesDirs(null).filterNotNull())
        roots.remove(context.getExternalFilesDir(null))
        roots.add(Environment.getExternalStorageDirectory())
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
        roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))

        val mountedStoragePaths = FileUtils.getMountedStoragePathsAsFileArray()
        if (mountedStoragePaths != null) {
            roots.addAll(mountedStoragePaths.toList())
        }
        roots.add(File("/"))
    }
    // Remove any invalid directories and sort.
    val iterator = roots.iterator()
    while (iterator.hasNext()) {
        val f = iterator.next()
        if (f == null || !f.exists() || !f.isDirectory) {
            iterator.remove()
        }
    }
    return TreeSet(roots)
}
