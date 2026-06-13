package com.offlinetranslator.app.feature.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val phase: Phase = Phase.Idle,
    /** True when the check was triggered automatically (cold start): stay quiet if up-to-date. */
    val silent: Boolean = true,
    val latestVersion: String = "",
    val notes: String = "",
    val apkUrl: String = "",
    val apkSha256: String = "",
    val sizeBytes: Long = 0,
    val downloaded: Long = 0,
    val total: Long = 0,
    val file: File? = null,
    val message: String? = null,
) {
    enum class Phase { Idle, Checking, Available, Downloading, ReadyToInstall, UpToDate, Failed }

    val percent: Int
        get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
) : ViewModel() {

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    fun check(silent: Boolean) {
        if (_ui.value.phase == UpdateUiState.Phase.Checking ||
            _ui.value.phase == UpdateUiState.Phase.Downloading
        ) return
        _ui.value = UpdateUiState(phase = UpdateUiState.Phase.Checking, silent = silent)
        viewModelScope.launch {
            _ui.value = when (val r = checker.check()) {
                is UpdateResult.Available -> _ui.value.copy(
                    phase = UpdateUiState.Phase.Available,
                    latestVersion = r.version,
                    notes = r.notes,
                    apkUrl = r.apkUrl,
                    apkSha256 = r.apkSha256,
                    sizeBytes = r.sizeBytes,
                )
                UpdateResult.UpToDate -> _ui.value.copy(phase = UpdateUiState.Phase.UpToDate)
                is UpdateResult.Error -> _ui.value.copy(
                    phase = UpdateUiState.Phase.Failed,
                    message = r.message,
                )
            }
        }
    }

    fun startDownload() {
        val url = _ui.value.apkUrl
        if (url.isBlank()) return
        val sha = _ui.value.apkSha256
        _ui.update { it.copy(phase = UpdateUiState.Phase.Downloading, downloaded = 0, total = 0) }
        viewModelScope.launch {
            checker.download(url, sha).collect { step ->
                _ui.update {
                    when (step) {
                        is DownloadStep.Progress ->
                            it.copy(downloaded = step.downloaded, total = step.total)
                        is DownloadStep.Completed ->
                            it.copy(phase = UpdateUiState.Phase.ReadyToInstall, file = step.file)
                        is DownloadStep.Failed ->
                            it.copy(phase = UpdateUiState.Phase.Failed, message = step.message)
                    }
                }
            }
        }
    }

    fun canInstall(): Boolean = checker.canInstall()

    fun installPermissionIntent(): Intent = checker.installPermissionIntent()

    /** Launch the system installer for the downloaded APK, if ready. */
    fun install() {
        _ui.value.file?.let { checker.install(it) }
    }

    /** Dismiss the dialog / clear a transient (UpToDate / Failed) state. */
    fun dismiss() {
        _ui.value = UpdateUiState(phase = UpdateUiState.Phase.Idle)
    }
}
