package com.offlinetranslator.app.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.model.LocalModel
import com.offlinetranslator.app.core.designsystem.components.GlassCard

@Composable
fun ModelsScreen(
    padding: PaddingValues,
    vm: ModelsViewModel = hiltViewModel(),
) {
    val models by vm.locals.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.models_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
        )

        if (models.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_loading))
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(models, key = { it.info.id }) { local ->
                    ModelRow(local, downloads[local.info.id], vm)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(local: LocalModel, dl: DownloadState?, vm: ModelsViewModel) {
    val runtimeBytes = dl?.downloadedBytes ?: 0L
    val effectiveBytes = maxOf(local.downloadedBytes, runtimeBytes)
    val isDownloading = dl?.running == true
    val isComplete = local.isComplete

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(local.info.displayName, style = MaterialTheme.typography.titleMedium)
                    if (local.info.description.isNotEmpty()) {
                        Text(
                            text = local.info.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (local.info.requiresToken) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    val sizeText = if (local.info.sizeBytes >= 1_000_000_000)
                        stringResource(R.string.common_size_gb, local.info.sizeBytes / 1_000_000_000.0)
                    else
                        stringResource(R.string.common_size_mb, local.info.sizeBytes / 1_000_000.0)
                    Text(
                        text = "${stringResource(R.string.models_size)}: $sizeText  ·  " +
                            "${stringResource(R.string.models_ram)}: ${local.info.ramRequirementMb} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(local, isDownloading)
            }

            if (!isComplete && (effectiveBytes > 0 || isDownloading)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (effectiveBytes.toFloat() / local.info.sizeBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val mb = effectiveBytes / 1_000_000.0
                val totalMb = local.info.sizeBytes / 1_000_000.0
                Text(
                    text = String.format("%.1f / %.1f MB", mb, totalMb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            dl?.error?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠ $err",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (isComplete) {
                    if (!local.isActive) {
                        Button(onClick = { vm.activate(local.info) }) {
                            Text(stringResource(R.string.models_action_activate))
                        }
                        Spacer(Modifier.height(0.dp))
                    }
                    OutlinedButton(onClick = { vm.delete(local.info) }) {
                        Text(stringResource(R.string.models_action_delete))
                    }
                } else if (isDownloading) {
                    OutlinedButton(onClick = { vm.cancelDownload(local.info) }) {
                        Text(stringResource(R.string.models_action_pause))
                    }
                } else if (effectiveBytes > 0) {
                    OutlinedButton(onClick = { vm.delete(local.info) }) {
                        Text(stringResource(R.string.models_action_delete))
                    }
                    TextButton(onClick = { vm.startDownload(local.info) }) {
                        Text(stringResource(R.string.models_action_resume))
                    }
                } else {
                    Button(onClick = { vm.startDownload(local.info) }) {
                        Text(stringResource(R.string.models_action_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(local: LocalModel, isDownloading: Boolean) {
    val (label, color) = when {
        local.isActive -> stringResource(R.string.models_status_active) to MaterialTheme.colorScheme.primary
        local.isComplete -> stringResource(R.string.models_status_ready) to MaterialTheme.colorScheme.tertiary
        isDownloading -> stringResource(R.string.models_status_downloading) to MaterialTheme.colorScheme.secondary
        local.downloadedBytes > 0 -> stringResource(R.string.models_status_downloading) to MaterialTheme.colorScheme.secondary
        else -> stringResource(R.string.models_status_not_downloaded) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}
