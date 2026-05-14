package com.offlinetranslator.app.core.data.model

import android.content.Context
import com.offlinetranslator.app.core.data.AppPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LocalModel(
    val info: ModelInfo,
    val file: File,
    val downloadedBytes: Long,
    val isComplete: Boolean,
    val isActive: Boolean,
)

/** Tracks where models live on disk and which one is active. */
@Singleton
class ModelStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferencesRepository,
) {
    private val baseDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    private val refresh = MutableStateFlow(0)

    fun fileFor(info: ModelInfo): File = File(baseDir, info.fileName)
    fun partFileFor(info: ModelInfo): File = File(baseDir, info.fileName + ".part")

    fun directoryPath(): String = baseDir.absolutePath

    fun bumpRefresh() { refresh.value = refresh.value + 1 }

    /** Snapshot list of all known models with current local state. */
    val locals: Flow<List<LocalModel>> =
        combine(prefs.flow, refresh.asStateFlow()) { p, _ ->
            ModelRegistry.ALL.map { info ->
                val full = fileFor(info)
                val part = partFileFor(info)
                val complete = full.exists() && full.length() >= info.sizeBytes * 0.99
                LocalModel(
                    info = info,
                    file = full,
                    downloadedBytes = when {
                        complete -> full.length()
                        part.exists() -> part.length()
                        else -> 0L
                    },
                    isComplete = complete,
                    isActive = p.activeModelId == info.id && complete,
                )
            }
        }

    fun delete(info: ModelInfo): Boolean {
        val ok1 = fileFor(info).delete()
        val ok2 = partFileFor(info).delete()
        bumpRefresh()
        return ok1 || ok2
    }
}
