package cn.sanxing.thrice.data.data.repository

import cn.sanxing.thrice.data.data.local.CourseDao
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CourseRepository @Inject constructor(private val dao: CourseDao) {

    fun observeByTerm(termId: Long): Flow<List<Course>> = dao.observeByTerm(termId)

    fun observeByTermAndDay(termId: Long, day: Int): Flow<List<Course>> = dao.observeByTermAndDay(termId, day)

    fun observeOther(termId: Long): Flow<List<Course>> = dao.observeByKind(termId, CourseKind.OTHER)

    fun observeByName(name: String): Flow<List<Course>> = dao.observeByName(name)

    suspend fun getByTerm(termId: Long): List<Course> = dao.getByTerm(termId)

    suspend fun get(id: Long): Course? = dao.get(id)

    suspend fun insertAll(courses: List<Course>) = dao.insertAll(courses)

    suspend fun insert(course: Course): Long = dao.insert(course)

    suspend fun update(course: Course) = dao.update(course)

    suspend fun delete(course: Course) = dao.delete(course)

    suspend fun deleteByTerm(termId: Long) = dao.deleteByTerm(termId)
}
