package com.offlinetranslator.app.feature.settings

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.InferenceBackend
import com.offlinetranslator.app.core.data.ModelSource
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.core.designsystem.theme.ThemeMode
import com.offlinetranslator.app.core.i18n.AppLanguage

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engineStatus by vm.engineStatus.collectAsStateWithLifecycle()

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

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_privacy_content),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
