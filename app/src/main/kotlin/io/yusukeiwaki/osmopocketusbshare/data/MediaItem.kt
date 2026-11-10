package io.yusukeiwaki.osmopocketusbshare.data

import android.net.Uri

enum class MediaKind { PHOTO, VIDEO }

data class MediaItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val dateTakenMillis: Long?,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    val mimeType: String = "",
    val sourceLabel: String = "",
    val volumeName: String = "",
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
}
