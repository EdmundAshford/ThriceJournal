package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.data.domain.model.NoteTagCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * 笔记标签 / 笔记-标签关联 DAO。
 *
 * 无外键约束：所有关联清理都在本接口的事务方法内显式完成，
 * 风格与 [FocusTagDao] 保持一致。
 */
@Dao
interface NoteTagDao {
    // ---------------- 标签 ----------------

    @Query("SELECT * FROM note_tags ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<NoteTag>>

    @Query("SELECT * FROM note_tags ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<NoteTag>

    @Query("SELECT * FROM note_tags WHERE id = :id")
    suspend fun getById(id: Long): NoteTag?

    /** 新建标签：重名触发唯一索引冲突（ABORT），异常交由调用方处理。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: NoteTag): Long

    /** 按 id 写入（备份恢复保留原 id 用），冲突时整体替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: NoteTag): Long

    @Update
    suspend fun update(tag: NoteTag)

    @Query("DELETE FROM note_tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM note_tags")
    suspend fun clearAll()

    // ---------------- 关联 ----------------

    @Query("SELECT tagId FROM note_tag_cross_ref WHERE noteId = :noteId")
    fun observeTagIdsForNote(noteId: Long): Flow<List<Long>>

    @Query("SELECT noteId FROM note_tag_cross_ref WHERE tagId = :tagId")
    fun observeNoteIdsForTag(tagId: Long): Flow<List<Long>>

    @Query("SELECT * FROM note_tag_cross_ref")
    suspend fun getAllRefs(): List<NoteTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefs(refs: List<NoteTagCrossRef>)

    @Query("DELETE FROM note_tag_cross_ref WHERE noteId = :noteId")
    suspend fun deleteRefsForNote(noteId: Long)

    @Query("DELETE FROM note_tag_cross_ref WHERE tagId = :tagId")
    suspend fun deleteRefsForTag(tagId: Long)

    @Query("DELETE FROM note_tag_cross_ref")
    suspend fun clearRefs()

    /** 事务：清空某篇笔记的旧关联后整体写入新关联。 */
    @Transaction
    suspend fun replaceTagsForNote(noteId: Long, tagIds: List<Long>) {
        deleteRefsForNote(noteId)
        if (tagIds.isNotEmpty()) {
            insertRefs(tagIds.map { NoteTagCrossRef(noteId = noteId, tagId = it) })
        }
    }

    /** 事务：先清标签关联再删标签，保证两步原子。 */
    @Transaction
    suspend fun deleteTagAndRefs(id: Long) {
        deleteRefsForTag(id)
        deleteById(id)
    }
}
