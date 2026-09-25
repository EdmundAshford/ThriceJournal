package cn.sanxing.thrice.data.data.local

import androidx.room.TypeConverter
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.TeacherSegment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Room 类型转换器：LocalDate / Set<Int>（JSON）/ CourseKind / List<TeacherSegment>（JSON）。
 *
 * 反序列化一律容错：备份导入、手工改库或降级数据里一旦出现非法值，
 * 抛异常会让整条列表 Flow 直接崩溃且无法恢复（用户只能清数据）。
 * 因此非法值统一回落到「空 / 默认」而不是向上抛。
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun localDateToString(date: LocalDate): String = date.toString()

    @TypeConverter
    fun stringToLocalDate(s: String): LocalDate =
        runCatching { LocalDate.parse(s) }.getOrDefault(LocalDate.of(1970, 1, 1))

    @TypeConverter
    fun weeksToString(weeks: Set<Int>): String = "[${weeks.sorted().joinToString(",")}]"

    @TypeConverter
    fun stringToWeeks(s: String): Set<Int> {
        if (s.isBlank()) return emptySet()
        // 兼容 JSON 数组 [1,2,3] 和旧包裹标记 [[1,2,3]]
        val cleaned = s.trim().removePrefix("[[").removeSuffix("]]").removePrefix("[").removeSuffix("]")
        return cleaned.split(",")
            .map { it.trim() }
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    @TypeConverter
    fun kindToString(kind: CourseKind): String = kind.name

    /** 未知 / 空值回落到 GRID：宁可让课程显示在网格里，也不要让读取整表崩溃。 */
    @TypeConverter
    fun stringToKind(s: String): CourseKind =
        runCatching { CourseKind.valueOf(s.trim().uppercase()) }.getOrDefault(CourseKind.GRID)

    @TypeConverter
    fun segmentsToString(segments: List<TeacherSegment>): String = json.encodeToString(segments)

    @TypeConverter
    fun stringToSegments(s: String): List<TeacherSegment> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<TeacherSegment>>(s) }.getOrDefault(emptyList())

    @TypeConverter
    fun billTypeToString(type: BillType): String = type.name

    @TypeConverter
    fun stringToBillType(s: String): BillType = runCatching { BillType.valueOf(s) }.getOrDefault(BillType.EXPENSE)
}
