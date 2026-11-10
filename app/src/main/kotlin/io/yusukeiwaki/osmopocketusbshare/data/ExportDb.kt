package io.yusukeiwaki.osmopocketusbshare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExportStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

enum class BatchStatus { ACTIVE, DONE }

data class ExportJob(
    val id: Long,
    val batchId: Long,
    val sourceUri: String,
    val displayName: String,
    val kind: MediaKind,
    val sourceSizeBytes: Long,
    val dateTakenMillis: Long?,
    val status: ExportStatus,
    val progressPercent: Int,
    val outputUri: String?,
    val outputSizeBytes: Long,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One export "session". While ACTIVE it owns child rows in export_jobs; when it fully
 * completes, the children are deleted and only this summary (the final result) remains.
 * A batch interrupted by USB removal stays ACTIVE so a later export request resumes it
 * by appending children instead of starting a new batch.
 */
data class ExportBatch(
    val id: Long,
    val status: BatchStatus,
    val totalCount: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * SQLite-backed export store with a batch model:
 * - At most [MAX_BATCHES] batches are kept: one ACTIVE (in progress / interrupted)
 *   plus one DONE history batch.
 * - The exports screen shows the children of the latest batch, or its summary once DONE.
 * - User cancellation deletes the child row outright (no CANCELLED tombstones).
 * The foreground service is the only writer of progress; screens observe the flows.
 */
class ExportDb(context: Context) : SQLiteOpenHelper(context, "exports.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE export_batches(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              status TEXT NOT NULL,
              total INTEGER NOT NULL DEFAULT 0,
              succeeded INTEGER NOT NULL DEFAULT 0,
              failed INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE export_jobs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              batch_id INTEGER NOT NULL DEFAULT 0,
              source_uri TEXT NOT NULL,
              display_name TEXT NOT NULL,
              kind TEXT NOT NULL,
              source_size INTEGER NOT NULL,
              date_taken INTEGER,
              status TEXT NOT NULL,
              progress INTEGER NOT NULL DEFAULT 0,
              output_uri TEXT,
              output_size INTEGER NOT NULL DEFAULT 0,
              error TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS export_jobs")
        db.execSQL("DROP TABLE IF EXISTS export_batches")
        onCreate(db)
    }

    private val _jobs = MutableStateFlow<List<ExportJob>>(emptyList())
    /** Children of the latest batch only. */
    val jobs: StateFlow<List<ExportJob>> = _jobs.asStateFlow()

    private val _batches = MutableStateFlow<List<ExportBatch>>(emptyList())
    val batches: StateFlow<List<ExportBatch>> = _batches.asStateFlow()

    val latestBatch: ExportBatch? get() = _batches.value.firstOrNull()

    @Synchronized
    fun refresh() {
        val batchList = mutableListOf<ExportBatch>()
        readableDatabase.query("export_batches", null, null, null, null, null, "id DESC").use { cursor ->
            while (cursor.moveToNext()) {
                batchList += ExportBatch(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    status = BatchStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    totalCount = cursor.getInt(cursor.getColumnIndexOrThrow("total")),
                    succeededCount = cursor.getInt(cursor.getColumnIndexOrThrow("succeeded")),
                    failedCount = cursor.getInt(cursor.getColumnIndexOrThrow("failed")),
                    createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                )
            }
        }
        _batches.value = batchList

        val latestId = batchList.firstOrNull()?.id
        val result = mutableListOf<ExportJob>()
        if (latestId != null) {
            readableDatabase.query(
                "export_jobs", null, "batch_id=?", arrayOf(latestId.toString()), null, null, "created_at ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += readJob(cursor)
                }
            }
        }
        _jobs.value = result
    }

    private fun readJob(cursor: android.database.Cursor): ExportJob {
        val dateCol = cursor.getColumnIndexOrThrow("date_taken")
        return ExportJob(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            batchId = cursor.getLong(cursor.getColumnIndexOrThrow("batch_id")),
            sourceUri = cursor.getString(cursor.getColumnIndexOrThrow("source_uri")),
            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
            kind = MediaKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("kind"))),
            sourceSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("source_size")),
            dateTakenMillis = if (cursor.isNull(dateCol)) null else cursor.getLong(dateCol),
            status = ExportStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
            progressPercent = cursor.getInt(cursor.getColumnIndexOrThrow("progress")),
            outputUri = cursor.getString(cursor.getColumnIndexOrThrow("output_uri")),
            outputSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("output_size")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error")),
            createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun allJobsOf(batchId: Long): List<ExportJob> {
        val result = mutableListOf<ExportJob>()
        readableDatabase.query(
            "export_jobs", null, "batch_id=?", arrayOf(batchId.toString()), null, null, "created_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += readJob(cursor)
        }
        return result
    }

    private fun existsWhere(selection: String, args: Array<String>): Boolean =
        readableDatabase.query("export_jobs", arrayOf("id"), selection, args, null, null, null).use { it.moveToFirst() }

    /**
     * Enqueue items. They join the current ACTIVE batch (including one left incomplete
     * by a USB removal); a new batch is created only when the latest batch is DONE or
     * none exists. Files are identified by display name + exact size, so the same file
     * coming back through a different source URI after a replug is not duplicated.
     */
    @Synchronized
    fun enqueue(items: List<MediaItem>): List<Long> {
        val now = System.currentTimeMillis()
        val ids = mutableListOf<Long>()
        val batchKeys = HashSet<String>()
        writableDatabase.beginTransaction()
        try {
            var batchId = latestBatchIdForUpdate()
            for (item in items) {
                if (!batchKeys.add("${item.displayName}|${item.sizeBytes}")) continue
                // Already completed in the active batch (or its output is still pending
                // there): never queue the same file twice in one batch.
                if (existsWhere(
                        "display_name=? AND source_size=? AND status=?",
                        arrayOf(item.displayName, item.sizeBytes.toString(), ExportStatus.COMPLETED.name),
                    )
                ) continue
                // A retry replaces the stale failure record for the same file.
                writableDatabase.delete(
                    "export_jobs", "display_name=? AND source_size=? AND status=?",
                    arrayOf(item.displayName, item.sizeBytes.toString(), ExportStatus.FAILED.name),
                )
                var existingId: Long? = null
                readableDatabase.query(
                    "export_jobs", arrayOf("id"), "source_uri=? AND status IN (?,?)",
                    arrayOf(item.id, ExportStatus.QUEUED.name, ExportStatus.RUNNING.name), null, null, null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) existingId = cursor.getLong(0)
                }
                if (existingId == null) {
                    readableDatabase.query(
                        "export_jobs", arrayOf("id"), "display_name=? AND source_size=? AND status IN (?,?)",
                        arrayOf(item.displayName, item.sizeBytes.toString(), ExportStatus.QUEUED.name, ExportStatus.RUNNING.name), null, null, null,
                    ).use { cursor ->
                        if (cursor.moveToFirst()) existingId = cursor.getLong(0)
                    }
                }
                if (existingId != null) {
                    ids += existingId!!
                    continue
                }
                if (batchId == null) {
                    batchId = createBatchLocked(now)
                }
                val values = ContentValues().apply {
                    put("batch_id", batchId)
                    put("source_uri", item.id)
                    put("display_name", item.displayName)
                    put("kind", item.kind.name)
                    put("source_size", item.sizeBytes)
                    item.dateTakenMillis?.let { put("date_taken", it) }
                    put("status", ExportStatus.QUEUED.name)
                    put("progress", 0)
                    put("output_size", 0)
                    put("created_at", now)
                    put("updated_at", now)
                }
                ids += writableDatabase.insertOrThrow("export_jobs", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        refresh()
        return ids
    }

    /** Id of the batch new children should join: the latest one iff it is still ACTIVE. */
    private fun latestBatchIdForUpdate(): Long? {
        readableDatabase.query(
            "export_batches", arrayOf("id", "status"), null, null, null, null, "id DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getString(1)
            return if (status == BatchStatus.ACTIVE.name) cursor.getLong(0) else null
        }
    }

    private fun createBatchLocked(now: Long): Long {
        val values = ContentValues().apply {
            put("status", BatchStatus.ACTIVE.name)
            put("total", 0)
            put("succeeded", 0)
            put("failed", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        val id = writableDatabase.insertOrThrow("export_batches", null, values)
        pruneBatchesLocked()
        return id
    }

    /** Keep at most [MAX_BATCHES]: the newest ones (an ACTIVE current + one DONE history). */
    private fun pruneBatchesLocked() {
        val keep = mutableListOf<Long>()
        readableDatabase.query("export_batches", arrayOf("id"), null, null, null, null, "id DESC").use { cursor ->
            while (cursor.moveToNext()) keep += cursor.getLong(0)
        }
        for (oldId in keep.drop(MAX_BATCHES)) {
            writableDatabase.delete("export_jobs", "batch_id=?", arrayOf(oldId.toString()))
            writableDatabase.delete("export_batches", "id=?", arrayOf(oldId.toString()))
        }
    }

    /**
     * Collapse a fully-finished ACTIVE batch: if every child is terminal and none failed
     * because the source vanished (USB), delete the children and keep only the summary.
     * A USB-interrupted batch stays ACTIVE with its children visible, ready to resume.
     */
    @Synchronized
    fun finalizeActiveBatch(usbRemovedMessage: String) {
        val batch = latestBatch ?: run { refresh(); latestBatch } ?: return
        if (batch.status != BatchStatus.ACTIVE) return
        val children = allJobsOf(batch.id)
        if (children.isEmpty()) return
        if (children.any { it.status == ExportStatus.QUEUED || it.status == ExportStatus.RUNNING }) return
        val usbIncomplete = children.any { it.status == ExportStatus.FAILED && it.errorMessage == usbRemovedMessage }
        if (usbIncomplete) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("status", BatchStatus.DONE.name)
                put("total", children.size)
                put("succeeded", children.count { it.status == ExportStatus.COMPLETED })
                put("failed", children.count { it.status == ExportStatus.FAILED })
                put("updated_at", now)
            }
            writableDatabase.update("export_batches", values, "id=?", arrayOf(batch.id.toString()))
            writableDatabase.delete("export_jobs", "batch_id=?", arrayOf(batch.id.toString()))
            pruneBatchesLocked()
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        refresh()
    }

    @Synchronized
    fun nextQueued(): ExportJob? {
        refresh()
        // Jobs flow holds only the latest batch's children; the queue drains that batch.
        return _jobs.value.filter { it.status == ExportStatus.QUEUED }.minByOrNull { it.createdAtMillis }
    }

    @Synchronized
    fun queuedJobs(): List<ExportJob> {
        refresh()
        return _jobs.value.filter { it.status == ExportStatus.QUEUED }.sortedBy { it.createdAtMillis }
    }

    @Synchronized
    fun update(jobId: Long, status: ExportStatus? = null, progress: Int? = null, outputUri: String? = null,
               outputSize: Long? = null, error: String? = null) {
        val values = ContentValues().apply {
            status?.let { put("status", it.name) }
            progress?.let { put("progress", it.coerceIn(0, 100)) }
            outputUri?.let { put("output_uri", it) }
            outputSize?.let { put("output_size", it) }
            error?.let { put("error", it) }
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("export_jobs", values, "id=?", arrayOf(jobId.toString()))
        refresh()
    }

    /** Delete a child row outright (user cancellation leaves no tombstone behind). */
    @Synchronized
    fun deleteJob(jobId: Long) {
        val batchId = _jobs.value.firstOrNull { it.id == jobId }?.batchId
        writableDatabase.delete("export_jobs", "id=?", arrayOf(jobId.toString()))
        // A batch whose children are all gone has nothing to show or resume.
        if (batchId != null && allJobsOf(batchId).isEmpty()) {
            writableDatabase.delete("export_batches", "id=? AND status=?", arrayOf(batchId.toString(), BatchStatus.ACTIVE.name))
        }
        refresh()
    }

    @Synchronized
    fun requestCancel(jobId: Long) {
        // User cancellation = deletion of the child record. Queued rows vanish at once;
        // a running row is deleted by the service as soon as it observes the request.
        refresh()
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.status == ExportStatus.QUEUED) {
            deleteJob(jobId)
        } else if (job.status == ExportStatus.RUNNING) {
            // Kept in memory only, in this process: the service polls isCancelRequested().
            cancelRequested += jobId
        }
    }

    fun isCancelRequested(jobId: Long): Boolean = jobId in cancelRequested

    fun clearCancelRequest(jobId: Long) {
        cancelRequested.remove(jobId)
    }

    /** Fail every waiting job at once (USB removed): they must not each start up and fail
     *  one by one, and the user gets one clear cause instead of a trickle of attempts. */
    @Synchronized
    fun failQueued(error: String): Int {
        val values = ContentValues().apply {
            put("status", ExportStatus.FAILED.name)
            put("error", error)
            put("updated_at", System.currentTimeMillis())
        }
        val count = writableDatabase.update("export_jobs", values, "status=?", arrayOf(ExportStatus.QUEUED.name))
        if (count > 0) refresh()
        return count
    }

    /** "Delete export history": every batch and every child row goes away. A running job
     *  is asked to stop first so its temp file is cleaned up by the service. */
    @Synchronized
    fun clearHistory() {
        refresh()
        _jobs.value.filter { it.status == ExportStatus.RUNNING }.forEach { cancelRequested += it.id }
        writableDatabase.delete("export_jobs", null, null)
        writableDatabase.delete("export_batches", null, null)
        refresh()
    }

    @Synchronized
    fun markInterruptedAsFailed() {
        writableDatabase.execSQL(
            "UPDATE export_jobs SET status=?, error=?, updated_at=? WHERE status=?",
            arrayOf<Any>(ExportStatus.FAILED.name, "アプリ終了により中断しました。再度エクスポートしてください。", System.currentTimeMillis(), ExportStatus.RUNNING.name),
        )
        refresh()
    }

    companion object {
        const val MAX_BATCHES = 2

        private val cancelRequested = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

        @Volatile
        private var instance: ExportDb? = null

        fun get(context: Context): ExportDb = instance ?: synchronized(this) {
            instance ?: ExportDb(context.applicationContext).also { it.refresh(); instance = it }
        }
    }
}
