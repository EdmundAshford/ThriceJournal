package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.MappingEntry
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.TeacherSegment

/**
 * 各格式解析器的统一产出。
 */
data class ParseOutcome(
    val courses: List<ParsedCourse>,
    val otherCourses: List<ParsedCourse> = emptyList(),
    val warnings: List<String> = emptyList(),
    val mappingReport: List<MappingEntry> = emptyList()
)

/**
 * 格式 A：表格型 / 挤在一行型。
 *
 * 结构（表格型，行式）：
 * ```
 * 星期一
 * 3-4
 * 计算机高级语言程序设计*
 * 周数: 7-16周/校区: 青竹山/地点: 东区教学楼B0413/.../学分:2.5
 * 5-6
 * 高等数学A1*
 * 周数: 5-7周(单),8-12周/...
 * ```
 * 结构（挤一行型）：`星期一3-4计算机高级语言程序设计*周数:.../学分:2.55-6高等数学A1*...`
 */
object FormatAParser {

    private val PAGE_MARK = Regex("={3,}\\s*\\[第\\s*\\d+\\s*页\\]")
    private val DAY_HEADER = Regex("^(星期|周|礼拜)[一二三四五六日天]$")
    private val SECTION_LINE = Regex("^(第)?\\d{1,2}-\\d{1,2}(节)?$")
    private val DAY_TOKEN = Regex("(?:星期|周|礼拜)[一二三四五六日天]")
    private val SECTION_TOKEN = Regex("\\d{1,2}-\\d{1,2}")

    /**
     * 解析格式 A 文本。
     *
     * @param normalized 已过 [cn.sanxing.thrice.parser.text.TextNormalizer] 的全文
     */
    fun parse(normalized: String): ParseOutcome {
        val nonBlank = normalized.lines().count { it.isNotBlank() }
        // 挤一行型：几乎没有换行且含课程标记
        return if (nonBlank <= 3 && normalized.contains('*')) {
            parseInline(normalized)
        } else {
            parseLineMode(normalized)
        }
    }

    // ------------------------------------------------------------------
    // 表格型（行式）
    // ------------------------------------------------------------------

    private fun parseLineMode(normalized: String): ParseOutcome {
        val lines = normalized.split('\n')
        val courses = mutableListOf<ParsedCourse>()
        val otherCourses = mutableListOf<ParsedCourse>()
        val warnings = mutableListOf<String>()
        val mapping = mutableListOf<MappingEntry>()
        var day = 0
        var section: Pair<Int, Int>? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }
            if (isSkipLine(line)) { i++; continue }

            val d = dayHeader(line)
            // 换日必须同时清空节次：新一天的首门课若缺节次行，不应继承上一天的节次
            if (d != null) { day = d; section = null; i++; continue }

            if (isSectionLine(line)) {
                CommonParser.parseSection(line)?.let { section = it }
                i++; continue
            }

            if (line.startsWith("其他课程")) {
                val content = line.removePrefix("其他课程").trimStart(':').trim()
                val (ocs, ws) = OtherCourseParser.parse(content)
                otherCourses.addAll(ocs); warnings.addAll(ws); i++; continue
            }

            if (line.endsWith("*")) {
                val name = line.removeSuffix("*").trim()
                val fieldLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val nl = lines[j].trim()
                    if (nl.isEmpty()) { j++; continue }
                    if (nl.endsWith("*") || dayHeader(nl) != null || isSectionLine(nl) ||
                        nl.startsWith("其他课程") || isSkipLine(nl)
                    ) break
                    fieldLines.add(nl)
                    j++
                }
                val block = fieldLines.joinToString("")
                val raw = line + block
                val (course, trailing, ws, fields) = buildCourse(name, day, section, block, raw)
                courses.add(course); warnings.addAll(ws)
                mapping.add(MappingEntry(raw, fields, courses.lastIndex))
                if (trailing != null) {
                    CommonParser.parseSection(trailing)?.let {
                        section = it
                        warnings.add("粘连修复：学分后紧跟节次 '${trailing}'，已作为下一门课节次")
                    }
                }
                i = j
                continue
            }
            i++
        }
        return ParseOutcome(courses, otherCourses, warnings, mapping)
    }

    // ------------------------------------------------------------------
    // 挤一行型
    // ------------------------------------------------------------------

    private fun parseInline(text: String): ParseOutcome {
        val courses = mutableListOf<ParsedCourse>()
        val otherCourses = mutableListOf<ParsedCourse>()
        val warnings = mutableListOf<String>()
        val mapping = mutableListOf<MappingEntry>()
        var i = 0
        var day = 0
        var section: Pair<Int, Int>? = null
        val n = text.length

        while (i < n) {
            val star = text.indexOf('*', i)
            if (star < 0) break
            val segment = text.substring(i, star)
            val (name, segDay, segSection) = extractNameAndContext(segment)
            if (segDay != null) day = segDay
            if (segSection != null) section = segSection
            if (name.isEmpty()) { i = star + 1; continue }

            // 字段块：向前扫描，直到遇到 星期 token 或 节次 token（非周次）
            var j = star + 1
            val fields = StringBuilder()
            while (j < n) {
                val dm = DAY_TOKEN.find(text, j)
                if (dm != null && dm.range.first == j) break
                val sm = SECTION_TOKEN.find(text, j)
                if (sm != null && sm.range.first == j && CommonParser.parseSection(sm.value) != null) {
                    val after = text.substring(sm.range.last + 1)
                    if (!after.startsWith("周")) break
                }
                fields.append(text[j]); j++
            }
            val block = fields.toString()
            val raw = segment + "*" + block
            val (course, trailing, ws, fmap) = buildCourse(name, day, section, block, raw)
            courses.add(course); warnings.addAll(ws)
            mapping.add(MappingEntry(raw, fmap, courses.lastIndex))
            if (trailing != null) {
                CommonParser.parseSection(trailing)?.let { section = it }
            }
            i = j
        }

        val otherIdx = text.indexOf("其他课程")
        if (otherIdx >= 0) {
            val content = text.substring(otherIdx).removePrefix("其他课程").trimStart(':').trim()
            val (ocs, ws) = OtherCourseParser.parse(content)
            otherCourses.addAll(ocs); warnings.addAll(ws)
        }
        return ParseOutcome(courses, otherCourses, warnings, mapping)
    }

    /** 从挤一行片段的前端剥离 星期 / 节次 上下文，返回 (课程名, 星期, 节次)。 */
    private fun extractNameAndContext(segment: String): Triple<String, Int?, Pair<Int, Int>?> {
        var s = segment.trim()
        var day: Int? = null
        var section: Pair<Int, Int>? = null
        var changed = true
        while (changed) {
            changed = false
            val dm = DAY_TOKEN.find(s)
            if (dm != null && dm.range.first == 0) {
                day = CommonParser.parseDayOfWeek(dm.value)
                s = s.substring(dm.range.last + 1)
                changed = true
            }
            val sm = SECTION_TOKEN.find(s)
            if (sm != null && sm.range.first == 0 && CommonParser.parseSection(sm.value) != null) {
                section = CommonParser.parseSection(sm.value)
                s = s.substring(sm.range.last + 1)
                changed = true
            }
        }
        return Triple(s.trim(), day, section)
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    private fun isSkipLine(line: String): Boolean =
        PAGE_MARK.containsMatchIn(line) || line.startsWith("*:") || line.startsWith("打印时间")

    private fun dayHeader(line: String): Int? =
        if (DAY_HEADER.matches(line)) CommonParser.parseDayOfWeek(line) else null

    private fun isSectionLine(line: String): Boolean =
        SECTION_LINE.matches(line) && CommonParser.parseSection(line) != null

    /**
     * 根据上下文（星期 / 节次）与字段块构造课程。
     * @return (课程, 学分粘连剩余串, warnings, 字段 map)
     */
    private fun buildCourse(
        name: String,
        day: Int,
        section: Pair<Int, Int>?,
        block: String,
        rawLine: String
    ): FourTuple {
        val warnings = mutableListOf<String>()
        val fields = CommonParser.extractFields(block)
        var course = ParsedCourse(
            name = name,
            dayOfWeek = day,
            startSection = section?.first ?: 0,
            endSection = section?.second ?: 0,
            colorHex = CommonParser.defaultColor(name),
            rawLine = rawLine
        )
        fields["sections"]?.let { CommonParser.parseSection(it)?.let { s -> course = course.copy(startSection = s.first, endSection = s.second) } }
        fields["dayOfWeek"]?.let { CommonParser.parseDayOfWeek(it)?.let { d -> course = course.copy(dayOfWeek = d) } }

        course = CommonParser.applyFieldMap(course, fields)
        val (c2, trailing, ws2) = CommonParser.applyWeeksAndCredit(course, fields)
        course = c2
        warnings.addAll(ws2)
        if (course.teacher.isNotEmpty()) {
            course = course.copy(teacherSegments = listOf(TeacherSegment(course.teacher, course.weeks, course.weeksRaw)))
        }
        return FourTuple(course, trailing, warnings, fields)
    }

    private data class FourTuple(
        val course: ParsedCourse,
        val trailing: String?,
        val warnings: List<String>,
        val fields: Map<String, String>
    )
}
