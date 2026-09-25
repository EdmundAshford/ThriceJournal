package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<Course>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: Course): Long

    @Update
    suspend fun update(course: Course)

    @Delete
    suspend fun delete(course: Course)

    @Query("DELETE FROM courses WHERE termId = :termId")
    suspend fun deleteByTerm(termId: Long)

    @Query("SELECT * FROM courses WHERE termId = :termId ORDER BY dayOfWeek, startSection")
    fun observeByTerm(termId: Long): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE termId = :termId AND dayOfWeek = :day ORDER BY startSection")
    fun observeByTermAndDay(termId: Long, day: Int): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE name LIKE '%' || :name || '%'")
    fun observeByName(name: String): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE termId = :termId AND kind = :kind ORDER BY name")
    fun observeByKind(termId: Long, kind: CourseKind): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE termId = :termId")
    suspend fun getByTerm(termId: Long): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun get(id: Long): Course?

    @Query("SELECT * FROM courses")
    suspend fun getAll(): List<Course>

    @Query("DELETE FROM courses")
    suspend fun clearAll()
}
