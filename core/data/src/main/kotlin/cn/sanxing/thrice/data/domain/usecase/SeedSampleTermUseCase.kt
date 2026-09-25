package cn.sanxing.thrice.data.domain.usecase

import cn.sanxing.thrice.data.data.local.SectionTimeDao
import cn.sanxing.thrice.data.data.local.SeedData
import cn.sanxing.thrice.data.data.repository.TermRepository
import cn.sanxing.thrice.data.domain.model.Term
import javax.inject.Inject

/** 首次启动预置：2026-2027-1 学期 + 6 条节次时间（幂等）。 */
class SeedSampleTermUseCase @Inject constructor(
    private val termRepository: TermRepository,
    private val sectionTimeDao: SectionTimeDao
) {

    /** 返回激活学期 id（已存在则直接返回）。 */
    suspend operator fun invoke(): Long {
        val existing = termRepository.getActive()
        if (existing != null) return existing.id
        val termId = termRepository.insert(
            Term(
                name = SeedData.DEFAULT_TERM_NAME,
                startDate = SeedData.DEFAULT_START_DATE,
                totalWeeks = SeedData.DEFAULT_TOTAL_WEEKS,
                isActive = true
            )
        )
        sectionTimeDao.insertAll(SeedData.defaultSectionTimes(termId))
        return termId
    }
}
