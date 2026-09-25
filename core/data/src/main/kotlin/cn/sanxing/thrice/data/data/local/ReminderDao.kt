package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE courseId = :courseId")
    fun observeByCourse(courseId: Long): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    fun observeEnabled(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE courseId = :courseId")
    suspend fun getByCourse(courseId: Long): Reminder?

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun getEnabled(): List<Reminder>

    @Query("DELETE FROM reminders")
    suspend fun clearAll()

    /** 删除某学期所有课程关联的提醒（子查询，须在删除课程之前调用）。 */
    @Query("DELETE FROM reminders WHERE courseId IN (SELECT id FROM courses WHERE termId = :termId)")
    suspend fun deleteByTerm(termId: Long)
}
