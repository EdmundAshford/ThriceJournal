package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 考试（可选实体，建表即可，UI 阶段才用）。 */
@Entity(tableName = "exams")
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val courseName: String,
    val date: String,
    val time: String,
    val location: String = "",
    val note: String = ""
)
