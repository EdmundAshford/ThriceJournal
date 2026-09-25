package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.NoteFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteFolderDao {
    @Query("SELECT * FROM note_folders ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<NoteFolder>>

    @Query("SELECT * FROM note_folders ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<NoteFolder>

    @Query("SELECT * FROM note_folders WHERE id = :id")
    suspend fun getById(id: Long): NoteFolder?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM note_folders")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: NoteFolder): Long

    /** 批量插入（备份恢复用），保留条目自带 id。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<NoteFolder>): List<Long>

    @Update
    suspend fun update(folder: NoteFolder)

    /** 删除文件夹前先把其中笔记归入「未分类」（无外键级联，需显式置空）。 */
    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :id")
    suspend fun detachNotes(id: Long)

    @Query("DELETE FROM note_folders WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 事务：置空笔记引用 + 删文件夹，保证两步原子。 */
    @Transaction
    suspend fun deleteFolderAndDetachNotes(id: Long) {
        detachNotes(id)
        deleteById(id)
    }

    @Query("DELETE FROM note_folders")
    suspend fun clearAll()
}
