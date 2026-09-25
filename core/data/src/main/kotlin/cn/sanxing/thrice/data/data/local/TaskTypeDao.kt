package cn.sanxing.thrice.data.data.local

import androidx.room.*
import cn.sanxing.thrice.data.domain.model.TaskType
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskTypeDao {
    @Query("SELECT * FROM task_types ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<TaskType>>

    @Query("SELECT * FROM task_types ORDER BY sortOrder ASC")
    suspend fun getAll(): List<TaskType>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(type: TaskType)

    @Delete
    suspend fun delete(type: TaskType)

    @Query("DELETE FROM task_types WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM task_types")
    suspend fun clearAll()
}
