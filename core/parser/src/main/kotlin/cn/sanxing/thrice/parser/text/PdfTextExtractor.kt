package cn.sanxing.thrice.parser.text

import kotlinx.serialization.Serializable

/**
 * 带坐标的文本片段。格式 B 是"分页 + 表格"结构，星期由**列位置**决定，
 * 因此 PDF 提取需要保留坐标（纯文本导出会丢失列信息，详见 README）。
 */
@Serializable
data class PdfTextSpan(
    val text: String,
    val page: Int,   // 从 1 开始
    val x: Float,    // 已按页面方向校正的 x（xDirAdj，自左向右）
    val y: Float     // 已按页面方向校正的 y（yDirAdj，自顶向下增大）
)

/**
 * PDF 文本提取结果：一组带坐标的片段。
 *
 * @param pageTexts 可选的「按页纯文本」（页号从 1 开始）。由提取器按内容流顺序直接产出时，
 * 其结果与 PDFTextStripper 的 getText 完全一致，纯文本解析路径（格式 A）应优先使用它，
 * 避免 [spans] 几何重排破坏阅读顺序；为空时回退到坐标拼接。
 */
@Serializable
data class PdfText(
    val spans: List<PdfTextSpan> = emptyList(),
    val pageTexts: Map<Int, String> = emptyMap()
) {

    val pages: List<Int>
        get() = (spans.map { it.page } + pageTexts.keys).distinct().sorted()

    /**
     * 某一页的片段，按 y **降序**、x 升序排序。
     *
     * 注意：PDF 的 y 轴自顶向下增大（见 [PdfTextSpan.y]），因此 y 降序实际是
     * **自底向上**。需要阅读顺序（自顶向下）的调用方应自己 `asReversed()`
     * （`FormatBParser.parsePositioned` 即如此处理）。
     */
    fun spansOf(page: Int): List<PdfTextSpan> =
        spans.filter { it.page == page }.sortedWith(compareByDescending<PdfTextSpan> { it.y }.thenBy { it.x })

    /**
     * 还原成"阅读顺序"纯文本（自顶向下、自左向右拼接），供格式检测与纯文本解析使用。
     * 提取器提供了 [pageTexts] 时原样使用（内容流顺序，块×行结构已由提取器保证）。
     */
    fun toPlainText(): String {
        if (pageTexts.isNotEmpty()) {
            return pages.joinToString(separator = "\n") { page ->
                pageTexts[page]?.trimEnd('\n', '\r').orEmpty()
            }
        }
        val sb = StringBuilder()
        for (page in pages) {
            var lastY = Float.NaN
            // spansOf 是自底向上，此处必须反转才能得到自顶向下的阅读顺序
            for (span in spansOf(page).asReversed()) {
                if (!lastY.isNaN() && kotlin.math.abs(span.y - lastY) > 1f) sb.append('\n')
                sb.append(span.text)
                lastY = span.y
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}

/**
 * PDF 文本提取接口。解析器模块只保留接口 + 纯文本输入，
 * 具体实现（pdfbox-android）放在 core:data 模块。
 */
interface PdfTextExtractor {
    /** 从 PDF 字节流提取带坐标的文本。 */
    fun extract(bytes: ByteArray): PdfText
}
