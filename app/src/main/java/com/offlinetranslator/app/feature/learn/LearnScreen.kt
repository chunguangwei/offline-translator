package com.offlinetranslator.app.feature.learn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.TranslationEntity
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.feature.history.HistoryViewModel
import com.offlinetranslator.app.feature.wordbook.WordBooksSection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * 学习 Tab：生词本（翻译记录收藏的词，抽卡练习）+ 单词本（上传建库+测试）。
 * 收藏动作在「历史」页（来源处），学习入口统一在这里。
 */
@Composable
fun LearnScreen(
    padding: PaddingValues,
    historyVm: HistoryViewModel = hiltViewModel(),
    learnVm: LearnViewModel = hiltViewModel(),
) {
    val all by historyVm.items.collectAsStateWithLifecycle()
    val starred = all.filter { it.starred }
    var tab by remember { mutableStateOf(0) }
    var showPractice by remember { mutableStateOf(false) }

    val learnUi by learnVm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showReview by remember { mutableStateOf(false) }
    var sessionItems by remember { mutableStateOf<List<LearnViewModel.ReviewItem>>(emptyList()) }
    var hardItems by remember { mutableStateOf<List<LearnViewModel.ReviewItem>>(emptyList()) }
    var showHardReview by remember { mutableStateOf(false) }
    var hardSessionItems by remember { mutableStateOf<List<LearnViewModel.ReviewItem>>(emptyList()) }
    LaunchedEffect(Unit) { learnVm.refresh() }
    LaunchedEffect(tab) { if (tab == 2) hardItems = learnVm.buildHardSession() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
    ) {
        // 聚合 SRS 头卡：连续天数 + 今日到期 + 开始复习。
        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.srs_streak, learnUi.displayStreak),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.srs_today_due, learnUi.todayDue),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (learnUi.todayDue > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                val items = learnVm.buildTodaySession()
                                sessionItems = items
                                showReview = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.srs_start_review))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.srs_all_clear),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.nav_learn),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.history_filter_starred)) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.wb_tab)) })
            FilterChip(tab == 2, { tab = 2 }, label = { Text(stringResource(R.string.srs_hard_tab)) })
            Spacer(Modifier.weight(1f))
            if (tab == 0 && starred.isNotEmpty()) {
                TextButton(onClick = { showPractice = true }) {
                    Text(stringResource(R.string.practice_start), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (tab == 1) {
            WordBooksSection()
        } else if (tab == 2) {
            if (learnUi.hardCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.srs_hard_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.srs_hard_count, learnUi.hardCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                hardSessionItems = learnVm.buildHardSession()
                                showHardReview = true
                            }
                        }) {
                            Text(
                                stringResource(R.string.srs_hard_practice),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(hardItems, key = { it.card.id }) { item ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(item.front, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        item.back,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (starred.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.history_starred_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(starred, key = { it.id }) { item ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.sourceText, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    item.translatedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // 取消收藏（移出生词本）。
                            IconButton(onClick = { historyVm.setStarred(item.id, false) }) {
                                Icon(
                                    Icons.Rounded.Star,
                                    contentDescription = stringResource(R.string.history_star),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPractice) {
        StarredPracticeDialog(items = starred, onDismiss = { showPractice = false })
    }

    if (showReview) {
        SrsReviewDialog(
            items = sessionItems,
            onGrade = { c, correct -> scope.launch { learnVm.grade(c, correct) } },
            onFinished = { count -> scope.launch { if (count > 0) learnVm.markStudiedToday(); learnVm.refresh() } },
            onDismiss = { showReview = false; learnVm.refresh() },
        )
    }

    if (showHardReview) {
        SrsReviewDialog(
            items = hardSessionItems,
            onGrade = { c, correct -> scope.launch { learnVm.grade(c, correct) } },
            onFinished = { count -> scope.launch { if (count > 0) learnVm.markStudiedToday(); learnVm.refresh() } },
            onDismiss = { showHardReview = false; learnVm.refresh(); scope.launch { hardItems = learnVm.buildHardSession() } },
        )
    }
}

/**
 * 统一复习翻卡 Dialog：跨来源今日到期卡。正面 front → 翻面 back(+note) → 认识/再练 评分落库。
 */
@Composable
internal fun SrsReviewDialog(
    items: List<LearnViewModel.ReviewItem>,
    onGrade: (com.offlinetranslator.app.core.data.db.ReviewCardEntity, Boolean) -> Unit,
    onFinished: (reviewedCount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val queue = remember { mutableStateListOf(*items.toTypedArray()) }
    var revealed by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(0) }
    var finishedFired by remember { mutableStateOf(false) }
    val total = items.size

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (queue.isEmpty()) {
                    LaunchedEffect(Unit) {
                        if (!finishedFired) {
                            finishedFired = true
                            onFinished(reviewed)
                        }
                    }
                    Text("🎉", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.srs_session_summary, reviewed),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.practice_close)) }
                } else {
                    val item = queue.first()
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
                                text = item.front,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (revealed) {
                                Text(
                                    text = item.back,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                if (item.note.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = item.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
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
                            val it = queue.removeAt(0)
                            onGrade(it.card, false)
                            queue.add(it)
                            reviewed++
                            revealed = false
                        }) { Text(stringResource(R.string.practice_again)) }
                        TextButton(onClick = {
                            val it = queue.removeAt(0)
                            onGrade(it.card, true)
                            reviewed++
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

/**
 * 生词本抽卡练习（自 HistoryScreen 迁来）：正面原文 → 翻面译文 → 认识移出/再练放回队尾。
 */
@Composable
private fun StarredPracticeDialog(items: List<TranslationEntity>, onDismiss: () -> Unit) {
    val queue = remember { mutableStateListOf(*items.shuffled().toTypedArray()) }
    var revealed by remember { mutableStateOf(false) }
    val total = items.size

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (queue.isEmpty()) {
                    Text("🎉", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.practice_done), style = MaterialTheme.typography.titleMedium)
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
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (revealed) {
                                Text(
                                    text = card.translatedText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
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
