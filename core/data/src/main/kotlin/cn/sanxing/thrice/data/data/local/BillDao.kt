package cn.sanxing.thrice.data.data.local

import androidx.room.*
import cn.sanxing.thrice.data.domain.model.Bill
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<Bill>>

    @Query("SELECT * FROM bills")
    suspend fun getAll(): List<Bill>

    /** 按主键取单条；不存在返回 null。用于「先确认存在再删除」，避免为删一条而物化整表。 */
    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): Bill?

    @Query("SELECT * FROM bills WHERE date BETWEEN :from AND :to ORDER BY date DESC, createdAt DESC")
    fun observeBetween(from: String, to: String): Flow<List<Bill>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bill: Bill): Long

    @Delete
    suspend fun delete(bill: Bill)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 分类重命名时同步账单（在同一事务里配合 BillCategoryDao.rename 使用）。 */
    @Query("UPDATE bills SET category = :newName WHERE category = :oldName")
    suspend fun reparentCategory(oldName: String, newName: String)

    @Query("DELETE FROM bills")
    suspend fun clearAll()
}
