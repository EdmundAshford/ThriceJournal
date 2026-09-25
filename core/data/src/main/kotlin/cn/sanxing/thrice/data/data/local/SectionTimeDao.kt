package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.sanxing.thrice.data.domain.model.SectionTime
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionTimeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SectionTime>)

    @Query("SELECT * FROM section_times WHERE termId = :termId ORDER BY sectionIndex")
    fun observeByTerm(termId: Long): Flow<List<SectionTime>>

    @Query("SELECT * FROM section_times WHERE termId = :termId ORDER BY sectionIndex")
    suspend fun getByTerm(termId: Long): List<SectionTime>

    @Query("DELETE FROM section_times WHERE termId = :termId")
    suspend fun deleteByTerm(termId: Long)

    /**
     * 整体替换某学期的节次时间。
     *
     * 必须是**单个事务**：分开调用 delete + insert 时两条语句各自提交，
     * 一旦 insert 失败（磁盘满、约束冲突、进程被杀），该学期的节次已被清空
     * 且无法回滚 —— 课表时间会彻底丢失。
     */
    @Transaction
    suspend fun replaceForTerm(termId: Long, items: List<SectionTime>) {
        deleteByTerm(termId)
        insertAll(items)
    }

    @Query("SELECT * FROM section_times ORDER BY termId, sectionIndex")
    suspend fun getAll(): List<SectionTime>

    @Query("DELETE FROM section_times")
    suspend fun clearAll()
}
