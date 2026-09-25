package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.TeacherSegment

/**
 * 解析共有逻辑：字段别名表、字段 kv 抽取、星期 / 节次 / 周次解析、学分粘连修复。
 *
 * 所有别名匹配大小写 / 中英 / 空白不敏感（文本已归一化为半角、去零宽）。
 */
object CommonParser {

    /** 一学年最多按 52 周计：周次解析的硬上界，防止异常输入把区间展开成死循环。 */
    const val MAX_WEEK = 52

    /** 节次合法上界（覆盖 11-12 等晚课；超过此值的号段视为噪声）。 */
    const val MAX_SECTION = 20

    /**
     * 字段别名表：canonical 字段名 → 别名列表。
     * 注意：`周数` 是真实导出里的写法（提示词别名表只列了 `周次`），此处补充，
     * 否则格式 A 的 `周数: 7-16周` 无法被识别。
     */
    val FIELD_ALIASES: Map<String, List<String>> = mapOf(
        "name" to listOf("课程名", "课程名称", "名称"),
        "teacher" to listOf("教师", "老师", "任课教师"),
        "location" to listOf("地点", "场地", "教室", "上课地点"),
        "dayOfWeek" to listOf("星期", "周几", "上课星期"),
        "sections" to listOf("节次", "上课节次", "时间"),
        "weeks" to listOf("周次", "上课周次", "周数"),
        "credit" to listOf("学分"),
        "category" to listOf("课程性质"),
        "assessment" to listOf("考核方式"),
        "campus" to listOf("校区"),
        "classGroup" to listOf("教学班"),
        "classMembers" to listOf("教学班组成"),
        "remark" to listOf("选课备注", "备注"),
        "hoursBreakdown" to listOf("课程学时组成"),
        "weeklyHours" to listOf("周学时"),
        "totalHours" to listOf("总学时")
    )

    /** 别名按长度降序，避免短别名在长别名前被误配。 */
    private val ALIAS_ENTRIES: List<Pair<String, String>> =
        FIELD_ALIASES.entries
            .flatMap { (canon, aliases) -> aliases.map { it to canon } }
            .sortedByDescending { it.first.length }

    /** 12 色预设色板（按课程名哈希取色）。 */
    private val COLOR_PALETTE = listOf(
        "#1E88E5", "#E53935", "#8E24AA", "#43A047", "#FB8C00",
        "#00ACC1", "#F4511E", "#3949AB", "#6D4C41", "#7CB342",
        "#5E35B1", "#039BE5"
    )

    /** 按课程名哈希到 12 色色板。 */
    fun defaultColor(name: String): String {
        if (name.isEmpty()) return COLOR_PALETTE[0]
        var h = 0
        for (c in name) h = (h * 31 + c.code) and Int.MAX_VALUE
        return COLOR_PALETTE[h % COLOR_PALETTE.size]
    }

    // ------------------------------------------------------------------
    // 星期
    // ------------------------------------------------------------------

    private val DAY_MAP = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7
    )

    /** 解析星期：支持 `周一`/`星期一`/`礼拜一`/`1`/`一`。返回 1..7，无法识别返回 null。 */
    fun parseDayOfWeek(s: String): Int? {
        val t = s.trim()
        val core = t.replace(Regex("^(星期|礼拜|周)"), "")
        return if (core.length == 1) DAY_MAP[core] else null
    }

    // ------------------------------------------------------------------
    // 节次
    // ------------------------------------------------------------------

    /** 节次合法区间（节次号 1..[MAX_SECTION]，覆盖 11-12 等晚课）。 */
    private fun validSection(a: Int, b: Int): Boolean = a in 1..MAX_SECTION && b in 1..MAX_SECTION && b >= a

    /**
     * 解析节次：支持 `第1-2节`/`1-2节`/`1-2`/`0102`/`(1-2节)`/`(第1-2节)`/`3-4`/`第9-10节`。
     * 返回 (startSection, endSection)，无法识别返回 null。
     */
    fun parseSection(s: String): Pair<Int, Int>? {
        var t = s.trim()
        // 「第」「(」可叠加出现（如 `(第1-2节)`），循环剥离直到不再变化
        do {
            val before = t
            t = t
                .removePrefix("第").removePrefix("(").removePrefix("（")
                .removeSuffix(")").removeSuffix("）").removeSuffix("节").trim()
        } while (t != before)
        // 四位数字（无分隔符）：0102 → 01-02
        if (t.length == 4 && t.all { it.isDigit() }) {
            val a = t.substring(0, 2).toInt()
            val b = t.substring(2, 4).toInt()
            return if (validSection(a, b)) a to b else null
        }
        val m = Regex("^(\\d{1,2})\\s*-\\s*(\\d{1,2})$").find(t) ?: return null
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return if (validSection(a, b)) a to b else null
    }

    /** 判断字符串是否整体形如节次模式（用于粘连修复时识别截断点）。 */
    fun isSectionPattern(s: String): Boolean = parseSection(s) != null

    // ------------------------------------------------------------------
    // 周次
    // ------------------------------------------------------------------

    /**
     * 周次解析结果。
     *
     * @param weeks    展开后的周次集合（已夹到 1..[MAX_WEEK] 并按升序去重）
     * @param warnings 解析过程中产生的非致命提示（非法片段、区间倒置、越界等），供导入预览页展示
     */
    data class WeeksParse(val weeks: Set<Int>, val warnings: List<String> = emptyList())

    /**
     * 解析周次并展开成整数集合。
     * 支持：`1-16周`、`1-8,10-16周`、`5-7周(单)`、`8-12周`、`单周`、`双周`、
     * `1,3,5,7`、`6-8周,10-17周,19周`、`7-16周`、`8-14周(双)`。
     *
     * 关键规则：后缀 `(单)`/`(双)`（全角 `（单）`/`（双）` 亦可）只作用于**紧邻的那个区间**，
     * 即 `5-7周(单),8-12周` = {5,7} ∪ {8..12}。
     *
     * 安全性：所有区间端点都会被 `toIntOrNull` 校验并夹到 [1, MAX_WEEK]，
     * 避免 `1-99999999周` 这类输入造成近乎死循环的展开（曾导致 ANR/OOM）。
     */
    fun parseWeeks(raw: String): WeeksParse {
        val warnings = mutableListOf<String>()
        val weeks = sortedSetOf<Int>()
        var s = raw.trim()
        // 去掉"周"字（`7-16周` → `7-16`）
        s = s.replace("周", "")
        // 全角逗号、顿号、~、— 统一吃掉
        s = s.replace("～", ",").replace("~", ",").replace("—", ",").replace("–", ",").replace("，", ",").replace("、", ",")
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            val oddEven: Int? = when {
                part.contains("(单)") || part.contains("（单）") -> 1
                part.contains("(双)") || part.contains("（双）") -> 2
                else -> null
            }
            var core = part
                .replace("(单)", "").replace("(双)", "")
                .replace("（单）", "").replace("（双）", "")
                .trim()
            core = core.replace("~", "-").replace("—", "-").replace("～", "-")
            when {
                core == "单" || core == "双" ->
                    warnings.add("缺少周次区间的单/双周标记：$part")
                core.matches(Regex("\\d+-\\d+")) -> {
                    val nums = core.split('-').map { it.toIntOrNull() }
                    val a = nums[0]
                    val b = nums[1]
                    if (a == null || b == null) {
                        warnings.add("周次数值超出可解析范围：$part")
                        continue
                    }
                    if (a > b) {
                        warnings.add("周次区间起止倒置（已忽略）：$part")
                        continue
                    }
                    if (b > MAX_WEEK || a < 1) {
                        warnings.add("周次超出 1..$MAX_WEEK（已夹取）：$part")
                    }
                    val lo = a.coerceAtLeast(1)
                    val hi = b.coerceAtMost(MAX_WEEK)
                    for (w in lo..hi) if (matchesOddEven(w, oddEven)) weeks.add(w)
                }
                core.matches(Regex("\\d+")) -> {
                    val w = core.toIntOrNull()
                    if (w == null) {
                        warnings.add("周次数值超出可解析范围：$part")
                        continue
                    }
                    if (w !in 1..MAX_WEEK) {
                        warnings.add("周次超出 1..$MAX_WEEK（已忽略）：$part")
                        continue
                    }
                    if (matchesOddEven(w, oddEven)) weeks.add(w)
                }
                else -> warnings.add("无法识别的周次片段：$part")
            }
        }
        return WeeksParse(weeks.toSet(), warnings)
    }

    private fun matchesOddEven(w: Int, oddEven: Int?): Boolean = when (oddEven) {
        1 -> w % 2 == 1
        2 -> w % 2 == 0
        else -> true
    }

    // ------------------------------------------------------------------
    // 学分粘连修复
    // ------------------------------------------------------------------

    /**
     * 提取学分数值（粘连修复）。
     * 例如 `学分:2.55-6高等数学A1*`：`2.5` 后紧跟下一门的节次 `5-6`。
     * 规则：`\d+(\.\d{1,2})?` 贪婪匹配，但小数最多两位；
     * 若小数末位数字与后续字符串构成**合法节次模式**（如 `5-6`），则截断，把末位数字归还给后续。
     *
     * @return (学分数值, 剩余字符串)。无法解析时 (null, 原串)。
     */
    fun parseGluedNumber(raw: String): Pair<Double?, String> {
        val m = Regex("^(\\d+)(\\.\\d{0,2})?").find(raw) ?: return null to raw
        val intPart = m.groupValues[1]
        var dec = m.groupValues[2].removePrefix(".")
        var rest = raw.substring(m.value.length)
        // 截断：若 dec 末位数字 + rest 组成合法节次模式，则把末位归还给 rest
        while (dec.isNotEmpty()) {
            val candidate = dec.takeLast(1) + rest
            if (Regex("^\\d{1,2}-\\d{1,2}").containsMatchIn(candidate)) {
                val head = Regex("^\\d{1,2}-\\d{1,2}").find(candidate)!!.value
                if (isSectionPattern(head)) {
                    rest = dec.takeLast(1) + rest
                    dec = dec.dropLast(1)
                    continue
                }
            }
            break
        }
        val value = if (dec.isEmpty()) intPart.toDouble() else (intPart + "." + dec).toDouble()
        return value to rest
    }

    /**
     * 解析学分字段，粘连时返回剩余串（作为下一门课的节次上下文）。
     * @return Triple(学分数值, 剩余串, warning)。无学分时 (null, null, null)。
     */
    fun parseCreditField(rawValue: String): Triple<Double?, String?, String?> {
        val v = rawValue.trim()
        if (v.isEmpty()) return Triple(null, null, null)
        val (num, rest) = parseGluedNumber(v)
        if (num == null) return Triple(null, null, "学分字段无法识别：$v")
        val warning = if (rest.isNotEmpty() && rest != v) "学分粘连修复：'$v' → 学分 $num，剩余 '$rest'" else null
        return Triple(num, rest, warning)
    }

    // ------------------------------------------------------------------
    // 字段 kv 抽取
    // ------------------------------------------------------------------

    /**
     * 从 `字段:值/字段:值/…` 形式的字段块中抽取 kv（canonical 字段名 → 原始值）。
     *
     * 分隔符只在 `/别名:` 处切分，因此值内部的 `/`（如 `讲课:40,实验/科研实践:16`）
     * 或 `QQ群：100000001` 的内层冒号不会被误切。
     */
    fun extractFields(block: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        val aliasAlt = ALIAS_ENTRIES.joinToString("|") { Regex.escape(it.first) }
        if (aliasAlt.isEmpty()) return result
        val sepRegex = Regex("/(?=(?:$aliasAlt)\\s*:)")
        val segments = block.split(sepRegex)
        for (seg in segments) {
            val segTrim = seg.trim().removePrefix("/").trim()
            if (segTrim.isEmpty()) continue
            // 正则以 ^ 锚定，命中即位于下标 0；别名已按长度降序，首个命中即为最长匹配
            var matched: Pair<String, String>? = null
            for ((alias, canon) in ALIAS_ENTRIES) {
                if (Regex("^${Regex.escape(alias)}\\s*:").containsMatchIn(segTrim)) {
                    matched = canon to alias
                    break
                }
            }
            if (matched != null) {
                val colonIdx = segTrim.indexOf(':')
                val value = if (colonIdx >= 0) segTrim.substring(colonIdx + 1).trim() else ""
                result[matched.first] = value
            }
        }
        return result
    }

    /**
     * 把字段 map 写入一个课程对象（字段的公共映射）。星期 / 节次 / 周次由调用方
     * 根据格式上下文先填好，此处只处理从 map 能直接拿到的字段。
     */
    fun applyFieldMap(course: ParsedCourse, fields: Map<String, String>): ParsedCourse {
        var c = course
        fields["name"]?.takeIf { it.isNotBlank() }?.let { c = c.copy(name = it) }
        fields["teacher"]?.let { c = c.copy(teacher = it) }
        fields["location"]?.let { c = c.copy(location = it) }
        fields["campus"]?.let { c = c.copy(campus = it) }
        fields["category"]?.let { c = c.copy(category = it) }
        fields["assessment"]?.let { c = c.copy(assessment = it) }
        fields["classGroup"]?.let { c = c.copy(classGroup = it) }
        fields["classMembers"]?.let { c = c.copy(classMembers = it) }
        fields["remark"]?.let { c = c.copy(remark = it) }
        fields["hoursBreakdown"]?.let { c = c.copy(hoursBreakdown = it) }
        fields["weeklyHours"]?.let { c = c.copy(weeklyHours = it.toIntOrNull()) }
        fields["totalHours"]?.let { c = c.copy(totalHours = it.toIntOrNull()) }
        return c
    }

    /**
     * 用字段 map 里的 `weeks`/`credit` 解析并写回（含周次展开与学分粘连处理）。
     * @return Triple(课程, 粘连剩余串（若学分被截断）, warnings)
     */
    fun applyWeeksAndCredit(
        course: ParsedCourse,
        fields: Map<String, String>
    ): Triple<ParsedCourse, String?, List<String>> {
        val warnings = mutableListOf<String>()
        var c = course
        var trailing: String? = null
        fields["weeks"]?.let {
            val wp = parseWeeks(it)
            c = c.copy(weeks = wp.weeks, weeksRaw = it)
            warnings.addAll(wp.warnings)
        }
        fields["credit"]?.let {
            val (num, rest, w) = parseCreditField(it)
            if (num != null) c = c.copy(credit = num)
            if (w != null) warnings.add(w)
            trailing = rest
        }
        return Triple(c, trailing, warnings)
    }

}
