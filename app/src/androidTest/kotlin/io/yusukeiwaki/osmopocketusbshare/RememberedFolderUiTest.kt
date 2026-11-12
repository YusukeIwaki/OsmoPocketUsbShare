package io.yusukeiwaki.osmopocketusbshare

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.yusukeiwaki.osmopocketusbshare.data.MediaItem
import io.yusukeiwaki.osmopocketusbshare.data.MediaKind
import io.yusukeiwaki.osmopocketusbshare.theme.OsmoPocketTheme
import io.yusukeiwaki.osmopocketusbshare.ui.BrowserScreen
import io.yusukeiwaki.osmopocketusbshare.ui.BrowserUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only tests: do not revoke the user's real USB grants or modify export history. */
@RunWith(AndroidJUnit4::class)
class RememberedFolderUiTest {
    @get:Rule val compose = createComposeRule()
    private val savedFolder = "content://documents/tree/USB%3ADCIM%2FDJI_001"
    private var pickerCalls = 0

    private fun show(state: BrowserUiState) {
        compose.setContent {
            OsmoPocketTheme {
                BrowserScreen(
                    state = state,
                    onRefresh = {}, onToggleLayout = {}, onItemClick = {}, onItemLongClick = {},
                    onToggleSelection = {}, onClearSelection = {}, onSelectAll = {},
                    onExportSelected = {}, onOpenExports = {}, onPickFolder = { pickerCalls++ },
                )
            }
        }
    }

    @Test
    fun savedFolderWithMedia_showsMediaAndHidesFolderSelection() {
        show(BrowserUiState(
            usbConnected = true, loading = false, savedFolderUri = savedFolder,
            items = listOf(MediaItem(
                id = "photo", uri = Uri.parse("file:///nonexistent/test.jpg"), displayName = "test.jpg",
                kind = MediaKind.PHOTO, sizeBytes = 100, dateTakenMillis = null,
            )),
        ))
        compose.onNodeWithTag("mediaGrid").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").assertDoesNotExist()
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("USBフォルダを開く").assertDoesNotExist()
    }

    @Test
    fun missingOrEmptySavedFolder_offersReplacement() {
        show(BrowserUiState(usbConnected = true, loading = false, savedFolderUri = savedFolder))
        compose.onNodeWithText("前回のフォルダに写真や動画が見つかりませんでした").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").performClick()
        assertEquals(1, pickerCalls)
    }

    @Test
    fun firstConnection_offersInitialSelection() {
        show(BrowserUiState(usbConnected = true, loading = false))
        compose.onNodeWithTag("pickFolderButton").assertIsDisplayed()
        compose.onNodeWithText("USBフォルダを開く").assertIsDisplayed()
    }

    @Test
    fun unplugged_keepsTheUsbPlaceholderWithoutFolderSelection() {
        show(BrowserUiState(usbConnected = false, loading = false, savedFolderUri = savedFolder))
        compose.onNodeWithTag("usbDisconnected").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").assertDoesNotExist()
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("USBフォルダを開く").assertDoesNotExist()
    }

    @Test
    fun loading_doesNotOfferPrematureReselection() {
        show(BrowserUiState(usbConnected = true, loading = true, savedFolderUri = savedFolder))
        compose.onNodeWithTag("loading").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").assertDoesNotExist()
    }

    @Test
    fun permissionFailure_explainsWhySelectionMustBeRetried() {
        show(BrowserUiState(usbConnected = true, loading = false, folderPermissionError = true))
        compose.onNodeWithTag("folderPermissionError").assertIsDisplayed()
        compose.onNodeWithTag("pickFolderButton").assertIsDisplayed()
    }
}
