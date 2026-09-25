package cn.sanxing.thrice.data.data.local

import androidx.room.*
import cn.sanxing.thrice.data.domain.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY date ASC, startTime ASC")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE date = :date ORDER BY startTime ASC")
    fun observeByDate(date: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE date IS NULL ORDER BY createdAt DESC")
    fun observeWithoutDate(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): Task?

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: Task): Long

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks")
    suspend fun clearAll()
}
