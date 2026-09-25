package cn.sanxing.thrice.data.data.local

import androidx.room.*
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import kotlinx.coroutines.flow.Flow

@Dao
interface BillCategoryDao {
    @Query("SELECT * FROM bill_categories ORDER BY type ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<BillCategory>>

    @Query("SELECT * FROM bill_categories WHERE type = :type ORDER BY sortOrder ASC, name ASC")
    fun observeByType(type: BillType): Flow<List<BillCategory>>

    @Query("SELECT * FROM bill_categories ORDER BY type ASC, sortOrder ASC, name ASC")
    suspend fun getAll(): List<BillCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: BillCategory)

    @Delete
    suspend fun delete(category: BillCategory)

    @Query("DELETE FROM bill_categories WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("UPDATE bill_categories SET name = :newName WHERE name = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("DELETE FROM bill_categories")
    suspend fun clearAll()
}
