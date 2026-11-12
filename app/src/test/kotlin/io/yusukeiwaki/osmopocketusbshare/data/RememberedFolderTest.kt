package io.yusukeiwaki.osmopocketusbshare.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException

class RememberedFolderTest {
    private val oldFolder = "content://documents/tree/OLD%3ADCIM%2FDJI_001"
    private val lastFolder = "content://documents/tree/USB%3ADCIM%2FDJI_002"

    private class FakeStorage : RememberedFolder.Storage {
        override var lastUri: String? = null
        var grants = emptyList<RememberedFolder.Grant>()
        var permissionFailure: Exception? = null
        val permissionRequests = mutableListOf<String>()

        override fun readableTreeGrants() = grants
        override fun persistReadPermission(uri: String) {
            permissionRequests += uri
            permissionFailure?.let { throw it }
        }
    }

    @Test
    fun noSavedFolderOrGrants_leavesAutomaticDiscoveryAvailable() {
        val folder = RememberedFolder(FakeStorage())
        assertNull(folder.uri())
        assertNull(folder.load<String> { error("Must not read an unselected folder") })
    }

    @Test
    fun upgrade_remembersTheMostRecentlyGrantedFolder() {
        val storage = FakeStorage().apply {
            grants = listOf(
                RememberedFolder.Grant(lastFolder, 200),
                RememberedFolder.Grant(oldFolder, 100),
            )
        }
        assertEquals(lastFolder, RememberedFolder(storage).uri())
        assertEquals(lastFolder, storage.lastUri)
        assertTrue("Existing permission must be reused", storage.permissionRequests.isEmpty())
    }

    @Test
    fun savedChoice_survivesRestartAndTakesPriorityOverOtherGrants() {
        val storage = FakeStorage()
        assertTrue(RememberedFolder(storage).remember(lastFolder).isSuccess)
        storage.grants = listOf(RememberedFolder.Grant(oldFolder, Long.MAX_VALUE))
        assertEquals(lastFolder, RememberedFolder(storage).uri())
        assertEquals(listOf(lastFolder), storage.permissionRequests)
    }

    @Test
    fun successfulSelection_replacesPreviousChoice() {
        val storage = FakeStorage().apply { lastUri = oldFolder }
        val folder = RememberedFolder(storage)
        assertTrue(folder.remember(lastFolder).isSuccess)
        assertEquals(lastFolder, folder.uri())
    }

    @Test
    fun failedPermission_doesNotReplacePreviousChoice() {
        val storage = FakeStorage().apply {
            lastUri = oldFolder
            permissionFailure = SecurityException("Grant denied")
        }
        val folder = RememberedFolder(storage)
        assertTrue(folder.remember(lastFolder).isFailure)
        assertEquals(oldFolder, folder.uri())
    }

    @Test
    fun failedFirstPermission_keepsFolderUnconfigured() {
        val storage = FakeStorage().apply { permissionFailure = SecurityException() }
        val folder = RememberedFolder(storage)
        assertTrue(folder.remember(lastFolder).isFailure)
        assertNull(folder.uri())
    }

    @Test
    fun load_readsOnlyTheLastFolder() {
        val storage = FakeStorage().apply {
            lastUri = lastFolder
            grants = listOf(RememberedFolder.Grant(oldFolder, 100))
        }
        val visited = mutableListOf<String>()
        val items = RememberedFolder(storage).load { uri ->
            visited += uri
            listOf("DJI_0001.MP4", "DJI_0002.JPG")
        }
        assertEquals(listOf(lastFolder), visited)
        assertEquals(listOf("DJI_0001.MP4", "DJI_0002.JPG"), items)
    }

    @Test
    fun emptyFolder_requestsReselectionWithoutReadingOldGrants() {
        val storage = FakeStorage().apply {
            lastUri = lastFolder
            grants = listOf(RememberedFolder.Grant(oldFolder, 100))
        }
        val items = RememberedFolder(storage).load<String> { uri ->
            assertEquals(lastFolder, uri)
            emptyList()
        }
        assertEquals(emptyList<String>(), items)
        assertEquals(lastFolder, storage.lastUri)
    }

    @Test
    fun unplugAndReplug_retriesTheSameFolderWithoutRequestingPermission() {
        val storage = FakeStorage().apply { lastUri = lastFolder }
        val folder = RememberedFolder(storage)
        assertEquals(emptyList<String>(), folder.load<String> { throw FileNotFoundException("USB unplugged") })
        // A persisted grant may disappear temporarily; the saved choice is still retained.
        assertEquals(lastFolder, folder.uri())
        assertEquals(listOf("DJI_0001.MP4"), RememberedFolder(storage).load { listOf("DJI_0001.MP4") })
        assertTrue(storage.permissionRequests.isEmpty())
    }

    @Test
    fun revokedPermission_allowsReselectionAndRemembersReplacement() {
        val storage = FakeStorage().apply { lastUri = oldFolder }
        val folder = RememberedFolder(storage)
        assertEquals(emptyList<String>(), folder.load<String> { throw SecurityException("Permission revoked") })
        assertEquals(oldFolder, storage.lastUri)
        assertTrue(folder.remember(lastFolder).isSuccess)
        assertEquals(listOf("photo.jpg"), folder.load {
            assertEquals(lastFolder, it)
            listOf("photo.jpg")
        })
    }

    @Test(expected = CancellationException::class)
    fun cancelledScan_doesNotBecomeAnEmptyFolder() {
        val storage = FakeStorage().apply { lastUri = lastFolder }
        RememberedFolder(storage).load<String> { throw CancellationException() }
    }
}
