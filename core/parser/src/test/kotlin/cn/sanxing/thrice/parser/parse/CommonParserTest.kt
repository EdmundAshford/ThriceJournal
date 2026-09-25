package cn.sanxing.thrice.parser.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共有解析逻辑单元测试：周次 / 节次 / 星期 / 学分粘连 / 字段抽取 / 别名。
 */
class CommonParserTest {

    // ---------- 周次 ----------

    @Test
    fun `周次 1-16周`() {
        assertEquals((1..16).toSet(), CommonParser.parseWeeks("1-16周").weeks)
    }

    @Test
    fun `周次 1-8,10-16周`() {
        assertEquals((1..8).toSet() + (10..16).toSet(), CommonParser.parseWeeks("1-8,10-16周").weeks)
    }

    @Test
    fun `周次 5-7周(单),8-12周 单双只作用紧邻区间`() {
        assertEquals(setOf(5, 7, 8, 9, 10, 11, 12), CommonParser.parseWeeks("5-7周(单),8-12周").weeks)
    }

    @Test
    fun `周次 8-14周(双)`() {
        assertEquals(setOf(8, 10, 12, 14), CommonParser.parseWeeks("8-14周(双)").weeks)
    }

    @Test
    fun `周次 1,3,5,7`() {
        assertEquals(setOf(1, 3, 5, 7), CommonParser.parseWeeks("1,3,5,7").weeks)
    }

    @Test
    fun `周次 6-8周,10-17周,19周`() {
        assertEquals(setOf(6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 19), CommonParser.parseWeeks("6-8周,10-17周,19周").weeks)
    }

    @Test
    fun `周次 全角逗号与顿号`() {
        assertEquals(setOf(1, 2, 3, 4), CommonParser.parseWeeks("1，2、3-4").weeks)
    }

    // ---------- 节次 ----------

    @Test
    fun `节次 第1-2节`() {
        assertEquals(1 to 2, CommonParser.parseSection("第1-2节"))
    }

    @Test
    fun `节次 1-2节`() {
        assertEquals(1 to 2, CommonParser.parseSection("1-2节"))
    }

    @Test
    fun `节次 1-2`() {
        assertEquals(1 to 2, CommonParser.parseSection("1-2"))
    }

    @Test
    fun `节次 0102`() {
        assertEquals(1 to 2, CommonParser.parseSection("0102"))
    }

    @Test
    fun `节次 (1-2节)`() {
        assertEquals(1 to 2, CommonParser.parseSection("(1-2节)"))
    }

    @Test
    fun `节次 第9-10节`() {
        assertEquals(9 to 10, CommonParser.parseSection("第9-10节"))
    }

    @Test
    fun `非法节次返回 null`() {
        assertNull(CommonParser.parseSection("55-6"))
    }

    // ---------- 星期 ----------

    @Test
    fun `星期 星期一`() {
        assertEquals(1, CommonParser.parseDayOfWeek("星期一"))
    }

    @Test
    fun `星期 周一`() {
        assertEquals(2, CommonParser.parseDayOfWeek("周二"))
    }

    @Test
    fun `星期 礼拜一`() {
        assertEquals(1, CommonParser.parseDayOfWeek("礼拜一"))
    }

    @Test
    fun `星期 数字 7`() {
        assertEquals(7, CommonParser.parseDayOfWeek("7"))
    }

    // ---------- 学分粘连 ----------

    @Test
    fun `粘连 学分2点55-6 截断为2点5 剩余以5-6开头`() {
        val (num, rest) = CommonParser.parseGluedNumber("2.55-6高等数学A1*")
        assertEquals(2.5, num!!, 1e-9)
        assertEquals("5-6高等数学A1*", rest)
        assertTrue(rest.startsWith("5-6"))
    }

    @Test
    fun `粘连 普通学分2点5 无剩余`() {
        val (num, rest) = CommonParser.parseGluedNumber("2.5")
        assertEquals(2.5, num!!, 1e-9)
        assertEquals("", rest)
    }

    // ---------- 字段抽取 ----------

    @Test
    fun `字段抽取 值内部斜杠与冒号不被误切`() {
        val f = CommonParser.extractFields("周数:7-16周/课程学时组成:讲课:40,实验/科研实践:16/周学时:4/总学时:40/学分:2.5")
        assertEquals("7-16周", f["weeks"])
        assertEquals("讲课:40,实验/科研实践:16", f["hoursBreakdown"])
        assertEquals("4", f["weeklyHours"])
        assertEquals("40", f["totalHours"])
        assertEquals("2.5", f["credit"])
    }

    @Test
    fun `字段抽取 跨行拆开的字段名与冒号`() {
        // 归一化前模拟：考核方式\n:考试
        val f = CommonParser.extractFields("考核方式:未安排/选课备注:QQ群:100000001")
        assertEquals("未安排", f["assessment"])
        assertEquals("QQ群:100000001", f["remark"])
    }

    @Test
    fun `字段抽取 场地别名映射到 location`() {
        val f = CommonParser.extractFields("场地:主教学楼306/教师:李和")
        assertEquals("主教学楼306", f["location"])
        assertEquals("李和", f["teacher"])
    }

    @Test
    fun `字段抽取 地点别名映射到 location`() {
        val f = CommonParser.extractFields("地点:东区教学楼B0413")
        assertEquals("东区教学楼B0413", f["location"])
    }

    @Test
    fun `考核方式未安排原样保留`() {
        val f = CommonParser.extractFields("考核方式:未安排")
        assertEquals("未安排", f["assessment"])
    }
}
