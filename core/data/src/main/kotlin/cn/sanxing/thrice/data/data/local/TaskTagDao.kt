package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.TaskTag
import cn.sanxing.thrice.data.domain.model.TaskTagCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * 任务标签 / 任务-标签关联 DAO。
 *
 * 多标签与 tasks.type 单分类并存、互不影响。无外键约束，
 * 关联清理在本接口的事务方法内显式完成。
 */
@Dao
interface TaskTagDao {
    // ---------------- 标签 ----------------

    @Query("SELECT * FROM task_tags ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<TaskTag>>

    @Query("SELECT * FROM task_tags ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<TaskTag>

    @Query("SELECT * FROM task_tags WHERE id = :id")
    suspend fun getById(id: Long): TaskTag?

    /** 新建标签：重名触发唯一索引冲突（ABORT），异常交由调用方处理。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TaskTag): Long

    /** 按 id 写入（备份恢复保留原 id 用），冲突时整体替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TaskTag): Long

    @Update
    suspend fun update(tag: TaskTag)

    @Query("DELETE FROM task_tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM task_tags")
    suspend fun clearAll()

    // ---------------- 关联 ----------------

    @Query("SELECT tagId FROM task_tag_cross_ref WHERE taskId = :taskId")
    fun observeTagIdsForTask(taskId: Long): Flow<List<Long>>

    @Query("SELECT taskId FROM task_tag_cross_ref WHERE tagId = :tagId")
    fun observeTaskIdsForTag(tagId: Long): Flow<List<Long>>

    @Query("SELECT * FROM task_tag_cross_ref")
    suspend fun getAllRefs(): List<TaskTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefs(refs: List<TaskTagCrossRef>)

    @Query("DELETE FROM task_tag_cross_ref WHERE taskId = :taskId")
    suspend fun deleteRefsForTask(taskId: Long)

    @Query("DELETE FROM task_tag_cross_ref WHERE tagId = :tagId")
    suspend fun deleteRefsForTag(tagId: Long)

    @Query("DELETE FROM task_tag_cross_ref")
    suspend fun clearRefs()

    /** 事务：清空某个任务的旧关联后整体写入新关联。 */
    @Transaction
    suspend fun replaceTagsForTask(taskId: Long, tagIds: List<Long>) {
        deleteRefsForTask(taskId)
        if (tagIds.isNotEmpty()) {
            insertRefs(tagIds.map { TaskTagCrossRef(taskId = taskId, tagId = it) })
        }
    }

    /** 事务：先清标签关联再删标签，保证两步原子。 */
    @Transaction
    suspend fun deleteTagAndRefs(id: Long) {
        deleteRefsForTag(id)
        deleteById(id)
    }
}
