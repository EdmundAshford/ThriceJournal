package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.model.CourseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherCourseParserTest {

    private val content =
        "计算机高级语言程序设计陈安(共4周)/8-14周(双)/无;   计算机高级语言课程设计陈安(共1周)/14周/QQ群：100000001;"

    @Test
    fun `解析 2 条其他课程`() {
        val (courses, _) = OtherCourseParser.parse(content)
        assertEquals(2, courses.size)
        assertTrue(courses.all { it.kind == CourseKind.OTHER })
        assertTrue(courses.all { it.dayOfWeek == 0 && it.startSection == 0 && it.endSection == 0 })
    }

    @Test
    fun `O1 计算机高级语言程序设计 周次 8,10,12,14`() {
        val (courses, warnings) = OtherCourseParser.parse(content)
        val o1 = courses.find { it.name == "计算机高级语言程序设计" }!!
        assertEquals("陈安", o1.teacher)
        assertEquals(setOf(8, 10, 12, 14), o1.weeks)
        assertEquals("", o1.remark) // "/无" 表示无备注
        // 周数校验通过，无告警
        assertTrue(warnings.none { it.contains("周数校验") })
    }

    @Test
    fun `O2 计算机高级语言课程设计 周次 14`() {
        val (courses, _) = OtherCourseParser.parse(content)
        val o2 = courses.find { it.name == "计算机高级语言课程设计" }!!
        assertEquals("陈安", o2.teacher)
        assertEquals(setOf(14), o2.weeks)
        assertEquals("QQ群:100000001", o2.remark)
    }

    @Test
    fun `周数不匹配时产生告警`() {
        val (_, warnings) = OtherCourseParser.parse("某课程某某(共3周)/8-14周(双)/无;")
        assertTrue(warnings.any { it.contains("周数校验") })
    }
}
