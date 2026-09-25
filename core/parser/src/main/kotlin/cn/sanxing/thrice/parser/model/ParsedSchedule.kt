package cn.sanxing.thrice.parser.model

import kotlinx.serialization.Serializable

/**
 * 导入格式枚举。格式 B 的分页文本与 PDF 都归入 [FORMAT_B]。
 */
@Serializable
enum class ScheduleFormat {
    FORMAT_A,
    FORMAT_B,
    CSV,
    UNKNOWN
}

/**
 * 课程类别：
 * - [GRID]  网格课程（有星期 / 节次，会显示在周课表格子里）
 * - [OTHER] 「其他课程」（无固定星期 / 节次，用哨兵值 0 表示，另设入口查看）
 */
@Serializable
enum class CourseKind {
    GRID,
    OTHER
}

/**
 * 教师 + 周次区间片段。一个格子里同一门课可能由多个老师分段承担
 * （如 计导 周一 7-8：陈安 7-10 / 周文 11 / 吴理 12），合并时保留这些分段。
 */
@Serializable
data class TeacherSegment(
    val teacher: String,
    val weeks: Set<Int>,
    val weeksRaw: String = ""
)

/**
 * 解析得到的单条课程（合并前的"原始条目"与合并后的课程都用它表示）。
 */
@Serializable
data class ParsedCourse(
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val campus: String = "",
    val credit: Double? = null,
    val category: String? = null,
    val colorHex: String = "",
    val weeks: Set<Int> = emptySet(),
    val dayOfWeek: Int = 0,          // 1..7；其他课程为 0
    val startSection: Int = 0,       // 其他课程为 0
    val endSection: Int = 0,         // 其他课程为 0
    val remark: String = "",
    val assessment: String = "",     // 考核方式；"未安排"原样保留
    val classGroup: String = "",     // 教学班
    val classMembers: String = "",   // 教学班组成
    val hoursBreakdown: String = "", // 课程学时组成
    val weeklyHours: Int? = null,
    val totalHours: Int? = null,
    val kind: CourseKind = CourseKind.GRID,
    val teacherSegments: List<TeacherSegment> = emptyList(),
    val weeksRaw: String = "",       // 原始周次串，便于回显与纠错
    val rawLine: String = ""         // 该条目的原始文本片段（映射报告用）
)

/**
 * 冲突：同一天、节次区间有重叠、周次有交集。
 */
@Serializable
data class Conflict(
    val courseA: String,
    val courseB: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val overlappingWeeks: Set<Int>
)

/**
 * 字段映射报告条目：一条原始输入 → 抽取到的字段（供"预览映射"页展示）。
 * @param courseIndex 该条对应 ParseResult.rawCourses 的下标；未产生课程的跳过行 = -1
 *                    （映射报告行与 rawCourses 不再默认同构，必须用此值定位）。
 */
@Serializable
data class MappingEntry(
    val rawLine: String,
    val fields: Map<String, String>,
    val courseIndex: Int = -1
)

/**
 * 解析结果四段式之一：格式判定结果。
 */
@Serializable
data class FormatDetection(
    val format: ScheduleFormat,
    val confidence: Double,
    val reason: String
)

/**
 * 解析结果汇总。
 * @param courses      合并后的网格课程
 * @param rawCourses   合并前的原始网格条目（格式A 18 条）
 * @param otherCourses 「其他课程」（2 条）
 */
@Serializable
data class ParseResult(
    val courses: List<ParsedCourse>,
    val rawCourses: List<ParsedCourse> = emptyList(),
    val otherCourses: List<ParsedCourse> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    val warnings: List<String> = emptyList(),
    val format: ScheduleFormat = ScheduleFormat.UNKNOWN,
    val rawText: String = "",
    val mappingReport: List<MappingEntry> = emptyList()
)
