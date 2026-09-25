package cn.sanxing.thrice.data.data.local

import cn.sanxing.thrice.data.domain.model.SectionTime
import java.time.LocalDate

/**
 * 预置数据：2026-2027-1 学期 + 12 条小节时间（可在设置里增删 / 调整）。
 */
object SeedData {

    const val DEFAULT_TERM_NAME = "2026-2027学年第1学期"

    /** 第 1 周周一 = 2026-08-31（用户确认的真实基准）。 */
    val DEFAULT_START_DATE: LocalDate = LocalDate.of(2026, 8, 31)

    const val DEFAULT_TOTAL_WEEKS = 20

    /** 12 条小节时间：sectionIndex 1..12，每节 45 分钟，节间休息 10 分钟。 */
    fun defaultSectionTimes(termId: Long): List<SectionTime> = listOf(
        SectionTime(termId = termId, sectionIndex = 1, startTime = "08:00", endTime = "08:45"),
        SectionTime(termId = termId, sectionIndex = 2, startTime = "08:55", endTime = "09:40"),
        SectionTime(termId = termId, sectionIndex = 3, startTime = "10:10", endTime = "10:55"),
        SectionTime(termId = termId, sectionIndex = 4, startTime = "11:05", endTime = "11:50"),
        SectionTime(termId = termId, sectionIndex = 5, startTime = "14:00", endTime = "14:45"),
        SectionTime(termId = termId, sectionIndex = 6, startTime = "14:55", endTime = "15:40"),
        SectionTime(termId = termId, sectionIndex = 7, startTime = "16:10", endTime = "16:55"),
        SectionTime(termId = termId, sectionIndex = 8, startTime = "17:05", endTime = "17:50"),
        SectionTime(termId = termId, sectionIndex = 9, startTime = "19:00", endTime = "19:45"),
        SectionTime(termId = termId, sectionIndex = 10, startTime = "19:55", endTime = "20:40"),
        SectionTime(termId = termId, sectionIndex = 11, startTime = "20:50", endTime = "21:35"),
        SectionTime(termId = termId, sectionIndex = 12, startTime = "21:45", endTime = "22:30")
    )
}
