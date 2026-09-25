package cn.sanxing.thrice.parser.merge

import cn.sanxing.thrice.parser.model.CourseKind
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.TeacherSegment

/**
 * 课程合并：同 `课程名 + 星期 + 节次` → 周次取并集。
 *
 * 关键行为：
 * - 教师按周次区间保留成 [TeacherSegment]（一个格子多老师时不丢失分段信息）；
 * - 地点 / 教师不同**不阻止**合并，但会在 warnings 里记录「同格多教师 / 多地点」；
 * - 「其他课程」（kind == OTHER）不参与网格合并。
 */
object CourseMerger {

    /** @param courses 合并后的课程列表（网格课程在前，其他课程在后） @param warnings 合并过程产生的提示 */
    data class MergeResult(val courses: List<ParsedCourse>, val warnings: List<String>)

    fun merge(courses: List<ParsedCourse>): MergeResult {
        val warnings = mutableListOf<String>()
        val others = courses.filter { it.kind == CourseKind.OTHER }
        // 星期**与**节次都没识别出来的课不参与合并：它们的键会退化成 `名字|0|0|0`，
        // 两门「同名但排课完全未知」的课会被错误地吞成一门。
        // 只缺星期（格式 B 纯文本解析即如此，dayOfWeek 恒为 0）仍要按 名字+节次 正常合并。
        val grid = courses.filter { it.kind == CourseKind.GRID }
        val (mergeable, unmergeable) = grid.partition { it.dayOfWeek in 1..7 || it.startSection > 0 }

        val groups = LinkedHashMap<String, MutableList<ParsedCourse>>()
        for (c in mergeable) {
            val key = "${c.name}|${c.dayOfWeek}|${c.startSection}|${c.endSection}"
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }

        val merged = unmergeable.toMutableList<ParsedCourse>()
        for ((_, list) in groups) {
            if (list.size == 1) {
                merged.add(list[0])
                continue
            }
            val base = list[0]
            val weeks = sortedSetOf<Int>()
            val segments = mutableListOf<TeacherSegment>()
            val teachers = mutableListOf<String>()
            val locations = mutableListOf<String>()
            for (c in list) {
                weeks.addAll(c.weeks)
                // 无教师的条目不产出空分段，否则课表上会多出一条空白教师行
                if (c.teacher.isNotEmpty()) segments.add(TeacherSegment(c.teacher, c.weeks, c.weeksRaw))
                if (c.teacher.isNotEmpty() && c.teacher !in teachers) teachers.add(c.teacher)
                if (c.location.isNotEmpty() && c.location !in locations) locations.add(c.location)
            }
            if (teachers.size > 1) {
                warnings.add("同格多教师：${base.name} 星期${base.dayOfWeek} 第${base.startSection}-${base.endSection}节 → ${teachers.joinToString("/")}")
            }
            if (locations.size > 1) {
                warnings.add("同格多地点：${base.name} → ${locations.joinToString("→")}")
            }
            merged.add(
                base.copy(
                    weeks = weeks.toSet(),
                    teacher = teachers.joinToString("/"),
                    location = locations.joinToString("→"),
                    teacherSegments = segments,
                    weeksRaw = list.map { it.weeksRaw }.filter { it.isNotBlank() }.joinToString(";")
                )
            )
        }
        merged.addAll(others)
        return MergeResult(merged, warnings)
    }
}
