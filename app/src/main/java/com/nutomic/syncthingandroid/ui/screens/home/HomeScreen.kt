package com.nutomic.syncthingandroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SafBridge
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.ui.LocalServiceState
import com.nutomic.syncthingandroid.ui.LocalSyncthingService
import com.nutomic.syncthingandroid.ui.TABLET_MIN_WIDTH_DP
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.contentWidthForTablet
import com.nutomic.syncthingandroid.ui.components.EmptyListHint
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.theme.AMOLED_CARD_BORDER_ALPHA
import com.nutomic.syncthingandroid.ui.theme.LocalAmoledTheme
import kotlinx.coroutines.launch

private const val TAB_FOLDERS = 0
private const val TAB_DEVICES = 1
private const val TAB_STATUS = 2

private val TAB_TITLES = intArrayOf(
    R.string.folders_fragment_title,
    R.string.devices_fragment_title,
    R.string.status_fragment_title
)

// MD3 bottom navigation: filled icon marks the selected destination, outlined
// icon the unselected ones (see "icon" guidance in the M3 NavigationBar spec).
// Devices/Status must use glyph pairs whose filled variant is visually solid;
// "Devices" and "DataUsage" are outline-style glyphs whose filled/outlined
// variants look identical, so the selected state would be invisible.
private val TAB_ICONS = listOf(
    Icons.Filled.Folder to Icons.Outlined.Folder,
    Icons.Filled.DeviceHub to Icons.Outlined.DeviceHub,
    Icons.Filled.PieChart to Icons.Outlined.PieChart,
)

/**
 * Home screen: folders / devices / status destinations on an MD3 bottom
 * navigation bar inside a drawer scaffold.
 * Ported from the legacy MainActivity + FolderListFragment + DeviceListFragment + StatusFragment.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onExitApp: () -> Unit,
) {
    val navigator = LocalAppNavigator.current
    val service = LocalSyncthingService.current
    val serviceState = LocalServiceState.current
    val api = service?.api
    val apiConfigLoaded = api?.isConfigLoaded ?: false

    // Folder/device lists are polled and owned by HomeDataHost (above the
    // NavDisplay), so they survive entry transitions; see HomeDataHost.
    val folders = LocalHomeFolderModels.current
    val devices = LocalHomeDeviceModels.current
    val isAmoled = LocalAmoledTheme.current
    val isTablet = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP

    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = TAB_FOLDERS, pageCount = { 3 })
    val selectPage: (Int) -> Unit = { page ->
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isTablet,
        drawerContent = {
            AppDrawer(
                stServiceRunning = serviceState == SyncthingService.State.ACTIVE,
                onShowDeviceId = { scope.launch { drawerState.close() }; navigator.showDeviceIdDialog() },
                onRecentChanges = { scope.launch { drawerState.close() }; navigator.openRecentChanges() },
                onWebGui = { scope.launch { drawerState.close() }; navigator.openWebGui() },
                onBackup = { scope.launch { drawerState.close() }; navigator.openSettings("ImportExport") },
                onRestart = { scope.launch { drawerState.close() }; navigator.confirmRestart() },
                onSettings = { scope.launch { drawerState.close() }; navigator.openSettings() },
                onExit = { scope.launch { drawerState.close() }; onExitApp() },
            )
        }
    ) {
        val homeContent: @Composable () -> Unit = {
            HomeScaffold(
                isTablet = isTablet,
                pagerState = pagerState,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onPageSelected = selectPage,
                onRefresh = {
                    if (api != null && apiConfigLoaded) {
                        api.rescanAll()
                    }
                },
                onSettings = { navigator.openSettings() },
                onAddFolder = { navigator.openFolderEdit(null, true) },
                onAddDevice = { navigator.openDeviceEdit(null, true) },
                folders = folders,
                devices = devices,
                serviceState = serviceState,
                isAmoled = isAmoled,
            )
        }
        if (isTablet) {
            Row(Modifier.fillMaxSize()) {
                TabletHomeNavigationRail(
                    currentPage = pagerState.currentPage,
                    onPageSelected = selectPage,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
                homeContent()
            }
        } else {
            homeContent()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeScaffold(
    isTablet: Boolean,
    pagerState: PagerState,
    onOpenDrawer: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onAddDevice: () -> Unit,
    folders: List<FolderUiModel>?,
    devices: List<DeviceUiModel>?,
    serviceState: SyncthingService.State,
    isAmoled: Boolean,
) {
    Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        if (!isTablet) {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(Icons.Outlined.Menu, stringResource(R.string.main_menu))
                            }
                        }
                    },
                    actions = {
                        if (pagerState.currentPage == TAB_FOLDERS) {
                            IconButton(onClick = onRefresh) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    stringResource(R.string.activity_main_bottom_navigation_rescan_all)
                                )
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                        }
                    }
                )
            },
            floatingActionButton = {
                // Add actions live on a bottom-right FAB (same spot as the folder
                // editor's save button), tab-aware: each list tab adds its own kind.
                when (pagerState.currentPage) {
                    TAB_FOLDERS -> {
                        FloatingActionButton(onClick = onAddFolder) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.add_folder))
                        }
                    }
                    TAB_DEVICES -> {
                        FloatingActionButton(onClick = onAddDevice) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.add_device))
                        }
                    }
                    else -> {}
                }
            },
            bottomBar = {
                if (!isTablet) {
                    // Pure AMOLED: black bar, separated from the content only by a faint
                    // hairline - no tinted surface, matching the outlined-card treatment.
                    Column {
                        if (isAmoled) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                                    .copy(alpha = AMOLED_CARD_BORDER_ALPHA)
                            )
                        }
                        NavigationBar(
                            containerColor = if (isAmoled) Color.Black
                                else MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            TAB_TITLES.forEachIndexed { index, titleRes ->
                                val selected = pagerState.currentPage == index
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { onPageSelected(index) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) TAB_ICONS[index].first else TAB_ICONS[index].second,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(stringResource(titleRes)) }
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                // Keep all three pages composed. Without this, every tab
                // switch had to rebuild the target page's whole UI on the
                // main thread mid-animation, which showed up as jank. Pages
                // now persist (including their scroll positions) and tab
                // switches only move the scroll offset.
                beyondViewportPageCount = 2
            ) { page ->
                when (page) {
                    TAB_FOLDERS -> FolderListPage(
                        folders = folders,
                    )
                    TAB_DEVICES -> DeviceListPage(
                        devices = devices,
                    )
                    else -> StatusPage(
                        serviceState = serviceState,
                        visible = pagerState.currentPage == TAB_STATUS
                    )
                }
            }
    }
}

@Composable
private fun TabletHomeNavigationRail(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val isAmoled = LocalAmoledTheme.current
    NavigationRail(
        containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        header = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.main_menu))
            }
            Spacer(Modifier.height(8.dp))
        },
    ) {
        TAB_TITLES.forEachIndexed { index, titleRes ->
            val selected = currentPage == index
            NavigationRailItem(
                selected = selected,
                onClick = { onPageSelected(index) },
                icon = {
                    Icon(
                        imageVector = if (selected) TAB_ICONS[index].first else TAB_ICONS[index].second,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(titleRes)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListPage(
    folders: List<FolderUiModel>?,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    if (folders.isNullOrEmpty()) {
        EmptyListHint(stringResource(R.string.folder_list_empty))
        return
    }
    // Group names are matched with a locale-aware collator so mixed Chinese /
    // Latin names sort by pinyin order instead of raw code points.
    val groupComparator = remember {
        val collator = java.text.Collator.getInstance()
        collator.strength = java.text.Collator.PRIMARY
        Comparator<String> { a, b -> collator.compare(a, b) }
    }
    val sections = remember(folders, groupComparator) {
        buildFolderSections(folders, groupComparator)
    }
    // Collapsed group names persist across restarts (SharedPreferences), so
    // the state survives process death and re-polling of the folder list.
    val prefs = context.appPreferences()
    var collapsedGroups by remember(prefs) {
        mutableStateOf(
            prefs.getStringSet(Constants.PREF_HOME_COLLAPSED_FOLDER_GROUPS, emptySet()).orEmpty()
        )
    }
    fun toggleGroup(groupName: String) {
        val collapsed = if (groupName in collapsedGroups) {
            collapsedGroups - groupName
        } else {
            collapsedGroups + groupName
        }
        collapsedGroups = collapsed
        // Drop stale entries whose group no longer exists (e.g. the last
        // folder of the group was deleted or reassigned).
        val existing = sections.map { it.groupName }.toSet()
        prefs.edit()
            .putStringSet(
                Constants.PREF_HOME_COLLAPSED_FOLDER_GROUPS,
                collapsed.intersect(existing)
            )
            .apply()
    }
    // Pending re-authorization target: remembered across recompositions so the
    // picker result can be matched back to the tapped card. Cleared on cancel
    // and right after a successful reauthorize; the HomeDataHost poll then
    // flips needsSafAuthorization back to false and the card restores itself.
    var pendingReauthFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    // Same SAF picker contract as FolderEditScreen (OpenDocumentTree): take a
    // persistable grant, then reauthorize() the EXISTING forwarded path so the
    // imported config keeps working without a rewrite.
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val folderId = pendingReauthFolderId
        pendingReauthFolderId = null
        if (uri == null || folderId == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w("FolderListPage", "takePersistableUriPermission failed for $uri", e)
            return@rememberLauncherForActivityResult
        }
        val target = folders.find { it.id == folderId } ?: return@rememberLauncherForActivityResult
        val safBridge = (context.applicationContext as SyncthingApp).safBridge
        if (SafBridge.requiresBridge(uri)) {
            // Third-party provider root: path-stable re-authorization.
            safBridge.reauthorize(target.path, uri)
            Toast.makeText(
                context, R.string.saf_bridge_folder_mapped, Toast.LENGTH_LONG
            ).show()
        } else {
            // Plain storage location: no bridge mapping exists, so there is
            // nothing to reauthorize here — open the editor for a manual fix.
            navigator.openFolderEdit(target.id, false)
        }
        // No manual refresh: HomeDataHost re-polls buildFolderUiModels at
        // GUI_UPDATE_INTERVAL and publishes the updated models via the
        // LocalHomeFolderModels flow/state, restoring the normal card.
    }
    // Stable callbacks: combined with the FolderUiModel data class equality,
    // rows whose content did not change are skipped while scrolling.
    val onEdit: (FolderUiModel) -> Unit = remember(navigator) {
        { model -> navigator.openFolderEdit(model.id, false) }
    }
    // Intercepted tap for needsSafAuthorization cards: launch the SAF picker
    // directly instead of opening the editor (the editor would only do the
    // same via its auto-popup effect). Normal folders keep onEdit.
    val onReauthorize: (FolderUiModel) -> Unit = remember(safLauncher) {
        { model ->
            pendingReauthFolderId = model.id
            Toast.makeText(
                context, R.string.saf_bridge_needs_authorization, Toast.LENGTH_LONG
            ).show()
            safLauncher.launch(null)
        }
    }
    val onOverride: (FolderUiModel) -> Unit = remember(context) {
        { model ->
            context.startService(
                Intent(context, SyncthingService::class.java).apply {
                    putExtra(SyncthingService.EXTRA_FOLDER_ID, model.id)
                    action = SyncthingService.ACTION_OVERRIDE_CHANGES
                }
            )
        }
    }
    val onRevert: (FolderUiModel) -> Unit = remember(context) {
        { model ->
            context.startService(
                Intent(context, SyncthingService::class.java).apply {
                    putExtra(SyncthingService.EXTRA_FOLDER_ID, model.id)
                    action = SyncthingService.ACTION_REVERT_LOCAL_CHANGES
                }
            )
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch),
            modifier = Modifier
                .contentWidthForTablet(LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP)
                .fillMaxSize(),
            // Keep the last row reachable above the bottom-right FAB.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // One item per group: the whole section is a single card that expands
            // or collapses inside itself. Adding/removing individual folder items
            // on toggle (the old design) made the collapse janky, because every
            // toggle rewrote the LazyColumn item set and forced a full reflow.
            items(sections, key = { "group:" + it.groupName }) { section ->
                HomeGroupCard(
                    title = if (section.groupName.isEmpty())
                        stringResource(R.string.folder_group_ungrouped)
                    else section.groupName,
                    itemCount = section.items.size,
                    expanded = section.groupName !in collapsedGroups,
                    onToggle = { toggleGroup(section.groupName) },
                ) {
                    GroupRowDivider()
                    section.items.forEachIndexed { index, model ->
                        FolderRowContent(
                            model = model,
                            onEdit = onEdit,
                            onOverride = onOverride,
                            onRevert = onRevert,
                            onReauthorize = onReauthorize,
                        )
                        if (index < section.items.lastIndex) {
                            GroupRowDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceListPage(
    devices: List<DeviceUiModel>?,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    if (devices.isNullOrEmpty()) {
        EmptyListHint(stringResource(R.string.no_devices_configured))
        return
    }
    // Same locale-aware collator and grouping rules as the folder list.
    val groupComparator = remember {
        val collator = java.text.Collator.getInstance()
        collator.strength = java.text.Collator.PRIMARY
        Comparator<String> { a, b -> collator.compare(a, b) }
    }
    val sections = remember(devices, groupComparator) {
        buildDeviceSections(devices, groupComparator)
    }
    val prefs = context.appPreferences()
    var collapsedGroups by remember(prefs) {
        mutableStateOf(
            prefs.getStringSet(Constants.PREF_HOME_COLLAPSED_DEVICE_GROUPS, emptySet()).orEmpty()
        )
    }
    fun toggleGroup(groupName: String) {
        val collapsed = if (groupName in collapsedGroups) {
            collapsedGroups - groupName
        } else {
            collapsedGroups + groupName
        }
        collapsedGroups = collapsed
        val existing = sections.map { it.groupName }.toSet()
        prefs.edit()
            .putStringSet(
                Constants.PREF_HOME_COLLAPSED_DEVICE_GROUPS,
                collapsed.intersect(existing)
            )
            .apply()
    }
    val onEdit: (DeviceUiModel) -> Unit = remember(navigator) {
        { model -> navigator.openDeviceEdit(model.id, false) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch),
            modifier = Modifier
                .contentWidthForTablet(LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP)
                .fillMaxSize(),
            // Keep the last row reachable above the bottom-right FAB.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            items(sections, key = { "dgroup:" + it.groupName }) { section ->
                HomeGroupCard(
                    title = if (section.groupName.isEmpty())
                        stringResource(R.string.folder_group_ungrouped)
                    else section.groupName,
                    itemCount = section.items.size,
                    expanded = section.groupName !in collapsedGroups,
                    onToggle = { toggleGroup(section.groupName) },
                ) {
                    GroupRowDivider()
                    section.items.forEachIndexed { index, model ->
                        DeviceRowContent(
                            model = model,
                            onEdit = onEdit,
                        )
                        if (index < section.items.lastIndex) {
                            GroupRowDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hairline separator used inside the grouped list cards: faint in the AMOLED
 * theme (matching the card outline), regular outlineVariant otherwise.
 */
@Composable
private fun GroupRowDivider() {
    val dividerColor = if (LocalAmoledTheme.current) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = AMOLED_CARD_BORDER_ALPHA)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    HorizontalDivider(
        color = dividerColor,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/**
 * Prefetch strategy that never queues prefetch requests.
 *
 * Workaround for a Compose runtime 1.11 crash where resuming a prefetched
 * (paused) item composition throws
 * "IllegalArgumentException: Cannot disable reuse from root if it was caused
 * by other groups". Prefetching is a pure performance hint, so skipping it
 * only trades a small scroll-ahead cost for stability.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object NoLazyListPrefetch : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}
