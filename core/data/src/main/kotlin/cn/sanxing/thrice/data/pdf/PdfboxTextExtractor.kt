package cn.sanxing.thrice.data.pdf

import cn.sanxing.thrice.parser.text.PdfText
import cn.sanxing.thrice.parser.text.PdfTextExtractor
import cn.sanxing.thrice.parser.text.PdfTextSpan
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 pdfbox-android 提取 PDF 文本（实现 parser 模块的 [PdfTextExtractor]）。
 *
 * 三个关键点，缺任一真实系统导出的 PDF 都会解析出 0 门课：
 * - **资源加载器**：pdfbox-android 的 CMap/字体资源放在 AAR 的 assets 里，必须先调
 *   `PDFBoxResourceLoader.init(context)`（见 DataModule.providePdfTextExtractor）。否则
 *   查不到 `Adobe-GB1-UCS2`，中文整体乱码（形如 `Thep: 7-16Th`），解析结果为 0 门课。
 * - **纯文本布局**：`sortByPosition = false` + `wordSeparator = "\n"`，按内容流顺序、每个
 *   文本块独占一行输出（与参考样例 `sample_formatA.txt` 一致）。该文本逐页存入
 *   `PdfText.pageTexts`，格式 A 等纯文本解析路径原样使用，与桌面 pdfbox 2.0.27 验证矩阵一致。
 *   置 `sortByPosition = true` 会重排成"阅读顺序"：星期列标签被挤到课程格之后，
 *   导致星期丢失、节次错位。
 * - **坐标片段**：同时重写 `processTextPosition` 收集每个 TextPosition 的真实 (x, y)，
 *   格式 B 的"按列定星期"才能工作（`xDirAdj/yDirAdj` 已按页面方向校正，自左上起算）。
 *   不能把整页 getText 结果当一个 span（x/y 恒 0 会让格式 B 星期全部丢成 0）。
 */
@Singleton
class PdfboxTextExtractor @Inject constructor() : PdfTextExtractor {

    override fun extract(bytes: ByteArray): PdfText {
        val spans = mutableListOf<PdfTextSpan>()
        val pageTexts = mutableMapOf<Int, String>()
        // 闭包里要写当前页号，用单元素数组模拟可捕获的可变 Int
        val pageNo = intArrayOf(0)

        return try {
            PDDocument.load(bytes).use { doc ->
                val stripper = object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        val unicode = text.unicode
                        if (!unicode.isNullOrEmpty()) {
                            spans.add(
                                PdfTextSpan(
                                    text = unicode,
                                    page = pageNo[0],
                                    x = text.xDirAdj,
                                    y = text.yDirAdj
                                )
                            )
                        }
                        super.processTextPosition(text)
                    }
                }
                stripper.sortByPosition = false
                stripper.wordSeparator = "\n"

                for (page in 1..doc.numberOfPages) {
                    pageNo[0] = page
                    stripper.startPage = page
                    stripper.endPage = page
                    // getText 驱动 processTextPosition 回调；页面原文另存，保证格式 A 路径零漂移
                    pageTexts[page] = stripper.getText(doc).trimEnd('\n', '\r')
                }
                PdfText(spans = spans, pageTexts = pageTexts)
            }
        } catch (e: Exception) {
            PdfText(emptyList())
        }
    }
}
