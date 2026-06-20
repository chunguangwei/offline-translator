package com.offlinetranslator.app.feature.settings

import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.BuildConfig
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.InferenceBackend
import com.offlinetranslator.app.core.data.ModelSource
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.core.designsystem.theme.ThemeMode
import com.offlinetranslator.app.core.i18n.AppLanguage
import com.offlinetranslator.app.feature.backup.BackupViewModel
import com.offlinetranslator.app.feature.learn.cancelDaily
import com.offlinetranslator.app.feature.learn.scheduleDaily
import android.widget.Toast
import kotlinx.coroutines.launch
import com.offlinetranslator.app.feature.update.UpdateDialogHost
import com.offlinetranslator.app.feature.update.UpdateUiState
import com.offlinetranslator.app.feature.update.UpdateViewModel

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenModels: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engineStatus by vm.engineStatus.collectAsStateWithLifecycle()

    val updateVm: UpdateViewModel = hiltViewModel()
    val updateState by updateVm.ui.collectAsStateWithLifecycle()
    UpdateDialogHost(updateVm)

    val context = LocalContext.current

    // ---- Backup & restore ----
    val backupVm: BackupViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    var backupMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backupMsg) {
        backupMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            backupMsg = null
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                val json = backupVm.buildBackupJson()
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                backupMsg = context.getString(R.string.backup_export_done)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                val text = context.contentResolver.openInputStream(it)?.use { ins ->
                    ins.readBytes().toString(Charsets.UTF_8)
                }
                backupMsg = if (text == null) {
                    context.getString(R.string.backup_restore_fail)
                } else {
                    when (val r = backupVm.restore(text)) {
                        is BackupViewModel.RestoreResult.Success ->
                            context.getString(
                                R.string.backup_restore_done,
                                r.books, r.entries, r.translations, r.chats,
                            )
                        is BackupViewModel.RestoreResult.Failure -> r.reason
                    }
                }
            }
        }
    }

    // On Android 13+ enabling the reminder requires the POST_NOTIFICATIONS
    // runtime permission. We persist + schedule regardless of the result so the
    // toggle reflects user intent; if denied, the worker simply skips posting.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        vm.setReminder(true, state.reminderHour, state.reminderMinute)
        scheduleDaily(context, state.reminderHour, state.reminderMinute)
    }

    fun enableReminder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setReminder(true, state.reminderHour, state.reminderMinute)
            scheduleDaily(context, state.reminderHour, state.reminderMinute)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )

        SettingSection(title = stringResource(R.string.settings_theme)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.themeMode == ThemeMode.SYSTEM,
                    onClick = { vm.setTheme(ThemeMode.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_theme_system)) },
                )
                FilterChip(
                    selected = state.themeMode == ThemeMode.LIGHT,
                    onClick = { vm.setTheme(ThemeMode.LIGHT) },
                    label = { Text(stringResource(R.string.settings_theme_light)) },
                )
                FilterChip(
                    selected = state.themeMode == ThemeMode.DARK,
                    onClick = { vm.setTheme(ThemeMode.DARK) },
                    label = { Text(stringResource(R.string.settings_theme_dark)) },
                )
            }
        }

        SettingSection(title = stringResource(R.string.settings_language)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.language == AppLanguage.SYSTEM,
                    onClick = { vm.setLanguage(AppLanguage.SYSTEM) },
                    label = { Text(stringResource(R.string.settings_language_system)) },
                )
                FilterChip(
                    selected = state.language == AppLanguage.ZH,
                    onClick = { vm.setLanguage(AppLanguage.ZH) },
                    label = { Text(stringResource(R.string.lang_zh)) },
                )
                FilterChip(
                    selected = state.language == AppLanguage.EN,
                    onClick = { vm.setLanguage(AppLanguage.EN) },
                    label = { Text(stringResource(R.string.lang_en)) },
                )
            }
        }

        SettingSection(title = stringResource(R.string.srs_reminder_title)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.srs_reminder_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = state.reminderEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            enableReminder()
                        } else {
                            vm.setReminder(false, state.reminderHour, state.reminderMinute)
                            cancelDaily(context)
                        }
                    },
                )
            }
            if (state.reminderEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            TimePickerDialog(
                                context,
                                { _, h, m ->
                                    vm.setReminder(true, h, m)
                                    scheduleDaily(context, h, m)
                                },
                                state.reminderHour,
                                state.reminderMinute,
                                true,
                            ).show()
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.srs_reminder_time),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = String.format("%02d:%02d", state.reminderHour, state.reminderMinute),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        SettingSection(title = stringResource(R.string.settings_model_manage)) {
            Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_model_manage_action))
            }
        }

        SettingSection(title = stringResource(R.string.settings_model_source)) {
            // Horizontally scroll instead of wrapping — keeps the four options
            // on one line on narrow phones (the previous Row wrapped chips
            // onto two lines, which looked broken).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.modelSource == ModelSource.HF_DIRECT,
                    onClick = { vm.setModelSource(ModelSource.HF_DIRECT) },
                    label = { Text(stringResource(R.string.settings_model_source_hf)) },
                )
                FilterChip(
                    selected = state.modelSource == ModelSource.HF_MIRROR,
                    onClick = { vm.setModelSource(ModelSource.HF_MIRROR) },
                    label = { Text(stringResource(R.string.settings_model_source_mirror)) },
                )
                FilterChip(
                    selected = state.modelSource == ModelSource.CUSTOM,
                    onClick = { vm.setModelSource(ModelSource.CUSTOM) },
                    label = { Text(stringResource(R.string.settings_model_source_custom)) },
                )
                FilterChip(
                    selected = state.modelSource == ModelSource.LOCAL_ONLY,
                    onClick = { vm.setModelSource(ModelSource.LOCAL_ONLY) },
                    label = { Text(stringResource(R.string.settings_model_source_local)) },
                )
            }
        }

        if (state.modelSource == ModelSource.CUSTOM) {
            SettingSection(title = stringResource(R.string.settings_custom_mirror)) {
                var url by remember(state.customMirrorBase) { mutableStateOf(state.customMirrorBase) }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        vm.setCustomMirrorBase(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://your-mirror.example.com/path/to/") },
                    supportingText = { Text(stringResource(R.string.settings_custom_mirror_hint)) },
                )
            }
        }

        SettingSection(title = stringResource(R.string.settings_backend)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.backend == InferenceBackend.AUTO,
                    onClick = { vm.setBackend(InferenceBackend.AUTO) },
                    label = { Text(stringResource(R.string.settings_backend_auto)) },
                )
                FilterChip(
                    selected = state.backend == InferenceBackend.GPU,
                    onClick = { vm.setBackend(InferenceBackend.GPU) },
                    label = { Text(stringResource(R.string.settings_backend_gpu)) },
                )
                FilterChip(
                    selected = state.backend == InferenceBackend.CPU,
                    onClick = { vm.setBackend(InferenceBackend.CPU) },
                    label = { Text(stringResource(R.string.settings_backend_cpu)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            // Show what the engine actually negotiated. Critical for the GPU
            // case because some Adreno/Mali drivers silently fall back to CPU.
            BackendStatusLine(engineStatus)
        }

        SettingSection(title = stringResource(R.string.settings_update)) {
            Text(
                text = stringResource(R.string.settings_current_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val checking = updateState.phase == UpdateUiState.Phase.Checking
            Button(
                onClick = { updateVm.check(silent = false) },
                enabled = !checking,
            ) {
                Text(
                    stringResource(
                        if (checking) R.string.update_checking else R.string.update_check,
                    ),
                )
            }
        }

        SettingSection(title = stringResource(R.string.backup_section)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch("译人备份.json") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.backup_export))
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.backup_restore))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingSection(title = stringResource(R.string.settings_about)) {
            val uriHandler = LocalUriHandler.current
            val repoUrl = stringResource(R.string.settings_about_repo_url)
            Text(
                text = stringResource(R.string.settings_about_app_line, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_repo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri(repoUrl) },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_about_credits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val contactEmail = stringResource(R.string.settings_about_contact_email)
            Text(
                text = stringResource(R.string.settings_about_contact_label) + ": " + contactEmail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri("mailto:$contactEmail") },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_privacy_content),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun BackendStatusLine(status: com.offlinetranslator.app.engine.llm.EngineStatus) {
    val state = status.state
    val active = status.activeBackend
    val fellBack = status.backendFellBack
    val (label, isWarn) = when {
        state == com.offlinetranslator.app.engine.llm.EngineState.IDLE ->
            stringResource(R.string.settings_backend_status_idle) to false
        state == com.offlinetranslator.app.engine.llm.EngineState.MODEL_MISSING ->
            stringResource(R.string.settings_backend_status_no_model) to false
        state == com.offlinetranslator.app.engine.llm.EngineState.LOADING ->
            stringResource(R.string.settings_backend_status_loading) to false
        state == com.offlinetranslator.app.engine.llm.EngineState.ERROR ->
            stringResource(R.string.settings_backend_status_error) to true
        else -> {
            val name = when (active) {
                com.offlinetranslator.app.engine.llm.ActiveBackend.GPU -> "GPU"
                com.offlinetranslator.app.engine.llm.ActiveBackend.CPU -> "CPU"
                com.offlinetranslator.app.engine.llm.ActiveBackend.MIXED -> "GPU + CPU"
                else -> "—"
            }
            val txt = if (fellBack) {
                stringResource(R.string.settings_backend_status_fellback, name)
            } else {
                stringResource(R.string.settings_backend_status_active, name)
            }
            txt to fellBack
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (isWarn) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
