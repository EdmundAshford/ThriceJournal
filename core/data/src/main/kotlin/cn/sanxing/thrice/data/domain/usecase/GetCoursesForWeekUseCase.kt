package cn.sanxing.thrice.data.domain.usecase

import cn.sanxing.thrice.data.data.repository.CourseRepository
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 查询某学期某周的网格课程（周次包含该周的才返回）。 */
class GetCoursesForWeekUseCase @Inject constructor(
    private val courseRepository: CourseRepository
) {
    operator fun invoke(termId: Long, week: Int): Flow<List<Course>> =
        courseRepository.observeByTerm(termId).map { list ->
            list.filter { it.kind == CourseKind.GRID && week in it.weeks }
        }
}
