package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 睡眠类型：夜间睡眠 / 午休。 */
enum class SleepKind { NIGHT, NAP }

/**
 * 一条睡眠记录。
 *
 * 进行中的记录 [wakeAtEpochMs] / [minutes] 为 null（杀进程后据此恢复）；
 * 夜睡跨天时按入睡时刻所在自然日归属。
 */
@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = SleepKind.NIGHT.name,
    val sleepAtEpochMs: Long,
    val wakeAtEpochMs: Long? = null,
    val minutes: Int? = null,
    val note: String = ""
)
