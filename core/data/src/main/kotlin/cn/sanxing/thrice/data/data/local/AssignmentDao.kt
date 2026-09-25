package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Assignment
import kotlinx.coroutines.flow.Flow

@Dao
interface AssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(assignment: Assignment): Long

    @Update
    suspend fun update(assignment: Assignment)

    @Query("DELETE FROM assignments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM assignments WHERE termId = :termId")
    suspend fun deleteByTerm(termId: Long)

    @Query("SELECT * FROM assignments WHERE termId = :termId ORDER BY dueDate")
    fun observeByTerm(termId: Long): Flow<List<Assignment>>

    @Query("SELECT * FROM assignments ORDER BY termId, dueDate")
    suspend fun getAll(): List<Assignment>

    @Query("DELETE FROM assignments")
    suspend fun clearAll()
}
