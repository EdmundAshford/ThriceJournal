package cn.sanxing.thrice.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.TeacherSegment
import cn.sanxing.thrice.ui.theme.paletteColorFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** UI 层共用小工具：周次计算、周次串化、课程取色、时间格式化。 */
object UiUtils {

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 由学期起始日（第 1 周周一）计算某天的周次。
     * 返回值可能 <=0（未开学）或 > totalWeeks（学期已结束），由调用方降级处理。
     *
     * 用向下取整除法：截断除法会把开学前 1~6 天误算成第 1 周。
     */
    fun weekOf(startDate: LocalDate, today: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(startDate, today)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /**
     * 解析 "yyyy-MM-dd"；非法返回 null。
     *
     * 账单日期等字段可能来自**用户导入的备份 JSON**，外部构造 / 损坏的文件会写入
     * 非法值；裸调 `LocalDate.parse` 会让打开统计页就直接崩溃。
     */
    fun parseDateOrNull(raw: String?): LocalDate? =
        raw?.takeIf { it.length >= 8 }?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    /** 压缩周次集合为区间串：{5,7,8,9,10,11,12} → "5,7-12"；空集 → ""。 */
    fun formatWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val sb = StringBuilder()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) {
                prev = v
            } else {
                appendRange(sb, start, prev)
                start = v
                prev = v
            }
        }
        appendRange(sb, start, prev)
        return sb.toString()
    }

    private fun appendRange(sb: StringBuilder, start: Int, end: Int) {
        if (sb.isNotEmpty()) sb.append(',')
        if (start == end) sb.append(start) else sb.append(start).append('-').append(end)
    }

    /** 教师分段串：陈安(7-10)/周文(11)/孙芳(12)。 */
    fun formatSegments(segments: List<TeacherSegment>): String =
        segments.joinToString("/") { s ->
            val w = s.weeksRaw.ifBlank { formatWeeks(s.weeks) }
            if (w.isBlank()) s.teacher else "${s.teacher}($w)"
        }

    /** 课程显示色：colorHex 优先，否则按课名哈希到 12 色色板。 */
    fun courseColor(course: Course): Color =
        parseHex(course.colorHex) ?: paletteColorFor(course.name)

    /** 解析 "#RRGGBB" / "#AARRGGBB"；非法返回 null。 */
    fun parseHex(hex: String): Color? {
        val s = hex.trim()
        if (!s.startsWith('#')) return null
        return try {
            when (s.length) {
                7 -> Color(android.graphics.Color.parseColor(s))
                9 -> Color(android.graphics.Color.parseColor(s))
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * colorHex → "#RRGGBB" 文本（用于改色回显）。
     *
     * 必须显式 [Locale.US]：不指定 locale 时 `%X` 在部分语言下会输出非 ASCII 数字，
     * 生成的字符串再交给 `Color.parseColor` 解析会失败，自定义主题色直接失效。
     */
    fun colorToHex(color: Color): String =
        "#%06X".format(Locale.US, 0xFFFFFF and (color.toArgbCompat()))

    private fun Color.toArgbCompat(): Int =
        (this.alpha * 255).toInt().shl(24) or
            (this.red * 255).toInt().shl(16) or
            (this.green * 255).toInt().shl(8) or
            (this.blue * 255).toInt()

    /**
     * 课程覆盖的小节序号列表（如第 1..2 节返回 [1, 2]）。
     * 课表网格按小节行绘制，跨节课程高度按行数累加。
     */
    fun coveredSections(startSection: Int, endSection: Int): List<Int> {
        if (startSection <= 0 || endSection < startSection) return emptyList()
        return (startSection..endSection).toList()
    }

    /** 时间列上的小节序号展示串（如 1 → "1"）。 */
    fun sectionNumberLabel(section: Int): String = section.toString()

    /** "第1-2节 08:00-09:40"；节次时间为空时退化为"第1-2节"。 */
    fun sectionTimeText(context: Context, sectionTimes: List<SectionTime>, startSection: Int, endSection: Int): String {
        if (startSection <= 0 || endSection <= 0) {
            return context.getString(R.string.sections_range_format, startSection, endSection)
        }
        val start = sectionTimes.firstOrNull { it.sectionIndex == startSection }?.startTime
        val end = sectionTimes.firstOrNull { it.sectionIndex == endSection }?.endTime
        return if (start.isNullOrBlank() || end.isNullOrBlank()) {
            context.getString(R.string.sections_range_format, startSection, endSection)
        } else {
            context.getString(R.string.section_time_format, startSection, endSection, start, end)
        }
    }

    /** 星期（1..7，0=无）→ 字符串资源。 */
    fun dayLabelRes(day: Int): Int = when (day) {
        1 -> R.string.day_mon
        2 -> R.string.day_tue
        3 -> R.string.day_wed
        4 -> R.string.day_thu
        5 -> R.string.day_fri
        6 -> R.string.day_sat
        7 -> R.string.day_sun
        else -> R.string.day_none
    }

    fun formatEpochMillis(millis: Long): String {
        if (millis <= 0) return ""
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(dateTimeFormat)
    }

}

/** 本地化时长文案：分钟数 →「N 分钟」/「N 小时」/「N 小时 M 分钟」。 */
@Composable
fun formatDurationMinutes(totalMinutes: Long): String {
    val m = abs(totalMinutes)
    val hours = m / 60
    val mins = (m % 60).toInt()
    return when {
        hours <= 0L -> stringResource(R.string.duration_minutes, m.toInt())
        mins == 0 -> stringResource(R.string.duration_hours, hours.toInt())
        else -> stringResource(R.string.duration_hours_minutes, hours.toInt(), mins)
    }
}
