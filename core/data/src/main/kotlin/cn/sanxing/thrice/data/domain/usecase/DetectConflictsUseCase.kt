package cn.sanxing.thrice.data.domain.usecase

import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import javax.inject.Inject

/** 数据层的课程冲突（同天同节次且周次有交集）。 */
data class CourseConflict(
    val a: Course,
    val b: Course,
    val overlappingWeeks: Set<Int>
)

/** 对已保存课程做冲突检测。 */
class DetectConflictsUseCase @Inject constructor() {

    operator fun invoke(courses: List<Course>): List<CourseConflict> {
        val result = mutableListOf<CourseConflict>()
        val grid = courses.filter { it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 }
        for (i in grid.indices) {
            for (j in i + 1 until grid.size) {
                val a = grid[i]
                val b = grid[j]
                if (a.dayOfWeek != b.dayOfWeek) continue
                if (a.startSection > b.endSection || b.startSection > a.endSection) continue
                val overlap = a.weeks.intersect(b.weeks)
                if (overlap.isNotEmpty()) result.add(CourseConflict(a, b, overlap))
            }
        }
        return result
    }
}
