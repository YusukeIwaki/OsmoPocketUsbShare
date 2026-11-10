package io.yusukeiwaki.osmopocketusbshare.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.yusukeiwaki.osmopocketusbshare.MainActivity
import io.yusukeiwaki.osmopocketusbshare.R
import io.yusukeiwaki.osmopocketusbshare.data.ExportDb
import io.yusukeiwaki.osmopocketusbshare.data.ExportJob
import io.yusukeiwaki.osmopocketusbshare.data.ExportStatus
import io.yusukeiwaki.osmopocketusbshare.data.MediaKind
import io.yusukeiwaki.osmopocketusbshare.data.SlimLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that drains the export queue (ExportDb) one job at a time.
 * - Runs independently of the UI: closing the app does not stop it.
 * - Cancellation is garbage-free: the temp file is deleted and a pending MediaStore
 *   entry is removed before the job is marked CANCELLED.
 * - Output lands in DCIM/OsmoPocketUsbShare via MediaStore so Google Photos can see/sync it.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processing = false

    @Volatile
    private var currentJobId: Long? = null

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            // USB storage was removed mid-export: cancel the running job; processJob maps it to
            // a FAILED state with a USB-specific message and cleans up the temp/pending output.
            // Waiting jobs are failed immediately with the same cause instead of each
            // starting up and failing one after another.
            val db = ExportDb.get(this@ExportService)
            db.failQueued(USB_REMOVED_MESSAGE)
            val jobId = currentJobId ?: return
            detachedJobIds += jobId
            db.requestCancel(jobId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Remove temp files left behind by a killed process so cancelled/killed exports leave no garbage.
        runCatching { File(cacheDir, "export_tmp").listFiles()?.forEach { it.delete() } }
        ExportDb.get(this).markInterruptedAsFailed()
        // Two filters on purpose: an IntentFilter with a "file" data scheme never matches
        // ACTION_USB_DEVICE_DETACHED (it carries no data URI), so a single combined filter
        // silently ignored cable pulls. ACTION_MEDIA_BAD_REMOVAL is the surprise-removal
        // broadcast sent when a mounted volume disappears without a clean eject.
        val usbFilter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val mediaFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, usbDetachReceiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbDetachReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
                if (jobId > 0) ExportDb.get(this).requestCancel(jobId)
            }
            ACTION_CANCEL_ALL -> ExportDb.get(this).queuedJobs().forEach { ExportDb.get(this).requestCancel(it.id) }
        }
        startForeground(NOTIFICATION_ID, buildNotification("エクスポート待機中", null, 0))
        drainQueue()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbDetachReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    @Synchronized
    private fun drainQueue() {
        if (processing) return
        processing = true
        scope.launch {
            try {
                while (true) {
                    val db = ExportDb.get(this@ExportService)
                    val job = db.nextQueued() ?: break
                    processJob(db, job)
                }
                // Queue drained: collapse a fully-finished batch into its summary
                // (children are deleted). A USB-interrupted batch stays ACTIVE so the
                // next export request resumes it instead of opening a new batch.
                ExportDb.get(this@ExportService).finalizeActiveBatch(USB_REMOVED_MESSAGE)
            } finally {
                processing = false
                // DETACH (not REMOVE): the single shared notification must stay in the
                // shade showing the latest result after the service stops.
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun processJob(db: ExportDb, job: ExportJob) {
        currentJobId = job.id
        db.clearCancelRequest(job.id)
        db.update(job.id, status = ExportStatus.RUNNING, progress = 0, error = null)
        notifyProgress(job, 0)
        val tempDir = File(cacheDir, "export_tmp").apply { mkdirs() }
        val isVideo = job.kind == MediaKind.VIDEO
        val tempFile = File(tempDir, "job_${job.id}.tmp${if (isVideo) ".mp4" else ".bin"}")
        var pendingUri: Uri? = null
        val sourceUri = resolveSourceUri(job)
        // Source watchdog: broadcasts alone are not reliable enough to stop an export the
        // moment USB is pulled — an already-open stream can keep feeding the transcoder
        // from buffers, so progress would otherwise keep advancing after removal. Poll the
        // source itself; two consecutive misses (~2s) count as "source gone".
        val watchdog = scope.launch {
            var consecutiveMissing = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (io.yusukeiwaki.osmopocketusbshare.data.MetadataReader.exists(this@ExportService, sourceUri)) {
                    consecutiveMissing = 0
                } else if (++consecutiveMissing >= 2) {
                    detachedJobIds += job.id
                    db.requestCancel(job.id)
                    db.failQueued(USB_REMOVED_MESSAGE)
                    break
                }
            }
        }
        try {
            // Already exported once (e.g. the job DB was reset but the output is still in
            // DCIM/OsmoPocketUsbShare): adopt the existing output instead of writing the
            // file again and making Google Photos re-sync it.
            val existingOutput = findExistingOutput(job)
            if (existingOutput != null) {
                db.update(job.id, status = ExportStatus.COMPLETED, progress = 100, outputUri = existingOutput.first.toString(), outputSize = existingOutput.second)
                notifyFinished(job, success = true)
                return
            }
            if (!isVideo) {
                copyPhoto(sourceUri, tempFile, isCancelled = { db.isCancelRequested(job.id) }) { progress -> if (!db.isCancelRequested(job.id)) db.update(job.id, progress = progress) }
            } else {
                val info = VideoProbe.probe(this, sourceUri)
                if (SlimLogic.isHdrTransfer(info.colorTransfer)) {
                    // slim_videos.py skips SDR compression for HDR/Log: ship the original instead.
                    copyPhoto(sourceUri, tempFile, isCancelled = { db.isCancelRequested(job.id) }) { progress -> if (!db.isCancelRequested(job.id)) db.update(job.id, progress = progress) }
                } else {
                    // Transformer must be created and driven from a Looper (main) thread.
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        VideoTranscoder(this@ExportService).transcode(sourceUri, tempFile, info, isCancelled = { db.isCancelRequested(job.id) }) { progress ->
                            if (!db.isCancelRequested(job.id)) {
                                db.update(job.id, progress = progress)
                                notifyProgress(job, progress)
                            }
                        }
                    }
                }
            }
            if (db.isCancelRequested(job.id)) throw VideoTranscoder.CancelledException()
            if (!tempFile.exists() || tempFile.length() == 0L) throw IllegalStateException("出力ファイルが作成されませんでした")
            if (isVideo && job.sourceSizeBytes > 0) {
                val savings = SlimLogic.savingsPercent(job.sourceSizeBytes, tempFile.length())
                // Keep the smaller file: if savings < 20% the transcode is still a valid 2K copy;
                // slim_videos.py would fall back to the original, so do the same for large sources.
                if (savings < SlimLogic.MIN_SAVINGS_PERCENT && tempFile.length() >= job.sourceSizeBytes) {
                    tempFile.delete()
                    copyPhoto(sourceUri, tempFile, isCancelled = { db.isCancelRequested(job.id) }) {}
                }
            }
            // Stamp the capture date into the MP4 itself (mvhd/tkhd/mdhd creation_time):
            // muxers write the export time, and Google Photos orders videos by this
            // embedded value — slim_videos.py preserves the capture date, so we do too.
            if (isVideo) {
                job.dateTakenMillis?.let { Mp4DatePatcher.setCreationTime(tempFile, it) }
            }
            // The temp file is complete on internal storage now; the USB source may go
            // away during publishing without affecting the export.
            watchdog.cancel()
            pendingUri = publishToMediaStore(job, tempFile)
            tempFile.delete()
            db.update(job.id, status = ExportStatus.COMPLETED, progress = 100, outputUri = pendingUri.toString(), outputSize = jobOutputSize(pendingUri))
            notifyFinished(job, success = true)
        } catch (cancelled: VideoTranscoder.CancelledException) {
            tempFile.delete()
            pendingUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            // A cancel caused by the source vanishing (broadcast or watchdog) is a USB
            // failure, not a user cancel — classify by the source's current existence too,
            // so the outcome does not depend on which detection path fired first.
            val detached = job.id in detachedJobIds ||
                !io.yusukeiwaki.osmopocketusbshare.data.MetadataReader.exists(this, sourceUri)
            if (detached) {
                db.update(job.id, status = ExportStatus.FAILED, progress = 0, error = USB_REMOVED_MESSAGE)
            } else {
                // User cancellation deletes the child record (no tombstone).
                db.deleteJob(job.id)
            }
            notifyFinished(job, success = false, cancelled = !detached)
        } catch (error: Exception) {
            tempFile.delete()
            pendingUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            val detached = job.id in detachedJobIds ||
                !io.yusukeiwaki.osmopocketusbshare.data.MetadataReader.exists(this, sourceUri)
            if (detached) {
                db.update(job.id, status = ExportStatus.FAILED, error = USB_REMOVED_MESSAGE)
                notifyFinished(job, success = false)
            } else if (db.isCancelRequested(job.id)) {
                db.deleteJob(job.id)
                notifyFinished(job, success = false, cancelled = true)
            } else {
                db.update(job.id, status = ExportStatus.FAILED, error = error.message ?: error.javaClass.simpleName)
                notifyFinished(job, success = false)
            }
        } finally {
            watchdog.cancel()
            detachedJobIds.remove(job.id)
            if (currentJobId == job.id) currentJobId = null
            db.clearCancelRequest(job.id)
        }
    }

    private fun resolveSourceUri(job: ExportJob): Uri {
        // MediaItem.id is the MediaStore/SAF uri string, or an absolute file path for inbox/volume files.
        return if (job.sourceUri.startsWith("/") ) Uri.fromFile(File(job.sourceUri)) else Uri.parse(job.sourceUri)
    }

    private fun copyPhoto(source: Uri, dest: File, isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit) {
        contentResolver.openInputStream(source)?.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                val total = runCatching {
                    if (source.scheme == "file") File(source.path!!).length()
                    else contentResolver.query(source, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                    } ?: 0L
                }.getOrDefault(0L)
                while (true) {
                    // Observe cancellation inside the loop: a copy from an already-open USB
                    // stream can otherwise keep running long after removal/cancel.
                    if (isCancelled()) throw VideoTranscoder.CancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied * 100 / total).toInt().coerceIn(0, 99))
                }
            }
        } ?: throw IllegalStateException("元ファイルを開けませんでした")
        onProgress(99)
    }

    /** The output this job would produce, if it already exists in DCIM/OsmoPocketUsbShare. */
    private fun findExistingOutput(job: ExportJob): Pair<Uri, Long>? {
        val isVideo = job.kind == MediaKind.VIDEO
        val outputName = if (isVideo) {
            job.displayName.substringBeforeLast('.') + "_slim.mp4"
        } else {
            job.displayName
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        return runCatching {
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(outputName),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol) ?: continue
                    if (!path.contains("OsmoPocketUsbShare")) continue
                    val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                    return android.content.ContentUris.withAppendedId(collection, cursor.getLong(idCol)) to size
                }
                null
            }
        }.getOrNull()
    }

    private fun publishToMediaStore(job: ExportJob, file: File): Uri {
        val isVideo = job.kind == MediaKind.VIDEO
        val displayName = if (isVideo) {
            val base = job.displayName.substringBeforeLast('.')
            "${base}_slim.mp4"
        } else {
            job.displayName
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else (mimeOf(job.displayName)))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/OsmoPocketUsbShare")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            job.dateTakenMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = contentResolver.insert(collection, values) ?: throw IllegalStateException("保存先を作成できませんでした")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: throw IllegalStateException("保存先を開けませんでした")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
        } catch (error: Exception) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw error
        }
        return uri
    }

    private fun mimeOf(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
        "png" -> "image/png"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    private fun jobOutputSize(uri: Uri?): Long {
        if (uri == null) return 0L
        return runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "エクスポート", NotificationManager.IMPORTANCE_LOW).apply {
            description = "動画・写真のエクスポート進捗"
        }
        manager.createNotificationChannel(channel)
    }

    /** Tapping any export notification lands on the exports screen, not the app top. */
    private fun exportsPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_EXPORTS, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(text: String, job: ExportJob?, progress: Int): Notification {
        val openIntent = exportsPendingIntent()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("OsmoPocket USB Share")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (job != null) {
            builder.setProgress(100, progress, progress <= 0)
            val cancelIntent = Intent(this, ExportCancelReceiver::class.java).apply {
                action = ExportCancelReceiver.ACTION_CANCEL_JOB
                putExtra(ExportCancelReceiver.EXTRA_JOB_ID, job.id)
            }
            val cancelPending = PendingIntent.getBroadcast(
                this, job.id.toInt(), cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "キャンセル", cancelPending)
        }
        return builder.build()
    }

    private fun notifyProgress(job: ExportJob, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification("${job.displayName} をエクスポート中 $progress%", job, progress))
    }

    private fun notifyFinished(job: ExportJob, success: Boolean, cancelled: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        val text = when {
            success -> "${job.displayName} のエクスポートが完了しました"
            cancelled -> "${job.displayName} のエクスポートをキャンセルしました"
            else -> "${job.displayName} のエクスポートに失敗しました"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle("OsmoPocket USB Share")
            .setContentText(text)
            .setContentIntent(exportsPendingIntent())
            .setAutoCancel(true)
            .build()
        // One shared notification id for progress AND results: the shade always shows
        // just the latest export state instead of one notification per file.
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "io.yusukeiwaki.osmopocketusbshare.action.START_EXPORT"
        const val ACTION_CANCEL = "io.yusukeiwaki.osmopocketusbshare.action.CANCEL_EXPORT"
        const val ACTION_CANCEL_ALL = "io.yusukeiwaki.osmopocketusbshare.action.CANCEL_ALL_EXPORTS"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_OPEN_EXPORTS = "extra_open_exports"
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 42
        const val USB_REMOVED_MESSAGE = "USBが取り外されました。接続し直して再度エクスポートしてください。"
        private val detachedJobIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        fun enqueueAndStart(context: Context, items: List<io.yusukeiwaki.osmopocketusbshare.data.MediaItem>) {
            val db = ExportDb.get(context)
            val newIds = db.enqueue(items)
            // Everything selected was already exported: nothing to run at all.
            if (newIds.isEmpty() && db.queuedJobs().isEmpty()) return
            ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java).setAction(ACTION_START))
        }

        fun cancel(context: Context, jobId: Long) {
            ExportDb.get(context).requestCancel(jobId)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ExportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB_ID, jobId),
            )
        }
    }
}

class ExportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL_JOB) {
            val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
            if (jobId > 0) ExportService.cancel(context, jobId)
        }
    }

    companion object {
        const val ACTION_CANCEL_JOB = "io.yusukeiwaki.osmopocketusbshare.action.CANCEL_JOB"
        const val EXTRA_JOB_ID = "extra_job_id"
    }
}
