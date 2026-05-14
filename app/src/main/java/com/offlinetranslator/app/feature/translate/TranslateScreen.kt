package com.offlinetranslator.app.feature.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetranslator.app.R

/**
 * Translate screen — Doubao-style redesign.
 *
 * Two stacked translucent cards (Source / Target) with a centered swap button
 * sitting between them. The Source card is neutral surface; the Target card is
 * tinted with a primary→tertiary gradient so the result reads as the "AI"
 * side at a glance. Each card has:
 *   - A small color-bar + label header (原文 / 译文) for quick role recognition
 *   - Body text styled at 18sp with comfortable line-height
 *   - Footer action bar (paste/clear on input, copy on output)
 */
@Composable
fun TranslateScreen(
    padding: PaddingValues,
    vm: TranslateViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) { ui.error?.let { snackbarHost.showSnackbar(it) } }

    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Title
            Text(
                text = stringResource(R.string.translate_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                ),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.translate_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            Spacer(Modifier.height(20.dp))

            // Two-card stack with a swap button overlapping the gap.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Source card
                    SourceCard(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        lang = ui.source,
                        text = ui.input,
                        onTextChange = vm::setInput,
                        onPaste = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                            if (t.isNotEmpty()) vm.setInput(t)
                        },
                        onClear = vm::clear,
                    )

                    // Target card
                    TargetCard(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        lang = ui.target,
                        text = ui.output,
                        isTranslating = ui.isTranslating,
                        onCopy = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("translation", ui.output))
                        },
                    )
                }

                // Floating circular Swap button at the seam between cards.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = vm::swapLang,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                            ),
                    ) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = stringResource(R.string.translate_swap),
                            tint = Color.White,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bottom CTA — primary translate button with gradient.
            Button(
                onClick = {
                    keyboard?.hide()
                    vm.translate()
                },
                enabled = ui.input.isNotBlank() && !ui.isTranslating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (ui.isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.translate_translating),
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        stringResource(R.string.translate_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Neutral-surface input card with header + footer action row. */
@Composable
private fun SourceCard(
    modifier: Modifier,
    lang: TranslateLang,
    text: String,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CardHeader(
                label = stringResource(R.string.voice_label_source),
                lang = lang,
                accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.translate_input_hint),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 18.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            // Footer actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.isNotEmpty()) {
                    Text(
                        text = "${text.length}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                IconButton(onClick = onPaste) {
                    Icon(
                        Icons.Rounded.ContentPaste,
                        contentDescription = stringResource(R.string.translate_paste),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Rounded.Clear,
                            contentDescription = stringResource(R.string.translate_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Gradient-tinted output card. */
@Composable
private fun TargetCard(
    modifier: Modifier,
    lang: TranslateLang,
    text: String,
    isTranslating: Boolean,
    onCopy: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    ),
                ),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CardHeader(
                label = stringResource(R.string.voice_label_target),
                lang = lang,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    text.isEmpty() && isTranslating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.translate_translating),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    text.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.translate_output_placeholder),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                        )
                    }
                    else -> {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 26.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.translate_copy),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Color-bar + label + language pill, shared header style for both cards. */
@Composable
private fun CardHeader(
    label: String,
    lang: TranslateLang,
    accentColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 12.dp)
                .clip(CircleShape)
                .background(accentColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
            color = accentColor,
        )
        Spacer(Modifier.width(10.dp))
        // Language pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accentColor.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(if (lang == TranslateLang.ZH) R.string.lang_zh else R.string.lang_en),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = accentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
