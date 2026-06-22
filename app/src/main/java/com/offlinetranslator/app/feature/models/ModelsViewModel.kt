package com.offlinetranslator.app.feature.models

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import com.offlinetranslator.app.core.data.model.LocalModel
import com.offlinetranslator.app.core.data.model.ModelInfo
import com.offlinetranslator.app.core.data.model.ModelStorage
import com.offlinetranslator.app.feature.models.download.ModelDownloadManager
import com.offlinetranslator.app.engine.llm.GemmaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Per-model download state, drives UI progress bar + error display + notification.
 */
data class DownloadState(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val error: String? = null,
    val displayName: String = "",
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: ModelStorage,
    private val prefs: AppPreferencesRepository,
    private val downloadManager: ModelDownloadManager,
    private val engine: GemmaEngine,
) : ViewModel() {

    val locals: StateFlow<List<LocalModel>> = storage.locals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 下载状态来自应用级 [ModelDownloadManager]——下载跑在前台服务护住的应用级协程里，
     * 不随本 ViewModel 销毁而中断；熄屏/切后台也继续（对齐 iOS 后台下载）。
     */
    val downloads: StateFlow<Map<String, DownloadState>> = downloadManager.downloads

    fun startDownload(info: ModelInfo) = downloadManager.start(info)

    fun cancelDownload(info: ModelInfo) = downloadManager.cancel(info)

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
            downloadManager.clear(info.id)
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
