package cn.sanxing.thrice.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import cn.sanxing.thrice.ui.theme.LocalAppFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.theme.LocalExtendedColors

/**
 * 课程详情底部弹窗（用户点名的重点：每门课都能看详细信息）。
 *
 * 全部字段"有值显示值、无值显示 —"，空行不消失；
 * 课程名与章节标题带几何线条装饰；提供编辑 / 改色 / 删除 / 设提醒入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    sectionTimes: List<SectionTime>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
    onSetReminder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            DetailHeader(course)
            Spacer(Modifier.height(10.dp))

            // 课程类别（其他课程有明显标识）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.detail_kind),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = LocalAppFontFamily.current,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                if (course.kind == CourseKind.OTHER) {
                    OtherCourseBadge()
                } else {
                    Text(
                        text = stringResource(R.string.grid_course_badge),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(8.dp))

            DetailRow(R.string.field_name, course.name)
            DetailRow(R.string.detail_teacher, course.teacher)
            if (course.teacherSegments.isNotEmpty()) {
                DetailRow(
                    R.string.detail_teacher_segments,
                    UiUtils.formatSegments(course.teacherSegments)
                )
            }
            DetailRow(R.string.detail_location, course.location)
            DetailRow(R.string.detail_campus, course.campus)
            DetailRow(R.string.detail_credit, course.credit?.toString())
            DetailRow(R.string.detail_category, course.category)
            DetailRow(R.string.detail_assessment, course.assessment)
            DetailRow(R.string.detail_class_group, course.classGroup)
            DetailRow(R.string.detail_class_members, course.classMembers)

            // 周次：原始串 + 展开后的周次列表
            DetailRow(R.string.detail_weeks_raw, course.weeksRaw.ifBlank { UiUtils.formatWeeks(course.weeks) })
            if (course.weeks.isNotEmpty()) {
                WeekChips(course.weeks)
            }
            DetailRow(R.string.detail_weekly_hours, course.weeklyHours?.toString())
            DetailRow(R.string.detail_total_hours, course.totalHours?.toString())
            DetailRow(R.string.detail_hours_breakdown, course.hoursBreakdown)
            DetailRow(R.string.detail_remark, course.remark)
            DetailRow(R.string.detail_note, course.note)

            // 节次与时间：第7-8节 15:30-17:10（其他课程无节次）
            val sectionText = if (course.kind == CourseKind.GRID && course.startSection > 0) {
                UiUtils.sectionTimeText(androidx.compose.ui.platform.LocalContext.current, sectionTimes, course.startSection, course.endSection)
            } else null
            DetailRow(R.string.detail_section_time, sectionText)
            if (course.kind == CourseKind.GRID && course.dayOfWeek in 1..7) {
                DetailRow(R.string.field_day, dayText(course.dayOfWeek))
            }

            // 例外（调课 / 停课 / 补课 / 临时地点）
            if (course.overrideDate != null || course.overrideNote.isNotBlank()) {
                DetailRow(
                    R.string.detail_override,
                    listOfNotNull(
                        course.overrideDate?.toString(),
                        course.overrideNote.takeIf { it.isNotBlank() }
                    ).joinToString("：")
                )
            }

            DetailRow(R.string.detail_created, UiUtils.formatEpochMillis(course.createdAt).ifBlank { null })
            DetailRow(R.string.detail_updated, UiUtils.formatEpochMillis(course.updatedAt).ifBlank { null })

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(6.dp))

            // 操作入口
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = { onDismiss(); onEdit() }) {
                    Text(stringResource(R.string.action_edit))
                }
                TextButton(onClick = { onRecolor() }) {
                    Text(stringResource(R.string.menu_recolor))
                }
                TextButton(onClick = { onDismiss(); onSetReminder() }) {
                    Text(stringResource(R.string.detail_set_reminder))
                }
                TextButton(onClick = { onDismiss(); onDelete() }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 弹窗头部：课程名（带几何线条装饰）+ 色块。 */
@Composable
private fun DetailHeader(course: Course) {
    val extended = LocalExtendedColors.current
    val color = UiUtils.courseColor(course)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(6.dp)
                .height(40.dp)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = course.name.ifBlank { stringResource(R.string.empty_dash) },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    // 几何线条装饰：标题下方错位短粗线 + 小三角（前卫艺术感）
                    val w = size.width
                    val h = size.height
                    drawLine(
                        color = extended.lineColor.copy(alpha = 0.8f),
                        start = Offset(0f, h + 6.dp.toPx()),
                        end = Offset(w * 0.30f, h + 6.dp.toPx()),
                        strokeWidth = 3.dp.toPx()
                    )
                    drawLine(
                        color = extended.lineColor.copy(alpha = 0.4f),
                        start = Offset(w * 0.34f, h + 6.dp.toPx()),
                        end = Offset(w * 0.62f, h + 6.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
        )
    }
}

/** 字段行：标签（等宽、主题色）+ 值（空值显示 —）。 */
@Composable
private fun DetailRow(labelRes: Int, value: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = LocalAppFontFamily.current,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.empty_dash),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 展开后的周次：等宽小色块逐个列出（如 7-12 → 7 8 9 10 11 12）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekChips(weeks: Set<Int>) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        weeks.sorted().forEach { week ->
            Box(
                Modifier
                    .background(scheme.primaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = week.toString(),
                    fontSize = 10.sp,
                    fontFamily = LocalAppFontFamily.current,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun dayText(day: Int): String = stringResource(when (day) {
    1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu
    5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun
})
