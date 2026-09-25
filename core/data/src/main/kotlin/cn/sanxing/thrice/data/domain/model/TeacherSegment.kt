package cn.sanxing.thrice.data.domain.model

import kotlinx.serialization.Serializable

/** 教师 + 周次区间片段（一个格子多老师时保留分段信息）。 */
@Serializable
data class TeacherSegment(
    val teacher: String,
    val weeks: Set<Int>,
    val weeksRaw: String = ""
)
