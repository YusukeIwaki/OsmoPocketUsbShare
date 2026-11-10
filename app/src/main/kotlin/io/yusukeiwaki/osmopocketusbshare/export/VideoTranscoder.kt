package io.yusukeiwaki.osmopocketusbshare.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import io.yusukeiwaki.osmopocketusbshare.data.SlimLogic
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val durationMillis: Long,
    val colorTransfer: Int?,
    val isTenBit: Boolean,
)

object VideoProbe {
    fun probe(context: Context, uri: Uri): VideoInfo {
        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                val fps = when {
                    format.containsKey(MediaFormat.KEY_FRAME_RATE) -> runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(30.0)
                    else -> 30.0
                }
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
                val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else 0
                // HEVC Main10 profile = 2; AVC High10 etc. Treat HEVC Main10 / 10-bit descriptors as 10-bit.
                val tenBit = profile == 2 || mime == MimeTypes.VIDEO_H265 && profile == 2
                return VideoInfo(width, height, if (fps > 0) fps else 30.0, durationUs / 1000, transfer, tenBit)
            }
        } finally {
            extractor.release()
        }
        return VideoInfo(0, 0, 30.0, 0L, null, false)
    }
}

/**
 * Hardware-accelerated 2K transcode mirroring slim_videos.py decisions:
 * - long edge 2560 (never upscale), HEVC, auto bitrate from SlimLogic (peak handling is encoder-internal),
 * - AAC audio, DJI data/tmcd tracks dropped by mapping only the EditedMediaItem's video+audio,
 * - HDR (PQ/HLG) sources are NOT tone-mapped silently: caller copies the original instead.
 * Pixel pipeline on Android is the platform MediaCodec hardware codec (Pixel: Tensor media block)
 * with a GL scale (bilinear) instead of FFmpeg Lanczos — see evaluation/ for the SSIM comparison.
 */
class VideoTranscoder(private val context: Context) {

    suspend fun transcode(
        input: Uri,
        outputFile: File,
        info: VideoInfo,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val (targetW, targetH) = SlimLogic.targetDimensions(info.width, info.height)
        val bitrate = SlimLogic.autoBitrateBps(targetW, targetH, info.fps)
        val presentation = Presentation.createForWidthAndHeight(targetW, targetH, Presentation.LAYOUT_SCALE_TO_FIT)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
            )
            .setRequestedAudioEncoderSettings(
                androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(192_000).build(),
            )
            .build()
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            var transformerRef: Transformer? = null
            val progressHolder = ProgressHolder()
            lateinit var pollRunnable: Runnable
            pollRunnable = Runnable {
                val transformer = transformerRef
                if (transformer != null && continuation.isActive) {
                    if (isCancelled()) {
                        runCatching { transformer.cancel() }
                        // Transformer.cancel() does not guarantee a listener callback in all states;
                        // force-resume as cancelled so the service can clean up the temp file.
                        mainHandler.postDelayed({
                            if (continuation.isActive) continuation.resumeWithException(CancelledException())
                        }, 1500)
                    } else {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 100))
                        }
                        mainHandler.postDelayed(pollRunnable, 300)
                    }
                }
            }
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        mainHandler.removeCallbacks(pollRunnable)
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        mainHandler.removeCallbacks(pollRunnable)
                        if (continuation.isActive) {
                            if (isCancelled()) continuation.resumeWithException(CancelledException())
                            else continuation.resumeWithException(exportException)
                        }
                    }
                })
                .build()
            transformerRef = transformer
            continuation.invokeOnCancellation { mainHandler.post { runCatching { transformer.cancel() } } }
            transformer.start(edited, outputFile.absolutePath)
            mainHandler.postDelayed(pollRunnable, 300)
        }
    }

    class CancelledException : Exception("cancelled")
}
