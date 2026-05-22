package com.offlinetranslator.app.feature.update

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R

/**
 * Renders the update prompt driven by [UpdateViewModel]. Drop it anywhere in the
 * tree (AppShell for the cold-start auto-check, Settings for the manual button)
 * and call [UpdateViewModel.check] to trigger it.
 *
 * Transient outcomes (already up-to-date / network error) surface as a Toast,
 * but only when the check was *not* silent — a background cold-start check that
 * finds nothing new stays completely quiet.
 */
@Composable
fun UpdateDialogHost(vm: UpdateViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // After the user returns from the "install unknown apps" settings page, retry.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (vm.canInstall()) vm.install()
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            UpdateUiState.Phase.UpToDate -> {
                if (!state.silent) {
                    Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
                vm.dismiss()
            }
            UpdateUiState.Phase.Failed -> {
                if (!state.silent) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.update_failed, state.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                vm.dismiss()
            }
            else -> Unit
        }
    }

    val showDialog = state.phase == UpdateUiState.Phase.Available ||
        state.phase == UpdateUiState.Phase.Downloading ||
        state.phase == UpdateUiState.Phase.ReadyToInstall
    if (!showDialog) return

    val downloading = state.phase == UpdateUiState.Phase.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) vm.dismiss() },
        title = { Text(stringResource(R.string.update_available_title, state.latestVersion)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.notes.isNotBlank()) {
                    Text(
                        text = state.notes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 6.dp),
                    )
                    Text(
                        text = stringResource(R.string.update_downloading, state.percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdateUiState.Phase.Available -> TextButton(onClick = { vm.startDownload() }) {
                    Text(stringResource(R.string.update_download))
                }
                UpdateUiState.Phase.Downloading -> TextButton(enabled = false, onClick = {}) {
                    Text("${state.percent}%")
                }
                UpdateUiState.Phase.ReadyToInstall -> TextButton(onClick = {
                    if (vm.canInstall()) vm.install() else permLauncher.launch(vm.installPermissionIntent())
                }) {
                    Text(stringResource(R.string.update_install))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = { vm.dismiss() }) {
                    Text(stringResource(R.string.update_later))
                }
            }
        },
    )
}
