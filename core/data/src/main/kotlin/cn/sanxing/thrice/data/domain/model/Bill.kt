package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 账单类型：支出 / 收入。数据库存枚举名（Converters）。 */
enum class BillType { EXPENSE, INCOME }

/**
 * 一笔账单。
 * @param amount 金额，恒为正数；收支方向由 [type] 表示
 * @param category 分类名（关联 [BillCategory.name]）
 * @param date yyyy-MM-dd（账单归属日）
 * @param note 备注
 */
@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: BillType = BillType.EXPENSE,
    val amount: Double,
    val category: String,
    val date: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 账单分类（用户可自定义增删改）。按 [BillType] 区分支出/收入两套。
 */
@Entity(tableName = "bill_categories")
data class BillCategory(
    @PrimaryKey val name: String,
    val type: BillType = BillType.EXPENSE,
    val colorHex: String = "#FF7043",
    val sortOrder: Int = 0
)
