package cn.sanxing.thrice.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.recurrence.isRecurring
import cn.sanxing.thrice.data.domain.recurrence.toggleCompletionOn
import cn.sanxing.thrice.notification.ReminderScheduler
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.common.formatDurationMinutes
import cn.sanxing.thrice.ui.components.PostcardFrame
import cn.sanxing.thrice.ui.tasks.TaskOccurrence
import cn.sanxing.thrice.ui.tasks.expandOccurrences
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import cn.sanxing.thrice.parser.reminder.ReminderCalculator

private val DATE_FMT = DateTimeFormatter.ofPattern("M.d")

/**
 * 日程页：指定日期的任务（优先）+ 课程，扁平化显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    container: AppContainer,
    onOpenEdit: (Long) -> Unit
) {
    val term by container.termRepository.observeActive().collectAsState(initial = null)
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val t = term
    val dayOfWeek = selectedDate.dayOfWeek.value
    val week = t?.let { UiUtils.weekOf(it.startDate, selectedDate) }

    val todayCourses by (t?.let { container.courseRepository.observeByTermAndDay(it.id, dayOfWeek) }
        ?: flowOf(emptyList())).collectAsState(emptyList())
    val sectionTimes by (t?.let { container.sectionTimeDao.observeByTerm(it.id) }
        ?: flowOf(emptyList())).collectAsState(emptyList())
    val tasks by container.taskDao.observeAll().collectAsState(initial = emptyList())
    // 重复任务按规则展开到当天
    val dayOccurrences = tasks.expandOccurrences(selectedDate, selectedDate)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val inWeek = t != null && week != null && week in 1..t.totalWeeks
    val visible = if (inWeek) {
        todayCourses.filter { it.kind == CourseKind.GRID && (week!! in it.weeks || it.overrideDate == selectedDate) }
            .sortedBy { it.startSection }
    } else emptyList()

    val now = LocalDateTime.now()
    val isToday = selectedDate == LocalDate.now()
    val nextCourse = if (isToday) visible.firstOrNull { courseEnd(it, sectionTimes, selectedDate).isAfter(now) } else null

    PostcardFrame(
        title = stringResource(R.string.schedule_title),
        subtitle = t?.let { stringResource(R.string.week_format, week ?: 1) },
        fillHeight = true
    ) {
        // 日期切换栏（紧凑单行）：前后天用图标按钮，避免英文长文案把日期挤成竖排
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { selectedDate = selectedDate.minusDays(1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.schedule_prev_day))
            }
            TextButton(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                onClick = { selectedDate = LocalDate.now() }
            ) {
                Text(
                    stringResource(R.string.week_today),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            IconButton(
                onClick = { selectedDate = selectedDate.plusDays(1) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.schedule_next_day))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = selectedDate.format(DATE_FMT),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(onClick = { showDatePicker = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.schedule_pick_date_cd))
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 下一节课（仅今天显示）
            if (isToday) {
                nextCourse?.let { course ->
                    val start = courseStart(course, sectionTimes, selectedDate)
                    val minutesLeft = Duration.between(now, start).toMinutes().coerceAtLeast(0)
                    SectionTitle(stringResource(R.string.next_course))
                    Text(
                        stringResource(R.string.next_course_in, formatDurationMinutes(minutesLeft), course.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } ?: Text(
                    text = if (visible.isEmpty()) stringResource(R.string.no_courses_today)
                    else stringResource(R.string.today_finished),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                HorizontalDivider()
            }

            // 自定义任务（优先显示，重复任务展开为当天发生）
            SectionTitle(stringResource(R.string.schedule_custom_tasks))
            if (dayOccurrences.isEmpty()) {
                EmptyText(stringResource(R.string.tasks_empty))
            } else {
                dayOccurrences.forEach { occ ->
                    TaskRow(
                        task = occ.task,
                        isCompleted = occ.completed,
                        isRecurring = occ.task.isRecurring,
                        onToggleComplete = {
                            val day = occ.date
                            scope.launch {
                                val updated = if (day != null) occ.task.toggleCompletionOn(day)
                                else occ.task.copy(completed = !occ.completed)
                                container.taskDao.upsert(updated)
                                ReminderScheduler.rescheduleTasks(context)
                            }
                        }
                    )
                }
            }

            HorizontalDivider()

            // 课程列表
            SectionTitle(if (isToday) stringResource(R.string.today_courses) else stringResource(R.string.schedule_day_courses))
            if (visible.isEmpty()) {
                EmptyText(stringResource(R.string.no_courses_today))
            } else {
                visible.forEach { course ->
                    TodayCourseRow(course, sectionTimes) { onOpenEdit(course.id) }
                }
            }
        }
    }

    // 日期选择器弹窗
    if (showDatePicker) {
        // DatePicker 以 UTC 零点归一 millis，初值与回读都按 UTC，避免东八区错位一天
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

@Composable
private fun TodayCourseRow(
    course: Course,
    sectionTimes: List<cn.sanxing.thrice.data.domain.model.SectionTime>,
    onClick: () -> Unit
) {
    val color = UiUtils.courseColor(course)
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = 0.75f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            UiUtils.sectionTimeText(androidx.compose.ui.platform.LocalContext.current, sectionTimes, course.startSection, course.endSection),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(course.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            val sub = listOfNotNull(
                course.location.takeIf { it.isNotBlank() },
                course.teacher.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

private fun courseStart(course: Course, sectionTimes: List<cn.sanxing.thrice.data.domain.model.SectionTime>, date: LocalDate): LocalDateTime {
    val d = course.overrideDate ?: date
    val hm = sectionTimes.firstOrNull { it.sectionIndex == course.startSection }?.startTime
    // 节次时间由用户手填 / 备份导入，可能不是严格 "HH:mm"（如 "8:00"）；
    // 裸 substring+toInt 会分别抛越界与格式异常，这里统一走容错解析。
    return d.atTime(ReminderCalculator.parseTime(hm) ?: LocalTime.of(8, 0))
}

private fun courseEnd(course: Course, sectionTimes: List<cn.sanxing.thrice.data.domain.model.SectionTime>, date: LocalDate): LocalDateTime {
    val d = course.overrideDate ?: date
    val hm = sectionTimes.firstOrNull { it.sectionIndex == course.endSection }?.endTime
    return d.atTime(ReminderCalculator.parseTime(hm) ?: LocalTime.of(8, 45))
}

@Composable
private fun TaskRow(
    task: Task,
    isCompleted: Boolean,
    isRecurring: Boolean,
    onToggleComplete: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isCompleted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            )
            .border(
                1.dp,
                if (isCompleted) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                shape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isCompleted,
            onCheckedChange = { onToggleComplete() }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "[${task.type}]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        if (isRecurring) {
            Icon(
                Icons.Filled.Repeat,
                contentDescription = stringResource(R.string.cd_repeat_icon),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else null
            )
            if (task.description.isNotBlank()) {
                Text(
                    task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else null
                )
            }
            val timeText = listOfNotNull(task.startTime, task.endTime).joinToString(" - ")
            if (timeText.isNotBlank()) {
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}
