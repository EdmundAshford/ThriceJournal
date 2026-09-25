package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 节次时间表。sectionIndex 为「小节」序号（1..N，数量由用户在设置中调整），
 * 课程的 startSection / endSection 直接引用小节序号；相邻小节的同一门课
 * 是否连堂由课表界面按课程数据判断展示合并。
 */
@Entity(tableName = "section_times")
data class SectionTime(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val termId: Long,
    val sectionIndex: Int,
    val startTime: String,
    val endTime: String
)
