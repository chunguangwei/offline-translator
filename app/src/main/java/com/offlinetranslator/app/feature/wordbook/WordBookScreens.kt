package com.offlinetranslator.app.feature.wordbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R
import com.offlinetranslator.app.core.data.db.WordBookEntity
import com.offlinetranslator.app.core.data.db.WordEntryEntity
import com.offlinetranslator.app.core.designsystem.components.GlassCard
import com.offlinetranslator.app.feature.learn.LearnViewModel
import com.offlinetranslator.app.feature.learn.SrsReviewDialog
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 单词本区块（嵌在历史 Tab 第三段）：列表 → 详情 → 测试，本地状态导航。
 * 设计规则见 docs/superpowers/specs/2026-06-12-yiren-wordbook-design.md。
 */
@Composable
fun WordBooksSection(vm: WordBookViewModel = hiltViewModel()) {
    val books by vm.books.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<WordBookEntity?>(null) }
    var showImport by remember { mutableStateOf(false) }

    // 删除后回列表。
    LaunchedEffect(books) {
        if (selected != null && books.none { it.id == selected!!.id }) selected = null
    }

    val sel = selected
    if (sel == null) {
        BookList(books = books, onOpen = { selected = it }, onNew = { showImport = true }, vm = vm)
    } else {
        BookDetail(book = sel, onBack = { selected = null }, vm = vm)
    }

    if (showImport) {
        ImportDialog(vm = vm, onDismiss = {
            vm.resetImport()
            showImport = false
        })
    }
}

// ───────────────────────── 列表 ─────────────────────────

@Composable
private fun BookList(
    books: List<WordBookEntity>,
    onOpen: (WordBookEntity) -> Unit,
    onNew: () -> Unit,
    vm: WordBookViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onNew) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(stringResource(R.string.wb_new))
            }
        }
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.wb_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books, key = { it.id }) { book ->
                    BookCard(book = book, onOpen = { onOpen(book) }, onDelete = { vm.deleteBook(book.id) }, vm = vm)
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: WordBookEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    vm: WordBookViewModel,
) {
    val entries by vm.entries(book.id).collectAsStateWithLifecycle()
    val mastered = entries.count { it.proficiency >= 3 }
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(book.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (book.purpose.isNotBlank()) {
                    Text(
                        book.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.wb_stats, entries.size, mastered, book.dailyGoal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.wb_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ───────────────────────── 导入 ─────────────────────────

@Composable
private fun ImportDialog(vm: WordBookViewModel, onDismiss: () -> Unit) {
    val ui by vm.importUi.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var dailyGoal by remember { mutableStateOf(10) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            vm.readTextFile(uri)?.let { text = it }
        }
    }

    Dialog(onDismissRequest = { if (!ui.isExtracting) onDismiss() }) {
        // 实底卡片：弹窗不透底（玻璃拟态在 Dialog 里会透出下层内容，观感差）。
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.wb_new),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))

                if (ui.drafts.isEmpty() && !ui.isExtracting) {
                    // 第一步：给文本（粘贴 / 选 txt 文件）→ 提取。
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                        placeholder = { Text(stringResource(R.string.wb_paste_hint)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*")) }) {
                            Text(stringResource(R.string.wb_pick_file))
                        }
                        Button(onClick = { vm.extract(text) }, enabled = text.isNotBlank()) {
                            Text(stringResource(R.string.wb_extract))
                        }
                    }
                    ui.error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    // 第二步：提取进度 + 预览 + 命名保存。
                    if (ui.isExtracting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(0.dp))
                            Text(
                                "  " + stringResource(
                                    if (ui.loadingModel) R.string.translate_loading_model
                                    else R.string.wb_extracting, ui.extractedCount,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = vm::cancelExtract) { Text(stringResource(R.string.practice_close)) }
                        }
                    } else {
                        Text(
                            stringResource(R.string.wb_extract_done, ui.drafts.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(ui.drafts, key = { it.english }) { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${d.english} — ${d.chinese}", style = MaterialTheme.typography.bodyMedium)
                                    if (d.note.isNotBlank()) {
                                        Text(
                                            d.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { vm.removeDraft(d) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.wb_name_hint)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = purpose, onValueChange = { purpose = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text(stringResource(R.string.wb_purpose_hint)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.wb_daily_goal), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 20, 30).forEach { n ->
                            FilterChip(
                                selected = dailyGoal == n,
                                onClick = { dailyGoal = n },
                                label = { Text("$n") },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.saveBook(name, purpose, dailyGoal) { onDismiss() } },
                        enabled = !ui.isExtracting && ui.drafts.isNotEmpty() && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.wb_save)) }
                }
            }
        }
    }
}

// ───────────────────────── 详情 + 测试 ─────────────────────────

private enum class QuizDirection { ZH2EN, EN2ZH, MIXED }

@Composable
private fun BookDetail(
    book: WordBookEntity,
    onBack: () -> Unit,
    vm: WordBookViewModel,
    learnVm: LearnViewModel = hiltViewModel(),
) {
    val entries by vm.entries(book.id).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var direction by remember { mutableStateOf(QuizDirection.MIXED) }
    var quizBatch by remember { mutableStateOf<List<WordEntryEntity>?>(null) }
    // SRS：今日复习用单词本的到期卡；掌握标记按 box≥MAX_BOX。
    var stats by remember { mutableStateOf(LearnViewModel.BookStats(0, 0, emptySet())) }
    var srsItems by remember { mutableStateOf<List<LearnViewModel.ReviewItem>>(emptyList()) }
    var showSrsReview by remember { mutableStateOf(false) }
    LaunchedEffect(book.id, entries) { stats = learnVm.bookStats(book.id) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Column {
                Text(book.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.wb_stats_srs, entries.size, stats.mastered, stats.due),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 方向选择 + 两种测试入口。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(direction == QuizDirection.ZH2EN, { direction = QuizDirection.ZH2EN },
                label = { Text(stringResource(R.string.wb_dir_zh2en)) })
            FilterChip(direction == QuizDirection.EN2ZH, { direction = QuizDirection.EN2ZH },
                label = { Text(stringResource(R.string.wb_dir_en2zh)) })
            FilterChip(direction == QuizDirection.MIXED, { direction = QuizDirection.MIXED },
                label = { Text(stringResource(R.string.wb_dir_mixed)) })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { srsItems = learnVm.buildBookSession(book.id); showSrsReview = true } },
                enabled = stats.due > 0,
            ) { Text(stringResource(R.string.wb_review_due)) }
            OutlinedButton(
                onClick = { scope.launch { quizBatch = vm.buildQuizBatch(book, dailyOnly = false) } },
                enabled = entries.isNotEmpty(),
            ) { Text(stringResource(R.string.wb_quiz_all)) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.id }) { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${e.english} — ${e.chinese}" + if (stats.masteredIds.contains(e.id)) " ✓" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (e.note.isNotBlank()) {
                            Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { vm.deleteEntry(e.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showSrsReview) {
        SrsReviewDialog(
            items = srsItems,
            onGrade = { c, ok -> scope.launch { learnVm.grade(c, ok) } },
            onFinished = { n -> scope.launch { if (n > 0) learnVm.markStudiedToday(); stats = learnVm.bookStats(book.id) } },
            onDismiss = { showSrsReview = false; scope.launch { stats = learnVm.bookStats(book.id) } },
        )
    }

    quizBatch?.let { batch ->
        if (batch.isEmpty()) {
            quizBatch = null
        } else {
            QuizDialog(batch = batch, direction = direction, vm = vm, onDismiss = { quizBatch = null })
        }
    }
}

/**
 * 翻卡测试：正面按方向出题 → 「查看」翻面（答案+注释）→ 认识(+1)/不认识(归0放回队尾)。
 * 混合方向时每张卡随机定向（本轮内稳定）。
 */
@Composable
private fun QuizDialog(
    batch: List<WordEntryEntity>,
    direction: QuizDirection,
    vm: WordBookViewModel,
    onDismiss: () -> Unit,
) {
    // 每张卡的实际方向（混合时随机，一次定死）。
    val cardDirs = remember {
        batch.associate { e ->
            e.id to when (direction) {
                QuizDirection.MIXED -> if (Random.nextBoolean()) QuizDirection.ZH2EN else QuizDirection.EN2ZH
                else -> direction
            }
        }
    }
    val queue = remember { mutableStateListOf(*batch.toTypedArray()) }
    val missed = remember { mutableStateOf(setOf<Long>()) } // 被「不认识」放回过的卡
    var revealed by remember { mutableStateOf(false) }
    var firstPass by remember { mutableStateOf(0) } // 一次过（没被放回过就认识）的张数
    val total = batch.size

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
                    Text(
                        stringResource(R.string.wb_quiz_done, total, firstPass),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.practice_close)) }
                } else {
                    val card = queue.first()
                    val dir = cardDirs[card.id] ?: QuizDirection.EN2ZH
                    Text(
                        stringResource(R.string.practice_progress, total - queue.size + 1, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp).clickable { revealed = !revealed },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 正面：按方向出题。
                        Text(
                            text = if (dir == QuizDirection.ZH2EN) card.chinese else card.english,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (revealed) {
                            Text(
                                text = if (dir == QuizDirection.ZH2EN) card.english else card.chinese,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                            if (card.note.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    card.note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            Text(
                                stringResource(R.string.wb_reveal),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = {
                            // 不认识：归 0，放回队尾再练。
                            vm.grade(card, known = false)
                            missed.value = missed.value + card.id
                            val c = queue.removeAt(0)
                            queue.add(c)
                            revealed = false
                        }) { Text(stringResource(R.string.practice_again)) }
                        TextButton(onClick = {
                            vm.grade(card, known = true)
                            if (card.id !in missed.value) firstPass++
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
