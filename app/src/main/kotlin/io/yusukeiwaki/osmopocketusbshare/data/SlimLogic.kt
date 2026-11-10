package io.yusukeiwaki.osmopocketusbshare.data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure port of the decision logic in slim_videos.py (2K default).
 * - Long edge capped at 2560, aspect preserved, never upscale, even dimensions.
 * - Auto bitrate: 8 Mbps at 1920x1080/30fps scaled by (pixels)^0.75 and fps, floor 750 kbps, rounded to 1 Mbps.
 * - Peak bitrate is 2x target (applied by the encoder where supported).
 * - Capture date: embedded metadata wins; DJI/Insta360-style filename is only a fallback.
 */
object SlimLogic {
    const val MAX_LONG_EDGE_2K = 2560
    const val MIN_SAVINGS_PERCENT = 20.0
    const val MIN_SSIM = 0.97

    fun targetDimensions(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE_2K): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val ratio = min(1.0, maxLongEdge.toDouble() / max(width, height).toDouble())
        val w = max(2, (width * ratio).toInt() / 2 * 2)
        val h = max(2, (height * ratio).toInt() / 2 * 2)
        return w to h
    }

    fun autoBitrateBps(targetWidth: Int, targetHeight: Int, fps: Double): Int {
        val safeFps = if (fps.isFinite() && fps > 0) fps else 30.0
        val pixels = targetWidth.toDouble() * targetHeight.toDouble()
        val raw = 8_000_000.0 * (pixels / (1920.0 * 1080.0)).pow(0.75) * max(1.0, safeFps / 30.0)
        val rounded = (raw / 1_000_000.0).roundToInt() * 1_000_000
        return max(750_000, rounded)
    }

    fun peakBitrateBps(targetBps: Int): Int = targetBps * 2

    fun needsCompressionSavingsCheck(estimatedOutputBytes: Long, inputBytes: Long): Boolean =
        inputBytes > 0 && estimatedOutputBytes >= 0

    fun savingsPercent(inputBytes: Long, outputBytes: Long): Double =
        if (inputBytes <= 0) 0.0 else 100.0 * (1.0 - outputBytes.toDouble() / inputBytes.toDouble())

    private val filenamePattern = Regex("""(?<!\d)(\d{8})_?(\d{6})(?!\d)""")

    /** DJI_20261107124028_0007_D.MP4 / VID_20261107_124028_012.mp4 -> epoch millis (Asia/Tokyo fallback). */
    fun dateFromFilename(name: String, zone: ZoneId = ZoneId.of("Asia/Tokyo")): Long? {
        val m = filenamePattern.find(name) ?: return null
        return runCatching {
            val local = LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            local.atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    fun isHdrTransfer(colorTransfer: Int?): Boolean {
        // MediaFormat COLOR_TRANSFER_ST2084=6 / HLG=7 (mirrors slim_videos.py smpte2084 / arib-std-b67 skip).
        return colorTransfer == 6 || colorTransfer == 7
    }

    @Suppress("unused")
    fun utcFormatter(): DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
}
