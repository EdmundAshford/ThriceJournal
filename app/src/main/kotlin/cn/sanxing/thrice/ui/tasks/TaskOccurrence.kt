package cn.sanxing.thrice.ui.tasks

import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.recurrence.isCompletedOn
import cn.sanxing.thrice.data.domain.recurrence.occurrencesInRange
import java.time.LocalDate

/**
 * 任务在某一天的一次「发生」：无规则任务是其锚点日期；重复任务按规则展开出多个实例。
 * [date] 为 null 表示无日期任务。完成态随 [date] 变化（分次完成）。
 */
data class TaskOccurrence(
    val task: Task,
    val date: LocalDate?
) {
    val completed: Boolean
        get() = if (date != null) task.isCompletedOn(date) else task.completed

    val dateStr: String? get() = date?.toString()
}

/** 把任务列表展开为闭区间 [[from], [to]] 内的全部发生（无日期任务不参与）。 */
fun List<Task>.expandOccurrences(from: LocalDate, to: LocalDate): List<TaskOccurrence> =
    flatMap { task ->
        task.occurrencesInRange(from, to).map { day -> TaskOccurrence(task, day) }
    }
