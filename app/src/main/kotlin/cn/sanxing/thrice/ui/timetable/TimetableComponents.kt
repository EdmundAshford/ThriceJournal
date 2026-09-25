@file:OptIn(ExperimentalFoundationApi::class)

package cn.sanxing.thrice.ui.timetable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import cn.sanxing.thrice.ui.theme.LocalAppFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.ui.common.ColorPickerSection
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.theme.CoursePalette

/**
 * 课程卡片：几何色块 + 线条装饰 + 等宽数字。
 * 圆角 12dp、边距 2dp、课名 12sp 加粗、地点/教师 10sp（提示词六节硬性要求）。
 *
 * @param rowSpan 覆盖的小节行数（连堂课按行高累加，>1 时 zIndex 抬升覆盖下方格子）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CourseCard(
    course: Course,
    rowHeight: Dp,
    rowSpan: Int,
    sectionTimes: List<SectionTime>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = UiUtils.courseColor(course)
    val totalHeight = rowHeight * rowSpan - 4.dp
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .zIndex(if (rowSpan > 1) 1f else 0f)
            .padding(2.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.28f))
            .border(1.5.dp, color.copy(alpha = 0.8f), shape)
            .drawBehind {
                // 线条装饰（低透明度，不干扰阅读）：右下角两条斜线 + 一个几何三角
                val w = size.width
                val h = size.height
                drawLine(
                    color = color.copy(alpha = 0.35f),
                    start = Offset(w * 0.62f, h),
                    end = Offset(w, h * 0.34f),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = color.copy(alpha = 0.22f),
                    start = Offset(w * 0.78f, h),
                    end = Offset(w, h * 0.56f),
                    strokeWidth = 1.dp.toPx()
                )
                // 左侧粗色条（前卫艺术的粗线条）
                drawRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(0f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), h * 0.64f)
                )
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 7.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = course.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 13.sp,
                maxLines = when {
                    rowSpan >= 2 -> 4
                    else -> 2
                },
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 单小节行空间紧张：优先保证课名，地点/教师与时间仅连堂卡片展示
            if (rowSpan >= 2) {
                Spacer(Modifier.height(1.dp))
                val sub = listOfNotNull(
                    course.location.takeIf { it.isNotBlank() },
                    course.teacher.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                    )
                }
                Text(
                    text = UiUtils.sectionTimeText(androidx.compose.ui.platform.LocalContext.current, sectionTimes, course.startSection, course.endSection),
                    fontSize = 9.sp,
                    fontFamily = LocalAppFontFamily.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * 改色对话框：预设色板 + RGB 滑块 + 自定义 Hex。
 */
@Composable
fun CourseColorPickerDialog(
    initialHex: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var custom by remember { mutableStateOf(initialHex) }
    val valid = UiUtils.parseHex(custom) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_recolor_title)) },
        text = {
            // 色板 + 滑块 + 输入框较高，小屏上可滚动
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorPickerSection(
                    colorHex = custom,
                    onColorChange = { custom = it },
                    presetColors = CoursePalette.map { UiUtils.colorToHex(it) }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { if (valid) onPick(custom.uppercase()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 「其他课程 (N)」可折叠条：展开后在条内列出（不占网格空间）。
 * 用于课表页底部——即使网格里没有这些课，也一定看得见。
 */
@Composable
fun OtherCoursesBar(
    courses: List<Course>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                .combinedClickable(onClick = onToggle, onLongClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OtherCourseBadge()
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.other_courses_title, courses.size),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.other_courses_collapse else R.string.other_courses_expand
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.primary
            )
        }
        if (expanded) {
            if (courses.isEmpty()) {
                Text(
                    text = stringResource(R.string.other_courses_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            } else {
                courses.forEach { course ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onCourseClick(course) },
                                onLongClick = { onCourseClick(course) }
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(UiUtils.courseColor(course))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = course.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface
                            )
                            Text(
                                text = listOfNotNull(
                                    course.teacher.takeIf { it.isNotBlank() },
                                    (course.weeksRaw.ifBlank { UiUtils.formatWeeks(course.weeks) })
                                        .takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「其他课程」标识徽标（用户要求其他课程要有明显标识）。 */
@Composable
fun OtherCourseBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(scheme.tertiary.copy(alpha = 0.75f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = stringResource(R.string.other_course_badge),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onTertiary
        )
    }
}
