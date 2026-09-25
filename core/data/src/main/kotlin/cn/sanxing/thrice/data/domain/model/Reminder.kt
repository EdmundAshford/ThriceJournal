package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 课程提醒设置（每门课可配置提前提醒分钟数）。 */
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val minutesBefore: Int,
    val enabled: Boolean
)
