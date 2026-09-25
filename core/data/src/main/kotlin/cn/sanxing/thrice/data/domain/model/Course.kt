package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 课程。周次用 [Set]（Room 存 JSON），[TeacherSegment] 存 JSON，[kind] 存枚举名。
 *
 * 其他课程（无固定星期 / 节次）用哨兵值：dayOfWeek=0、startSection=0、endSection=0。
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val campus: String = "",
    val classGroup: String = "",
    val classMembers: String = "",
    val assessment: String = "",
    val remark: String = "",
    val hoursBreakdown: String = "",
    val weeklyHours: Int? = null,
    val totalHours: Int? = null,
    val credit: Double? = null,
    val category: String? = null,
    val colorHex: String = "",
    val weeks: Set<Int> = emptySet(),
    val dayOfWeek: Int = 0,      // 1..7；其他课程为 0
    val startSection: Int = 0,
    val endSection: Int = 0,
    val note: String = "",
    val kind: CourseKind = CourseKind.GRID,
    val teacherSegments: List<TeacherSegment> = emptyList(),
    val weeksRaw: String = "",
    /** 调课/补课：把这一次课钉到具体日期（为 null 时按周次规律）。 */
    val overrideDate: LocalDate? = null,
    /** 例外说明：停课/临时地点/补课原因等。 */
    val overrideNote: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
