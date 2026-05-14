package com.offlinetranslator.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for chat bubbles.
 *
 * Supported syntax (covers ~95% of LLM outputs):
 *   - Headings:   #  ##  ###
 *   - Bold:       **text** or __text__
 *   - Italic:     *text* or _text_
 *   - Strikethrough: ~~text~~
 *   - Inline code: `code`
 *   - Code block: ```...```  (renders with monospace + grey panel)
 *   - Unordered list: -  *  +  followed by space
 *   - Ordered list:   1. 2. 3. etc.
 *   - Blockquote: >
 *
 * The goal is to **strip raw `**`/`*`/backticks** out of LLM output so users
 * see formatted text instead of "literally the markdown source".
 *
 * Intentionally does NOT use a third-party library (commonmark / markwon)
 * to keep the APK small and start-up fast.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    if (text.isEmpty()) {
        Text(text = "", modifier = modifier, color = color, style = MaterialTheme.typography.bodyLarge)
        return
    }
    val blocks = parseBlocks(text)
    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Spacer(Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> {
                    val sz = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        else -> 17.sp
                    }
                    Text(
                        text = parseInline(block.content),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = sz,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInline(block.content),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MdBlock.UnorderedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "•  ",
                            color = color,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = parseInline(block.content),
                            color = color,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is MdBlock.OrderedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${block.number}. ",
                            color = color,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            text = parseInline(block.content),
                            color = color,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        ) { Text(" ") } // dummy to give the bar height
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = parseInline(block.content),
                            color = if (color == Color.Unspecified)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                            else color.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontStyle = FontStyle.Italic,
                            ),
                        )
                    }
                }
                is MdBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp)),
                    ) {
                        Text(
                            text = block.content,
                            color = color,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock
    data class Paragraph(val content: String) : MdBlock
    data class UnorderedItem(val content: String) : MdBlock
    data class OrderedItem(val number: Int, val content: String) : MdBlock
    data class Quote(val content: String) : MdBlock
    data class CodeBlock(val content: String) : MdBlock
}

/**
 * Parse the input into block-level elements. Consecutive paragraph-like lines
 * are merged so soft-wrapping is preserved.
 */
private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split('\n')
    var i = 0
    val paraBuf = StringBuilder()

    fun flushPara() {
        if (paraBuf.isNotEmpty()) {
            out.add(MdBlock.Paragraph(paraBuf.toString().trimEnd()))
            paraBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Code fence
        if (trimmed.startsWith("```")) {
            flushPara()
            i++
            val code = StringBuilder()
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            // skip the closing fence (or EOF)
            if (i < lines.size) i++
            out.add(MdBlock.CodeBlock(code.toString()))
            continue
        }

        // Heading
        val hMatch = Regex("^(#{1,6})\\s+(.+)").find(trimmed)
        if (hMatch != null) {
            flushPara()
            out.add(MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2]))
            i++
            continue
        }

        // Unordered list item
        val ulMatch = Regex("^([-*+])\\s+(.+)").find(trimmed)
        if (ulMatch != null) {
            flushPara()
            out.add(MdBlock.UnorderedItem(ulMatch.groupValues[2]))
            i++
            continue
        }

        // Ordered list item
        val olMatch = Regex("^(\\d{1,3})[.)]\\s+(.+)").find(trimmed)
        if (olMatch != null) {
            flushPara()
            out.add(MdBlock.OrderedItem(olMatch.groupValues[1].toInt(), olMatch.groupValues[2]))
            i++
            continue
        }

        // Blockquote
        if (trimmed.startsWith("> ")) {
            flushPara()
            out.add(MdBlock.Quote(trimmed.removePrefix("> ")))
            i++
            continue
        }

        // Blank line = paragraph separator
        if (trimmed.isEmpty()) {
            flushPara()
            i++
            continue
        }

        // Paragraph line — accumulate (preserve soft line breaks as space)
        if (paraBuf.isNotEmpty()) paraBuf.append(' ')
        paraBuf.append(line.trimEnd())
        i++
    }
    flushPara()
    return out
}

/**
 * Inline parser. Walks the string with a small state machine and emits an
 * [AnnotatedString] with appropriate spans. Markdown markers themselves
 * (`**`, `*`, `` ` ``, `~~`) are CONSUMED, not displayed — that's the whole
 * point of doing this.
 */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        // Bold: ** or __ … same delimiter to close.
        if ((c == '*' || c == '_') && i + 1 < n && text[i + 1] == c) {
            val delim = "$c$c"
            val end = text.indexOf(delim, i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(parseInline(text.substring(i + 2, end)))
                pop()
                i = end + 2
                continue
            }
        }
        // Strikethrough: ~~ … ~~
        if (c == '~' && i + 1 < n && text[i + 1] == '~') {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(parseInline(text.substring(i + 2, end)))
                pop()
                i = end + 2
                continue
            }
        }
        // Italic: single * or _ (must not be a list marker — handled at block level).
        if ((c == '*' || c == '_') && (i == 0 || text[i - 1] != c)) {
            // Find a matching un-doubled closer.
            var j = i + 1
            while (j < n) {
                if (text[j] == c && (j + 1 >= n || text[j + 1] != c) && (j == 0 || text[j - 1] != c)) break
                j++
            }
            if (j < n && j > i + 1) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(parseInline(text.substring(i + 1, j)))
                pop()
                i = j + 1
                continue
            }
        }
        // Inline code: `…`
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x33808080),
                    ),
                )
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        append(c)
        i++
    }
}
