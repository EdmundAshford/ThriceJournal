package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.MappingEntry
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.TeacherSegment

/**
 * 通用表格 / 文本解析器（智能导入）。
 *
 * 支持的输入：
 *  - 分隔符自动识别：英文逗号、中文逗号、分号、制表符、竖线、连续空格（从各类系统软件/Excel/笔记粘贴均可）；
 *  - 有表头：表头中英文别名模糊匹配（"课程名字段"、"上课时间(周几)" 等也能认）；
 *  - 无表头：逐格按内容特征归类（星期/节次/周次/地点/学分/教师/课程名）；
 *  - 复合时间格：一格内含 "星期一 第1-2节 1-16周(单)" 也能拆出星期、节次、周次；
 *  - 整行空格分隔："高等数学 周一 1-2节 1-16周 张三 教二楼301"。
 */
object CsvParser {

    /** 表头别名（在 CommonParser 基础上补充常见中英文写法）。 */
    private val EXTRA_ALIASES: Map<String, List<String>> = mapOf(
        "name" to listOf("课程", "课名", "科目", "课程名字段", "course", "coursename", "name", "title", "subject"),
        "teacher" to listOf("授课教师", "主讲教师", "任课老师", "教师姓名", "teacher", "instructor", "professor"),
        "location" to listOf("上课教室", "上课地点", "教室名称", "room", "classroom", "location", "place", "venue"),
        "dayOfWeek" to listOf("星期几", "工作日", "weekday", "day", "dayofweek"),
        "sections" to listOf("上课节次", "节次时间", "时间段", "上课时间", "时间", "section", "sections", "period", "time"),
        "weeks" to listOf("上课周", "周次范围", "week", "weeks", "weekrange"),
        "credit" to listOf("学分值", "credit", "credits"),
        "category" to listOf("课程类型", "性质", "type", "category"),
        "classGroup" to listOf("班级", "行政班", "class", "classgroup"),
        "campus" to listOf("上课校区", "campus"),
        "remark" to listOf("说明", "note", "remark", "remarks")
    )

    /**
     * 解析分隔符文本 / 表格文本。
     *
     * @param normalized 已过 [cn.sanxing.thrice.parser.text.TextNormalizer] 的全文
     */
    fun parse(normalized: String): ParseOutcome {
        val courses = mutableListOf<ParsedCourse>()
        val warnings = mutableListOf<String>()
        val mapping = mutableListOf<MappingEntry>()

        val rawLines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return ParseOutcome(emptyList(), emptyList(), listOf("内容为空"), emptyList())

        val delimiter = detectDelimiter(rawLines)
        val grid = rawLines.map { splitLine(it, delimiter) }

        // ---- 判定首行是不是表头：至少映射到 2 个不同 canonical 字段，且包含 name 或时间类字段 ----
        val headerMapping = grid.first().map { canonicalOf(it) }
        val mappedDistinct = headerMapping.filterNotNull().toSet()
        val looksLikeHeader = mappedDistinct.size >= 2 &&
            (mappedDistinct.contains("name") ||
                mappedDistinct.contains("dayOfWeek") ||
                mappedDistinct.contains("sections") ||
                mappedDistinct.contains("weeks"))

        val dataRows: List<List<String>> = if (looksLikeHeader) grid.drop(1) else grid
        if (looksLikeHeader) {
            warnings.add("识别到表头，列映射：" + grid.first().mapIndexedNotNull { i, h ->
                headerMapping[i]?.let { "$h→$it" }
            }.joinToString("，"))
        } else {
            warnings.add("未检测到明确表头，已按每格内容智能识别（可在下一步逐条修正）")
        }

        for (cells in dataRows) {
            val fields = LinkedHashMap<String, String>()
            val composite = SlotPiece()

            if (looksLikeHeader) {
                cells.forEachIndexed { c, cell ->
                    val canon = headerMapping.getOrNull(c)
                    if (canon != null) {
                        absorbCell(canon, cell, fields, composite)
                    } else {
                        // 未映射列：尝试从中抢救时间三件套
                        extractSlot(cell, composite)
                    }
                }
            } else {
                // 无表头：逐格按内容分类
                val leftovers = mutableListOf<String>()
                for (cell in cells) {
                    val classified = classifyBareCell(cell, composite)
                    if (classified != null) fields[classified.first] = classified.second
                    else leftovers.add(cell)
                }
                if (fields["name"] == null && leftovers.isNotEmpty()) {
                    if (leftovers.size == 1 && (composite.day != null || composite.section != null)) {
                        // 整行挤在一格里（如"高等数学 周一 1-2节 1-16周 教二楼301"）：
                        // 删掉已识别的时间片段，剩余内容第一截作为课程名
                        var rest = leftovers.first()
                        rest = DAY_REGEX.replace(rest, " ")
                        rest = SECTION_REGEX.replace(rest, " ")
                        rest = WEEKS_REGEX.replace(rest, " ")
                        rest = rest.replace(Regex("[,，;；|]"), " ")
                        val tokens = rest.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
                        val nameTok = tokens.firstOrNull { it.length >= 2 }
                        if (nameTok != null) {
                            fields["name"] = nameTok
                            val more = tokens.dropWhile { it != nameTok }.drop(1)
                            more.firstOrNull { looksLikeLocation(it) }?.let { fields["location"] = it }
                            more.firstOrNull { looksLikeTeacher(it) }?.let { fields["teacher"] = it }
                            if (more.isNotEmpty()) fields["remark"] = more.joinToString(" ")
                        } else {
                            fields["name"] = leftovers.first()
                        }
                    } else {
                        fields["name"] = leftovers.first()
                        if (leftovers.size > 1) fields["remark"] = leftovers.drop(1).joinToString(" ")
                    }
                }
            }

            val name = fields["name"]?.takeIf { it.isNotBlank() }
            if (name == null) {
                warnings.add("跳过未能识别课程名的一行：${cells.joinToString(delimiter.display).take(60)}")
                mapping.add(MappingEntry(cells.joinToString(delimiter.display), fields))
                continue
            }

            var course = ParsedCourse(name = name, colorHex = CommonParser.defaultColor(name),
                rawLine = cells.joinToString(delimiter.display))
            course = CommonParser.applyFieldMap(course, fields)

            composite.day?.let { course = course.copy(dayOfWeek = it) }
            composite.section?.let { course = course.copy(startSection = it.first, endSection = it.second) }
            fields["dayOfWeek"]?.let { CommonParser.parseDayOfWeek(it)?.let { d -> course = course.copy(dayOfWeek = d) } }
            fields["sections"]?.let { CommonParser.parseSection(it)?.let { s -> course = course.copy(startSection = s.first, endSection = s.second) } }

            val (c2, _, ws) = CommonParser.applyWeeksAndCredit(course, fields)
            course = c2
            warnings.addAll(ws)

            if (composite.day == null && course.dayOfWeek !in 1..7) {
                warnings.add("「$name」未识别到星期几，请在下一步手动修正")
            }
            if (composite.section == null && course.startSection <= 0) {
                warnings.add("「$name」未识别到节次，请在下一步手动修正")
            }

            if (course.teacher.isNotEmpty()) {
                course = course.copy(
                    teacherSegments = listOf(TeacherSegment(course.teacher, course.weeks, course.weeksRaw))
                )
            }
            courses.add(course)
            mapping.add(MappingEntry(course.rawLine, fields, courses.lastIndex))
        }

        if (courses.isEmpty()) warnings.add("没有解析出任何课程，可换用 PDF 导入或在粘贴文本后选择强制格式")
        return ParseOutcome(courses, emptyList(), warnings, mapping)
    }

    // ------------------------------------------------------------------
    // 分隔符
    // ------------------------------------------------------------------

    private enum class Delim(val display: String) {
        TAB("\t"), SEMI(";"), BAR("|"), COMMA(","), CN_COMMA("，"), WHITESPACE(" ")
    }

    private fun detectDelimiter(lines: List<String>): Delim {
        val candidates = listOf(Delim.TAB, Delim.SEMI, Delim.BAR, Delim.COMMA, Delim.CN_COMMA)
        val sample = lines.take(20)
        var best = Delim.COMMA
        var bestScore = -1
        for (d in candidates) {
            val counts = sample.map { splitCharCount(it, d.display) }.filter { it > 0 }
            if (counts.isEmpty()) continue
            // 多数行都能切开 + 列数多才是好分隔符
            val coverage = counts.size * 10
            val avgCols = counts.average()
            val score = (coverage + avgCols * 3).toInt()
            if (score > bestScore) { bestScore = score; best = d }
        }
        if (bestScore <= 0) return Delim.WHITESPACE
        // 逗号只有 1 列、但空格能切多列时退回空格
        if (splitLine(sample.first(), best).size < 2) {
            val wsCols = sample.first().split(Regex("\\s{2,}|\\t+")).filter { it.isNotBlank() }.size
            if (wsCols >= 2) return Delim.WHITESPACE
        }
        return best
    }

    private fun splitCharCount(line: String, ch: String): Int = line.count { it.toString() == ch }

    private fun splitLine(line: String, d: Delim): List<String> = when (d) {
        Delim.WHITESPACE -> line.split(Regex("\\s{2,}|\\t+")).map { it.trim() }.filter { it.isNotEmpty() }
        Delim.COMMA -> splitQuoted(line, ',')
        else -> line.split(d.display).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 最小 CSV 切分：支持双引号包裹与转义双引号。 */
    private fun splitQuoted(line: String, sep: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else inQuotes = !inQuotes
                }
                ch == sep && !inQuotes -> { result.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString().trim())
        // 保留**中间**的空单元格：丢弃它们会让表头列序与数据列序错位
        //（表头 `课程名,教师,地点` + 数据 `高数,,教三` → 教三会被当成教师）。
        // 仅去掉末尾因行尾分隔符产生的空串。
        val trimmed = result.dropLastWhile { it.isEmpty() }
        return if (trimmed.isEmpty()) result else trimmed
    }

    // ------------------------------------------------------------------
    // 表头映射
    // ------------------------------------------------------------------

    private fun canonicalOf(header: String): String? {
        val h = header.trim()
        // 精确
        for ((canon, aliases) in CommonParser.FIELD_ALIASES) {
            if (aliases.any { it.equals(h, ignoreCase = true) }) return canon
        }
        for ((canon, aliases) in EXTRA_ALIASES) {
            if (aliases.any { it.equals(h, ignoreCase = true) }) return canon
        }
        // 去空白/标点后包含
        val hf = fuzzy(h)
        val allAliases: List<Pair<String, String>> =
            (CommonParser.FIELD_ALIASES.toList() + EXTRA_ALIASES.toList())
                .flatMap { (canon, list) -> list.map { it to canon } }
                .sortedByDescending { it.first.length }
        for ((alias, canon) in allAliases) {
            if (hf.contains(fuzzy(alias))) return canon
        }
        return null
    }

    private fun fuzzy(s: String): String =
        s.lowercase().replace(Regex("[\\s\\-_/\\(\\)（）\\[\\]【】:：*，,。.、]"), "")

    // ------------------------------------------------------------------
    // 单元格内容归类
    // ------------------------------------------------------------------

    /** 时间三件套临时收纳（星期 / 节次；周次直接写回 fields）。 */
    private class SlotPiece {
        var day: Int? = null
        var section: Pair<Int, Int>? = null
    }

    /** 有表头时：把一格按列 canonical 吸收；时间类列同时做复合拆解。 */
    private fun absorbCell(canon: String, cell: String, fields: HashMap<String, String>, slot: SlotPiece) {
        when (canon) {
            "dayOfWeek" -> {
                fields["dayOfWeek"] = cell
                extractDay(cell)?.let { slot.day = it }
            }
            "sections" -> {
                fields["sections"] = cell
                extractDay(cell)?.let { slot.day = it }
                extractSection(cell)?.let { slot.section = it }
                extractWeeks(cell)?.let { fields["weeks"] = it }
            }
            "weeks" -> {
                extractWeeks(cell)?.let { fields["weeks"] = it }
                extractSection(cell)?.let { slot.section = it }
                extractDay(cell)?.let { slot.day = it }
            }
            else -> fields[canon] = cell
        }
    }

    /** 从未知格中抢救星期/节次/周次。 */
    private fun extractSlot(cell: String, slot: SlotPiece) {
        extractDay(cell)?.let { slot.day = it }
        extractSection(cell)?.let { slot.section = it }
    }

    /**
     * 无表头时逐格分类。返回 (canonical, value)；无法归类返回 null（作为课程名候选）。
     */
    private fun classifyBareCell(cell: String, slot: SlotPiece): Pair<String, String>? {
        extractDay(cell)?.let { if (slot.day == null) slot.day = it }
        extractSection(cell)?.let { if (slot.section == null) slot.section = it }
        extractWeeks(cell)?.let { return "weeks" to it }
        if (looksLikeDay(cell)) return "dayOfWeek" to cell
        if (looksLikeSection(cell)) return "sections" to cell
        if (looksLikeLocation(cell)) return "location" to cell
        if (looksLikeTeacher(cell)) return "teacher" to cell
        creditOf(cell)?.let { return "credit" to it }
        return null
    }

    private fun looksLikeDay(cell: String): Boolean =
        Regex("^(星期|礼拜|周)\\s*[一二三四五六日天1-7]$").containsMatchIn(cell) ||
            cell.trim().let { it.length == 1 && CommonParser.parseDayOfWeek(it) != null }

    private fun looksLikeSection(cell: String): Boolean =
        CommonParser.parseSection(cleanSection(cell)) != null

    private fun looksLikeLocation(cell: String): Boolean =
        Regex("(楼|室|馆|栋|区|教[室一二三四五六]|room|lab)", RegexOption.IGNORE_CASE).containsMatchIn(cell)

    private fun looksLikeTeacher(cell: String): Boolean =
        (cell.endsWith("老师") || cell.endsWith("教师")) && cell.length in 2..6

    private fun creditOf(cell: String): String? {
        val t = cell.trim()
        val m = Regex("^(\\d{1,2}(?:\\.\\d{1,2})?)$").find(t) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return if (v in 0.0..15.0) m.groupValues[1] else null
    }

    private fun cleanSection(s: String): String =
        s.replace(Regex("[（(][单双][)）]"), "").trim()

    // ------------------------------------------------------------------
    // 复合时间格正则
    // ------------------------------------------------------------------

    private val DAY_REGEX = Regex("(?:星期|礼拜|周)\\s*([一二三四五六日天1-7])")

    private fun extractDay(cell: String): Int? {
        val m = DAY_REGEX.find(cell) ?: return null
        return CommonParser.parseDayOfWeek("周" + m.groupValues[1])
    }

    private val SECTION_REGEX = Regex("第?\\s*(\\d{1,2})\\s*[-－—~～]\\s*(\\d{1,2})\\s*节?")

    private fun extractSection(cell: String): Pair<Int, Int>? {
        SECTION_REGEX.findAll(cell).forEach { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@forEach
            val b = m.groupValues[2].toIntOrNull() ?: return@forEach
            // 节次号一般 ≤ 20；周次区间（如 1-16）不应当成节次
            if (a in 1..20 && b in 1..20 && b - a in 0..11 && (b <= 12 || m.value.contains("节") || m.value.contains("第"))) {
                return a to b
            }
        }
        return null
    }

    private val WEEKS_REGEX =
        Regex("[0-9０-９]+(?:\\s*[-－—~～]\\s*[0-9０-９]+)?(?:\\s*[,，、]\\s*[0-9０-９]+(?:\\s*[-－—~～]\\s*[0-9０-９]+)?)*\\s*周(?:\\s*[（(]\\s*[单双]\\s*[)）])?")

    /** 抽取周次串并交给 CommonParser 验证；无效返回 null。 */
    private fun extractWeeks(cell: String): String? {
        val m = WEEKS_REGEX.find(cell) ?: return null
        val raw = m.value.replace("０", "0").replace("１", "1").replace("２", "2")
            .replace("３", "3").replace("４", "4").replace("５", "5")
            .replace("６", "6").replace("７", "7").replace("８", "8").replace("９", "9")
        val parsed = CommonParser.parseWeeks(raw)
        return raw.takeIf { parsed.weeks.isNotEmpty() && parsed.warnings.none { it.contains("无法识别") } }
    }
}
