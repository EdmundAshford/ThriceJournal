package cn.sanxing.thrice.parser.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒时刻计算测试：单周/双周/有交集的周次，边界（今天已过 → 下周）。
 */
class ReminderCalculatorTest {

    private val termStart = LocalDate.of(2026, 8, 31) // 第 1 周周一（周一）
    private val totalWeeks = 20

    /** 真实基准：线性代数A 周五 1-2 节（08:00），周次 {6,7,8,10..17,19}。 */
    private val weeks = setOf(6, 7, 8) + (10..17).toSet() + setOf(19)

    private fun at(date: LocalDate, h: Int, m: Int) = LocalDateTime.of(date, LocalTime.of(h, m))

    @Test
    fun `今天有课且未到点 返回今天提前量时刻`() {
        // 2026-09-18 是周五（第 3 周），但该课第 3 周没课 → 下周第 6 周才有课
        // 先找一个有课的周五：2026-09-25（第 4 周？）→ 第4周不在 weeks。
        // 第 6 周周五 = 2026-08-31 + 5*7 + 4 = 2026-10-09
        val friday6 = LocalDate.of(2026, 10, 9)
        assertEquals(6, ReminderCalculator.weekOf(termStart, friday6))
        val now = at(friday6, 7, 0)
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now
        )
        assertEquals(at(friday6, 7, 45), next)
    }

    @Test
    fun `今天的课已过 顺延到下周同一门课`() {
        val friday6 = LocalDate.of(2026, 10, 9)
        val now = at(friday6, 9, 0) // 8:00 的课已上，7:45 的提醒已过
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now
        )
        // 下一周第 7 周也有课：2026-10-16 07:45
        assertEquals(at(LocalDate.of(2026, 10, 16), 7, 45), next)
    }

    @Test
    fun `单周课程在双周不提醒`() {
        val oddWeeks = setOf(5, 7, 9)
        // 第 6 周（偶数）周五：不提醒，应跳到第 7 周
        val friday6 = LocalDate.of(2026, 10, 9)
        val now = at(friday6, 7, 0)
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, oddWeeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 10, now = now
        )
        // 第 7 周周五 = 2026-10-16
        assertEquals(at(LocalDate.of(2026, 10, 16), 7, 50), next)
    }

    @Test
    fun `有交集的周次 下一周仍提醒`() {
        val overlap = setOf(6, 7, 13, 19)
        val friday6 = LocalDate.of(2026, 10, 9)
        val now = at(friday6, 9, 0) // 今天已过
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, overlap, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 30, now = now
        )
        assertEquals(at(LocalDate.of(2026, 10, 16), 7, 30), next)
    }

    @Test
    fun `周次不含该周 该天不排`() {
        // 第 3 周（2026-09-18 周五）weeks 不含 3 → 跳到第 6 周
        val friday3 = LocalDate.of(2026, 9, 18)
        val now = at(friday3, 7, 0)
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now
        )
        assertEquals(at(LocalDate.of(2026, 10, 9), 7, 45), next)
    }

    @Test
    fun `调课钉到具体日期 只认那一天`() {
        val pinned = LocalDate.of(2026, 10, 20) // 周二
        val now = at(pinned, 7, 0)
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, emptySet(), dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now,
            overrideDate = pinned
        )
        assertEquals(at(pinned, 7, 45), next)
        // 时刻已过 → null（不再按周次规律补）
        val after = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = at(pinned, 12, 0),
            overrideDate = pinned
        )
        assertNull(after)
    }

    @Test
    fun `学期结束后返回null`() {
        val now = at(LocalDate.of(2027, 2, 1), 8, 0) // 第 20 周已结束
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now
        )
        assertNull(next)
    }

    @Test
    fun `未来多次提醒递增`() {
        val now = at(LocalDate.of(2026, 10, 9), 7, 0)
        val upcoming = ReminderCalculator.upcomingReminders(
            termStart, totalWeeks, weeks, dayOfWeek = 5,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15, now = now, count = 3
        )
        assertEquals(3, upcoming.size)
        assertTrue(upcoming[0].isBefore(upcoming[1]))
        assertEquals(6, ReminderCalculator.weekOf(termStart, upcoming[0].toLocalDate()))
    }

    @Test
    fun `星期0不排`() {
        val next = ReminderCalculator.nextReminder(
            termStart, totalWeeks, weeks, dayOfWeek = 0,
            courseStartTime = LocalTime.of(8, 0), minutesBefore = 15,
            now = at(LocalDate.of(2026, 10, 9), 7, 0)
        )
        assertNull(next)
    }
}
