package io.yusukeiwaki.osmopocketusbshare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.yusukeiwaki.osmopocketusbshare.data.MediaItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    onRefresh: () -> Unit,
    onToggleLayout: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemLongClick: (MediaItem) -> Unit,
    onToggleSelection: (MediaItem) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onExportSelected: () -> Unit,
    onOpenExports: () -> Unit,
    onPickFolder: () -> Unit,
) {
    // Back in selection mode only exits selection; it must not pop the browser destination.
    BackHandler(enabled = state.selectionMode) { onClearSelection() }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedIds.size}件を選択中") },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection, modifier = Modifier.testTag("clearSelection")) {
                            Icon(Icons.Filled.Close, contentDescription = "選択を解除")
                        }
                    },
                    actions = {
                        // Text-only action: export lives on the FAB, so the bar stays minimal.
                        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("selectAll")) {
                            Text("すべて選択")
                        }
                    },
                )
            } else {
                var menuExpanded by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text("OsmoPocket USB Share") },
                    actions = {
                        IconButton(onClick = onToggleLayout, modifier = Modifier.testTag("layoutToggle")) {
                            Icon(
                                imageVector = if (state.layout == BrowserLayout.GRID) Icons.Filled.List else Icons.Filled.GridView,
                                contentDescription = if (state.layout == BrowserLayout.GRID) "リスト表示に切り替え" else "グリッド表示に切り替え",
                            )
                        }
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "再読み込み") }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "メニュー") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("エクスポート状況") },
                                    onClick = { menuExpanded = false; onOpenExports() },
                                )
                                if (state.canPickFolder) {
                                    DropdownMenuItem(
                                        text = { Text("USBフォルダを開く") },
                                        onClick = { menuExpanded = false; onPickFolder() },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onExportSelected,
                    modifier = Modifier.testTag("exportSelectedFab"),
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text("エクスポート (${state.selectedIds.size})") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).testTag("loading"))
                !state.usbConnected -> UsbDisconnectedPlaceholder()
                state.items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (state.savedFolderUri != null) "前回のフォルダに写真や動画が見つかりませんでした" else "写真や動画が見つかりませんでした",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (state.savedFolderUri != null) {
                            "USB接続を確認するか、DCIM内のフォルダを選び直してください。選んだフォルダは記憶され、次回から自動で読み込みます。"
                        } else {
                            "Osmo PocketのDCIM内にある写真・動画のフォルダを選んでください。選んだフォルダは記憶され、次回から自動で読み込みます。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.folderPermissionError) {
                        Text(
                            "フォルダへのアクセス許可を保存できませんでした。もう一度選択してください。",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("folderPermissionError"),
                        )
                    }
                    androidx.compose.material3.Button(onClick = onPickFolder, modifier = Modifier.testTag("pickFolderButton")) {
                        Text(if (state.savedFolderUri != null) "別のUSBフォルダを選ぶ" else "USBフォルダを開く")
                    }
                }
                state.layout == BrowserLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().testTag("mediaGrid"),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    gridItems(state.items, key = { it.id }) { item ->
                        val selected = item.id in state.selectedIds
                        // Google Photos-style affordance: selected thumbnails shrink slightly
                        // inside their cell, so the selection reads at a glance. The cell
                        // itself keeps its full size as the tap target.
                        val thumbnailScale by animateFloatAsState(
                            targetValue = if (selected) 0.86f else 1f,
                            animationSpec = tween(durationMillis = 180),
                            label = "selectionScale_${item.id}",
                        )
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .testTag("mediaItem_${item.displayName}")
                                .combinedClickable(
                                    onClick = { if (state.selectionMode) onToggleSelection(item) else onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                ),
                        ) {
                            MediaThumbnail(
                                item.uri,
                                item.kind,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = thumbnailScale
                                        scaleY = thumbnailScale
                                    }
                                    .testTag("mediaThumbnail_${item.displayName}"),
                            )
                            if (item.isVideo) {
                                Text(
                                    text = formatDuration(item.durationMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "選択中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
                                )
                            }
                        }
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize().testTag("mediaList")) {
                    items(state.items, key = { it.id }) { item ->
                        val selected = item.id in state.selectedIds
                        ListItem(
                            headlineContent = { Text(item.displayName) },
                            supportingContent = { Text("${formatSize(item.sizeBytes)} ・ ${formatDate(item.dateTakenMillis)}") },
                            leadingContent = { MediaThumbnail(item.uri, item.kind, modifier = Modifier.size(56.dp)) },
                            trailingContent = {
                                if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "選択中", tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mediaItem_${item.displayName}")
                                .combinedClickable(
                                    onClick = { if (state.selectionMode) onToggleSelection(item) else onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UsbDisconnectedPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("usbDisconnected"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            text = "USBでOsmo Pocketをさしてください",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
