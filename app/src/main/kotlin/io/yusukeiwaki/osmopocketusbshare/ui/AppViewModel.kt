package io.yusukeiwaki.osmopocketusbshare.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.yusukeiwaki.osmopocketusbshare.data.ExportDb
import io.yusukeiwaki.osmopocketusbshare.data.ExportJob
import io.yusukeiwaki.osmopocketusbshare.data.MediaItem
import io.yusukeiwaki.osmopocketusbshare.data.MediaRepository
import io.yusukeiwaki.osmopocketusbshare.export.ExportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BrowserLayout { GRID, LIST }

data class BrowserUiState(
    val usbConnected: Boolean = false,
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val layout: BrowserLayout = BrowserLayout.GRID,
    val selectedIds: Set<String> = emptySet(),
) {
    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    val selectedItems: List<MediaItem> get() = items.filter { it.id in selectedIds }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    private val db = ExportDb.get(application)

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    val exportJobs: StateFlow<List<ExportJob>> = db.jobs.stateIn(viewModelScope, SharingStarted.Eagerly, db.jobs.value)

    val exportBatches: StateFlow<List<io.yusukeiwaki.osmopocketusbshare.data.ExportBatch>> =
        db.batches.stateIn(viewModelScope, SharingStarted.Eagerly, db.batches.value)

    @Volatile
    private var initialLoadDone = false

    init {
        refresh()
    }

    /**
     * Resume-time entry point: the ViewModel (and its loaded media) already survives
     * configuration changes, so a plain resume/rotation must NOT trigger a full rescan.
     * Only rescan when nothing has loaded yet or the USB connection state actually changed.
     */
    fun refreshOnResume() {
        if (!initialLoadDone) {
            refresh()
            return
        }
        viewModelScope.launch {
            // Connection checks talk to system providers over Binder; keep them off the main
            // thread so rotation/redraw is never stalled behind them.
            val connectedNow = withContext(Dispatchers.IO) { repository.isUsbConnected() }
            if (connectedNow != _state.value.usbConnected) refresh()
        }
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        // Coalesce overlapping triggers (startup, resume, broadcasts): parallel scans over
        // USB only make each other slower.
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            // Keep showing current content during a rescan; the spinner is only for the first load.
            if (_state.value.items.isEmpty()) {
                _state.value = _state.value.copy(loading = true)
            }
            val startedAt = System.currentTimeMillis()
            val (connected, items) = withContext(Dispatchers.IO) {
                val nowConnected = repository.isUsbConnected()
                nowConnected to if (nowConnected) repository.loadMedia() else emptyList()
            }
            android.util.Log.d(
                "OsmoPocketUsb",
                "refresh: connected=$connected items=${items.size} took=${System.currentTimeMillis() - startedAt}ms",
            )
            // Drop selections for media that vanished (e.g. USB removed) so stale ids never linger.
            val validIds = items.map { it.id }.toSet()
            _state.value = _state.value.copy(
                usbConnected = connected,
                loading = false,
                items = items,
                selectedIds = _state.value.selectedIds.intersect(validIds),
            )
            initialLoadDone = true
        }
    }

    fun setLayout(layout: BrowserLayout) {
        _state.value = _state.value.copy(layout = layout)
    }

    fun toggleLayout() {
        setLayout(if (_state.value.layout == BrowserLayout.GRID) BrowserLayout.LIST else BrowserLayout.GRID)
    }

    fun startSelection(item: MediaItem) {
        _state.value = _state.value.copy(selectedIds = setOf(item.id))
    }

    fun toggleSelection(item: MediaItem) {
        val current = _state.value.selectedIds
        val next = if (item.id in current) current - item.id else current + item.id
        // Invariant: an empty selection always exits selection mode (covered by instrumentation tests).
        _state.value = _state.value.copy(selectedIds = next)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    fun selectAll() {
        _state.value = _state.value.copy(selectedIds = _state.value.items.map { it.id }.toSet())
    }

    fun exportSelected() {
        val items = _state.value.selectedItems
        if (items.isEmpty()) return
        ExportService.enqueueAndStart(getApplication(), items)
        clearSelection()
    }

    fun exportItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        ExportService.enqueueAndStart(getApplication(), items)
    }

    fun cancelExport(jobId: Long) {
        ExportService.cancel(getApplication(), jobId)
    }

    fun clearExportHistory() {
        db.clearHistory()
    }

    fun onSafTreeGranted(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            refresh()
        }
    }

    fun inboxDirPath(): String = repository.inboxDir().absolutePath

    fun openTreeIntent(): android.content.Intent = repository.openTreeIntent()

    fun hasSafTree(): Boolean = repository.hasSafTree()
}
