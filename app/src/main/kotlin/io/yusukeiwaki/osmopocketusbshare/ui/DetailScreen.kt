package io.yusukeiwaki.osmopocketusbshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.yusukeiwaki.osmopocketusbshare.data.MediaItem
import io.yusukeiwaki.osmopocketusbshare.data.MetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    items: List<MediaItem>,
    initialIndex: Int,
    onBack: () -> Unit,
    onExport: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Keep the swiped-to page across rotation instead of jumping back to the entry item.
    var savedPage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialIndex.coerceIn(0, items.lastIndex)) }
    val pagerState = rememberPagerState(initialPage = savedPage) { items.size }
    LaunchedEffect(pagerState.currentPage) { savedPage = pagerState.currentPage }
    var menuExpanded by remember { mutableStateOf(false) }
    val current = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]

    // The source can disappear while this screen is open (USB removed). Re-check on page
    // changes and whenever the screen resumes, and say so instead of showing a broken image.
    val context = LocalContext.current
    var available by remember(current.id) { mutableStateOf<Boolean?>(null) }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, current.id) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(current.id, resumeTick) {
        available = withContext(Dispatchers.IO) { MetadataReader.exists(context, current.uri) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detailBack")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("detailMenu")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "メニュー")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("エクスポート") },
                                onClick = { menuExpanded = false; onExport(current) },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (available == false) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("mediaUnavailable"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "このメディアは読み込めません。USBが取り外された可能性があります。",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                    Text("戻って、USBを接続し直してください。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("detailPager"),
                ) { page ->
                    val item = items[page]
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DetailMediaImage(item.uri, item.kind, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Text(
                text = "${pagerState.currentPage + 1} / ${items.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
            )
            MetadataPanel(item = current)
        }
    }
}

@Composable
fun MetadataPanel(item: MediaItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("metadataPanel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("詳細", style = MaterialTheme.typography.titleMedium)
        MetadataRow("ファイル名", item.displayName)
        MetadataRow("撮影日時", formatDetailDate(item.dateTakenMillis))
        MetadataRow("ファイルサイズ", formatSize(item.sizeBytes))
        if (item.width > 0 && item.height > 0) MetadataRow("解像度", "${item.width} × ${item.height}")
        if (item.isVideo) MetadataRow("長さ", formatDuration(item.durationMillis).ifEmpty { "不明" })
        if (item.mimeType.isNotEmpty()) MetadataRow("形式", item.mimeType)
        if (item.sourceLabel.isNotEmpty()) MetadataRow("保存元", item.sourceLabel)
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("metadata_$label"))
    }
}
