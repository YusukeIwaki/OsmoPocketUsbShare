package io.yusukeiwaki.osmopocketusbshare

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.yusukeiwaki.osmopocketusbshare.data.ExportDb
import io.yusukeiwaki.osmopocketusbshare.data.ExportStatus
import io.yusukeiwaki.osmopocketusbshare.data.MediaItem
import io.yusukeiwaki.osmopocketusbshare.data.MediaKind
import io.yusukeiwaki.osmopocketusbshare.data.MediaRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumentation tests driving the real UI on a device/emulator.
 * Test media is written into the app "usb_inbox" folder, which MediaRepository
 * treats as a mounted OTG volume (the same code path lists MediaStore USB volumes
 * and SAF trees on real hardware). Screenshots are saved under
 * files/test_screenshots/ for test_report.html.
 */
@RunWith(AndroidJUnit4::class)
class OsmoPocketUiTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    // Grant runtime permissions before the activity launches so no system dialog blocks Compose.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(
            androidx.test.rule.GrantPermissionRule.grant(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
            ),
        )
        .around(composeRule)

    private lateinit var appContext: Context
    private lateinit var inbox: File
    private lateinit var shotDir: File

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        inbox = File(appContext.getExternalFilesDir(null), "usb_inbox").apply { mkdirs() }
        shotDir = File(appContext.getExternalFilesDir(null), "test_screenshots").apply { mkdirs() }
        clearInbox()
        // Isolate from real USB/SAF state on a developer device: revoke persisted folder
        // grants so only the inbox test media is listed. (Re-grant manually after test runs.)
        for (permission in appContext.contentResolver.persistedUriPermissions) {
            runCatching {
                appContext.revokeUriPermission(
                    permission.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        appContext.getSharedPreferences("usb_folder", Context.MODE_PRIVATE).edit().clear().commit()
        // Note: never delete the live DB file here — the app process holds it open.
        // Export assertions below filter by each test's unique file name instead.
        // Start each test with an empty export history (batches + children) so batch
        // assertions are isolated; this goes through the DB file, not the live instance.
        ExportDb(appContext).clearHistory()
    }

    @After
    fun tearDown() {
        clearInbox()
    }

    /** Fresh installs show runtime-permission dialogs over MainActivity; allow them via UiAutomator. */
    private fun dismissPermissionDialogs() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(6) {
            val allow = device.findObject(androidx.test.uiautomator.UiSelector().text("許可"))
            if (allow != null && allow.exists()) {
                runCatching { allow.click() }
                Thread.sleep(600)
            } else {
                return
            }
        }
    }

    private fun clearInbox() {
        inbox.listFiles()?.forEach { it.delete() }
    }

    private fun writePhoto(name: String): File {
        val file = File(inbox, name)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(40, 90, 160))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun copyAsset(assetName: String, destName: String): File {
        val dest = File(inbox, destName)
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        }
        return dest
    }

    private fun relaunch() {
        // The activity is launched by the compose rule before the test body; permission dialogs
        // (fresh installs) must be dismissed here, not in @Before (which runs before launch).
        dismissPermissionDialogs()
        // Media is (re)scanned when the user taps refresh; use the real UI path to pick up new inbox files.
        composeRule.onNodeWithContentDescription("再読み込み").performClick()
        composeRule.waitForIdle()
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue("screenshot $name", bitmap != null)
        FileOutputStream(File(shotDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(300)
                composeRule.waitForIdle()
            }
        }
        throw AssertionError("tag $tag not displayed within ${timeoutMs}ms", lastError)
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                composeRule.onNodeWithText(text).assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(300)
                composeRule.waitForIdle()
            }
        }
        throw AssertionError("text $text not displayed within ${timeoutMs}ms", lastError)
    }

    /** Wait until the latest batch is DONE (finalized: children collapsed into the summary). */
    private fun waitForBatchDone(timeoutMs: Long = 180_000): io.yusukeiwaki.osmopocketusbshare.data.ExportBatch {
        val db = ExportDb(appContext)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            db.refresh()
            val batch = db.latestBatch
            if (batch != null && batch.status == io.yusukeiwaki.osmopocketusbshare.data.BatchStatus.DONE) return batch
            Thread.sleep(500)
        }
        db.refresh()
        throw AssertionError("batch not DONE within ${timeoutMs}ms; batches=${db.batches.value}")
    }

    /** User cancellation deletes the child row: wait until no job with this name remains. */
    private fun waitForJobAbsent(displayName: String, timeoutMs: Long = 60_000) {
        val db = ExportDb(appContext)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            db.refresh()
            if (db.jobs.value.none { it.displayName == displayName }) return
            Thread.sleep(300)
        }
        db.refresh()
        throw AssertionError("job $displayName still present; jobs=${db.jobs.value.map { it.displayName to it.status }}")
    }

    private fun waitForExportStatus(displayName: String, predicate: (ExportStatus) -> Boolean, timeoutMs: Long = 120_000): ExportStatus {
        val db = ExportDb(appContext)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            db.refresh()
            val match = db.jobs.value.filter { it.displayName == displayName }.map { it.status }.firstOrNull(predicate)
            if (match != null) return match
            Thread.sleep(500)
        }
        db.refresh()
        throw AssertionError("export status not reached for $displayName; jobs=${db.jobs.value.map { it.displayName to it.status }}")
    }

    @Test
    fun usbDisconnected_showsPlaceholderOnly() {
        clearInbox()
        relaunch()
        waitForTag("usbDisconnected")
        composeRule.onNodeWithText("USBでOsmo Pocketをさしてください").assertIsDisplayed()
        screenshot("test_usb_disconnected.png")
    }

    @Test
    fun gridListToggle_showsThumbnailNameSizeDate() {
        writePhoto("IMG_20260101_120000_001.jpg")
        copyAsset("test_video_4k.mp4", "DJI_20260101120100_0002_D.MP4")
        relaunch()
        waitForTag("mediaGrid")
        screenshot("test_grid.png")

        composeRule.onNodeWithTag("layoutToggle").performClick()
        waitForTag("mediaList")
        // Material 3 ListItem: thumbnail (leading), file name (headline), size/date (supporting).
        composeRule.onNodeWithText("IMG_20260101_120000_001.jpg").assertIsDisplayed()
        screenshot("test_list.png")

        composeRule.onNodeWithTag("layoutToggle").performClick()
        waitForTag("mediaGrid")
    }

    @Test
    fun longPressSelection_deselectToZero_exitsSelectionMode() {
        writePhoto("IMG_20260102_120000_001.jpg")
        writePhoto("IMG_20260102_120100_002.jpg")
        relaunch()
        waitForTag("mediaGrid")

        composeRule.onNodeWithTag("mediaItem_IMG_20260102_120000_001.jpg")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        screenshot("test_selection_1.png")

        // Selection action bar: text-only "すべて選択"; export lives only on the FAB.
        composeRule.onNodeWithText("すべて選択").assertIsDisplayed()
        composeRule.onNodeWithTag("exportSelectedTop").assertDoesNotExist()
        composeRule.onNodeWithTag("exportSelectedFab").assertIsDisplayed()
        composeRule.onNodeWithTag("selectAll").performClick()
        waitForText("2件を選択中")
        screenshot("test_selection_all.png")

        // Tapping items off again drops the selection to zero -> selection mode must exit.
        composeRule.onNodeWithTag("mediaItem_IMG_20260102_120000_001.jpg").performClick()
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("mediaItem_IMG_20260102_120100_002.jpg").performClick()
        waitForText("OsmoPocket USB Share")
        composeRule.onNodeWithText("OsmoPocket USB Share").assertIsDisplayed()
        screenshot("test_selection_cleared.png")
    }

    @Test
    fun detailSwipe_metadataAndBack_returnsToGrid() {
        writePhoto("IMG_20260103_120000_001.jpg")
        copyAsset("test_video_4k.mp4", "DJI_20260103120100_0002_D.MP4")
        relaunch()
        waitForTag("mediaGrid")

        composeRule.onNodeWithTag("mediaItem_DJI_20260103120100_0002_D.MP4").performClick()
        waitForTag("detailPager")
        waitForTag("metadataPanel")
        // Capture date and file size are mandatory metadata rows.
        composeRule.onNodeWithText("撮影日時").assertIsDisplayed()
        composeRule.onNodeWithText("ファイルサイズ").assertIsDisplayed()
        screenshot("test_detail.png")

        composeRule.onNodeWithTag("detailPager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        screenshot("test_detail_swipe.png")

        Espresso.pressBack()
        waitForTag("mediaGrid")
        screenshot("test_back_to_grid.png")
    }

    @Test
    fun detailPhoto_showsFullResolutionAndVideoDuration() {
        writePhoto("IMG_20260501_120000_001.jpg") // generated at 640x480
        copyAsset("test_video_4k.mp4", "DJI_20260501120100_0002_D.MP4")
        relaunch()
        waitForTag("mediaGrid")

        // Video detail must show its real duration (recorded in the file), not 不明.
        composeRule.onNodeWithTag("mediaItem_DJI_20260501120100_0002_D.MP4").performClick()
        waitForTag("detailPager")
        composeRule.onNodeWithTag("metadata_長さ").assertIsDisplayed()
        composeRule.onNodeWithText("0:04").assertIsDisplayed()
        Espresso.pressBack()
        waitForTag("mediaGrid")

        // Photo detail must decode the original (640x480), not an upscaled thumbnail.
        composeRule.onNodeWithTag("mediaItem_IMG_20260501_120000_001.jpg").performClick()
        waitForTag("detailBitmap_640x480")
        screenshot("test_detail_full_photo.png")
    }

    @Test
    fun detailMenuExport_photoCompletes() {
        writePhoto("IMG_20260104_120000_001.jpg")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_IMG_20260104_120000_001.jpg").performClick()
        waitForTag("detailPager")
        composeRule.onNodeWithTag("detailMenu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("エクスポート").performClick()
        // Completion collapses the batch: children are deleted, only the summary remains.
        val batch = waitForBatchDone(timeoutMs = 60_000)
        assertTrue("expected 1 succeeded in batch summary: $batch", batch.totalCount == 1 && batch.succeededCount == 1)
        screenshot("test_export_photo_completed.png")
    }

    @Test
    fun multiSelectExport_videoTranscodesTo2k() {
        copyAsset("test_video_4k.mp4", "DJI_20260105120100_0009_D.MP4")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_DJI_20260105120100_0009_D.MP4")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForBatchDone(timeoutMs = 180_000)
        screenshot("test_export_video_completed.png")

        // The exported *_slim.mp4 must be 2560 wide (2K) in DCIM/OsmoPocketUsbShare.
        // (Child rows are gone once the batch completes, so find the output itself.)
        val outputUri = appContext.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("DJI_20260105120100_0009_D_slim.mp4"),
            "${MediaStore.MediaColumns._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cursor.getLong(0),
            ) else null
        }
        assertTrue("exported output must exist in MediaStore", outputUri != null)
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, outputUri)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            assertTrue("expected 2K width 2560 but was $width", width == 2560)
            // The MP4 creation_time must be the capture date (from the filename here),
            // not the export date — Google Photos orders videos by this embedded value.
            val date = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
            assertTrue("expected capture date 20260105 in output metadata but was $date", date != null && date.startsWith("20260105"))
        } finally {
            retriever.release()
        }
    }

    @Test
    fun cancelLongExport_leavesNoOutputOrTempFile() {
        copyAsset("test_video_long.mp4", "DJI_20260201120100_0099_D.MP4")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_DJI_20260201120100_0099_D.MP4")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()

        // Cancel from the exports screen as soon as the cancel action appears.
        val deadline = System.currentTimeMillis() + 30_000
        var clicked = false
        while (System.currentTimeMillis() < deadline && !clicked) {
            try {
                composeRule.onAllNodesWithContentDescription("キャンセル")[0].performClick()
                clicked = true
            } catch (error: Throwable) {
                Thread.sleep(300)
                composeRule.waitForIdle()
            }
        }
        assertTrue("cancel button should appear", clicked)
        // User cancellation deletes the child record outright (no CANCELLED tombstone).
        waitForJobAbsent("DJI_20260201120100_0099_D.MP4", timeoutMs = 60_000)
        screenshot("test_export_cancelled.png")

        // No garbage: no published *_slim.mp4 for this source, and no temp files remain.
        val slimName = "DJI_20260201120100_0099_D_slim.mp4"
        appContext.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(slimName),
            null,
        )?.use { cursor ->
            assertFalse("cancelled export must not publish an output file", cursor.moveToFirst())
        }
        val tempDir = File(appContext.cacheDir, "export_tmp")
        val leftovers = tempDir.listFiles()?.filter { it.length() > 0 } ?: emptyList()
        assertTrue("temp files must be cleaned up, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun sourceRemovedWhileQueued_failsWithoutOutputOrTemp() {
        // Two long videos: the first occupies the exporter; the second's source disappears
        // while it waits — the same failure path as a USB volume vanishing before/during export.
        copyAsset("test_video_long.mp4", "DJI_20260301120000_0101_D.MP4")
        val second = copyAsset("test_video_long.mp4", "DJI_20260301120100_0102_D.MP4")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_DJI_20260301120000_0101_D.MP4")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("mediaItem_DJI_20260301120100_0102_D.MP4").performClick()
        waitForText("2件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()

        // Remove the queued job's source before the exporter reaches it.
        assertTrue("source delete", second.delete())

        waitForExportStatus("DJI_20260301120000_0101_D.MP4", { it == ExportStatus.COMPLETED }, timeoutMs = 180_000)
        waitForExportStatus("DJI_20260301120100_0102_D.MP4", { it == ExportStatus.FAILED }, timeoutMs = 60_000)
        screenshot("test_source_removed_failed.png")

        // The batch is USB-interrupted, hence NOT finalized: it stays ACTIVE with both
        // children visible so a later export can resume it.
        val db2 = ExportDb(appContext)
        db2.refresh()
        val batch = db2.latestBatch
        assertTrue("USB-interrupted batch must stay ACTIVE: $batch", batch != null && batch.status == io.yusukeiwaki.osmopocketusbshare.data.BatchStatus.ACTIVE)
        assertTrue("both children must remain visible in the interrupted batch", db2.jobs.value.size == 2)

        val slimName = "DJI_20260301120100_0102_D_slim.mp4"
        appContext.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(slimName),
            null,
        )?.use { cursor ->
            assertFalse("failed export must not publish an output file", cursor.moveToFirst())
        }
        val tempDir = File(appContext.cacheDir, "export_tmp")
        val leftovers = tempDir.listFiles()?.filter { it.length() > 0 } ?: emptyList()
        assertTrue("temp files must be cleaned up, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun sourceRemovedDuringExport_stopsWithUsbMessageAndNoOutput() {
        // Pull-the-cable simulation for a RUNNING job: deleting the source has the same
        // effect as its USB volume vanishing. The exporter must notice promptly (source
        // watchdog), stop, and fail with the USB-removed message — an already-open stream
        // must not let progress keep advancing.
        val name = "DJI_20260302120100_0103_D.MP4"
        val source = copyAsset("test_video_long.mp4", name)
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForExportStatus(name, { it == ExportStatus.RUNNING }, timeoutMs = 30_000)

        assertTrue("source delete", source.delete())

        waitForExportStatus(name, { it == ExportStatus.FAILED }, timeoutMs = 60_000)
        screenshot("test_source_removed_during_export.png")

        val db = ExportDb(appContext)
        db.refresh()
        val job = db.jobs.value.first { it.displayName == name && it.status == ExportStatus.FAILED }
        assertTrue("expected USB-removed message but was: ${job.errorMessage}", job.errorMessage?.contains("USB") == true)

        val slimName = "DJI_20260302120100_0103_D_slim.mp4"
        appContext.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(slimName),
            null,
        )?.use { cursor ->
            assertFalse("failed export must not publish an output file", cursor.moveToFirst())
        }
        val tempDir = File(appContext.cacheDir, "export_tmp")
        val leftovers = tempDir.listFiles()?.filter { it.length() > 0 } ?: emptyList()
        assertTrue("temp files must be cleaned up, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun reexportCompletedFile_isSkipped() {
        // Exporting the same file again must not create a new job (and must not rewrite
        // the output or re-trigger Google Photos sync): completed files are skipped.
        // Unique name per run: a previous run's completed record must not leak into this one.
        val name = "IMG_20260701_120000_${System.currentTimeMillis() % 100000}.jpg"
        writePhoto(name)
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForBatchDone(timeoutMs = 60_000)

        composeRule.onNodeWithTag("exportsBack").performClick()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        Thread.sleep(2_000)
        composeRule.waitForIdle()

        // The output must still exist exactly once: no rewrite, no duplicate (1) file,
        // so Google Photos gets no spurious update for an already-exported photo.
        val count = appContext.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            null,
        )?.use { cursor -> cursor.count } ?: -1
        assertTrue("output must exist exactly once, count=$count", count == 1)
        val db = ExportDb(appContext)
        db.refresh()
        assertTrue("latest batch must be DONE after the skipped re-export", db.latestBatch?.status == io.yusukeiwaki.osmopocketusbshare.data.BatchStatus.DONE)
        screenshot("test_reexport_skipped.png")
    }

    @Test
    fun reexportAfterFailure_replacesStaleFailureRecord() {
        // After a USB-removal failure, the batch stays incomplete (ACTIVE); re-exporting
        // resumes THAT batch (same id), and once it completes only the summary remains —
        // no stale failure row is left on screen.
        val name = "DJI_20260801120100_${System.currentTimeMillis() % 100000}_D.MP4"
        val source = copyAsset("test_video_long.mp4", name)
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForExportStatus(name, { it == ExportStatus.RUNNING }, timeoutMs = 30_000)
        assertTrue("source delete", source.delete())
        waitForExportStatus(name, { it == ExportStatus.FAILED }, timeoutMs = 60_000)

        val db = ExportDb(appContext)
        db.refresh()
        val interrupted = db.latestBatch
        assertTrue("interrupted batch must stay ACTIVE", interrupted != null && interrupted.status == io.yusukeiwaki.osmopocketusbshare.data.BatchStatus.ACTIVE)
        val interruptedBatchId = interrupted!!.id

        // "Replug": restore the source and export the same file again.
        copyAsset("test_video_long.mp4", name)
        composeRule.onNodeWithTag("exportsBack").performClick()
        waitForTag("mediaGrid")
        relaunch()
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        val done = waitForBatchDone(timeoutMs = 180_000)

        assertTrue("resume must reuse the interrupted batch", done.id == interruptedBatchId)
        assertTrue("summary must record the success: $done", done.totalCount == 1 && done.succeededCount == 1 && done.failedCount == 0)
        db.refresh()
        assertTrue("children must be collapsed into the summary", db.jobs.value.none { it.displayName == name })
        screenshot("test_reexport_after_failure.png")
    }

    @Test
    fun exportsHistory_canBeCleared() {
        val name = "IMG_20260901_120000_${System.currentTimeMillis() % 100000}.jpg"
        writePhoto(name)
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$name")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForBatchDone(timeoutMs = 60_000)
        // Batch DONE: the screen shows the summary, not child rows.
        waitForTag("exportsSummary")

        composeRule.onNodeWithText("履歴を消す").assertIsDisplayed()
        composeRule.onNodeWithTag("clearExportHistory").performClick()
        composeRule.waitForIdle()
        waitForTag("exportsEmpty")

        // Deleting history removes batches AND all their children.
        val db = ExportDb(appContext)
        db.refresh()
        assertTrue("all batches must be deleted", db.batches.value.isEmpty())
        assertTrue("all children must be deleted", db.jobs.value.isEmpty())
        screenshot("test_history_cleared.png")
    }

    @Test
    fun batchEnqueueWhileRunning_joinsSameBatch() {
        // A second export request that arrives while a batch is still running must be
        // appended as a child of that batch, not start a new one. When the batch fully
        // completes, children collapse into the summary.
        val videoName = "DJI_20261001120100_${System.currentTimeMillis() % 100000}_D.MP4"
        val photoName = "IMG_20261001_120000_${System.currentTimeMillis() % 100000}.jpg"
        copyAsset("test_video_long.mp4", videoName)
        writePhoto(photoName)
        relaunch()
        waitForTag("mediaGrid")

        composeRule.onNodeWithTag("mediaItem_$videoName")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()
        waitForExportStatus(videoName, { it == ExportStatus.RUNNING }, timeoutMs = 30_000)

        // While the video is still transcoding, queue the photo into the same batch.
        composeRule.onNodeWithTag("exportsBack").performClick()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_$photoName")
            .performTouchInput { longClick() }
        waitForText("1件を選択中")
        composeRule.onNodeWithTag("exportSelectedFab").performClick()

        val db = ExportDb(appContext)
        val deadline = System.currentTimeMillis() + 30_000
        var children: List<io.yusukeiwaki.osmopocketusbshare.data.ExportJob> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            db.refresh()
            children = db.jobs.value
            if (children.count { it.displayName == videoName || it.displayName == photoName } == 2) break
            Thread.sleep(300)
        }
        assertTrue("both exports must be children of one batch: $children", children.size == 2)
        assertTrue("only one batch may exist", db.batches.value.size == 1)
        assertTrue("children share the batch id", children.map { it.batchId }.distinct().size == 1)

        val done = waitForBatchDone(timeoutMs = 240_000)
        assertTrue("summary aggregates both children: $done", done.totalCount == 2 && done.succeededCount == 2)
        db.refresh()
        assertTrue("children collapse after completion", db.jobs.value.isEmpty())
        screenshot("test_batch_joined.png")
    }

    @Test
    fun batchMaxTwoRetained_oldestHistoryPruned() {
        // Three sequential one-photo batches: only the two newest batches are kept
        // (one history + the latest), older ones are pruned with their summaries.
        val db = ExportDb(appContext)
        repeat(3) { index ->
            val name = "IMG_20261101_12000${index}_${System.currentTimeMillis() % 100000}.jpg"
            writePhoto(name)
            relaunch()
            waitForTag("mediaGrid")
            composeRule.onNodeWithTag("mediaItem_$name")
                .performTouchInput { longClick() }
            waitForText("1件を選択中")
            composeRule.onNodeWithTag("exportSelectedFab").performClick()
            waitForBatchDone(timeoutMs = 60_000)
            composeRule.onNodeWithTag("exportsBack").performClick()
            waitForTag("mediaGrid")
        }
        db.refresh()
        assertTrue("at most 2 batches are kept, found ${db.batches.value.size}", db.batches.value.size == 2)
        assertTrue("kept batches are DONE summaries", db.batches.value.all { it.status == io.yusukeiwaki.osmopocketusbshare.data.BatchStatus.DONE })
        screenshot("test_batch_pruned.png")
    }

    @Test
    fun duplicateSources_dedupedByNameAndSize() {
        // Same physical file reported by two sources (different URIs, e.g. MediaStore after
        // a replug + the SAF tree) must appear once; the higher-priority source wins, and
        // unknown-size items are never merged.
        fun item(id: String, name: String, size: Long, kind: MediaKind) = MediaItem(
            id = id, uri = android.net.Uri.parse(id), displayName = name, kind = kind,
            sizeBytes = size, dateTakenMillis = 1767225600000L,
        )
        val fromMediaStore = item("content://media/85b0-be1e/video/media/42", "DJI_20260101120000_0001_D.MP4", 123_456L, MediaKind.VIDEO)
        val fromSaf = item("content://com.android.externalstorage.documents/tree/85B0-BE1E%3ADCIM/document/85B0-BE1E%3ADCIM%2FDJI_20260101120000_0001_D.MP4", "DJI_20260101120000_0001_D.MP4", 123_456L, MediaKind.VIDEO)
        val distinct = item("content://media/85b0-be1e/images/media/7", "IMG_20260101_120000_001.jpg", 999L, MediaKind.PHOTO)
        val unknownA = item("content://media/85b0-be1e/images/media/8", "IMG_20260101_120100_002.jpg", 0L, MediaKind.PHOTO)
        val unknownB = item("content://com.android.externalstorage.documents/tree/x/document/y", "IMG_20260101_120100_002.jpg", 0L, MediaKind.PHOTO)

        val result = MediaRepository.dedupeAcrossSources(listOf(fromMediaStore, fromSaf, distinct, unknownA, unknownB))

        assertTrue("expected 4 items after dedupe but got ${result.size}: $result", result.size == 4)
        assertTrue("higher-priority source must win", result.first { it.displayName == fromMediaStore.displayName } == fromMediaStore)
    }

    @Test
    fun mediaRemoved_refreshClearsListToPlaceholder() {
        val photo = writePhoto("IMG_20260402_120000_001.jpg")
        relaunch()
        waitForTag("mediaGrid")
        assertTrue(photo.delete())
        relaunch() // user-visible refresh path, same as the USB-detach rescan
        waitForTag("usbDisconnected")
        composeRule.onNodeWithText("USBでOsmo Pocketをさしてください").assertIsDisplayed()
        screenshot("test_media_removed_placeholder.png")
    }

    @Test
    fun detailSourceRemoved_showsUnavailableMessage() {
        val photo = writePhoto("IMG_20260401_120000_001.jpg")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_IMG_20260401_120000_001.jpg").performClick()
        waitForTag("detailPager")

        assertTrue(photo.delete())
        // Recreate the activity (scenario-managed leave-and-return) so the detail
        // screen re-checks its source; raw startActivity calls spawn a second activity
        // instance on this platform and corrupt the test rule's bookkeeping.
        composeRule.activityRule.scenario.recreate()

        // Two correct outcomes, depending on which rescan wins: the detail either reports the
        // missing source, or the refreshed (now empty) list pops back to the USB placeholder.
        // A silently broken image is the only failure mode.
        val deadline = System.currentTimeMillis() + 20_000
        var settled = false
        while (System.currentTimeMillis() < deadline && !settled) {
            settled = runCatching {
                composeRule.onNodeWithText("このメディアは読み込めません。USBが取り外された可能性があります。").assertIsDisplayed()
                true
            }.getOrDefault(false) || runCatching {
                composeRule.onNodeWithTag("usbDisconnected").assertIsDisplayed()
                true
            }.getOrDefault(false)
            if (!settled) {
                Thread.sleep(300)
                composeRule.waitForIdle()
            }
        }
        assertTrue("detail must not keep showing a broken image after its source is removed", settled)
        screenshot("test_detail_unavailable.png")
    }

    @Test
    fun rotation_keepsContentWithoutReloading() {
        writePhoto("IMG_20260601_120000_001.jpg")
        copyAsset("test_video_4k.mp4", "DJI_20260601120100_0002_D.MP4")
        relaunch()
        waitForTag("mediaGrid")
        composeRule.onNodeWithTag("mediaItem_DJI_20260601120100_0002_D.MP4").performClick()
        waitForTag("detailPager")
        composeRule.onNodeWithTag("detailPager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()

        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.setOrientationLeft()
        composeRule.waitForIdle()
        Thread.sleep(500)
        // Rotation must not fall back to the loading spinner, and must keep the swiped page.
        composeRule.onNodeWithTag("loading").assertDoesNotExist()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()
        screenshot("test_rotation_landscape.png")

        device.setOrientationNatural()
        composeRule.waitForIdle()
        Thread.sleep(500)
        composeRule.onNodeWithTag("loading").assertDoesNotExist()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()
    }

    @Test
    fun backFromExports_returnsToBrowser() {
        writePhoto("IMG_20260107_120000_001.jpg")
        relaunch()
        waitForTag("mediaGrid")
        // Open exports via the overflow menu.
        composeRule.onNodeWithContentDescription("メニュー").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("エクスポート状況").performClick()
        waitForText("エクスポート状況")
        Espresso.pressBack()
        waitForTag("mediaGrid")
    }

    // NOTE: the notification deep link (tap -> exports screen) is verified manually on
    // device (see test_report.html section 20, screenshots/14_notification_deeplink.png).
    // Every instrumentation variant of delivering an intent to the compose rule's
    // activity destabilized ActivityScenario teardown on this device, so it is
    // intentionally not part of the automated suite.
}
