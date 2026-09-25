package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cn.sanxing.thrice.data.domain.model.Term
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    @Insert
    suspend fun insert(term: Term): Long

    @Update
    suspend fun update(term: Term)

    @Delete
    suspend fun delete(term: Term)

    @Query("SELECT * FROM terms ORDER BY isActive DESC, id DESC")
    fun observeAll(): Flow<List<Term>>

    @Query("SELECT * FROM terms")
    suspend fun getAll(): List<Term>

    @Query("SELECT * FROM terms WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    fun observeActive(): Flow<Term?>

    @Query("SELECT * FROM terms WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): Term?

    @Query("SELECT * FROM terms WHERE id = :id")
    suspend fun get(id: Long): Term?

    @Query("UPDATE terms SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE terms SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)
}
