package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    // 不在 SQL 层排序：统一由 Repository 组合「置顶 + sortOrder + 时间 + 标题」顺序。
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId")
    fun observeInFolder(folderId: Long): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId IS NULL")
    fun observeUncategorized(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    /** 批量插入（备份恢复用），保留条目自带 id。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<Note>): List<Long>

    @Update
    suspend fun update(note: Note)

    @Query("UPDATE notes SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes")
    suspend fun clearAll()
}
