package cn.sanxing.thrice.parser

import cn.sanxing.thrice.parser.format.FormatDetector
import cn.sanxing.thrice.parser.conflict.ConflictDetector
import cn.sanxing.thrice.parser.merge.CourseMerger
import cn.sanxing.thrice.parser.model.ParseResult
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.ScheduleFormat
import cn.sanxing.thrice.parser.model.CourseKind
import cn.sanxing.thrice.parser.parse.CommonParser
import cn.sanxing.thrice.parser.parse.CsvParser
import cn.sanxing.thrice.parser.parse.FormatAParser
import cn.sanxing.thrice.parser.parse.FormatBParser
import cn.sanxing.thrice.parser.parse.OtherCourseParser
import cn.sanxing.thrice.parser.parse.XlsxReader
import cn.sanxing.thrice.parser.text.PdfText
import cn.sanxing.thrice.parser.text.PdfTextExtractor
import cn.sanxing.thrice.parser.text.TextNormalizer

/**
 * 导入用例：接收原始文本/PDF/CSV，自动检测格式，调用对应解析器，
 * 合并同名课程，检测冲突，返回 [ParseResult]。
 */
class ImportScheduleUseCase(
    private val pdfExtractor: PdfTextExtractor? = null
) {

    /** 纯文本导入：自动检测格式 A/B；检测不到时回退到通用智能解析（CSV/TSV/自由文本）。 */
    fun importText(rawText: String): ParseResult {
        val normalized = TextNormalizer.normalize(rawText)
        val detection = FormatDetector.detect(normalized)
        return when (detection.format) {
            ScheduleFormat.FORMAT_A -> assemble(normalized, ScheduleFormat.FORMAT_A, FormatAParser.parse(normalized))
            ScheduleFormat.FORMAT_B -> assemble(normalized, ScheduleFormat.FORMAT_B, FormatBParser.parse(normalized))
            else -> {
                // 通用智能解析：表格 / 分隔文本 / 复合时间格
                val generic = CsvParser.parse(normalized)
                if (generic.courses.isNotEmpty()) {
                    assemble(normalized, ScheduleFormat.CSV, generic)
                } else {
                    ParseResult(
                        courses = emptyList(),
                        otherCourses = emptyList(),
                        conflicts = emptyList(),
                        warnings = listOf("无法识别格式：${detection.reason}"),
                        format = ScheduleFormat.UNKNOWN
                    )
                }
            }
        }
    }

    /** CSV 导入（按指定格式强制走 CSV 解析器）。 */
    fun importCsv(rawText: String): ParseResult {
        val normalized = TextNormalizer.normalize(rawText)
        val outcome = CsvParser.parse(normalized)
        return assemble(normalized, ScheduleFormat.CSV, outcome)
    }

    /**
     * Excel(.xlsx) 导入：零依赖解压第一张工作表为 TSV 后复用 CSV 智能解析
     * （表头别名 / 无表头智能归类 / 复合时间格同一套规则）。
     * 非 .xlsx 内容会抛 IllegalArgumentException，由 UI 提示另存 .xlsx。
     */
    fun importExcel(bytes: ByteArray): ParseResult {
        val tsv = XlsxReader.readTsv(bytes)
        val normalized = TextNormalizer.normalize(tsv)
        val outcome = CsvParser.parse(normalized)
        return assemble(normalized, ScheduleFormat.CSV, outcome)
    }

    /** PDF 字节流导入：先检测格式，再走对应解析路径。 */
    fun importPdf(bytes: ByteArray): ParseResult {
        val extractor = pdfExtractor
            ?: return ParseResult(
                courses = emptyList(),
                otherCourses = emptyList(),
                conflicts = emptyList(),
                warnings = listOf("未注入 PdfTextExtractor，无法解析 PDF"),
                format = ScheduleFormat.UNKNOWN
            )
        val pdfText = extractor.extract(bytes)
        val plainText = pdfText.toPlainText()
        val normalized = TextNormalizer.normalize(plainText)
        val detection = FormatDetector.detect(normalized)

        return when (detection.format) {
            ScheduleFormat.FORMAT_A -> {
                // 格式 A：按文本解析（星期由"星期X"标记决定）
                val outcome = FormatAParser.parse(normalized)
                assemble(normalized, ScheduleFormat.FORMAT_A, outcome)
            }
            ScheduleFormat.FORMAT_B -> {
                // 格式 B：优先走坐标解析（星期由列位置决定）
                val positionedOutcome = FormatBParser.parsePositioned(pdfText)
                val hasGridCourses = positionedOutcome.courses.any { it.dayOfWeek in 1..7 }
                if (hasGridCourses) {
                    // 坐标解析成功
                    val mergeResult = CourseMerger.merge(positionedOutcome.courses)
                    val conflicts = ConflictDetector.detect(mergeResult.courses)
                    ParseResult(
                        courses = mergeResult.courses.filter { it.kind == cn.sanxing.thrice.parser.model.CourseKind.GRID },
                        // rawCourses 必须与 mappingReport 的 courseIndex 同源（均为解析器合并前的产出），
                        // 否则「手动修正」会改到错误的课程。
                        rawCourses = positionedOutcome.courses.filter { it.kind == cn.sanxing.thrice.parser.model.CourseKind.GRID },
                        otherCourses = positionedOutcome.otherCourses,
                        conflicts = conflicts,
                        warnings = (positionedOutcome.warnings + mergeResult.warnings).toMutableList(),
                        format = ScheduleFormat.FORMAT_B,
                        rawText = plainText,
                        // 坐标解析器当前不产映射条目（默认空表）；保留传入口，
                        // 将来 FormatBParser 补 mapping 后此处自动生效。
                        mappingReport = positionedOutcome.mappingReport
                    )
                } else {
                    // 坐标解析失败，回退到文本解析
                    val outcome = FormatBParser.parse(normalized)
                    assemble(normalized, ScheduleFormat.FORMAT_B, outcome)
                }
            }
            else -> {
                // 未知格式：A/B 与通用 CSV 三种解析器都试，取识别出网格课程最多的一个；
                // 全军覆没时给出明确告警，不要让 UI 显示「0 门课、0 告警」这种无诊断信息的结果。
                val candidates = listOf(
                    ScheduleFormat.FORMAT_A to FormatAParser.parse(normalized),
                    ScheduleFormat.FORMAT_B to FormatBParser.parse(normalized),
                    ScheduleFormat.CSV to CsvParser.parse(normalized)
                )
                val best = candidates.maxByOrNull { (_, o) -> o.courses.count { it.dayOfWeek in 1..7 } }!!
                if (best.second.courses.none { it.dayOfWeek in 1..7 }) {
                    ParseResult(
                        courses = emptyList(),
                        otherCourses = emptyList(),
                        conflicts = emptyList(),
                        warnings = listOf("无法识别 PDF 课表格式：${detection.reason}"),
                        format = ScheduleFormat.UNKNOWN,
                        rawText = plainText
                    )
                } else {
                    assemble(normalized, best.first, best.second)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 手动修正 / 强制格式重解析（导入 Step2 的映射修正用）
    // ------------------------------------------------------------------

    /**
     * 手动修正单条原始条目：按 [patch] 覆盖字段后返回新条目。
     *
     * patch 支持的键：`name` / `teacher` / `location` / `dayOfWeek`(1-7) /
     * `sections`(如 `3-4`) / `weeks`(如 `7-12,5-7(单)`) / `credit`。
     * 周次与节次走解析器自身规则（[CommonParser.parseWeeks] / [CommonParser.parseSection]），
     * 与导入路径保持同一套语义，避免手改后与自动解析结果不一致。
     * 无法解析的取值一律忽略，保留原值。
     */
    fun applyManualFix(course: ParsedCourse, patch: Map<String, String>): ParsedCourse {
        var c = course
        // 手填内容同样要归一化：否则全角「７-１６周」「（单）」等会静默解析失败
        patch["name"]?.let { TextNormalizer.normalize(it).trim() }
            ?.takeIf { it.isNotEmpty() }?.let { c = c.copy(name = it) }
        patch["teacher"]?.let { TextNormalizer.normalize(it).trim() }
            ?.takeIf { it.isNotEmpty() }?.let { c = c.copy(teacher = it) }
        patch["location"]?.let { TextNormalizer.normalize(it).trim() }
            ?.takeIf { it.isNotEmpty() }?.let { c = c.copy(location = it) }
        patch["dayOfWeek"]?.trim()?.toIntOrNull()?.takeIf { it in 1..7 }?.let {
            c = c.copy(dayOfWeek = it, kind = CourseKind.GRID)
        }
        patch["sections"]?.let { raw ->
            CommonParser.parseSection(TextNormalizer.normalize(raw))
                ?.let { (s, e) -> c = c.copy(startSection = s, endSection = e) }
        }
        patch["weeks"]?.let { raw ->
            val text = TextNormalizer.normalize(raw).trim()
            val parsed = CommonParser.parseWeeks(text)
            // 解析不出任何周次时保留原值，避免把已有周次清空
            if (parsed.weeks.isNotEmpty()) c = c.copy(weeks = parsed.weeks, weeksRaw = text)
        }
        patch["credit"]?.trim()?.toDoubleOrNull()?.takeIf { it in 0.0..30.0 }
            ?.let { c = c.copy(credit = it) }
        return c
    }

    /**
     * 按用户指定的格式强制重解析（Step2 里格式判定错了时用）。
     * [ScheduleFormat.UNKNOWN] 时回退到 [importText] 的自动判定。
     */
    fun reparseWithOverride(rawText: String, format: ScheduleFormat): ParseResult {
        val normalized = TextNormalizer.normalize(rawText)
        return when (format) {
            ScheduleFormat.FORMAT_A -> assemble(normalized, ScheduleFormat.FORMAT_A, FormatAParser.parse(normalized))
            ScheduleFormat.FORMAT_B -> assemble(normalized, ScheduleFormat.FORMAT_B, FormatBParser.parse(normalized))
            ScheduleFormat.CSV -> assemble(normalized, ScheduleFormat.CSV, CsvParser.parse(normalized))
            ScheduleFormat.UNKNOWN -> importText(rawText)
        }
    }

    private fun assemble(
        normalized: String,
        format: ScheduleFormat,
        outcome: cn.sanxing.thrice.parser.parse.ParseOutcome
    ): ParseResult {
        // 「其他课程」：优先采用解析器在结构内识别出的结果（规则更贴合各自格式的排版）。
        // 解析器没有产出时才回退到全文「其他课程：」段扫描——该路径按 ';' 切分，
        // 整篇文本会切出几十条垃圾（如教学班组成里的 "1952"），故仅作兜底。
        val (otherCourses, otherWarnings) = outcome.otherCourses.takeIf { it.isNotEmpty() }
            ?.let { it to emptyList<String>() }
            ?: OtherCourseParser.parse(otherSectionOf(normalized))
        val allParsed = outcome.courses + otherCourses

        // 合并同名课程
        val mergeResult = CourseMerger.merge(allParsed)
        val conflicts = ConflictDetector.detect(mergeResult.courses)

        return ParseResult(
            courses = mergeResult.courses.filter { it.kind == cn.sanxing.thrice.parser.model.CourseKind.GRID },
            // 原始条目 = 解析器直接产出、未合并的列表（导入 Step2「手动修正」用它逐条改）
            rawCourses = outcome.courses,
            otherCourses = otherCourses,
            conflicts = conflicts,
            warnings = (outcome.warnings + mergeResult.warnings + otherWarnings).toMutableList(),
            format = format,
            rawText = normalized,
            // R17：此前漏传，格式 A（含 PDF 判为格式 A）的映射报告永远为空，
            // Step2 点开「映射报告」一条原始条目都看不到。
            // mapping 条目 courseIndex 与 rawCourses 同源（均为 outcome.courses）。
            mappingReport = outcome.mappingReport
        )
    }

    /**
     * 取「其他课程：」段的正文（含末尾 `;`）。
     *
     * 取**最后**一个标记，避免课程名里出现同词；取到全文**最后**一个 `;` 为止，
     * 以免把表格图例（`*: 讲课 : 实验 : ...`）和「打印时间」也当成条目。
     * 没有该段时返回空串。
     */
    private fun otherSectionOf(text: String): String {
        val m = Regex("其他课程\\s*[:：]").findAll(text).lastOrNull() ?: return ""
        val start = m.range.last + 1
        val lastSemi = text.lastIndexOf(';')
        return if (lastSemi >= start) text.substring(start, lastSemi + 1) else text.substring(start)
    }
}
