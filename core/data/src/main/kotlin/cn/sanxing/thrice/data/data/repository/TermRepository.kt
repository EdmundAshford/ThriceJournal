package cn.sanxing.thrice.data.data.repository

import cn.sanxing.thrice.data.data.local.AssignmentDao
import cn.sanxing.thrice.data.data.local.CourseDao
import cn.sanxing.thrice.data.data.local.ExamDao
import cn.sanxing.thrice.data.data.local.ReminderDao
import cn.sanxing.thrice.data.data.local.SectionTimeDao
import cn.sanxing.thrice.data.data.local.SeedData
import cn.sanxing.thrice.data.data.local.TermDao
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.Term
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TermRepository @Inject constructor(
    private val dao: TermDao,
    private val courseDao: CourseDao,
    private val sectionTimeDao: SectionTimeDao,
    private val examDao: ExamDao,
    private val assignmentDao: AssignmentDao,
    private val reminderDao: ReminderDao
) {

    fun observeAll(): Flow<List<Term>> = dao.observeAll()

    fun observeActive(): Flow<Term?> = dao.observeActive()

    suspend fun getActive(): Term? = dao.getActive()

    suspend fun get(id: Long): Term? = dao.get(id)

    suspend fun insert(term: Term): Long = dao.insert(term)

    suspend fun update(term: Term) = dao.update(term)

    suspend fun setActive(id: Long) {
        dao.clearActive()
        dao.setActive(id)
    }

    /**
     * 新建一套空白课表方案（含默认节次时间），并立即切换为当前方案。
     */
    suspend fun createBlankScheme(name: String, startDate: LocalDate, totalWeeks: Int): Long {
        dao.clearActive()
        val id = dao.insert(
            Term(name = name.ifBlank { "我的课表" }, startDate = startDate,
                totalWeeks = totalWeeks.coerceIn(1, 40), isActive = true)
        )
        sectionTimeDao.insertAll(SeedData.defaultSectionTimes(id))
        return id
    }

    /**
     * 复制整套方案：学期信息、节次时间、课程（提醒不复制，可在课表页重新开启）。
     * 复制后立即切换为当前方案。
     *
     * @return 新方案 id；源方案不存在返回 null
     */
    suspend fun duplicateScheme(sourceId: Long, newName: String): Long? {
        val source = dao.get(sourceId) ?: return null
        dao.clearActive()
        val newId = dao.insert(
            source.copy(
                id = 0,
                name = newName.ifBlank { source.name + " 副本" },
                isActive = true
            )
        )
        sectionTimeDao.getByTerm(sourceId).forEach { st ->
            sectionTimeDao.insertAll(listOf(st.copy(id = 0, termId = newId)))
        }
        if (sectionTimeDao.getByTerm(newId).isEmpty()) {
            sectionTimeDao.insertAll(SeedData.defaultSectionTimes(newId))
        }
        courseDao.getByTerm(sourceId).forEach { c: Course ->
            courseDao.insert(c.copy(id = 0, termId = newId))
        }
        examDao.getAll().filter { it.termId == sourceId }.forEach { e ->
            examDao.insert(e.copy(id = 0, termId = newId))
        }
        assignmentDao.getAll().filter { it.termId == sourceId }.forEach { a ->
            assignmentDao.insert(a.copy(id = 0, termId = newId))
        }
        return newId
    }

    /**
     * 删除整套方案（课程 / 节次 / 考试 / 作业 / 提醒一并删除）。
     * 若删的是当前方案，自动切换到剩余方案中最新的一套；没有剩余方案时返回 false（禁止删除最后一套）。
     */
    suspend fun deleteScheme(id: Long): Boolean {
        val all = dao.getAll()
        if (all.size <= 1) return false
        val target = all.firstOrNull { it.id == id } ?: return false
        reminderDao.deleteByTerm(id)
        courseDao.deleteByTerm(id)
        sectionTimeDao.deleteByTerm(id)
        examDao.deleteByTerm(id)
        assignmentDao.deleteByTerm(id)
        dao.delete(target)
        if (target.isActive) {
            dao.getAll().maxByOrNull { it.id }?.let { dao.setActive(it.id) }
        }
        return true
    }
}
