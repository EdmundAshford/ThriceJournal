package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 专注标签（计时模块独立标签系统，与任务标签互不影响）。 */
@Entity(tableName = "focus_tags")
data class FocusTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB 颜色。 */
    val colorArgb: Int = 0xFF2E6DA4.toInt(),
    val sortOrder: Int = 0
)

/** 专注模式：正计时 / 倒计时。 */
enum class FocusMode { STOPWATCH, COUNTDOWN }

/** 会话结果状态。 */
enum class FocusStatus {
    /** 正常完成（倒计时到点，或正计时手动结束）。 */
    COMPLETED,
    /** 离开策略判定失败。 */
    FAILED,
    /** 用户主动放弃（不计入有效专注统计，但保留记录）。 */
    ABANDONED
}

/**
 * 一次专注会话（结束时落库；进行中的状态由 FocusController 进程内持有）。
 *
 * @param plannedSeconds 倒计时目标秒；正计时为 0。
 * @param startedAtEpochMs 开始时刻（墙上时间，用于展示归属日期）。
 * @param endedAtEpochMs 结束时刻；未落库进行中场景为 null。
 * @param focusedSeconds 实际专注秒数（扣除暂停）。
 */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long? = null,
    val mode: String = FocusMode.STOPWATCH.name,
    val plannedSeconds: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val focusedSeconds: Long = 0,
    val status: String = FocusStatus.COMPLETED.name,
    val createdAt: Long = System.currentTimeMillis()
)
