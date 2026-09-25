package cn.sanxing.thrice.parser.format

import cn.sanxing.thrice.parser.model.FormatDetection
import cn.sanxing.thrice.parser.model.ScheduleFormat

/**
 * 格式自动判定：A（表格型 / 挤一行型）、B（分页型 / 结构化型）、CSV、未知。
 * 输入应为 TextNormalizer 归一化后的文本。
 */
object FormatDetector {

    private val PAGE_MARK = Regex("={3,}\\s*\\[第\\s*\\d+\\s*页\\]")
    private val PAREN_SECTION = Regex("\\(\\s*\\d{1,2}\\s*-\\s*\\d{1,2}\\s*节\\s*\\)")
    private val DAY_TOKEN = Regex("星期[一二三四五六日天]")

    fun detect(normalized: String): FormatDetection {
        if (isCsv(normalized)) {
            return FormatDetection(ScheduleFormat.CSV, 0.95, "检测到逗号分隔的表格式表头（含课程名字段），判定为 CSV")
        }

        var aScore = 0
        var bScore = 0
        val reasons = mutableListOf<String>()

        // 格式 A 特征
        if (normalized.contains("周数")) { aScore += 3; reasons.add("出现「周数」字段（格式A特征）") }
        if (normalized.contains("地点")) { aScore += 2; reasons.add("出现「地点」字段") }
        if (DAY_TOKEN.containsMatchIn(normalized) && !normalized.contains("时间段")) {
            aScore += 1; reasons.add("出现「星期X」且无「时间段」表头")
        }

        // 格式 B 特征
        if (normalized.contains("时间段")) { bScore += 4; reasons.add("出现「时间段」表头（格式B特征）") }
        if (PAREN_SECTION.containsMatchIn(normalized)) { bScore += 4; reasons.add("出现 (N-M节) 节次写法") }
        if (normalized.contains("场地")) { bScore += 3; reasons.add("出现「场地」字段（格式B用词）") }
        if (PAGE_MARK.containsMatchIn(normalized)) { bScore += 2; reasons.add("出现分页标记") }
        if (normalized.contains("打印时间")) { bScore += 1; reasons.add("出现「打印时间」尾注") }

        return when {
            aScore > bScore -> FormatDetection(
                ScheduleFormat.FORMAT_A,
                confidence(aScore, bScore),
                reasons.joinToString("；")
            )
            bScore > aScore -> FormatDetection(
                ScheduleFormat.FORMAT_B,
                confidence(bScore, aScore),
                reasons.joinToString("；")
            )
            else -> FormatDetection(ScheduleFormat.UNKNOWN, 0.0, "A/B 特征均不足，无法判定（a=$aScore, b=$bScore）")
        }
    }

    private fun confidence(win: Int, lose: Int): Double {
        val total = win + lose
        if (total == 0) return 0.0
        return (0.5 + 0.5 * win.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun isCsv(text: String): Boolean {
        val first = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        if (!first.contains(',')) return false
        val headers = first.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (headers.size < 3) return false
        val known = listOf("课程名", "课程名称", "名称", "教师", "老师", "任课教师", "地点", "场地", "教室", "星期", "周几", "节次", "周次", "周数", "学分")
        val hit = headers.count { h -> known.any { k -> h.contains(k) } }
        return hit >= 2
    }
}
