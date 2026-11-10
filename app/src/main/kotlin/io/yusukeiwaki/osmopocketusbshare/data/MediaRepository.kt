package io.yusukeiwaki.osmopocketusbshare.data

import android.content.ContentUris
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Media sources, in priority order:
 * 1. MediaStore volumes other than the primary internal volume (a USB OTG stick / Osmo Pocket
 *    mounted as mass storage is indexed by MediaStore under its own volume name).
 * 2. A user-granted SAF tree (persisted URI permission) — fallback when MediaStore cannot see the volume.
 * 3. Removable StorageVolume directories scanned directly (DCIM / root) when readable.
 * 4. App inbox folder (getExternalFilesDir("usb_inbox")) — used for diagnostics and instrumentation tests
 *    to simulate a mounted OTG volume without physical hardware.
 */
class MediaRepository(private val context: Context) {

    fun isUsbConnected(): Boolean {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val hasUsbDevice = (usbManager?.deviceList?.isNotEmpty() == true)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val hasRemovableMounted = storageManager?.storageVolumes?.any { volume ->
            volume.isRemovable && volume.state == android.os.Environment.MEDIA_MOUNTED
        } == true
        return hasUsbDevice || hasRemovableMounted || safTreeAvailable() || inboxHasFiles()
    }

    /**
     * A persisted SAF grant survives unmounting, so it alone must not count as "connected".
     * The tree only counts while its root document actually exists (volume still mounted).
     */
    fun safTreeAvailable(): Boolean {
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val available = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, permission.uri)?.exists() == true
            }.getOrDefault(false)
            if (available) return true
        }
        return false
    }

    fun hasSafTree(): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri != null }

    /**
     * Intent that opens the system folder picker at the removable USB volume.
     * On devices where MediaStore does not index the USB volume (observed with Osmo Pocket 4's
     * composite RNDIS+mass-storage device on Android 17), the SAF tree grant is the working path.
     * StorageVolume.createOpenDocumentTreeIntent() is the supported way to land on that volume;
     * a hand-built EXTRA_INITIAL_URI tree URI was ignored by DocumentsUI in testing.
     */
    fun openTreeIntent(): android.content.Intent {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volume = storageManager?.storageVolumes?.firstOrNull { it.isRemovable && it.uuid != null }
        if (volume != null) {
            runCatching { return volume.createOpenDocumentTreeIntent() }
        }
        return android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    fun inboxDir(): File = File(context.getExternalFilesDir(null), "usb_inbox").apply { mkdirs() }

    private fun inboxHasFiles(): Boolean =
        inboxDir().listFiles()?.any { it.isFile && isMediaFile(it.name) } == true

    private fun isMediaFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".heic") || lower.endsWith(".mp4") || lower.endsWith(".mov")
    }

    private fun kindOf(name: String, mime: String?): MediaKind {
        val lower = name.lowercase()
        return if (mime?.startsWith("video") == true || lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            MediaKind.VIDEO
        } else {
            MediaKind.PHOTO
        }
    }

    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = LinkedHashMap<String, MediaItem>()
        runCatching { loadFromMediaStoreVolumes(items) }
        runCatching { loadFromSafTree(items) }
        runCatching { loadFromRemovableVolumes(items) }
        runCatching { loadFromInbox(items) }
        // The same physical files can be reported by several sources at once (e.g. after a
        // replug MediaStore indexes the volume while the SAF tree grant still lists it, or
        // overlapping folder grants cover the same DCIM). Without this cross-source dedupe
        // the grid shows every photo/video twice.
        dedupeAcrossSources(items.values)
            .sortedWith(compareByDescending<MediaItem> { it.dateTakenMillis ?: 0L }.thenBy { it.displayName })
    }

    private fun loadFromMediaStoreVolumes(out: MutableMap<String, MediaItem>) {
        val volumes = MediaStore.getExternalVolumeNames(context).filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
        for (volume in volumes) {
            queryVolume(MediaStore.Images.Media.getContentUri(volume), MediaKind.PHOTO, volume, out)
            queryVolume(MediaStore.Video.Media.getContentUri(volume), MediaKind.VIDEO, volume, out)
        }
    }

    private fun queryVolume(collection: Uri, kind: MediaKind, volume: String, out: MutableMap<String, MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
        )
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val wCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val hCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                val dateTaken = if (cursor.isNull(dateCol)) null else cursor.getLong(dateCol).takeIf { it > 0 }
                out[uri.toString()] = MediaItem(
                    id = uri.toString(),
                    uri = uri,
                    displayName = name,
                    kind = kind,
                    sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol),
                    dateTakenMillis = dateTaken ?: SlimLogic.dateFromFilename(name),
                    width = if (wCol >= 0 && !cursor.isNull(wCol)) cursor.getInt(wCol) else 0,
                    height = if (hCol >= 0 && !cursor.isNull(hCol)) cursor.getInt(hCol) else 0,
                    durationMillis = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else null,
                    mimeType = cursor.getString(mimeCol) ?: "",
                    sourceLabel = "USB ($volume)",
                    volumeName = volume,
                )
            }
        }
    }

    private fun loadFromSafTree(out: MutableMap<String, MediaItem>) {
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val tree = DocumentFile.fromTreeUri(context, permission.uri) ?: continue
            collectDocumentFile(tree, out, depth = 0)
        }
    }

    private fun collectDocumentFile(dir: DocumentFile, out: MutableMap<String, MediaItem>, depth: Int) {
        if (depth > 4) return
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectDocumentFile(child, out, depth + 1)
            } else if (child.isFile && isMediaFile(child.name ?: "")) {
                val name = child.name ?: continue
                val kind = kindOf(name, child.type)
                // SAF items need the same enrichment as other sources: resolution + duration
                // (both are recorded in the files; previously this path never read them).
                val videoMeta = if (kind == MediaKind.VIDEO) MetadataReader.videoMetadata(context, child.uri) else null
                val photoDims = if (kind == MediaKind.PHOTO) MetadataReader.dimensions(context, child.uri, kind) else (0 to 0)
                out[child.uri.toString()] = MediaItem(
                    id = child.uri.toString(),
                    uri = child.uri,
                    displayName = name,
                    kind = kind,
                    sizeBytes = child.length(),
                    dateTakenMillis = videoMeta?.dateTakenMillis
                        ?: if (kind == MediaKind.PHOTO) MetadataReader.dateTaken(context, child.uri, name, kind) else SlimLogic.dateFromFilename(name)
                        ?: child.lastModified().takeIf { it > 0 },
                    width = videoMeta?.width ?: photoDims.first,
                    height = videoMeta?.height ?: photoDims.second,
                    durationMillis = videoMeta?.durationMillis,
                    mimeType = child.type ?: "",
                    sourceLabel = "USB (フォルダ)",
                )
            }
        }
    }

    private fun loadFromRemovableVolumes(out: MutableMap<String, MediaItem>) {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return
        for (volume in storageManager.storageVolumes) {
            if (!volume.isRemovable) continue
            val root = volume.directory ?: continue
            val candidates = listOf(File(root, "DCIM"), File(root, "DCIM/Camera"), root)
            for (dir in candidates) {
                if (!dir.isDirectory) continue
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && isMediaFile(file.name)) {
                        val uri = Uri.fromFile(file)
                        val key = file.absolutePath
                        if (out.containsKey(key)) return@forEach
                        val kind = kindOf(file.name, null)
                        val videoMeta = if (kind == MediaKind.VIDEO) MetadataReader.videoMetadata(context, uri) else null
                        val photoDims = if (kind == MediaKind.PHOTO) MetadataReader.dimensions(context, uri, kind) else (0 to 0)
                        out[key] = MediaItem(
                            id = key,
                            uri = uri,
                            displayName = file.name,
                            kind = kind,
                            sizeBytes = file.length(),
                            dateTakenMillis = videoMeta?.dateTakenMillis
                                ?: if (kind == MediaKind.PHOTO) MetadataReader.dateTaken(context, uri, file.name, kind) else SlimLogic.dateFromFilename(file.name)
                                ?: SlimLogic.dateFromFilename(file.name) ?: file.lastModified().takeIf { it > 0 },
                            width = videoMeta?.width ?: photoDims.first,
                            height = videoMeta?.height ?: photoDims.second,
                            durationMillis = videoMeta?.durationMillis,
                            mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
                            sourceLabel = "USB",
                        )
                    }
                }
            }
        }
    }

    private fun loadFromInbox(out: MutableMap<String, MediaItem>) {
        val dir = inboxDir()
        dir.listFiles()?.filter { it.isFile && isMediaFile(it.name) }?.forEach { file ->
            val uri = Uri.fromFile(file)
            val kind = kindOf(file.name, null)
            val videoMeta = if (kind == MediaKind.VIDEO) MetadataReader.videoMetadata(context, uri) else null
            val photoDims = if (kind == MediaKind.PHOTO) MetadataReader.dimensions(context, uri, kind) else (0 to 0)
            out[file.absolutePath] = MediaItem(
                id = file.absolutePath,
                uri = uri,
                displayName = file.name,
                kind = kind,
                sizeBytes = file.length(),
                dateTakenMillis = videoMeta?.dateTakenMillis
                    ?: if (kind == MediaKind.PHOTO) MetadataReader.dateTaken(context, uri, file.name, kind) else SlimLogic.dateFromFilename(file.name)
                    ?: SlimLogic.dateFromFilename(file.name) ?: file.lastModified().takeIf { it > 0 },
                width = videoMeta?.width ?: photoDims.first,
                height = videoMeta?.height ?: photoDims.second,
                durationMillis = videoMeta?.durationMillis,
                mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
                sourceLabel = "USB (inbox)",
            )
        }
    }

    companion object {
        /**
         * Cross-source dedupe: sources are loaded in priority order (MediaStore, SAF,
         * removable volumes, inbox), and the same physical file reported by a later source
         * (different URI, same display name and exact byte size) is dropped. Items whose
         * size is unknown keep their own identity and are never merged.
         */
        fun dedupeAcrossSources(items: Collection<MediaItem>): List<MediaItem> {
            val seen = HashSet<String>()
            return items.filter { item ->
                val key = if (item.sizeBytes > 0) {
                    "file:${item.displayName.lowercase()}|${item.sizeBytes}"
                } else {
                    "uri:${item.id}"
                }
                seen.add(key)
            }
        }

        fun treeUriFromIntent(data: android.content.Intent?): Uri? = data?.data
        fun isTreeUri(uri: Uri): Boolean = runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
    }
}
