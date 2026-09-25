package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Exam
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exam: Exam): Long

    @Update
    suspend fun update(exam: Exam)

    @Query("DELETE FROM exams WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM exams WHERE termId = :termId")
    suspend fun deleteByTerm(termId: Long)

    @Query("SELECT * FROM exams WHERE termId = :termId ORDER BY date")
    fun observeByTerm(termId: Long): Flow<List<Exam>>

    @Query("SELECT * FROM exams ORDER BY termId, date")
    suspend fun getAll(): List<Exam>

    @Query("DELETE FROM exams")
    suspend fun clearAll()
}
