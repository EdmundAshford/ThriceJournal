package cn.sanxing.thrice.parser.parse

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * 极简 .xlsx 读取器（零第三方依赖）：xlsx 本质是 zip+XML，
 * 这里只解析「第一个工作表」，输出与 [CsvParser] 兼容的 TSV 文本
 * （行间 \n、单元格间 \t，保留空列位置），随后走同一套智能表格解析。
 *
 * 支持的单元格类型：
 *  - t="s" 共享字符串（sharedStrings.xml，最常见）
 *  - t="inlineStr" 内联字符串
 *  - t="str" 公式字符串、数值、布尔
 *
 * 不支持老式 .xls（BIFF 二进制），调用方应提示用户另存为 .xlsx。
 */
object XlsxReader {

    /** 读取第一个工作表为 TSV；非 zip / 找不到工作表时抛 [IllegalArgumentException]。 */
    fun readTsv(bytes: ByteArray): String {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw IllegalArgumentException("not an xlsx (zip) file")
        }
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        val sheetEntry = entries.keys
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .minByOrNull { sheetNumberOf(it) }
            ?: throw IllegalArgumentException("xlsx has no worksheet")

        return parseSheet(entries[sheetEntry]!!, shared)
    }

    private fun sheetNumberOf(path: String): Int {
        val m = Regex("sheet(\\d+)\\.xml$").find(path)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    // ---------------- sharedStrings.xml ----------------

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = ArrayList<String>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
            private var inSi = false
            private val sb = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val tag = localName?.takeUnless { it.isEmpty() } ?: qName.orEmpty()
                when (tag) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    // 富文本：<si> 内多个 <r><t>，文本都在 <t> 里
                    "t" -> if (inSi) textNode = StringBuilder()
                }
            }

            private var textNode: StringBuilder? = null

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                textNode?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = localName?.takeUnless { it.isEmpty() } ?: qName.orEmpty()
                when (tag) {
                    "t" -> {
                        sb.append(textNode?.toString().orEmpty())
                        textNode = null
                    }
                    "si" -> {
                        strings.add(sb.toString())
                        inSi = false
                    }
                }
            }
        })
        return strings
    }

    // ---------------- worksheets/sheetN.xml ----------------

    private fun parseSheet(bytes: ByteArray, shared: List<String>): String {
        // rowIndex(1-based) -> colIndex(1-based) -> text
        val grid = sortedMapOf<Int, MutableMap<Int, String>>()
        var maxRow = 0
        var maxCol = 0

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
            private var row = 0
            private var col = 0
            private var cellType = ""
            private var inValue = false
            private var inInlineText = false
            private val valueBuf = StringBuilder()
            private var cellSeq = 0

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val tag = localName?.takeUnless { it.isEmpty() } ?: qName.orEmpty()
                when (tag) {
                    "row" -> {
                        row = attributes?.getValue("r")?.toIntOrNull() ?: (maxRow + 1)
                        cellSeq = 0
                    }
                    "c" -> {
                        cellSeq++
                        col = colIndexFromRef(attributes?.getValue("r")) ?: cellSeq
                        cellType = attributes?.getValue("t") ?: "n"
                        valueBuf.setLength(0)
                    }
                    "v" -> inValue = true
                    "t" -> {
                        // inlineStr：<c t="inlineStr"><is><t>…</t></is></c>
                        if (cellType == "inlineStr") inInlineText = true
                    }
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inValue || inInlineText) valueBuf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = localName?.takeUnless { it.isEmpty() } ?: qName.orEmpty()
                when (tag) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = valueBuf.toString()
                        val text = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw.trim()
                        }
                        if (text.isNotEmpty() && row > 0) {
                            grid.getOrPut(row) { sortedMapOf() }[col] = text
                            if (row > maxRow) maxRow = row
                            if (col > maxCol) maxCol = col
                        }
                    }
                }
            }
        })

        val sb = StringBuilder()
        for (r in 1..maxRow) {
            val cells = grid[r]
            val parts = (1..maxCol).map { cells?.get(it).orEmpty() }
            // 整行全空跳过
            if (parts.any { it.isNotEmpty() }) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(parts.joinToString("\t") { sanitize(it) })
            }
        }
        return sb.toString()
    }

    /** A1 引用 → 列序号（A=1, B=2 …）；无引用返回 null。 */
    private fun colIndexFromRef(ref: String?): Int? {
        if (ref == null) return null
        var idx = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') idx = idx * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') idx = idx * 26 + (ch - 'a' + 1)
            else if (idx > 0) break
        }
        return idx.takeIf { it > 0 }
    }

    /** TSV 安全：去掉单元格内的换行 / 制表符（Excel 文本里的换行转成空格）。 */
    private fun sanitize(s: String): String =
        s.replace('\t', ' ').replace("\r\n", " ").replace('\n', ' ').trim()
}
