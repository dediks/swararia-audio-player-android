package com.swararia.audiolab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DownloadsScreen(upgrade: UpgradeUiActions, ingest: IngestState, modifier: Modifier,
    onSettings: () -> Unit, onCancelIngest: () -> Unit) {
    val jobs = upgrade.jobs
    val completed = jobs.count { it.status == "COMPLETED" }
    val failed = jobs.count { it.status == "FAILED" }
    val canceled = jobs.count { it.status == "CANCELED" }
    val active = jobs.any { it.status == "QUEUED" || it.status == "RUNNING" }
    val queued = jobs.any { it.status == "QUEUED" }
    val running = jobs.any { it.status == "RUNNING" }
    var menu by remember { mutableStateOf(false) }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    BrandLogo(32.dp)
                    Box {
                        GlyphButton(UiIcons.More, "Menu Downloads", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        }
                    }
                }
            }
            if (jobs.isEmpty()) item(key = "empty") {
                Text("Belum ada download.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            } else item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$completed dari ${jobs.size} selesai", style = MaterialTheme.typography.titleMedium)
                    if (failed > 0 || canceled > 0) Text("$failed gagal · $canceled dibatalkan", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // This measures completed items, not invented byte totals or time remaining.
                    LinearProgressIndicator(progress = { completed.toFloat() / jobs.size }, modifier = Modifier.fillMaxWidth())
                }
            }
            items(jobs, key = { it.id }) { job -> DownloadJobRow(job) }
            if (ingest.batchId == null && (ingest.busy || ingest.error)) item(key = "direct-operation") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ingest.stage, style = MaterialTheme.typography.titleSmall)
                    if (ingest.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = onCancelIngest, enabled = ingest.cancelable) { Text("Cancel") }
                    }
                    if (ingest.error && ingest.detail.isNotBlank()) Text(ingest.detail, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) OutlinedButton(onClick = upgrade.onCancelBatch,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel batch") }
            if (failed > 0 || (queued && !running && !ingest.busy)) Button(
                onClick = { jobs.firstOrNull()?.let { upgrade.onRetryFailed(it.batchId) } },
                enabled = !upgrade.operationBusy && !ingest.busy && !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (failed > 0) "Retry failed ($failed)" else "Start download")
            }
        }
    }
}

@Composable
private fun DownloadJobRow(job: DownloadJob) {
    val running = job.status == "RUNNING"
    val progress = job.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Surface(color = if (running) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                YoutubeThumbnail(job.thumbnailUrl, job.title, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(when (job.status) {
                        "QUEUED" -> "Menunggu"
                        "RUNNING" -> job.stage.ifBlank { "Memproses…" }
                        "COMPLETED" -> "Selesai"
                        "FAILED" -> "Gagal"
                        "CANCELED" -> "Dibatalkan"
                        else -> job.stage.ifBlank { "Status belum tersedia" }
                    }, style = MaterialTheme.typography.bodyMedium,
                        color = if (job.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (job.status) {
                    "COMPLETED" -> Icon(UiIcons.Check, "Selesai", tint = MaterialTheme.colorScheme.primary)
                    "RUNNING" -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    "QUEUED" -> Icon(UiIcons.Queue, "Menunggu")
                    "FAILED", "CANCELED" -> Icon(UiIcons.Close, null)
                }
            }
            if (running) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            job.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
}
