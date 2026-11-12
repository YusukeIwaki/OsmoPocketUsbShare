package io.yusukeiwaki.osmopocketusbshare.data

/** Persists the user's choice independently of whether its USB volume is currently mounted. */
class RememberedFolder(private val storage: Storage) {
    data class Grant(val uri: String, val persistedAt: Long)

    interface Storage {
        var lastUri: String?
        fun readableTreeGrants(): List<Grant>
        fun persistReadPermission(uri: String)
    }

    fun uri(): String? {
        storage.lastUri?.let { return it }
        // Upgrade from versions that retained grants but did not record the last choice.
        val latest = storage.readableTreeGrants().maxByOrNull { it.persistedAt } ?: return null
        storage.lastUri = latest.uri
        return latest.uri
    }

    fun remember(uri: String): Result<Unit> = runCatching {
        // A failed permission request must not replace the working saved folder.
        storage.persistReadPermission(uri)
        storage.lastUri = uri
    }

    /** null means no choice yet; empty means the saved folder needs attention. */
    fun <T> load(readFolder: (String) -> List<T>): List<T>? {
        val savedUri = uri() ?: return null
        // Never switch to another old grant after unplugging or losing access.
        return try {
            readFolder(savedUri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
}
