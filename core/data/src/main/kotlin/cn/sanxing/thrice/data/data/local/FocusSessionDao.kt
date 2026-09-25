package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.FocusSession
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<FocusSession>>

    /** 按会话开始时刻落在闭区间 [start, end] 查询。 */
    @Query("SELECT * FROM focus_sessions WHERE startedAtEpochMs BETWEEN :start AND :end ORDER BY startedAtEpochMs ASC")
    fun observeRange(start: Long, end: Long): Flow<List<FocusSession>>

    @Query("SELECT * FROM focus_sessions WHERE startedAtEpochMs BETWEEN :start AND :end ORDER BY startedAtEpochMs ASC")
    suspend fun getRange(start: Long, end: Long): List<FocusSession>

    @Query("SELECT * FROM focus_sessions ORDER BY startedAtEpochMs DESC")
    suspend fun getAll(): List<FocusSession>

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun getById(id: Long): FocusSession?

    /** 按开始墙钟时刻计数：进程被杀恢复补落库时去重，避免同一会话写两条。 */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE startedAtEpochMs = :startedAt")
    suspend fun countByStartedAt(startedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: FocusSession): Long

    @Update
    suspend fun update(session: FocusSession)

    @Delete
    suspend fun delete(session: FocusSession)

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM focus_sessions")
    suspend fun clearAll()
}
