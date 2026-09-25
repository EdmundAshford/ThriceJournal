package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.MappingEntry
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.TeacherSegment
import cn.sanxing.thrice.parser.text.PdfText
import cn.sanxing.thrice.parser.text.PdfTextSpan
import kotlin.math.abs

/**
 * 格式 B：分页型 / 结构化文本型。
 *
 * 结构：分页 + `时间段/节次/星期一…星期日` 表头 + 单元格
 * `课程名* (1-2节)6-11周/校区:…/场地:…/教师:…/…`。
 *
 * 关键点：
 * - 星期由**列位置**决定（星期一..星期日 7 列）。纯文本导出会**丢失列信息**，
 *   因此 `parse` 只能拿到课程名 / 节次 / 周次 / 教师 / 地点 / 学分等字段，
 *   星期需走 `parsePositioned`（借助 PDF 坐标）。见 README 与 RESULT.md。
 * - 同一单元格可能有多门课（如 周四 1-2 高数A1 + 高数A2）；同一门课可能拆成
 *   多个"周次/教师"块（计导 7-9 / 10 / 11 / 12）。
 * - 页面尾部 `其他课程：…`、图例行 `*: 讲课…`、`打印时间:…` 会被过滤。
 * - 跨页字段碎片（如第 2 页开头的 `:24/学分:1.5`）无法可靠回贴，跳过并告警。
 */
object FormatBParser {

    private val PAGE_MARK = Regex("={3,}\\s*\\[第\\s*\\d+\\s*页\\]")
    private val COLUMN_LABEL = Regex("^(时间段|节次|上午|下午|晚上)$")
    private val DAY_HEADER = Regex("^(星期|周|礼拜)[一二三四五六日天]$")
    private val SECTION_NUMBER = Regex("^(\\d{1,2})$")
    private val SECTION_IN_CELL = Regex("\\(\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*节\\s*\\)")

    /** 列识别失败时的空结果（调用方据此回退纯文本解析）。 */
    private val EMPTY_OUTCOME = ParseOutcome(emptyList())

    /** 同一视觉行内，相邻 glyph 起点 x 差超过该值（pt）即视为不同文本块。 */
    private const val RUN_GAP_PT = 12f

    fun parse(normalized: String): ParseOutcome =
        parseLines(splitInlineFieldLines(normalized.split('\n')), emptyMap())

    // ------------------------------------------------------------------
    // 带坐标解析（星期由列位置决定）
    // ------------------------------------------------------------------

    /**
     * 带坐标解析：星期由课程名所在的**表格列**决定。
     *
     * pdfbox 回调的粒度是单个 glyph：同一视觉行的 glyph 必须先按 x 间距聚成文本块
     * （[clusterRuns]），再以**块首 x** 判定列 —— 否则单元格内的长字段串会逐字
     * 撒进相邻列，节次数字也会被当成「节次号」误删（真实 PDF 上这两点都会让解析崩掉）。
     *
     * 流程：
     * 1. 第 1 页的「星期一..星期日」文本块（或单 span）学习 7 列的 x 中点边界与表格左右边缘；
     * 2. 逐页把 glyph 按 y 聚成视觉行（y 差 ≤1pt 视为同行），行内按 x 聚成文本块；
     * 3. 丢弃标签块（表头 / 节次 / 上下午 / 页码）与表格水平范围外的块（页眉标题 / 时间列）；
     * 4. 每列内部保持「课名 → 该课字段块」的上下顺序，按列序拼成线性行表交给 [parseLines]，
     *    每行标注所属星期；「其他课程」横跨行从标记处整行收走、归入第 1 列。
     *
     * 表头识别失败（<2 个星期标记）时返回空结果，调用方回退纯文本解析（星期为 0，需手改）。
     */
    fun parsePositioned(pdf: PdfText): ParseOutcome {
        val firstPage = pdf.pages.firstOrNull() ?: return EMPTY_OUTCOME
        val pageRows = pdf.pages.associateWith { page ->
            groupVisualRows(pdf.spansOf(page)).map(::clusterRuns)
        }
        val column = buildColumnMap(pdf, pageRows[firstPage].orEmpty()) ?: return EMPTY_OUTCOME
        // 真实 PDF 中表头是独立文本块：表头行（以及常与它同 y 的页眉标题）整体丢弃，
        // 防止无 `*` 的标题碎片被「折行课名」机制拼进第一门课
        val topGateY = pageRows[firstPage].orEmpty()
            .flatten()
            .filter { DAY_HEADER.matches(it.text.trim()) }
            .minOfOrNull { it.spans.first().y }

        val buckets = Array(7) { mutableListOf<String>() }
        for (page in pdf.pages) {
            // spansOf 按 y 降序（自底向上），阅读顺序需反转为自顶向下
            for (row in pageRows[page].orEmpty().asReversed()) {
                // 表头门只用于第 1 页：后续页顶部 y≤门限处是正常课程（页间不断行）
                if (page == firstPage && topGateY != null && row.first().spans.first().y <= topGateY) continue
                // 「其他课程：…」横跨整行（后续块的 x 可能落入其他列）：从该块起整行收走
                val otherIdx = row.indexOfFirst { it.text.contains("其他课程") }
                if (otherIdx >= 0) {
                    val start = row[otherIdx].text.indexOf("其他课程")
                    val sb = StringBuilder(row[otherIdx].text.substring(start))
                    for (k in (otherIdx + 1)..row.lastIndex) sb.append(row[k].text)
                    buckets[0].add(sb.toString())
                    continue
                }

                val byDay = LinkedHashMap<Int, StringBuilder>()
                for (run in row) {
                    // 边缘门控针对原始整块：图例/页眉等块虽起点在表外（如时间列 x=18），
                    // 文本却可能横跨多列，切分后尾部会漏进课程列
                    if (run.firstX < column.left || run.firstX >= column.right) continue
                    for (piece in splitByColumns(listOf(run), column)) {
                        val t = piece.text.trim()
                        if (t.isEmpty() || isPositionedLabel(t)) continue
                        val day = column.xToDay(piece.firstX) ?: continue
                        byDay.getOrPut(day) { StringBuilder() }.append(piece.text)
                    }
                }
                for ((day, sb) in byDay) {
                    if (sb.isNotBlank()) buckets[day - 1].add(sb.toString())
                }
            }
        }

        val lines = mutableListOf<String>()
        val dayByLine = mutableMapOf<Int, Int>()
        for (day in 1..7) {
            for (raw in buckets[day - 1]) {
                // 课名与字段被 PDF 并到同一视觉行时拆开；拆出的两段同属本列
                for (piece in splitInline(raw)) {
                    dayByLine[lines.size] = day
                    lines.add(piece)
                }
            }
        }
        return parseLines(lines, dayByLine)
    }

    /** 同一视觉行内连续 glyph 聚成的文本块；列判定只看 [firstX]，表头用 [centerX]。 */
    private class TextRun(val spans: List<PdfTextSpan>) {
        val firstX: Float get() = spans.first().x
        val centerX: Float get() = (spans.first().x + spans.last().x) / 2f
        val text: String by lazy { spans.joinToString("") { it.text } }
    }

    /** 把按 y 降序、x 升序排好的片段聚成视觉行（y 差 ≤1pt 视为同一行）。 */
    private fun groupVisualRows(sorted: List<PdfTextSpan>): List<List<PdfTextSpan>> {
        val rows = mutableListOf<MutableList<PdfTextSpan>>()
        var anchorY = Float.NaN
        for (span in sorted) {
            if (rows.isEmpty() || abs(span.y - anchorY) > 1f) {
                rows.add(mutableListOf(span))
                anchorY = span.y
            } else {
                rows.last().add(span)
            }
        }
        return rows
    }

    /**
     * 行内按 x 间距聚块：相邻片段起点 x 差 ≤ [RUN_GAP_PT] 视为同一块。
     * 10pt 中文字形步距约 8pt、数字约 3.7pt，而表格列间隙在数十 pt 以上，
     * 12pt 阈值能稳定切开不同单元格，又不拆散单元格内文本。
     */
    private fun clusterRuns(row: List<PdfTextSpan>): List<TextRun> {
        // 入参按 y 降序到达：同一视觉行里 y 略小（高 0.5pt）的节次号会排在行尾，
        // 必须先按 x 重排，否则它会被粘进行末单元格（如「武术*3」）
        val runs = mutableListOf<TextRun>()
        var current = mutableListOf<PdfTextSpan>()
        for (span in row.sortedBy { it.x }) {
            val last = current.lastOrNull()
            if (last != null && span.x - last.x > RUN_GAP_PT) {
                runs.add(TextRun(current))
                current = mutableListOf()
            }
            current.add(span)
        }
        if (current.isNotEmpty()) runs.add(TextRun(current))
        return runs
    }

    /** 坐标路径中应丢弃的标签：星期表头 / 时间段 / 上下午 / 页码标记 / 节次号（均为整段精确匹配）。 */
    private fun isPositionedLabel(t: String): Boolean {
        if (DAY_HEADER.matches(t) || COLUMN_LABEL.matches(t) || PAGE_MARK.containsMatchIn(t)) return true
        if (t.startsWith("打印时间")) return true
        if (SECTION_NUMBER.matches(t) && t.toIntOrNull() in 1..12) return true
        // 合成 / 挤压数据里一行可能由多个标签粘连而成（如「时间段节次星期一星期二…」）：
        // 剔除全部标签 token 后若没有任何实质内容，则整段视为标签
        return t.replace(LABEL_TOKEN, "").replace(Regex("\\s+"), "").isEmpty()
    }

    /** 行内可能出现的标签 token（用于粘连标签行的识别）。 */
    private val LABEL_TOKEN = Regex("(?:星期|周|礼拜)[一二三四五六日天]|时间段|节次|上午|下午|晚上")

    private data class ColumnMap(
        val xToDay: (Float) -> Int?,
        val left: Float,
        val right: Float,
        val bounds: List<Pair<Float, Float>>
    )

    /**
     * 相邻两列的字段行首尾相接时（左列文本接近列界、右列文本紧随其后，x 间隙 ≤ [RUN_GAP_PT]），
     * clusterRuns 会把它们误聚成一块。按学到的列分界线把这种跨界块切开，各归各列。
     */
    private fun splitByColumns(runs: List<TextRun>, column: ColumnMap): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (run in runs) {
            var current = mutableListOf<PdfTextSpan>()
            var zone = -1
            for (span in run.spans) {
                val z = column.bounds.indexOfFirst { span.x >= it.first && span.x < it.second }
                if (current.isNotEmpty() && z >= 0 && zone >= 0 && z != zone) {
                    out.add(TextRun(current))
                    current = mutableListOf()
                }
                if (z >= 0) zone = z
                current.add(span)
            }
            if (current.isNotEmpty()) out.add(TextRun(current))
        }
        return out
    }

    /**
     * 从第 1 页学习列边界：真实 PDF 的表头是逐字 glyph（靠 [TextRun] 聚块后匹配），
     * 合成数据的表头是单 span（直接匹配 span 文本），两种来源都收集。
     * 返回 null 表示无法识别表头（<2 列）。
     */
    private fun buildColumnMap(pdf: PdfText, firstPageRows: List<List<TextRun>>): ColumnMap? {
        val page = pdf.pages.first()
        val dayXs = mutableListOf<Pair<Int, Float>>()
        // 真实 PDF：聚块后的「星期一」等。必须带星期前缀——裸单字（如节次号「2」「4」）
        // 同样会被 parseDayOfWeek 识别，误收会污染列映射
        for (run in firstPageRows.flatten()) {
            val t = run.text.trim()
            if (DAY_HEADER.matches(t)) dayXs.add(CommonParser.parseDayOfWeek(t)!! to run.centerX)
        }
        // 合成数据：单个 span 即完整星期标签（其 x 间距很小，聚块会与相邻标签粘连）
        for (span in pdf.spans) {
            if (span.page != page) continue
            val t = span.text.trim()
            if (DAY_HEADER.matches(t)) dayXs.add(CommonParser.parseDayOfWeek(t)!! to span.x)
        }
        val sorted = dayXs.distinctBy { it.first }.sortedBy { it.second }
        if (sorted.size < 2) return null
        val dayList = sorted.map { it.first }
        val gap = (sorted.last().second - sorted.first().second) / (sorted.size - 1)
        val boundaries = sorted.indices.map { idx ->
            val left = if (idx == 0) -Float.MAX_VALUE else (sorted[idx - 1].second + sorted[idx].second) / 2f
            val right = if (idx == sorted.size - 1) Float.MAX_VALUE else (sorted[idx].second + sorted[idx + 1].second) / 2f
            left to right
        }
        val mapper: (Float) -> Int? = { x ->
            boundaries.indexOfFirst { x >= it.first && x < it.second }
                .takeIf { it >= 0 }?.let { dayList[it] }
        }
        // 表格水平边缘：用首/末表头 ± 半列宽，挡掉左侧时间列与右侧外的页眉碎片
        return ColumnMap(
            mapper,
            sorted.first().second - gap / 2f,
            sorted.last().second + gap / 2f,
            boundaries
        )
    }

    // ------------------------------------------------------------------
    // 行式解析（纯文本 dayByLine 为空 → 星期未知；带坐标时按列填星期）
    // ------------------------------------------------------------------

    private fun parseLines(lines: List<String>, dayByLine: Map<Int, Int>): ParseOutcome {
        val courses = mutableListOf<ParsedCourse>()
        val otherCourses = mutableListOf<ParsedCourse>()
        val warnings = mutableListOf<String>()
        val mapping = mutableListOf<MappingEntry>()
        var pendingName = ""
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }

            if (isStructural(line)) { pendingName = ""; i++; continue }
            if (SECTION_NUMBER.matches(line) && line.toIntOrNull() in 1..12) { pendingName = ""; i++; continue }

            if (line.startsWith("其他课程")) {
                val content = line.removePrefix("其他课程").trimStart(':').trim()
                val (ocs, ws) = OtherCourseParser.parse(content)
                otherCourses.addAll(ocs); warnings.addAll(ws)
                pendingName = ""
                i++; continue
            }

            if (line.endsWith("*")) {
                val name = (pendingName + line.removeSuffix("*")).trim()
                pendingName = ""
                val day = dayByLine[i]
                val fieldLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val nl = lines[j].trim()
                    if (nl.isEmpty()) { j++; continue }
                    if (PAGE_MARK.containsMatchIn(nl)) { j++; continue } // 跨页透明
                    if (nl.endsWith("*") || isStructural(nl) || (SECTION_NUMBER.matches(nl) && nl.toIntOrNull() in 1..12) ||
                        nl.startsWith("其他课程")
                    ) break
                    if (looksLikeWrappedName(lines, j)) break
                    fieldLines.add(nl)
                    j++
                }
                val block = fieldLines.joinToString("")
                val raw = line + block
                val (course, ws, fields) = buildCell(name, day, block, raw)
                courses.add(course); warnings.addAll(ws)
                mapping.add(MappingEntry(raw, fields, courses.lastIndex))
                i = j
                continue
            }

            // 非课程名行：可能是跨页字段碎片（以 : 或 / 开头）或换行的课程名前缀
            if (line.startsWith(":") || line.startsWith("/")) {
                warnings.add("跨页字段碎片已跳过：$line")
                i++; continue
            }
            // 纯文本（无冒号、无斜杠）→ 换行的课程名前缀
            pendingName += line
            i++
        }
        return ParseOutcome(courses, otherCourses, warnings, mapping)
    }

    /**
     * 把「课名* + 字段块」挤在同一行的情况拆成两行。
     *
     * 真实导出里单元格行距过近时，文本抽取会把课名和它的字段并成一行
     * （实测：`高等数学A1*(1-2节)6-11周/校区:…/学分:2.5`）。这种行既不以 `*` 结尾、
     * 也不以 `(N-M节)` 开头，会被整个丢掉 —— 拆开后主流程即可正常识别。
     */
    private fun splitInlineFieldLines(lines: List<String>): List<String> =
        lines.flatMap(::splitInline)

    /** 单行版本：命中粘连规则时拆成「课名*」「字段块」两段，否则原样返回（供坐标路径复用）。 */
    private fun splitInline(raw: String): List<String> {
        val t = raw.trim()
        val si = t.indexOf('*')
        return if (si in 1..40 && si < t.length - 1 && !t.endsWith("*")) {
            listOf(t.substring(0, si + 1), t.substring(si + 1))
        } else {
            listOf(raw)
        }
    }

    /**
     * lines[idx] 是否是一段「折行课名」的前半截。
     *
     * 真实导出按固定宽度硬折行，课名会被从中间劈成两行
     * （`计算机高级语言程序设` / `计*`）。若不识别，前半截会被当成上一门课的字段
     * 碎片吞掉，只剩尾巴（`计`）当课名 —— 实测就是这条导致格式B 少一门课、多一门「计」。
     *
     * 判定：本行无 `:` 无 `/`（不是字段碎片），且向下 3 行内出现以 `*` 结尾的行，
     * 途中不得碰到字段碎片（含 `:` 或以 `/` 开头）。
     * 反例（必须不判为课名）：`成`（"教学班组成"的折行碎片）后面紧跟 `/考核方式:…`。
     */
    private fun looksLikeWrappedName(lines: List<String>, idx: Int): Boolean {
        val cur = lines[idx].trim()
        if (cur.isEmpty() || cur.endsWith("*") || ':' in cur || '/' in cur) return false
        var k = idx + 1
        var hops = 0
        while (k < lines.size && hops < 3) {
            val nx = lines[k].trim()
            k++
            if (nx.isEmpty() || PAGE_MARK.containsMatchIn(nx)) continue
            if (nx.endsWith("*")) return true
            if (nx.startsWith("/") || ':' in nx) return false
            hops++
        }
        return false
    }

    private fun isStructural(line: String): Boolean =
        PAGE_MARK.containsMatchIn(line) ||
            line.startsWith("*:") ||
            line.startsWith("打印时间") ||
            line == "时间段" || line == "节次" ||
            DAY_HEADER.matches(line) ||
            line == "上午" || line == "下午" || line == "晚上" ||
            line.contains("学年第") || line.contains("学期") ||
            line.startsWith("学号") || line.endsWith("课表")

    /**
     * 解析一个单元格：`(N-M节)周次/字段kv…`。
     * @return (课程, warnings, 字段 map)
     */
    private fun buildCell(name: String, day: Int?, block: String, rawLine: String): Triple<ParsedCourse, List<String>, Map<String, String>> {
        val warnings = mutableListOf<String>()
        var course = ParsedCourse(
            name = name,
            dayOfWeek = day ?: 0,
            colorHex = CommonParser.defaultColor(name),
            rawLine = rawLine
        )

        val secM = SECTION_IN_CELL.find(block)
        val startSection: Int
        val endSection: Int
        var rest = block
        if (secM != null) {
            startSection = secM.groupValues[1].toInt()
            endSection = secM.groupValues[2].toInt()
            rest = block.substring(secM.range.last + 1)
        } else {
            startSection = 0
            endSection = 0
            warnings.add("单元格缺少 (N-M节) 节次信息：$name")
        }
        course = course.copy(startSection = startSection, endSection = endSection)

        // rest 形如 "6-11周/校区:.../..."；周次在第一个 / 之前
        val weeksRaw = rest.substringBefore('/').trim()
        val kvPart = rest.substringAfter('/', "")
        val wp = CommonParser.parseWeeks(weeksRaw)
        course = course.copy(weeks = wp.weeks, weeksRaw = weeksRaw)
        warnings.addAll(wp.warnings)

        val fields = CommonParser.extractFields(kvPart)
        course = CommonParser.applyFieldMap(course, fields)
        val (c2, trailing, ws2) = CommonParser.applyWeeksAndCredit(course, fields)
        course = c2
        warnings.addAll(ws2)
        if (trailing != null && trailing.isNotBlank()) {
            warnings.add("学分粘连剩余片段：$trailing")
        }
        if (course.teacher.isNotEmpty()) {
            course = course.copy(teacherSegments = listOf(TeacherSegment(course.teacher, course.weeks, course.weeksRaw)))
        }
        return Triple(course, warnings, fields)
    }
}
