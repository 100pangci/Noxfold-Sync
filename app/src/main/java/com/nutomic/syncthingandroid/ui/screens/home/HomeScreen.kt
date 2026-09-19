package com.nutomic.syncthingandroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveContent
import com.nutomic.syncthingandroid.ui.adaptive.adaptiveContentSideInset
import com.nutomic.syncthingandroid.ui.adaptive.AdaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.adaptiveWidthClass
import com.nutomic.syncthingandroid.ui.adaptive.rememberWindowSizeClass
import com.nutomic.syncthingandroid.ui.appPreferences
import com.nutomic.syncthingandroid.ui.components.EmptyListHint
import com.nutomic.syncthingandroid.ui.nav.AppRoute
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.theme.AMOLED_CARD_BORDER_ALPHA
import com.nutomic.syncthingandroid.ui.theme.LocalAmoledTheme
import com.nutomic.syncthingandroid.util.isTelevision
import kotlinx.coroutines.launch

private const val TAB_FOLDERS = 0
private const val TAB_DEVICES = 1
private const val TAB_STATUS = 2
private const val HOME_TAB_TRANSITION_MILLIS = 280

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
    selectedFolderId: String? = null,
    selectedDeviceId: String? = null,
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

    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Tablets and desktop windows get a navigation rail; phones and TVs keep the bottom
    // bar (TVs because of their low height and D-pad navigation).
    val windowWidthClass = rememberWindowSizeClass().adaptiveWidthClass
    val useNavigationRail = !LocalConfiguration.current.isTelevision &&
        windowWidthClass != AdaptiveWidthClass.Compact

    // Single source of truth for the selected destination: the rail drives it, the pager
    // follows it (and reports swipes back). Previously the rail kept its own state while
    // the pager kept another, so switching layouts could leave a stale selection that no
    // longer matched the pager.
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_FOLDERS) }
    // Create a fresh pager for every compact session. Re-attaching the PagerState that
    // was used before entering the rail layout left it stuck on its old page; a new
    // state always starts on the currently selected destination.
    val compactPagerState = if (useNavigationRail) {
        null
    } else {
        rememberPagerState(initialPage = selectedTab, pageCount = { 3 })
    }
    val folderListState = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch)
    val deviceListState = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch)
    LaunchedEffect(compactPagerState) {
        val pager = compactPagerState ?: return@LaunchedEffect
        // settledPage only reports where a swipe came to rest, so a programmatic
        // animation does not momentarily drag the selection back to the old page.
        snapshotFlow { pager.settledPage }.collect { selectedTab = it }
    }

    fun selectTab(index: Int) {
        // Re-selecting the active destination closes its detail pane; changing
        // destinations closes it before paging.
        navigator.clearHomeDetail()
        if (index == selectedTab) return
        selectedTab = index
        // The rail animates its own content; the pager follows the selection here.
        compactPagerState?.let { pager ->
            scope.launch {
                pager.animateScrollToPage(
                    page = index,
                    animationSpec = tween(HOME_TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                HomeNavigationRail(
                    selectedTab = selectedTab,
                    onSelectTab = ::selectTab,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // AdaptiveContent centres the list at 840dp. Keep the screen-level
                // Scaffold FAB on that same content edge instead of marooning it at
                // the far right of a wide tablet window.
                val contentSideInset = adaptiveContentSideInset(
                    availableWidth = maxWidth,
                    isTelevision = LocalConfiguration.current.isTelevision,
                )
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            navigationIcon = {
                                // With the rail the drawer button lives in the rail header at
                                // the top-left of the window (see HomeNavigationRail).
                                if (!useNavigationRail) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Outlined.Menu, stringResource(R.string.main_menu))
                                    }
                                }
                            },
                            actions = {
                                if (selectedTab == TAB_FOLDERS) {
                                    IconButton(onClick = {
                                        if (api != null && apiConfigLoaded) {
                                            api.rescanAll()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Refresh,
                                            stringResource(R.string.activity_main_bottom_navigation_rescan_all)
                                        )
                                    }
                                }
                                IconButton(onClick = { navigator.openSettings() }) {
                                    Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                                }
                            },
                            windowInsets = if (useNavigationRail) {
                                // The rail already handles the start side of the system bars.
                                WindowInsets.systemBars.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End
                                )
                            } else {
                                TopAppBarDefaults.windowInsets
                            },
                        )
                    },
                    floatingActionButton = {
                        // Add actions live on a bottom-right FAB (same spot as the folder
                        // editor's save button), tab-aware: each list tab adds its own kind.
                        when (selectedTab) {
                            TAB_FOLDERS -> {
                                FloatingActionButton(
                                    onClick = {
                                        navigator.navigateToHomeDetail(AppRoute.FolderEdit(isCreate = true))
                                    },
                                    modifier = Modifier.padding(end = contentSideInset),
                                ) {
                                    Icon(Icons.Outlined.Add, stringResource(R.string.add_folder))
                                }
                            }
                            TAB_DEVICES -> {
                                FloatingActionButton(
                                    onClick = {
                                        navigator.navigateToHomeDetail(AppRoute.DeviceEdit(isCreate = true))
                                    },
                                    modifier = Modifier.padding(end = contentSideInset),
                                ) {
                                    Icon(Icons.Outlined.Add, stringResource(R.string.add_device))
                                }
                            }
                            else -> {}
                        }
                    },
                    bottomBar = {
                        if (!useNavigationRail) {
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
                                        val selected = selectedTab == index
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { selectTab(index) },
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
                    contentWindowInsets = if (useNavigationRail) {
                        // The rail consumes the vertical and start insets; only the
                        // remaining sides reach the pager.
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.End + WindowInsetsSides.Bottom
                        )
                    } else {
                        ScaffoldDefaults.contentWindowInsets
                    },
                ) { innerPadding ->
                    AdaptiveContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        if (useNavigationRail) {
                            TabletHomeTabContent(
                                selectedTab = selectedTab,
                                folders = folders,
                                devices = devices,
                                serviceState = serviceState,
                                selectedFolderId = selectedFolderId,
                                selectedDeviceId = selectedDeviceId,
                                folderListState = folderListState,
                                deviceListState = deviceListState,
                            )
                        } else if (compactPagerState != null) {
                            HorizontalPager(
                                state = compactPagerState,
                                modifier = Modifier.fillMaxSize(),
                                // Keep all three pages composed. Without this, every tab
                                // switch had to rebuild the target page's whole UI on the
                                // main thread mid-animation, which showed up as jank. Pages
                                // now persist (including their scroll positions) and tab
                                // switches only move the scroll offset.
                                beyondViewportPageCount = 2
                            ) { page ->
                                HomeTabPage(
                                    tab = page,
                                    folders = folders,
                                    devices = devices,
                                    serviceState = serviceState,
                                    selectedFolderId = selectedFolderId,
                                    selectedDeviceId = selectedDeviceId,
                                    folderListState = folderListState,
                                    deviceListState = deviceListState,
                                    statusVisible = compactPagerState.currentPage == TAB_STATUS,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Animated, state-driven destinations for the tablet navigation rail. */
@Composable
private fun TabletHomeTabContent(
    selectedTab: Int,
    folders: List<FolderUiModel>?,
    devices: List<DeviceUiModel>?,
    serviceState: SyncthingService.State,
    selectedFolderId: String?,
    selectedDeviceId: String?,
    folderListState: LazyListState,
    deviceListState: LazyListState,
) {
    AnimatedContent(
        targetState = selectedTab,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (
                slideInHorizontally(
                    animationSpec = tween(HOME_TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing),
                    initialOffsetX = { direction * it / 10 },
                ) + fadeIn(tween(HOME_TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
            ) togetherWith (
                slideOutHorizontally(
                    animationSpec = tween(HOME_TAB_TRANSITION_MILLIS, easing = FastOutSlowInEasing),
                    targetOffsetX = { -direction * it / 10 },
                ) + fadeOut(tween(HOME_TAB_TRANSITION_MILLIS / 2))
            )
        },
        label = "tablet-home-tab",
    ) { tab ->
        HomeTabPage(
            tab = tab,
            folders = folders,
            devices = devices,
            serviceState = serviceState,
            selectedFolderId = selectedFolderId,
            selectedDeviceId = selectedDeviceId,
            folderListState = folderListState,
            deviceListState = deviceListState,
            statusVisible = tab == TAB_STATUS,
        )
    }
}

@Composable
private fun HomeTabPage(
    tab: Int,
    folders: List<FolderUiModel>?,
    devices: List<DeviceUiModel>?,
    serviceState: SyncthingService.State,
    selectedFolderId: String?,
    selectedDeviceId: String?,
    folderListState: LazyListState,
    deviceListState: LazyListState,
    statusVisible: Boolean,
) {
    when (tab) {
        TAB_FOLDERS -> FolderListPage(
            folders = folders,
            selectedFolderId = selectedFolderId,
            listState = folderListState,
        )
        TAB_DEVICES -> DeviceListPage(
            devices = devices,
            selectedDeviceId = selectedDeviceId,
            listState = deviceListState,
        )
        else -> StatusPage(
            serviceState = serviceState,
            visible = statusVisible,
        )
    }
}

/**
 * Primary destinations as a Material 3 navigation rail for tablet and desktop windows.
 * The drawer button sits in the rail header so it stays at the window's top-left corner
 * instead of drifting right with the top bar.
 */
@Composable
private fun HomeNavigationRail(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        header = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.main_menu))
            }
        },
    ) {
        TAB_TITLES.forEachIndexed { index, titleRes ->
            val selected = selectedTab == index
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(index) },
                icon = {
                    Icon(
                        imageVector = if (selected) TAB_ICONS[index].first else TAB_ICONS[index].second,
                        contentDescription = null
                    )
                },
                label = { Text(stringResource(titleRes)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListPage(
    folders: List<FolderUiModel>?,
    selectedFolderId: String?,
    listState: LazyListState,
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
            navigator.navigateToHomeDetail(
                AppRoute.FolderEdit(folderId = target.id, isCreate = false)
            )
        }
        // No manual refresh: HomeDataHost re-polls buildFolderUiModels at
        // GUI_UPDATE_INTERVAL and publishes the updated models via the
        // LocalHomeFolderModels flow/state, restoring the normal card.
    }
    // Stable callbacks: combined with the FolderUiModel data class equality,
    // rows whose content did not change are skipped while scrolling.
    val onEdit: (FolderUiModel) -> Unit = remember(navigator) {
        { model ->
            navigator.navigateToHomeDetail(
                AppRoute.FolderEdit(folderId = model.id, isCreate = false)
            )
        }
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
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
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
                        selected = model.id == selectedFolderId,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceListPage(
    devices: List<DeviceUiModel>?,
    selectedDeviceId: String?,
    listState: LazyListState,
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
        { model ->
            navigator.navigateToHomeDetail(
                AppRoute.DeviceEdit(deviceId = model.id, isCreate = false)
            )
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
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
                        selected = model.id == selectedDeviceId,
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
