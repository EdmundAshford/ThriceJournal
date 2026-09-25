package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 作业（可选实体，建表即可，UI 阶段才用）。 */
@Entity(tableName = "assignments")
data class Assignment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val courseName: String,
    val title: String,
    val dueDate: String,
    val note: String = ""
)
