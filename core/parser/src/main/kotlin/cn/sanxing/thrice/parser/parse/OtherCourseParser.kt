package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.CourseKind
import cn.sanxing.thrice.parser.model.ParsedCourse

/**
 * 「其他课程」段解析。
 *
 * 原文（示例数据均为虚构）：
 * ```
 * 其他课程：计算机高级语言程序设计陈安(共4周)/8-14周(双)/无;
 *           计算机高级语言课程设计陈安(共1周)/14周/QQ群：100000001;
 * ```
 * 结构：`课程名 + 教师 + (共N周)/周次/备注`，`;` 分隔多条。
 *
 * 说明：课程名与教师之间无分隔符，教师按 `(共N周)` 前的 2 个字符切分
 * （两位姓名如"陈安"）；三位及以上姓名需在"手动修正"阶段处理。
 */
object OtherCourseParser {

    /** 从"其他课程"段内容（已去掉 `其他课程:` 前缀）解析出若干条 OTHER 课程。 */
    fun parse(content: String): Pair<List<ParsedCourse>, List<String>> {
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<ParsedCourse>()
        val entries = content.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        for (entry in entries) {
            val parts = entry.split('/')
            if (parts.size < 2) {
                warnings.add("其他课程条目格式异常（缺少周次）：$entry")
                continue
            }
            val head = parts[0].trim()                 // 课程名 + 教师 + (共N周)
            val weeksRaw = parts[1].trim()             // 周次
            // 备注里可能自带 '/'(如 `QQ群:1/微信:2`)，只取第 3 段会静默丢内容，全部接回
            val remark = parts.drop(2).joinToString("/").trim()

            val nMatch = Regex("\\(共(\\d+)周\\)").find(head)
            val n = nMatch?.groupValues?.get(1)?.toIntOrNull()
            val nameTeacher = head.substring(0, nMatch?.range?.first ?: head.length).trim()

            // 教师 = 末尾 2 个字符；课程名 = 其余。
            // 这是启发式规则：复姓、三字姓名与英文名必然切错，此处给出提示交给「手动修正」处理。
            val teacher = if (nameTeacher.length > 2) nameTeacher.takeLast(2) else ""
            val name = if (nameTeacher.length > 2) nameTeacher.dropLast(2) else nameTeacher
            if (nameTeacher.length in 1..2) {
                warnings.add("其他课程[$nameTeacher] 未识别出教师（姓名长度不足），可在手动修正中补充")
            }

            val wp = CommonParser.parseWeeks(weeksRaw)
            wp.warnings.forEach { warnings.add("其他课程[$name] $it") }

            // 周数校验：展开后周次个数应等于 (共N周)
            if (n != null && wp.weeks.size != n) {
                warnings.add(
                    "其他课程[$name] 周数校验：标注共${n}周，实际展开${wp.weeks.size}周（${wp.weeks.sorted()}）"
                )
            }

            val note = if (remark == "无" || remark.isEmpty()) "" else remark.replace('：', ':')
            courses.add(
                ParsedCourse(
                    name = name,
                    teacher = teacher,
                    weeks = wp.weeks,
                    dayOfWeek = 0,
                    startSection = 0,
                    endSection = 0,
                    kind = CourseKind.OTHER,
                    weeksRaw = weeksRaw,
                    remark = note,
                    colorHex = CommonParser.defaultColor(name),
                    rawLine = entry
                )
            )
        }
        return courses to warnings
    }
}
