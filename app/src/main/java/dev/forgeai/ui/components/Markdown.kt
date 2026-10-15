package dev.forgeai.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Lightweight markdown for assistant messages.
//
// Thinking blocks (<think>...</think>) are removed entirely so models with
// reasoning (Bonsai, LFM2.5, etc.) look exactly like non-thinking models — only
// the final answer is rendered. A block that is never closed is hidden while
// streaming, but recovered at the end of the turn (some models, e.g. Bonsai
// Q1_0, put the whole answer inside <think> and never close it). Code fences
// are the only thing styled as code:
//   - ```lang ... ``` -> code card with monospace font + copy button
//   - `inline` / **bold** -> inline styling in plain text
// ---------------------------------------------------------------------------

sealed class MdSegment {
    data class Text(val text: String) : MdSegment()
    data class Code(val language: String, val code: String) : MdSegment()
}

private val codeRe = Regex("```([^`\\n]*)\\n([\\s\\S]*?)```")
private val strayThinkTagRe = Regex("</?think>", RegexOption.IGNORE_CASE)

/**
 * Removes thinking ranges from the model output.
 *
 * Everything between a properly closed <think> ... </think> is dropped
 * (thinking is internal). Two degraded-model cases are handled so a real
 * answer is never thrown away:
 *
 *  - [final] == true and <think> is never closed: some models (e.g. Bonsai
 *    Q1_0) write the whole response, final answer included, inside <think>
 *    and end without emitting </think>. While streaming we keep hiding it
 *    (it may still be thinking), but once the turn is over we recover the
 *    text so the user sees the answer instead of "(sem resposta)".
 *  - [final] == true and <think>...</think> leaves nothing but whitespace
 *    after it: the answer may live inside the block, so the inner text is
 *    kept instead of dropping everything.
 */
fun stripThink(raw: String, final: Boolean = false): String {
    val lower = raw.lowercase()
    val out = StringBuilder(raw.length)
    var pos = 0
    while (pos < raw.length) {
        val open = lower.indexOf("<think>", pos)
        if (open < 0) {
            out.append(raw, pos, raw.length)
            break
        }
        if (open > pos) out.append(raw, pos, open)
        val close = lower.indexOf("</think>", open + 7)
        if (close < 0) {
            if (final) out.append(raw, open + 7, raw.length) // recover the answer
            break // while streaming, an open block is treated as pending thinking
        }
        if (final && raw.substring(close + 8).isBlank()) {
            // closed block with no visible text after it -> keep the inner text
            out.append(raw, open + 7, close)
        }
        pos = close + 8
    }
    return strayThinkTagRe.replace(out.toString(), "")
}

/**
 * True when the output contains a <think> block that is never closed AND its
 * content looks like a finished response (non-empty, ends with sentence
 * punctuation).
 *
 * Some low-bit models (Bonsai Q1_0) write the whole answer inside <think>
 * and end without ever emitting </think>. Detecting this per model lets the
 * UI stream that content live instead of hiding it until the turn ends. The
 * punctuation guard avoids flagging a response that was merely truncated
 * mid-think (e.g. max tokens hit while reasoning).
 */
fun isOpenThinkAnswer(raw: String): Boolean {
    val lower = raw.lowercase()
    var pos = 0
    while (pos < raw.length) {
        val open = lower.indexOf("<think>", pos)
        if (open < 0) return false
        val close = lower.indexOf("</think>", open + 7)
        if (close < 0) {
            val inner = raw.substring(open + 7).trim()
            return inner.isNotEmpty() && inner.lastOrNull()?.let { it in ".!?…:" } == true
        }
        pos = close + 8
    }
    return false
}

/** Splits (think-free) model output into code blocks and plain text. */
fun parseMarkdown(
    raw: String,
    streaming: Boolean = false,
    streamOpenThink: Boolean = false,
): List<MdSegment> {
    val cleaned = stripThink(raw, final = !streaming || streamOpenThink).trimStart()
    val out = mutableListOf<MdSegment>()
    var pos = 0
    for (m in codeRe.findAll(cleaned)) {
        if (m.range.first > pos) out += MdSegment.Text(cleaned.substring(pos, m.range.first))
        out += MdSegment.Code(
            language = m.groupValues[1].trim().ifEmpty { "código" },
            code = m.groupValues[2].trimEnd(),
        )
        pos = m.range.last + 1
    }
    if (pos < cleaned.length) out += MdSegment.Text(cleaned.substring(pos))
    return out
}

/** Full message body renderer (text + code, thinking removed). */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    streaming: Boolean = false,
    showCursor: Boolean = false,
    streamOpenThink: Boolean = false,
) {
    val segments = parseMarkdown(text, streaming = streaming, streamOpenThink = streamOpenThink)
    if (segments.isEmpty()) {
        if (streaming) {
            // model is still "thinking" (only <think> produced so far)
            TypingIndicator(modifier = modifier)
        } else {
            Text(
                "O modelo não produziu uma resposta visível. Tente regenerar ou aumente o limite de tokens nas configurações.",
                modifier = modifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { i, seg ->
            when (seg) {
                is MdSegment.Code -> CodeBlock(seg.language, seg.code)
                is MdSegment.Text -> {
                    SelectionContainer {
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnnotatedText(
                                raw = seg.text,
                                color = textColor,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (streaming && showCursor && i == segments.lastIndex) {
                                Spacer(Modifier.width(2.dp))
                                CursorBlock()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Plain text with inline `code` and **bold**
// ---------------------------------------------------------------------------
private val inlineRe = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*")

@Composable
private fun AnnotatedText(raw: String, color: Color, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val annotated = remember(raw, codeBg) {
        buildAnnotatedString {
            var pos = 0
            for (m in inlineRe.findAll(raw)) {
                if (m.range.first > pos) append(raw.substring(pos, m.range.first))
                val code = m.groupValues[1]
                if (code.isNotEmpty()) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                        )
                    ) { append(code) }
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[2]) }
                }
                pos = m.range.last + 1
            }
            if (pos < raw.length) append(raw.substring(pos))
        }
    }
    Text(
        annotated,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
    )
}

// ---------------------------------------------------------------------------
// Fenced code block with copy button
// ---------------------------------------------------------------------------
@Composable
private fun CodeBlock(language: String, code: String) {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val codeBg = if (isDark) Color(0xFF15171C) else Color(0xFFF2F3F5)
    val codeFg = if (isDark) Color(0xFFE3E5EA) else Color(0xFF2B2D31)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = codeBg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // header: language + copy
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (copied) "Copiado ✓" else "Copiar",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("código", code))
                        copied = true
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Copiar código",
                        tint = if (copied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
            SelectionContainer {
                Text(
                    code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = codeFg,
                        lineHeight = 19.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
