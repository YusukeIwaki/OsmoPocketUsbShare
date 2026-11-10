package io.yusukeiwaki.osmopocketusbshare.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object MetadataReader {

    fun dateTaken(context: Context, uri: Uri, displayName: String, kind: MediaKind): Long? {
        // 1) Embedded metadata wins (mirrors slim_videos.py choose_date priority).
        if (kind == MediaKind.PHOTO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (raw != null) {
                        val parsed = parseExifDate(raw)
                        if (parsed != null) return parsed
                    }
                }
            }
        } else {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                    val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    if (raw != null) {
                        val parsed = parseVideoDate(raw)
                        if (parsed != null) return parsed
                    }
                } finally {
                    retriever.release()
                }
            }
        }
        // 2) Filename fallback only when no valid embedded date exists.
        return SlimLogic.dateFromFilename(displayName)
    }

    fun dimensions(context: Context, uri: Uri, kind: MediaKind): Pair<Int, Int> {
        if (kind == MediaKind.PHOTO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    val w = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
                    val h = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
                    if (w > 0 && h > 0) return w to h
                }
            }
            runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth > 0) return options.outWidth to options.outHeight
            }
        } else {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (w > 0) return w to h
                } finally {
                    retriever.release()
                }
            }
        }
        return 0 to 0
    }

    data class VideoMetadata(val width: Int, val height: Int, val durationMillis: Long?, val dateTakenMillis: Long?)

    /** One retriever session for all video fields (duration IS recorded by DJI; it must be read, not assumed). */
    fun videoMetadata(context: Context, uri: Uri): VideoMetadata {
        var width = 0
        var height = 0
        var duration: Long? = null
        var date: Long? = null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { parseVideoDate(it) }
            } finally {
                retriever.release()
            }
        }
        return VideoMetadata(width, height, duration, date)
    }

    fun durationMillis(context: Context, uri: Uri, kind: MediaKind): Long? {
        if (kind != MediaKind.VIDEO) return null
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    fun videoFpsAndTransfer(context: Context, uri: Uri): Pair<Double, Int?> {
        var fps = 30.0
        var transfer: Int? = null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                val rawFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                fps = rawFps?.toDoubleOrNull() ?: 30.0
            } finally {
                retriever.release()
            }
        }
        // Color transfer is read via MediaExtractor in the transcoder; retriever has no stable key.
        return fps to transfer
    }

    private fun parseExifDate(raw: String): Long? {
        // EXIF format: "yyyy:MM:dd HH:mm:ss" in local capture time; treat as Asia/Tokyo for display epoch.
        return runCatching {
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            }
            format.parse(raw)?.time
        }.getOrNull()
    }

    private fun parseVideoDate(raw: String): Long? {
        // MediaMetadataRetriever date formats vary: "20261107T094517.000Z" or ISO-like.
        val patterns = listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (pattern in patterns) {
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val parsed = format.parse(raw)
                if (parsed != null && parsed.time > 315532800000L) return parsed.time // after 1980
            }
        }
        return null
    }

    /** False when the source can no longer be opened (e.g. its USB volume was removed). */
    fun exists(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } ?: false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun fileSizeOf(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
