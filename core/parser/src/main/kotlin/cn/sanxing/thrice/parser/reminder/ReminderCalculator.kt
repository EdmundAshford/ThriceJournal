package cn.sanxing.thrice.parser.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 上课提醒时刻的纯函数计算（给定学期起始日、课程、提前量、now → 下次提醒时刻）。
 *
 * 与 Android 完全解耦，放纯 JVM 模块便于单元测试；app 模块的
 * ReminderScheduler 只做「把这些时刻排进系统」。
 */
object ReminderCalculator {

    /**
     * 由学期起始日（第 1 周周一）计算 date 位于第几周。
     *
     * 使用向下取整除法，因此开学前的日期返回 <=0（如开学前 1~6 天恒为 0），
     * 与 `week in 1..totalWeeks` 的判断语义一致；截断除法会把开学前日期误算成第 1 周。
     */
    fun weekOf(startDate: LocalDate, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(startDate, date), 7L).toInt() + 1

    /**
     * 下一次提醒触发时刻。
     *
     * @param termStart   学期起始日（第 1 周周一）
     * @param totalWeeks  学期总周数
     * @param weeks       课程有课的周次集合（只在含该周时才排提醒）
     * @param dayOfWeek   星期（1..7，周一=1）
     * @param courseStartTime 课程开始时刻（节次表 startTime）
     * @param minutesBefore   提前量（分钟）
     * @param now         当前时刻
     * @param overrideDate 调课/补课钉到的具体日期（null = 按周次规律）
     * @return 下次触发时刻；学期内不再有课返回 null
     */
    fun nextReminder(
        termStart: LocalDate,
        totalWeeks: Int,
        weeks: Set<Int>,
        dayOfWeek: Int,
        courseStartTime: LocalTime,
        minutesBefore: Int,
        now: LocalDateTime,
        overrideDate: LocalDate? = null
    ): LocalDateTime? {
        if (dayOfWeek !in 1..7) return null
        val today = now.toLocalDate()

        // 调课/补课：只认被钉住的那一天
        if (overrideDate != null) {
            val trigger = overrideDate.atTime(courseStartTime).minusMinutes(minutesBefore.toLong())
            return if (trigger.isAfter(now)) trigger else null
        }

        // 从「今天」与「开学日」中较晚者起逐日向后找，最多扫过整学期 + 7 天缓冲。
        // 起点取 max 而非直接用 today：若用户在开学前很久就导入课表，
        // 从 today 起算会扫不到学期末，导致漏排提醒。
        val maxDays = totalWeeks * 7 + 7L
        var date = if (termStart.isAfter(today)) termStart else today
        var scanned = 0L
        while (scanned <= maxDays) {
            val week = weekOf(termStart, date)
            if (week in 1..totalWeeks && week in weeks && date.dayOfWeek.value == dayOfWeek) {
                val trigger = date.atTime(courseStartTime).minusMinutes(minutesBefore.toLong())
                if (trigger.isAfter(now)) return trigger
                // 今天这节已过 → 同一门课可能下周还有，继续向后找
            }
            date = date.plusDays(1)
            scanned++
        }
        return null
    }

    /** 未来 [count] 次提醒时刻（用于一次多排或调试展示）。 */
    fun upcomingReminders(
        termStart: LocalDate,
        totalWeeks: Int,
        weeks: Set<Int>,
        dayOfWeek: Int,
        courseStartTime: LocalTime,
        minutesBefore: Int,
        now: LocalDateTime,
        count: Int
    ): List<LocalDateTime> {
        val result = mutableListOf<LocalDateTime>()
        var cursor = now
        repeat(count) {
            val next = nextReminder(
                termStart, totalWeeks, weeks, dayOfWeek, courseStartTime, minutesBefore, cursor
            ) ?: return result
            result.add(next)
            cursor = next.plusMinutes(1)
        }
        return result
    }

    /** 解析 "HH:mm" → LocalTime；非法返回 null。 */
    fun parseTime(hhmm: String?): LocalTime? =
        hhmm?.trim()?.takeIf { it.length >= 5 }?.let {
            runCatching { LocalTime.of(it.substring(0, 2).toInt(), it.substring(3, 5).toInt()) }.getOrNull()
        }
}
