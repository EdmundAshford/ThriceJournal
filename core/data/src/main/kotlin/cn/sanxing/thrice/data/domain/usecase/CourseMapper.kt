package cn.sanxing.thrice.data.domain.usecase

import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.TeacherSegment
import cn.sanxing.thrice.parser.model.ParsedCourse

/** 解析器模型 → 数据层 Course 的映射。 */
object CourseMapper {

    fun toCourse(p: ParsedCourse, termId: Long, now: Long = System.currentTimeMillis()): Course = Course(
        termId = termId,
        name = p.name,
        teacher = p.teacher,
        location = p.location,
        campus = p.campus,
        classGroup = p.classGroup,
        classMembers = p.classMembers,
        assessment = p.assessment,
        remark = p.remark,
        hoursBreakdown = p.hoursBreakdown,
        weeklyHours = p.weeklyHours,
        totalHours = p.totalHours,
        credit = p.credit,
        category = p.category,
        colorHex = p.colorHex,
        weeks = p.weeks,
        dayOfWeek = p.dayOfWeek,
        startSection = p.startSection,
        endSection = p.endSection,
        note = "",
        kind = if (p.kind == cn.sanxing.thrice.parser.model.CourseKind.OTHER) CourseKind.OTHER else CourseKind.GRID,
        teacherSegments = p.teacherSegments.map { TeacherSegment(it.teacher, it.weeks, it.weeksRaw) },
        weeksRaw = p.weeksRaw,
        createdAt = now,
        updatedAt = now
    )
}
