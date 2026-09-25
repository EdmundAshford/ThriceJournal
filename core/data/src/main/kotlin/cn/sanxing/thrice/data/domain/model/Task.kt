package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 自定义任务（支持自定义类型） */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val type: String = "其他",  // 改为字符串，支持自定义类型
    val date: String? = null,  // yyyy-MM-dd；无规则时为任务日期，有重复规则时为开始/锚点日期，null 表示无指定日期
    val startTime: String? = null,  // HH:mm
    val endTime: String? = null,
    val completed: Boolean = false,
    /** 提前提醒分钟数；null = 不提醒。 */
    val reminderMinutes: Int? = null,
    /**
     * 重复规则 JSON（见 TaskRecurrence）；null = 不重复（旧任务行为不变）。
     */
    val ruleJson: String? = null,
    /**
     * 重复任务按次完成记录：逗号分隔的 yyyy-MM-dd（每个发生日独立勾选）。
     * 无规则任务仍使用 [completed]。
     */
    val completedDates: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 任务类型实体（用户可自定义） */
@Entity(tableName = "task_types")
data class TaskType(
    @PrimaryKey val name: String,
    val colorHex: String = "#2196F3",
    val sortOrder: Int = 0
)

/**
 * 任务标签（多对多，与 [Task.type] 单分类并存、互不影响）。
 *
 * 名称全局唯一（数据库唯一索引兜底）；颜色沿用任务体系的 hex 字符串。
 * 不建外键，关联行的清理在 [cn.sanxing.thrice.data.data.local.TaskTagDao]
 * 的事务方法内显式完成。
 */
@Entity(tableName = "task_tags", indices = [Index(value = ["name"], unique = true)])
data class TaskTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#7E57C2",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 任务-标签多对多关联表（复合主键，无外键）。
 * 两个方向各建普通索引。
 */
@Entity(
    tableName = "task_tag_cross_ref",
    primaryKeys = ["taskId", "tagId"],
    indices = [Index("tagId"), Index("taskId")]
)
data class TaskTagCrossRef(
    val taskId: Long,
    val tagId: Long
)
