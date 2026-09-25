package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/** 学期。 */
@Entity(tableName = "terms")
data class Term(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startDate: LocalDate,
    val totalWeeks: Int,
    val isActive: Boolean
)
