package com.offlinetranslator.app.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.TranslationEntity
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val all by vm.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // 全部 / 生词本 两档过滤；生词本可发起抽卡练习。
    var starredOnly by remember { mutableStateOf(false) }
    var showPractice by remember { mutableStateOf(false) }
    val items = if (starredOnly) all.filter { it.starred } else all

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (all.isNotEmpty() && !starredOnly) {
                TextButton(onClick = vm::clear) {
                    Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !starredOnly,
                onClick = { starredOnly = false },
                label = { Text(stringResource(R.string.history_filter_all)) },
            )
            FilterChip(
                selected = starredOnly,
                onClick = { starredOnly = true },
                label = { Text(stringResource(R.string.history_filter_starred)) },
            )
            Spacer(Modifier.weight(1f))
            if (starredOnly && items.isNotEmpty()) {
                TextButton(onClick = { showPractice = true }) {
                    Text(stringResource(R.string.practice_start), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (starredOnly) R.string.history_starred_empty else R.string.history_empty
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    HistoryCard(
                        item = item,
                        onCopy = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("translation", item.translatedText))
                        },
                        onDelete = { vm.delete(item.id) },
                        onStar = { vm.setStarred(item.id, !item.starred) },
                    )
                }
            }
        }
    }

    if (showPractice) {
        PracticeDialog(
            items = all.filter { it.starred },
            onDismiss = { showPractice = false },
        )
    }
}

/**
 * 抽卡练习：正面原文 → 点击翻面看译文 → 「认识」移出本轮 /「再练」放回队尾。
 * 会话内循环，不持久化熟练度（离线轻量版）。
 */
@Composable
private fun PracticeDialog(items: List<TranslationEntity>, onDismiss: () -> Unit) {
    val queue = remember { androidx.compose.runtime.mutableStateListOf(*items.shuffled().toTypedArray()) }
    var revealed by remember { mutableStateOf(false) }
    val total = items.size

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (queue.isEmpty()) {
                    Text("🎉", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.practice_done),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.practice_close)) }
                } else {
                    val card = queue.first()
                    Text(
                        text = stringResource(R.string.practice_progress, total - queue.size + 1, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .clickable { revealed = !revealed },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = card.sourceText,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            if (revealed) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = card.translatedText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            } else {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.practice_tap_reveal),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = {
                            // 再练：放回队尾
                            val c = queue.removeAt(0)
                            queue.add(c)
                            revealed = false
                        }) { Text(stringResource(R.string.practice_again)) }
                        TextButton(onClick = {
                            queue.removeAt(0)
                            revealed = false
                        }) {
                            Text(
                                stringResource(R.string.practice_know),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
private fun HistoryCard(
    item: TranslationEntity,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onStar: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${langLabel(item.sourceLang)} → ${langLabel(item.targetLang)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text(
                    text = timeFmt.format(Date(item.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.sourceText,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.translatedText,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 收藏进生词本（练习素材）。
                IconButton(onClick = onStar) {
                    Icon(
                        if (item.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(R.string.history_star),
                        tint = if (item.starred) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(20.dp),
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.history_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(20.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.history_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun langLabel(code: String): String =
    if (code == "ZH") stringResource(R.string.lang_zh) else stringResource(R.string.lang_en)
