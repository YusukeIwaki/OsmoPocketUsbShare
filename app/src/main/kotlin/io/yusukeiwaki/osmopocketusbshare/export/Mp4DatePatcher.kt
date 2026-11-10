package io.yusukeiwaki.osmopocketusbshare.export

import java.io.File
import java.io.RandomAccessFile

/**
 * Rewrites the creation/modification times inside an MP4's mvhd/tkhd/mdhd boxes.
 *
 * Media3's muxer stamps outputs with the export time, while slim_videos.py (ffmpeg)
 * preserves the capture time — and Google Photos sorts videos by this embedded
 * creation_time, not by MediaStore DATE_TAKEN. Patching the box fields after
 * transcoding (before publishing) restores the slim behavior without a remux.
 *
 * QuickTime times are unsigned seconds since 1904-01-01 UTC.
 */
object Mp4DatePatcher {

    private const val QT_EPOCH_OFFSET_SECONDS = 2082844800L

    fun setCreationTime(file: File, epochMillis: Long): Boolean {
        if (!file.exists() || file.length() < 8 || epochMillis <= 0) return false
        val qtSeconds = epochMillis / 1000 + QT_EPOCH_OFFSET_SECONDS
        return runCatching {
            RandomAccessFile(file, "rw").use { raf -> patchRange(raf, 0L, raf.length(), qtSeconds) }
        }.getOrDefault(false)
    }

    private fun patchRange(raf: RandomAccessFile, start: Long, end: Long, qtSeconds: Long): Boolean {
        var patched = false
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var headerSize = 8L
            var boxSize = size32
            when {
                size32 == 1L -> {
                    boxSize = raf.readLong()
                    headerSize = 16L
                }
                size32 == 0L -> boxSize = end - pos
            }
            if (boxSize < headerSize || pos + boxSize > end) break
            when (type) {
                "mvhd", "tkhd", "mdhd" -> if (writeTimes(raf, pos + headerSize, qtSeconds)) patched = true
                "moov", "trak", "mdia" -> if (patchRange(raf, pos + headerSize, pos + boxSize, qtSeconds)) patched = true
            }
            pos += boxSize
        }
        return patched
    }

    private fun writeTimes(raf: RandomAccessFile, contentStart: Long, qtSeconds: Long): Boolean {
        raf.seek(contentStart)
        val version = raf.read()
        // After version(1) + flags(3): creation_time, modification_time
        // (v1: 64-bit each, v0: 32-bit each).
        return if (version == 1) {
            raf.seek(contentStart + 4)
            raf.writeLong(qtSeconds)
            raf.writeLong(qtSeconds)
            true
        } else {
            raf.seek(contentStart + 4)
            raf.writeInt(qtSeconds.toInt())
            raf.writeInt(qtSeconds.toInt())
            true
        }
    }
}
