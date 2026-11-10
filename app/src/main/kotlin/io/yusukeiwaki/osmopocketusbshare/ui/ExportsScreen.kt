package io.yusukeiwaki.osmopocketusbshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.yusukeiwaki.osmopocketusbshare.data.BatchStatus
import io.yusukeiwaki.osmopocketusbshare.data.ExportBatch
import io.yusukeiwaki.osmopocketusbshare.data.ExportJob
import io.yusukeiwaki.osmopocketusbshare.data.ExportStatus

/**
 * Shows the latest export batch only: its child rows while it is ACTIVE (running,
 * queued, or interrupted by USB removal), or its one-line summary once DONE — by then
 * the child rows have been deleted and only the final result is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportsScreen(
    jobs: List<ExportJob>,
    batch: ExportBatch?,
    onBack: () -> Unit,
    onCancel: (Long) -> Unit,
    onClearHistory: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("エクスポート状況") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("exportsBack")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (batch != null) {
                        TextButton(onClick = onClearHistory, modifier = Modifier.testTag("clearExportHistory")) {
                            Text("履歴を消す")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            batch == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("エクスポート履歴がありません", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("exportsEmpty"))
            }
            batch.status == BatchStatus.DONE -> Column(modifier = Modifier.fillMaxSize().padding(padding).testTag("exportsSummary")) {
                ListItem(
                    headlineContent = { Text("エクスポート結果") },
                    supportingContent = {
                        Column {
                            Text("${batch.totalCount}件中 ${batch.succeededCount}件成功" + if (batch.failedCount > 0) "、${batch.failedCount}件失敗" else "")
                            Text("完了日時: ${formatDetailDate(batch.updatedAtMillis)}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("exportsList")) {
                items(jobs, key = { it.id }) { job ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ListItem(
                            headlineContent = { Text(job.displayName) },
                            supportingContent = {
                                Column {
                                    Text(statusText(job))
                                    if (job.status == ExportStatus.COMPLETED) {
                                        Text("${formatSize(job.outputSizeBytes)} ・ DCIM/OsmoPocketUsbShare")
                                    }
                                    job.errorMessage?.let { if (it.isNotEmpty()) Text(it) }
                                }
                            },
                            trailingContent = {
                                if (job.status == ExportStatus.QUEUED || job.status == ExportStatus.RUNNING) {
                                    IconButton(onClick = { onCancel(job.id) }, modifier = Modifier.testTag("cancelJob_${job.id}")) {
                                        Icon(Icons.Filled.Cancel, contentDescription = "キャンセル")
                                    }
                                }
                            },
                        )
                        if (job.status == ExportStatus.RUNNING || job.status == ExportStatus.QUEUED) {
                            LinearProgressIndicator(
                                progress = { job.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth().testTag("exportProgress_${job.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(job: ExportJob): String = when (job.status) {
    ExportStatus.QUEUED -> "待機中"
    ExportStatus.RUNNING -> "エクスポート中 ${job.progressPercent}%"
    ExportStatus.COMPLETED -> "完了"
    ExportStatus.FAILED -> "失敗"
    ExportStatus.CANCELLED -> "キャンセル済み"
}
