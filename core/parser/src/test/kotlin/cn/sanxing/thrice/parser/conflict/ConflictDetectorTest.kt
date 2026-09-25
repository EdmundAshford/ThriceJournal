package cn.sanxing.thrice.parser.conflict

import cn.sanxing.thrice.parser.model.ParsedCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDetectorTest {

    private fun course(
        name: String,
        day: Int,
        start: Int,
        end: Int,
        weeks: Set<Int>
    ) = ParsedCourse(name = name, dayOfWeek = day, startSection = start, endSection = end, weeks = weeks)

    @Test
    fun `同格 A1(5-7单,8-12) 与 A2(13-19) 周次无交集 不算冲突`() {
        val a1 = course("高等数学A1", 1, 5, 6, setOf(5, 7, 8, 9, 10, 11, 12))
        val a2 = course("高等数学A2", 1, 5, 6, (13..19).toSet())
        val conflicts = ConflictDetector.detect(listOf(a1, a2))
        assertEquals(0, conflicts.size)
    }

    @Test
    fun `同天同节次周次相交 检出冲突`() {
        val a = course("课程A", 2, 3, 4, (1..8).toSet())
        val b = course("课程B", 2, 3, 4, (6..12).toSet())
        val conflicts = ConflictDetector.detect(listOf(a, b))
        assertEquals(1, conflicts.size)
        assertEquals(setOf(6, 7, 8), conflicts[0].overlappingWeeks)
    }

    @Test
    fun `不同天 不算冲突`() {
        val a = course("课程A", 1, 3, 4, (1..8).toSet())
        val b = course("课程B", 2, 3, 4, (1..8).toSet())
        assertTrue(ConflictDetector.detect(listOf(a, b)).isEmpty())
    }

    @Test
    fun `节次不重叠 不算冲突`() {
        val a = course("课程A", 1, 1, 2, (1..8).toSet())
        val b = course("课程B", 1, 5, 6, (1..8).toSet())
        assertTrue(ConflictDetector.detect(listOf(a, b)).isEmpty())
    }
}
