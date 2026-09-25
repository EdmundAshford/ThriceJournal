package cn.sanxing.thrice.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.data.domain.model.Note

/**
 * 随身记轻量标记语法（Markdown 子集，自写解析，不引入第三方库）：
 *
 * 行首（整行生效）：
 *  - "- "  无序列表，预览渲染为「• 」
 *  - "N. " 有序列表（N 为数字），预览保留序号
 *  - "> "  引用，预览缩进并以竖线前缀标识
 *
 * 行内（成对包裹，不跨行）：
 *  - "**x**" 粗体
 *  - "*x*"   斜体
 *  - "++x++" 下划线（约定 ++ 标记下划线）
 */

private val BULLET_LINE = Regex("""^\s*-\s+(.*)$""")
private val ORDERED_LINE = Regex("""^\s*(\d+)\.\s+(.*)$""")
private val QUOTE_LINE = Regex("""^\s*>\s?(.*)$""")

/** 行内标记统一匹配：粗体优先，其次下划线，最后斜体（避免 ** 被拆成两个 *）。 */
private val INLINE_REGEX = Regex(
    """\*\*([^*\n]+)\*\*|\+\+([^+\n]+)\+\+|\*([^*\n]+)\*"""
)

private val BOLD_REGEX = Regex("""\*\*([^*\n]+)\*\*""")
private val UNDERLINE_REGEX = Regex("""\+\+([^+\n]+)\+\+""")
private val ITALIC_REGEX = Regex("""\*([^*\n]+)\*""")

/**
 * 把笔记正文渲染为预览用 [AnnotatedString]：行内粗 / 斜 / 下划线，
 * 列表 / 引用段落缩进与前缀。
 *
 * @param baseStyle 基础样式（调用方已带入单篇字体 / 字号覆盖）
 * @param textColor 正文颜色
 * @param accentColor 引用竖线等前缀强调色
 */
fun parseNoteContent(
    markdown: String,
    baseStyle: TextStyle,
    textColor: Color,
    accentColor: Color
): AnnotatedString = buildAnnotatedString {
    // 整段基线样式
    pushStyle(baseStyle.toSpanStyle().copy(color = textColor))
    val lines = markdown.split('\n')
    lines.forEachIndexed { index, rawLine ->
        val bulletMatch = BULLET_LINE.matchEntire(rawLine)
        val orderedMatch = ORDERED_LINE.matchEntire(rawLine)
        val quoteMatch = QUOTE_LINE.matchEntire(rawLine)
        when {
            quoteMatch != null -> {
                // 引用：整段缩进 + 竖线前缀，正文用次级颜色
                pushStyle(
                    baseStyle.toParagraphStyle().copy(
                        textIndent = TextIndent(firstLine = 22.sp, restLine = 22.sp)
                    )
                )
                pushStyle(SpanStyle(color = accentColor))
                append("| ")
                pop()
                pushStyle(SpanStyle(color = textColor.copy(alpha = 0.75f)))
                appendInline(quoteMatch.groupValues[1])
                pop()
                pop()
            }

            bulletMatch != null -> {
                pushStyle(
                    baseStyle.toParagraphStyle().copy(
                        textIndent = TextIndent(firstLine = 22.sp, restLine = 22.sp)
                    )
                )
                pushStyle(SpanStyle(color = accentColor))
                append("• ")
                pop()
                appendInline(bulletMatch.groupValues[1])
                pop()
            }

            orderedMatch != null -> {
                pushStyle(
                    baseStyle.toParagraphStyle().copy(
                        textIndent = TextIndent(firstLine = 22.sp, restLine = 22.sp)
                    )
                )
                pushStyle(SpanStyle(color = accentColor))
                append("${orderedMatch.groupValues[1]}. ")
                pop()
                appendInline(orderedMatch.groupValues[2])
                pop()
            }

            else -> appendInline(rawLine)
        }
        if (index != lines.lastIndex) append('\n')
    }
    pop()
}

/** 追加一行的行内标记内容（粗 / 下划线 / 斜），标记符本身不展示。 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(text: String) {
    var cursor = 0
    INLINE_REGEX.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val (bold, underlined, italic) = match.destructured
        when {
            bold.isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(bold)
                pop()
            }

            underlined.isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(underlined)
                pop()
            }

            else -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italic)
                pop()
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/** 去掉行首列表 / 引用前缀与全部行内标记，得到纯文本（卡片摘要用）。 */
fun notePlainText(markdown: String): String =
    markdown.split('\n').joinToString("\n") { line ->
        val withoutPrefix = when {
            BULLET_LINE.matches(line) -> BULLET_LINE.matchEntire(line)!!.groupValues[1]
            ORDERED_LINE.matches(line) -> ORDERED_LINE.matchEntire(line)!!.groupValues[2]
            QUOTE_LINE.matches(line) -> QUOTE_LINE.matchEntire(line)!!.groupValues[1]
            else -> line
        }
        withoutPrefix
            .replace(BOLD_REGEX) { it.groupValues[1] }
            .replace(UNDERLINE_REGEX) { it.groupValues[1] }
            .replace(ITALIC_REGEX) { it.groupValues[1] }
    }

/** 卡片标题：无标题时取正文首个非空纯文本行；全空返回 null（调用方回落占位文案）。 */
fun noteDisplayTitle(note: Note): String? {
    note.title.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val firstLine = notePlainText(note.content)
        .split('\n')
        .firstOrNull { it.isNotBlank() }
        ?.trim()
    return firstLine?.takeIf { it.isNotEmpty() }
}

/** 卡片摘要：取纯文本前两个非空行。 */
fun noteSummary(markdown: String): String =
    notePlainText(markdown)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("  ")
