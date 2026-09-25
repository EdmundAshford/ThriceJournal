package cn.sanxing.thrice.data.domain.usecase

import cn.sanxing.thrice.data.data.repository.CourseRepository
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.parser.ImportScheduleUseCase as ParserImportUseCase
import cn.sanxing.thrice.parser.model.ParseResult
import javax.inject.Inject

/** 导入结果：已保存课程 + 告警 + 冲突数。 */
data class ImportResult(
    val courses: List<Course>,
    val warnings: List<String>,
    val conflictCount: Int
)

/**
 * 导入管线（数据层入口）：解析（走 parser 的 [ParserImportUseCase]）→ 映射 → 落库。
 * 供阶段 3 UI 在"预览映射 / 冲突检查 / 确认导入"流程里调用。
 */
class ImportScheduleUseCase @Inject constructor(
    private val parser: ParserImportUseCase,
    private val courseRepository: CourseRepository
) {

    suspend fun importText(text: String, termId: Long): ImportResult {
        val result = parser.importText(text)
        return persist(result, termId)
    }

    suspend fun importCsv(text: String, termId: Long): ImportResult {
        val result = parser.importCsv(text)
        return persist(result, termId)
    }

    suspend fun importPdf(bytes: ByteArray, termId: Long): ImportResult {
        val result = parser.importPdf(bytes)
        return persist(result, termId)
    }

    /** 只解析不落库：导入向导 Step1-3（预览映射 / 冲突检查）用。 */
    fun previewText(text: String): ParseResult = parser.importText(text)

    /** 只解析不落库（CSV 路径，强制路径）。 */
    fun previewCsv(text: String): ParseResult = parser.importCsv(text)

    /** 只解析不落库（Excel .xlsx 路径，内部转 TSV 后走 CSV 解析）。 */
    fun previewExcel(bytes: ByteArray): ParseResult = parser.importExcel(bytes)

    /** 只解析不落库（PDF 路径，抽文本 + 自动判格式）。 */
    fun previewPdf(bytes: ByteArray): ParseResult = parser.importPdf(bytes)

    /** 把（可能经过手动修正的）解析结果落库：导入向导 Step4「确认导入」用。 */
    suspend fun importParsed(result: ParseResult, termId: Long): ImportResult =
        persist(result, termId)

    private suspend fun persist(result: ParseResult, termId: Long): ImportResult {
        val now = System.currentTimeMillis()
        val courses = (result.courses + result.otherCourses).map { CourseMapper.toCourse(it, termId, now) }
        if (courses.isNotEmpty()) {
            courseRepository.insertAll(courses)
        }
        return ImportResult(courses, result.warnings, result.conflicts.size)
    }
}
