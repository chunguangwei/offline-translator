package com.offlinetranslator.app.feature.models

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.model.LocalModel
import com.offlinetranslator.app.core.data.model.ModelInfo
import com.offlinetranslator.app.core.data.model.ModelStorage
import com.offlinetranslator.app.feature.models.download.DownloadProgress
import com.offlinetranslator.app.feature.models.download.ModelDownloader
import com.offlinetranslator.app.engine.llm.GemmaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Per-model download state, drives UI progress bar + error display.
 */
data class DownloadState(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: ModelStorage,
    private val prefs: AppPreferencesRepository,
    private val downloader: ModelDownloader,
    private val engine: GemmaEngine,
) : ViewModel() {

    val locals: StateFlow<List<LocalModel>> = storage.locals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /**
     * Run download in viewModelScope (foreground) — bypass WorkManager whose Jobs
     * are blocked on some Chinese OEMs (vivo OriginOS, MIUI, etc.) by aggressive
     * background restrictions (CONSTRAINT_BACKGROUND_NOT_RESTRICTED).
     *
     * Trade-off: download pauses if the user kills the App. That's acceptable for
     * an MVP — `ModelDownloader` already does HTTP Range resume, so user can just
     * tap "继续" to resume.
     */
    fun startDownload(info: ModelInfo) {
        if (jobs[info.id]?.isActive == true) return
        _downloads.update { it + (info.id to DownloadState(running = true)) }
        jobs[info.id] = viewModelScope.launch {
            downloader.download(info).collect { p ->
                when (p) {
                    is DownloadProgress.Connecting -> _downloads.update {
                        it + (info.id to (it[info.id] ?: DownloadState()).copy(running = true, error = null))
                    }
                    is DownloadProgress.Progress -> _downloads.update {
                        it + (info.id to DownloadState(
                            downloadedBytes = p.downloaded,
                            totalBytes = p.total,
                            running = true,
                        ))
                    }
                    is DownloadProgress.Completed -> _downloads.update {
                        it + (info.id to DownloadState(
                            downloadedBytes = info.sizeBytes,
                            totalBytes = info.sizeBytes,
                            running = false,
                        ))
                    }
                    is DownloadProgress.Failed -> _downloads.update {
                        it + (info.id to (it[info.id] ?: DownloadState()).copy(
                            running = false,
                            error = p.error,
                        ))
                    }
                }
            }
        }
    }

    fun cancelDownload(info: ModelInfo) {
        jobs[info.id]?.cancel()
        jobs.remove(info.id)
        _downloads.update {
            it + (info.id to (it[info.id] ?: DownloadState()).copy(running = false))
        }
    }

    fun activate(info: ModelInfo) {
        viewModelScope.launch {
            prefs.setActiveModel(info.id)
            // Unload any previously-loaded engine so that the next ensureLoaded()
            // picks up the newly-activated model id from prefs.
            runCatching { engine.unload() }
        }
    }

    fun delete(info: ModelInfo) {
        viewModelScope.launch {
            // If the model being deleted is currently loaded in the engine,
            // unload it first to release the file handle (Windows-style locks
            // don't apply to Android, but native mmap can hold the inode).
            if (engine.status.value.activeModel?.id == info.id) {
                runCatching { engine.unload() }
            }
            storage.delete(info)
            _downloads.update { it - info.id }
        }
    }

    /**
     * Import a model file from a SAF picker URI into the app's models dir.
     * Runs on IO dispatcher — multi-GB copies must NOT block the main thread.
     */
    suspend fun importLocal(uri: Uri, info: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = storage.fileFor(info)
            context.contentResolver.openInputStream(uri).use { input ->
                target.outputStream().use { out -> input?.copyTo(out) }
            }
            storage.bumpRefresh()
            true
        }.onFailure { android.util.Log.w("OT-Models", "importLocal failed", it) }
            .getOrDefault(false)
    }
}
