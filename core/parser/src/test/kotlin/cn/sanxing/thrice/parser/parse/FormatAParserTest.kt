package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.ImportScheduleUseCase
import cn.sanxing.thrice.parser.TestSamples
import cn.sanxing.thrice.parser.model.ScheduleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式 A 解析测试（验收基准：18 原始 → 13 合并 → 6 课名 + 2 其他）。
 */
class FormatAParserTest {

    private val result = ImportScheduleUseCase().importText(TestSamples.load("sample_formatA.txt"))

    @Test
    fun `识别为格式A`() {
        assertEquals(ScheduleFormat.FORMAT_A, result.format)
    }

    @Test
    fun `解析 18 条原始条目`() {
        assertEquals(18, result.rawCourses.size)
    }

    @Test
    fun `合并后 13 条`() {
        assertEquals(13, result.courses.size)
    }

    @Test
    fun `不同课程名 6 个`() {
        assertEquals(6, result.courses.map { it.name }.distinct().size)
    }

    @Test
    fun `其他课程 2 条`() {
        assertEquals(2, result.otherCourses.size)
    }

    @Test
    fun `计导两条 周一7-8 与 周三3-4 周次均为 7至12`() {
        val jidao = result.courses.filter { it.name == "计算机科学导论" }
        assertEquals(2, jidao.size)
        val mon = jidao.find { it.dayOfWeek == 1 }
        val wed = jidao.find { it.dayOfWeek == 3 }
        assertNotNull(mon); assertNotNull(wed)
        assertEquals(7, mon!!.startSection); assertEquals(8, mon.endSection)
        assertEquals(3, wed!!.startSection); assertEquals(4, wed.endSection)
        assertEquals((7..12).toSet(), mon.weeks)
        assertEquals((7..12).toSet(), wed.weeks)
    }

    @Test
    fun `计导周一 教师保留分段 陈安 周文 吴理`() {
        val mon = result.courses.find { it.name == "计算机科学导论" && it.dayOfWeek == 1 }!!
        assertEquals("陈安/周文/吴理", mon.teacher)
        assertEquals(3, mon.teacherSegments.size)
        assertTrue(mon.teacherSegments.any { it.teacher == "陈安" && it.weeks == (7..10).toSet() })
        assertTrue(mon.teacherSegments.any { it.teacher == "周文" && it.weeks == setOf(11) })
        assertTrue(mon.teacherSegments.any { it.teacher == "吴理" && it.weeks == setOf(12) })
    }

    @Test
    fun `高数A1 周一5-6 周次 5,7,8至12`() {
        val a1 = result.courses.find { it.name == "高等数学A1" && it.dayOfWeek == 1 }!!
        assertEquals(5, a1.startSection); assertEquals(6, a1.endSection)
        assertEquals(setOf(5, 7, 8, 9, 10, 11, 12), a1.weeks)
        assertEquals("李和", a1.teacher)
        assertEquals("主教学楼306", a1.location)
        assertEquals(2.5, a1.credit!!, 1e-9)
        assertEquals("未安排", a1.assessment)
    }

    @Test
    fun `高数A2 周一5-6 周次 13至19`() {
        val a2 = result.courses.find { it.name == "高等数学A2" && it.dayOfWeek == 1 }!!
        assertEquals((13..19).toSet(), a2.weeks)
        assertEquals("主教学楼606", a2.location)
        assertEquals("考试", a2.assessment)
    }

    @Test
    fun `计算机高级语言程序设计 周一3-4 字段完整`() {
        val c = result.courses.find { it.name == "计算机高级语言程序设计" && it.dayOfWeek == 1 }!!
        assertEquals((7..16).toSet(), c.weeks)
        assertEquals("陈安", c.teacher)
        assertEquals("东区教学楼B0413", c.location)
        assertEquals(2.5, c.credit!!, 1e-9)
        assertEquals("考试", c.assessment)
        assertEquals("计算机高级语言程序设计-0001", c.classGroup)
        assertEquals("200101;200102", c.classMembers)
    }

    @Test
    fun `武术 周四3-4 讲课36 总学时28 原样保留`() {
        val w = result.courses.find { it.name == "武术" }!!
        assertEquals(4, w.dayOfWeek)
        assertEquals("讲课:36", w.hoursBreakdown)
        assertEquals(2, w.weeklyHours)
        assertEquals(28, w.totalHours)
        assertEquals("考查", w.assessment)
    }

    @Test
    fun `线性代数A 周五1-2 周次 6-8,10-17,19`() {
        val l = result.courses.find { it.name == "线性代数A" && it.dayOfWeek == 5 }!!
        assertEquals(setOf(6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 19), l.weeks)
        assertEquals(3.0, l.credit!!, 1e-9)
    }

    @Test
    fun `基准课表无冲突`() {
        assertEquals(0, result.conflicts.size)
    }

    @Test
    fun `挤一行型 粘连 学分2点5 下一门节次5-6`() {
        val text = "星期一3-4计算机高级语言程序设计*周数:7-16周/学分:2.55-6高等数学A1*周数:5-7周(单),8-12周/学分:2.5"
        val r = ImportScheduleUseCase().importText(text)
        assertEquals(2, r.rawCourses.size)
        val first = r.rawCourses[0]
        val second = r.rawCourses[1]
        assertEquals(2.5, first.credit!!, 1e-9)
        assertEquals(3, first.startSection); assertEquals(4, first.endSection)
        assertEquals(5, second.startSection); assertEquals(6, second.endSection)
        assertEquals(2.5, second.credit!!, 1e-9)
        assertEquals(setOf(5, 7, 8, 9, 10, 11, 12), second.weeks)
    }
}
