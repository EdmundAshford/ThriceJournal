package cn.sanxing.thrice.parser.parse

import cn.sanxing.thrice.parser.ImportScheduleUseCase
import cn.sanxing.thrice.parser.TestSamples
import cn.sanxing.thrice.parser.model.ScheduleFormat
import cn.sanxing.thrice.parser.text.PdfText
import cn.sanxing.thrice.parser.text.PdfTextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式 B 解析测试。
 *
 * 注意：纯文本导出会丢失"列位置"信息，因此 [ImportScheduleUseCase.importText]
 * 只能拿到课程名 / 节次 / 周次 / 教师 / 地点 / 学分（星期置 0）；
 * 星期需走 [FormatBParser.parsePositioned]（借助 PDF 坐标），见下方列→星期用例。
 */
class FormatBParserTest {

    private val result = ImportScheduleUseCase().importText(TestSamples.load("sample_formatB.txt"))

    @Test
    fun `识别为格式B`() {
        assertEquals(ScheduleFormat.FORMAT_B, result.format)
    }

    @Test
    fun `解析 18 条原始条目`() {
        assertEquals(18, result.rawCourses.size)
    }

    @Test
    fun `合并后 13 条（与格式A一致）`() {
        assertEquals(13, result.courses.size)
    }

    @Test
    fun `其他课程 2 条`() {
        assertEquals(2, result.otherCourses.size)
    }

    @Test
    fun `高数A1 1-2节 6-11周 字段完整`() {
        val a1 = result.courses.find { it.name == "高等数学A1" && it.startSection == 1 }!!
        assertEquals(2, a1.endSection)
        assertEquals((6..11).toSet(), a1.weeks)
        assertEquals("李和", a1.teacher)
        assertEquals("主教学楼306", a1.location)
        assertEquals(2.5, a1.credit!!, 1e-9)
    }

    @Test
    fun `计导 3-4节 合并周次 7至12`() {
        val jidao = result.courses.find { it.name == "计算机科学导论" && it.startSection == 3 }!!
        assertEquals((7..12).toSet(), jidao.weeks)
        // 合并后的 teacher = 去重后的教师名（分段明细留在 teacherSegments；
        // 与同套件 FormatAParserTest 的"计导周一 = 陈安/周文/吴理"口径一致）。
        // 样例真值：7-9周 陈安 / 10周 陈安 / 11周 郑华 / 12周 孙芳。
        assertEquals("陈安/郑华/孙芳", jidao.teacher)
        // "7-9 与 10 两段不丢"断言在 teacherSegments 上（更强，不丢分段信息）：
        assertEquals(4, jidao.teacherSegments.size)
        assertTrue(jidao.teacherSegments.any { it.teacher == "陈安" && it.weeks == (7..9).toSet() })
        assertTrue(jidao.teacherSegments.any { it.teacher == "陈安" && it.weeks == setOf(10) })
        assertTrue(jidao.teacherSegments.any { it.teacher == "郑华" && it.weeks == setOf(11) })
        assertTrue(jidao.teacherSegments.any { it.teacher == "孙芳" && it.weeks == setOf(12) })
    }

    @Test
    fun `武术 3-4节 6-19周`() {
        val w = result.courses.find { it.name == "武术" }!!
        assertEquals((6..19).toSet(), w.weeks)
        assertEquals("北区田径场", w.location)
        assertEquals(1.0, w.credit!!, 1e-9)
    }

    @Test
    fun `格式B课程身份与格式A一致（按 课名+节次+周次 比对）`() {
        val a = ImportScheduleUseCase().importText(TestSamples.load("sample_formatA.txt"))
        fun key(c: cn.sanxing.thrice.parser.model.ParsedCourse) =
            "${c.name}|${c.startSection}-${c.endSection}|${c.weeks.sorted().joinToString(",")}"
        val aKeys = a.courses.map(::key).sorted()
        val bKeys = result.courses.map(::key).sorted()
        assertEquals(aKeys, bKeys)
    }

    // ---------- 列 → 星期（带坐标） ----------

    @Test
    fun `parsePositioned 按列头顺序还原星期`() {
        // 合成一个带坐标的格式B：表头 星期一..星期日 7 列，一个课程落在第 4 列（星期四）
        // 坐标约定与真实 PDF 一致：y 自顶向下增大，课名在字段上方（y 更小）
        val spans = listOf(
            PdfTextSpan("时间段", 1, 0f, 10f),
            PdfTextSpan("节次", 1, 0f, 15f),
            PdfTextSpan("星期一", 1, 0f, 20f),
            PdfTextSpan("星期二", 1, 1f, 20f),
            PdfTextSpan("星期三", 1, 2f, 20f),
            PdfTextSpan("星期四", 1, 3f, 20f),
            PdfTextSpan("星期五", 1, 4f, 20f),
            PdfTextSpan("星期六", 1, 5f, 20f),
            PdfTextSpan("星期日", 1, 6f, 20f),
            PdfTextSpan("高等数学A1*", 1, 3f, 40f),
            PdfTextSpan("(1-2节)6-11周/校区:青竹山/场地:主教学楼306/教师:李和/学分:2.5", 1, 3f, 42f)
        )
        val outcome = FormatBParser.parsePositioned(PdfText(spans))
        assertEquals(1, outcome.courses.size)
        assertEquals(4, outcome.courses[0].dayOfWeek) // 星期四
        assertEquals("高等数学A1", outcome.courses[0].name)
    }
}
