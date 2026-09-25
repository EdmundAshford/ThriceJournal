package cn.sanxing.thrice.parser.ics

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 导出 ICS 所需的最小学期信息。 */
data class IcsTerm(
    val name: String,
    val startDate: LocalDate,
    val totalWeeks: Int,
    /** 学期 id，用于 UID 消歧（多学期分别导出到同一日历时避免互相覆盖）。 */
    val id: Long = 0
)

/** 导出 ICS 所需的最小课程信息（网格课程；其他课程 dayOfWeek=0 不导出）。 */
data class IcsCourse(
    val id: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val weeks: Set<Int>,
    val dayOfWeek: Int,          // 1..7（周一=1）；0 表示无固定星期
    val startSection: Int,       // 小节序号，与 IcsSectionTime.sectionIndex 同一域
    val endSection: Int,
    val note: String = ""
)

/** 小节时间（按小节序号索引，"HH:mm"）。 */
data class IcsSectionTime(
    val sectionIndex: Int,
    val startTime: String,       // "HH:mm"
    val endTime: String          // "HH:mm"
)

/**
 * 纯手写 iCalendar 导出（RFC 5545），不依赖任何第三方库。
 *
 * 要点：
 * - 每门课按 [IcsCourse.weeks] 展开，每周一个 VEVENT；
 * - 日期 = 学期起始日（第 1 周周一）+ (week-1)*7 + (dayOfWeek-1)；
 * - 事件时间取 [IcsSectionTime]，时区统一 `Asia/Shanghai`，用 `DTSTART;TZID=...` 形式；
 *   DTSTAMP 按 RFC 5545 使用 UTC（末尾 Z）；
 * - 开始节次缺时间配置的事件直接跳过（不静默伪造 08:00）；
 * - 行长度按 UTF-8 字节折行（≤75 字节，折行以 CRLF + 空格续行，不拆多字节字符）；
 * - SUMMARY / DESCRIPTION / LOCATION 的逗号、分号、反斜杠、换行做 TEXT 转义；
 * - UID 稳定唯一（学期 id + 课 id + 周 + 星期），重复导出不会重复事件；
 * - 全文行尾统一 CRLF（RFC 5545 要求）。不用 `StringBuilder.appendLine`，
 *   因为它写的是 `System.lineSeparator()`，在 Linux/Android/CI 上是 LF，会与折行的 CRLF 混用。
 */
object IcsExporter {

    const val TZID = "Asia/Shanghai"

    /** RFC 5545 规定内容行以 CRLF 结束。 */
    private const val CRLF = "\r\n"

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFmt = DateTimeFormatter.ofPattern("HHmmss")
    private val utcStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** 全量导出整个学期；[onlyWeek] 非空时只导出该周。 */
    fun export(
        term: IcsTerm,
        courses: List<IcsCourse>,
        sectionTimes: List<IcsSectionTime>,
        onlyWeek: Int? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        val sb = StringBuilder()
        // 开始节次没有配置时间的事件会在 appendVEvent 中被跳过（不静默伪造 08:00）
        sb.crlf("BEGIN:VCALENDAR")
        sb.crlf("VERSION:2.0")
        sb.crlf("PRODID:-//Thrice//EN")
        sb.crlf("CALSCALE:GREGORIAN")
        sb.crlf("X-WR-CALNAME:${escapeText(term.name)}")
        appendVTimezone(sb)

        val valid = courses.filter { it.dayOfWeek in 1..7 && it.weeks.isNotEmpty() }
        for (course in valid) {
            val weeks = course.weeks.sorted().filter { it in 1..term.totalWeeks }
            for (week in weeks) {
                if (onlyWeek != null && week != onlyWeek) continue
                appendVEvent(sb, term, course, sectionTimes, week, now)
            }
        }

        sb.crlf("END:VCALENDAR")
        return sb.toString()
    }

    /** 追加一行内容行（统一 CRLF 行尾）。 */
    private fun StringBuilder.crlf(line: String) {
        append(line).append(CRLF)
    }

    /** 展开某周某星期对应的日期。 */
    fun dateOfWeek(termStart: LocalDate, week: Int, dayOfWeek: Int): LocalDate =
        termStart.plusDays(((week - 1) * 7 + (dayOfWeek - 1)).toLong())

    private fun appendVTimezone(sb: StringBuilder) {
        // Asia/Shanghai 自 1991 年起无夏令时，固定 +08:00
        sb.crlf("BEGIN:VTIMEZONE")
        sb.crlf("TZID:$TZID")
        sb.crlf("BEGIN:STANDARD")
        sb.crlf("DTSTART:19700101T000000")
        sb.crlf("TZOFFSETFROM:+0800")
        sb.crlf("TZOFFSETTO:+0800")
        sb.crlf("END:STANDARD")
        sb.crlf("END:VTIMEZONE")
    }

    private fun appendVEvent(
        sb: StringBuilder,
        term: IcsTerm,
        course: IcsCourse,
        sectionTimes: List<IcsSectionTime>,
        week: Int,
        now: LocalDateTime
    ) {
        val date = dateOfWeek(term.startDate, week, course.dayOfWeek)
        val startSt = sectionTimes.firstOrNull { it.sectionIndex == course.startSection }
        val endSt = sectionTimes.firstOrNull { it.sectionIndex == course.endSection }
        // 开始时间未配置：无法生成可信事件，跳过而不是伪造 08:00
        val startTime = parseHm(startSt?.startTime) ?: return
        // 结束时间缺失时按 45 分钟单小节兜底（开始时间真实，误差有限）。
        // LocalTime.plusMinutes 会在跨日时回绕（23:30 + 45min = 00:15），
        // 那样的 DTEND 早于 DTSTART 是非法事件，故回绕时夹到当天 23:59。
        val fallback = startTime.plusMinutes(45)
        val endTime = parseHm(endSt?.endTime)?.takeIf { it.isAfter(startTime) }
            ?: fallback.takeIf { it.isAfter(startTime) }
            ?: LocalTime.of(23, 59)

        // RFC 5545：DTSTAMP 为 UTC 时刻（now 是系统本地时间，先转换再格式化）
        val dtStamp = now.atZone(ZoneId.systemDefault())
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(utcStampFmt)

        sb.crlf("BEGIN:VEVENT")
        appendFolded(sb, "UID", uidOf(course.id, week, course.dayOfWeek, term.id))
        appendFolded(sb, "DTSTAMP", dtStamp)
        appendFolded(sb, "DTSTART;TZID=$TZID", date.format(dateFmt) + "T" + startTime.format(timeFmt))
        appendFolded(sb, "DTEND;TZID=$TZID", date.format(dateFmt) + "T" + endTime.format(timeFmt))
        appendFolded(sb, "SUMMARY", escapeText(course.name))
        if (course.location.isNotBlank()) {
            appendFolded(sb, "LOCATION", escapeText(course.location))
        }
        val desc = buildString {
            if (course.teacher.isNotBlank()) append("教师：").append(course.teacher)
            append(";第 ").append(week).append(" 周/共 ").append(term.totalWeeks).append(" 周")
            if (course.note.isNotBlank()) append(";").append(course.note)
        }
        appendFolded(sb, "DESCRIPTION", escapeText(desc))
        sb.crlf("END:VEVENT")
    }

    /**
     * 稳定 UID：学期 id + 课 id + 周 + 星期（同一课同一周同一星期永远相同）。
     *
     * 带学期 id 是为了让「多套课表方案分别导出、导入同一日历」时不会互相覆盖。
     */
    fun uidOf(courseId: Long, week: Int, dayOfWeek: Int, termId: Long = 0): String =
        "thrice-course-$courseId-t$termId-w$week-d$dayOfWeek@thrice-app"

    private fun parseHm(s: String?): LocalTime? =
        s?.trim()?.takeIf { it.length >= 5 }?.let {
            runCatching { LocalTime.of(it.substring(0, 2).toInt(), it.substring(3, 5).toInt()) }.getOrNull()
        }

    /** RFC 5545 TEXT 转义：反斜杠、分号、逗号、换行。 */
    fun escapeText(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    /**
     * 折行：按 UTF-8 字节数折到 ≤75 字节/行（首行 ≤75，续行 ≤74 + 前导空格）。
     * 不在多字节字符中间断开。
     */
    fun foldLine(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var i = 0
        var limit = 75 // 首行上限
        while (i < bytes.size) {
            var end = minOf(i + limit, bytes.size)
            // 回退到字符边界（UTF-8 续字节 0x80..0xBF）
            while (end > i && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
            out.append(String(bytes, i, end - i, Charsets.UTF_8))
            i = end
            if (i < bytes.size) {
                out.append("\r\n ")
                limit = 74 // 续行含前导空格共 ≤75
            }
        }
        return out.toString()
    }

    private fun appendFolded(sb: StringBuilder, name: String, value: String) {
        sb.append(foldLine("$name:$value")).append("\r\n")
    }
}
