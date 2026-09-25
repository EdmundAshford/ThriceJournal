package cn.sanxing.thrice.parser.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * ICS 导出测试：用示例课（星期三 5-6 小节、7-16 周）验证
 * VEVENT 数量与首个 DTSTART（含 Asia/Shanghai 时区）。
 * 节次表与 App 一致，按小节序号（1..12）索引。
 */
class IcsExporterTest {

    private val term = IcsTerm(
        name = "2026-2027学年第1学期",
        startDate = LocalDate.of(2026, 8, 31),   // 第 1 周周一
        totalWeeks = 20
    )

    /** 小节时间表（小节序号 → 起止时刻），与课表设置中的结构一致。 */
    private val sectionTimes = listOf(
        IcsSectionTime(1, "08:00", "08:45"),
        IcsSectionTime(2, "08:55", "09:40"),
        IcsSectionTime(3, "10:10", "10:55"),
        IcsSectionTime(4, "11:05", "11:50"),
        IcsSectionTime(5, "14:00", "14:45"),
        IcsSectionTime(6, "14:55", "15:40"),
        IcsSectionTime(7, "16:10", "16:55"),
        IcsSectionTime(8, "17:05", "17:50"),
        IcsSectionTime(9, "19:00", "19:45"),
        IcsSectionTime(10, "19:55", "20:40"),
        IcsSectionTime(11, "20:50", "21:35"),
        IcsSectionTime(12, "21:45", "22:30")
    )

    private val course = IcsCourse(
        id = 9L,
        name = "计算机高级语言程序设计",
        teacher = "陈安",
        location = "主教学楼706",
        weeks = (7..16).toSet(),
        dayOfWeek = 3,          // 星期三
        startSection = 5,       // 第 5 小节 14:00 开始
        endSection = 6,         // 第 6 小节 15:40 结束
        note = "QQ群：100000001"
    )

    @Test
    fun `7-16周共10个VEVENT`() {
        val ics = IcsExporter.export(term, listOf(course), sectionTimes)
        val count = ics.lines().count { it == "BEGIN:VEVENT" }
        assertEquals(10, count)
    }

    @Test
    fun `首个DTSTART日期正确且带时区`() {
        // 第 7 周周三 = 2026-08-31 + (7-1)*7 + (3-1) 天 = 2026-10-14
        // 5-6 小节 → 14:00-15:40
        val ics = IcsExporter.export(term, listOf(course), sectionTimes)
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20261014T140000"))
        assertTrue(ics.contains("DTEND;TZID=Asia/Shanghai:20261014T154000"))
        // 第 16 周周三 = 2026-08-31 + 15*7 + 2 = 2026-12-16
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20261216T140000"))
    }

    @Test
    fun `仅本周导出只含一个VEVENT`() {
        val ics = IcsExporter.export(term, listOf(course), sectionTimes, onlyWeek = 8)
        assertEquals(1, ics.lines().count { it == "BEGIN:VEVENT" })
    }

    @Test
    fun `其他课程与其他星期不导出`() {
        val other = course.copy(id = 99, dayOfWeek = 0)
        val ics = IcsExporter.export(term, listOf(other), sectionTimes)
        assertEquals(0, ics.lines().count { it == "BEGIN:VEVENT" })
    }

    @Test
    fun `开始节次缺时间配置时跳过事件`() {
        val noTime = course.copy(id = 100, startSection = 12)
        val ics = IcsExporter.export(
            term, listOf(noTime), sectionTimes.filter { it.sectionIndex != 12 }
        )
        assertEquals(0, ics.lines().count { it == "BEGIN:VEVENT" })
        assertFalse(ics.contains("080000"))
    }

    @Test
    fun `版本与PRODID正确`() {
        val ics = IcsExporter.export(term, listOf(course), sectionTimes)
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:-//Thrice//EN"))
    }

    @Test
    fun `TEXT转义逗号分号换行`() {
        assertEquals("a\\,b\\;c\\\\d\\ne", IcsExporter.escapeText("a,b;c\\d\ne"))
    }

    @Test
    fun `长行按75字节折行且续行以空格开头`() {
        val longText = "很".repeat(60)  // 180 字节
        val folded = IcsExporter.foldLine("DESCRIPTION:$longText")
        val lines = folded.split("\r\n")
        assertTrue(lines.size >= 3)
        lines.forEachIndexed { i, l ->
            assertTrue("第 $i 行超长: ${l.toByteArray(Charsets.UTF_8).size}", l.toByteArray(Charsets.UTF_8).size <= 75)
            if (i > 0) assertTrue(l.startsWith(" "))
        }
        // 展开折行后内容一致
        val unfolded = lines.joinToString("") { if (it.startsWith(" ")) it.substring(1) else it }
        assertEquals("DESCRIPTION:$longText", unfolded)
    }

    @Test
    fun `UID稳定唯一`() {
        assertEquals(IcsExporter.uidOf(9L, 7, 3), IcsExporter.uidOf(9L, 7, 3))
        assertTrue(IcsExporter.uidOf(9L, 7, 3) != IcsExporter.uidOf(9L, 8, 3))
    }

    @Test
    fun `DTSTAMP为UTC时刻带Z后缀`() {
        val now = LocalDateTime.of(2026, 9, 18, 12, 0, 0)
        val ics = IcsExporter.export(term, listOf(course), sectionTimes, now = now)
        // 期望值按运行环境时区转换为 UTC，保证任何时区的 CI 都成立
        val expected = now.atZone(ZoneId.systemDefault())
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        assertTrue(ics.contains("DTSTAMP:$expected"))
    }
}
