package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.sanxing.thrice.data.domain.model.FocusTag
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusTagDao {
    @Query("SELECT * FROM focus_tags ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<FocusTag>>

    @Query("SELECT * FROM focus_tags ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<FocusTag>

    @Query("SELECT * FROM focus_tags WHERE id = :id")
    suspend fun getById(id: Long): FocusTag?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM focus_tags")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: FocusTag): Long

    @Delete
    suspend fun delete(tag: FocusTag)

    /** 删除标签前先把引用该标签的会话归入「未分类」（无外键级联，需显式置空）。 */
    @Query("UPDATE focus_sessions SET tagId = NULL WHERE tagId = :id")
    suspend fun detachSessions(id: Long)

    /** 事务：置空会话引用 + 删标签，保证两步原子。 */
    @Transaction
    suspend fun deleteTagAndDetach(id: Long) {
        detachSessions(id)
        deleteById(id)
    }

    @Query("DELETE FROM focus_tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM focus_tags")
    suspend fun clearAll()
}
