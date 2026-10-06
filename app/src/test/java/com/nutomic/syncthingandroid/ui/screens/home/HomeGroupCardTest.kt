package com.nutomic.syncthingandroid.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.nav.AppNavigator
import com.nutomic.syncthingandroid.ui.nav.LocalAppNavigator
import com.nutomic.syncthingandroid.ui.theme.LocalAmoledTheme
import com.nutomic.syncthingandroid.ui.theme.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SyncthingApp::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGroupCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SyncthingApp>()
    private val navigator: AppNavigator = mock()
    private lateinit var listState: LazyListState

    private fun folder(index: Int) = FolderUiModel(
        id = "f$index", group = "Test group", title = "Folder $index", path = "/test/$index",
        typeTag = Constants.FOLDER_TYPE_SEND_RECEIVE, pathShort = "/test/$index",
        overrideVisible = false, revertVisible = false, revertLabelRes = 0,
        conflictCount = 0, conflictFiles = emptyList(), lastItemText = null,
        lastItemTimeText = null, itemsAndSize = null, invalidText = null,
        statusText = "Idle", statusKind = StatusKind.OK, isSyncing = false,
        completion = 100, needsSafAuthorization = false,
    )

    private fun showFolders(amoled: Boolean = false) {
        val models = List(100, ::folder)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalAppNavigator provides navigator,
                    LocalAmoledTheme provides amoled,
                ) {
                    listState = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch)
                    FolderListPage(models, selectedFolderId = null, listState = listState)
                }
            }
        }
    }

    @Test
    fun largeFolderGroup_hasIndependentLazyRowsAndCanReachLastRow() {
        showFolders()
        composeRule.runOnIdle { assertEquals(101, listState.layoutInfo.totalItemsCount) }
        composeRule.onNodeWithText("Folder 99").assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(100)
        composeRule.onNodeWithText("Folder 99").assertIsDisplayed()
    }

    @Test
    fun collapse_removesRowsAndPersistsState_expandRestoresRows() {
        showFolders(amoled = true)
        composeRule.onNodeWithText("Test group").performClick()
        composeRule.runOnIdle {
            assertEquals(1, listState.layoutInfo.totalItemsCount)
            assertEquals(setOf("Test group"), app.preferences.getStringSet(Constants.PREF_HOME_COLLAPSED_FOLDER_GROUPS, emptySet()))
        }
        composeRule.onNodeWithText("Folder 0").assertDoesNotExist()
        composeRule.onNodeWithText("Test group").performClick()
        composeRule.runOnIdle { assertEquals(101, listState.layoutInfo.totalItemsCount) }
        composeRule.onNodeWithText("Folder 0").assertIsDisplayed()
    }

    @Test
    fun initiallyCollapsedGroup_onlyCreatesHeader() {
        app.preferences.edit().putStringSet(Constants.PREF_HOME_COLLAPSED_FOLDER_GROUPS, setOf("Test group")).commit()
        showFolders()
        composeRule.runOnIdle { assertEquals(1, listState.layoutInfo.totalItemsCount) }
        composeRule.onNodeWithText("Folder 0").assertDoesNotExist()
    }

    @Test
    fun statusRefresh_keepsLazyRowKeysAndScrollPosition() {
        val models = mutableStateOf(List(100, ::folder))
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    listState = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch)
                    FolderListPage(models.value, selectedFolderId = null, listState = listState)
                }
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(50)
        var firstKey: Any? = null
        var offset = 0
        composeRule.runOnIdle {
            firstKey = listState.layoutInfo.visibleItemsInfo.first().key
            offset = listState.firstVisibleItemScrollOffset
            models.value = models.value.map { it.copy(statusText = "Updated") }
        }
        composeRule.runOnIdle {
            assertEquals(firstKey, listState.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun largeDeviceGroup_alsoUsesIndependentRows() {
        val models = List(100) { index ->
            DeviceUiModel(
                id = "d$index", group = "Devices", displayName = "Device $index",
                lastSeenText = "", sharedFolderNames = emptyList(), statusText = "Idle",
                statusKind = StatusKind.OK, isSyncing = false, completion = 100, rateText = null,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppNavigator provides navigator) {
                    listState = rememberLazyListState(prefetchStrategy = NoLazyListPrefetch)
                    DeviceListPage(models, selectedDeviceId = null, listState = listState)
                }
            }
        }
        composeRule.runOnIdle { assertEquals(101, listState.layoutInfo.totalItemsCount) }
        composeRule.onNodeWithText("Device 99").assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(100)
        composeRule.onNodeWithText("Device 99").assertIsDisplayed()
    }
}
