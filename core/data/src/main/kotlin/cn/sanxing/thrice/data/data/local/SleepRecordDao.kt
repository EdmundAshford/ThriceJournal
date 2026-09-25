package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.SleepRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepRecordDao {
    @Query("SELECT * FROM sleep_records ORDER BY sleepAtEpochMs DESC")
    fun observeAll(): Flow<List<SleepRecord>>

    /** 按入睡时刻落在闭区间 [start, end] 查询。 */
    @Query("SELECT * FROM sleep_records WHERE sleepAtEpochMs BETWEEN :start AND :end ORDER BY sleepAtEpochMs ASC")
    fun observeRange(start: Long, end: Long): Flow<List<SleepRecord>>

    @Query("SELECT * FROM sleep_records WHERE sleepAtEpochMs BETWEEN :start AND :end ORDER BY sleepAtEpochMs ASC")
    suspend fun getRange(start: Long, end: Long): List<SleepRecord>

    @Query("SELECT * FROM sleep_records ORDER BY sleepAtEpochMs DESC")
    suspend fun getAll(): List<SleepRecord>

    /** 按主键取单条；不存在返回 null。用于「先确认存在再删除」，避免为删一条而物化整表。 */
    @Query("SELECT * FROM sleep_records WHERE id = :id")
    suspend fun getById(id: Long): SleepRecord?

    /** 未闭合记录（进行中，杀进程后恢复用）。 */
    @Query("SELECT * FROM sleep_records WHERE wakeAtEpochMs IS NULL ORDER BY sleepAtEpochMs DESC")
    fun observeOpen(): Flow<List<SleepRecord>>

    @Query("SELECT * FROM sleep_records WHERE wakeAtEpochMs IS NULL AND kind = :kind LIMIT 1")
    suspend fun findOpen(kind: String): SleepRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SleepRecord): Long

    @Update
    suspend fun update(record: SleepRecord)

    @Delete
    suspend fun delete(record: SleepRecord)

    @Query("DELETE FROM sleep_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sleep_records")
    suspend fun clearAll()
}
