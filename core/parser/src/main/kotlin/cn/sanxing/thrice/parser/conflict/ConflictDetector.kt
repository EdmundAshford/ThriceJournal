package cn.sanxing.thrice.parser.conflict

import cn.sanxing.thrice.parser.model.Conflict
import cn.sanxing.thrice.parser.model.CourseKind
import cn.sanxing.thrice.parser.model.ParsedCourse

/**
 * 冲突检测：同一天、节次区间有重叠、且**周次有交集**才算冲突。
 *
 * 例：周一 5-6 格内 高数A1(5-7单,8-12) 与 高数A2(13-19) 节次重叠但周次无交集 → 不算冲突。
 */
object ConflictDetector {

    fun detect(courses: List<ParsedCourse>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        // 节次未识别（0）的课没有可信的时间区间，与同日所有课都会「重叠」，
        // 会产生大量假冲突，必须排除。
        val grid = courses.filter {
            it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 && it.startSection > 0 && it.endSection > 0
        }
        for (i in grid.indices) {
            for (j in i + 1 until grid.size) {
                val a = grid[i]
                val b = grid[j]
                if (a.dayOfWeek != b.dayOfWeek) continue
                if (a.startSection > b.endSection || b.startSection > a.endSection) continue
                val overlap = a.weeks.intersect(b.weeks)
                if (overlap.isNotEmpty()) {
                    conflicts.add(
                        Conflict(
                            courseA = a.name,
                            courseB = b.name,
                            dayOfWeek = a.dayOfWeek,
                            startSection = maxOf(a.startSection, b.startSection),
                            endSection = minOf(a.endSection, b.endSection),
                            overlappingWeeks = overlap.toSortedSet()
                        )
                    )
                }
            }
        }
        return conflicts
    }
}
