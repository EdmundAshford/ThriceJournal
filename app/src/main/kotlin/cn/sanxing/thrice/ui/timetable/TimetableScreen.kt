package cn.sanxing.thrice.ui.timetable

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.Reminder
import cn.sanxing.thrice.export.ShareHelper
import cn.sanxing.thrice.export.TimetableImageExporter
import cn.sanxing.thrice.rescheduleAndRefreshWidgets
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.components.FullScreenOverlay
import cn.sanxing.thrice.ui.theme.AppFontOption
import cn.sanxing.thrice.ui.theme.LocalExtendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 周课表（重构版）：简洁布局，顶部控制栏 + 网格主体。
 */
@Composable
fun TimetableScreen(
    container: AppContainer,
    onOpenImport: () -> Unit,
    onOpenEdit: (Long) -> Unit,
    onOpenAllCourses: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extended = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    val term by container.termRepository.observeActive().collectAsState(null)
    var weekOffset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Course?>(null) }
    var recolorTarget by remember { mutableStateOf<Course?>(null) }
    var deleteTarget by remember { mutableStateOf<Course?>(null) }
    var reminderTarget by remember { mutableStateOf<Course?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var showTimetableSettings by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val currentWeek = term?.let { UiUtils.weekOf(it.startDate, today) } ?: 1
    val totalWeeks = term?.totalWeeks ?: 20
    val week = (currentWeek + weekOffset).coerceIn(1, totalWeeks)

    val courses by (term?.let { container.getCoursesForWeek(it.id, week) }
        ?: flowOf(emptyList())).collectAsState(emptyList())
    val sectionTimes by (term?.let { container.sectionTimeDao.observeByTerm(it.id) }
        ?: flowOf(emptyList())).collectAsState(emptyList())
    val others by (term?.let { container.courseRepository.observeOther(it.id) }
        ?: flowOf(emptyList())).collectAsState(emptyList())

    var othersExpanded by remember { mutableStateOf(false) }
    val appFontKey by container.settingsRepository.appFont
        .collectAsState(SettingsRepository.APP_FONT_SYSTEM)

    fun shareImage() {
        val t = term ?: return
        if (exporting) return
        exporting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    TimetableImageExporter.exportWeek(
                        context = context, term = t, week = week,
                        courses = courses, otherCourses = others, sectionTimes = sectionTimes,
                        appTypeface = AppFontOption.typefaceFor(context, appFontKey)
                    )
                }
            }.onSuccess { file ->
                runCatching {
                    context.startActivity(
                        ShareHelper.shareFile(
                            context, file, "image/png",
                            context.getString(R.string.share_image_chooser)
                        )
                    )
                }.onFailure {
                    Toast.makeText(context, R.string.share_failed, Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(context, R.string.share_failed, Toast.LENGTH_LONG).show()
            }
            exporting = false
        }
    }

    // 根容器：Column 与全屏覆盖层（导出中 / 课表设置）叠放，
    // 覆盖层必须在 Column 之后渲染才能压在其上；若与 Column 同级顺序布局，
    // Column 已占满视口，覆盖层会被排到屏幕外无法显示。
    Box(Modifier.fillMaxSize()) {
    // 主容器：撑满可用空间。不透明 surface 底色盖住全局 GeometricBackground，
    // 避免几何装饰线条透在网格上造成凌乱感。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .padding(8.dp)
    ) {
        // 顶部控制栏（固定高度）。使用紧凑文字按钮，避免 360dp 窄屏上
        // Material 按钮最小宽度把「新增」挤压成竖排。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(scheme.surface, RoundedCornerShape(6.dp))
                .border(1.dp, Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactHeaderButton("‹", onClick = { weekOffset = (weekOffset - 1).coerceAtLeast(1 - currentWeek) })
            CompactHeaderButton(
                stringResource(R.string.week_short_format, week),
                bold = true,
                onClick = { weekOffset = 0 }
            )
            CompactHeaderButton("›", onClick = { weekOffset = (weekOffset + 1).coerceAtMost(totalWeeks - currentWeek) })
            Spacer(Modifier.weight(1f))
            CompactHeaderButton(stringResource(R.string.timetable_settings_entry), onClick = { showTimetableSettings = true })
            CompactHeaderButton(stringResource(R.string.filter_all), onClick = onOpenAllCourses)
            CompactHeaderButton(stringResource(R.string.import_entry), onClick = onOpenImport)
            CompactHeaderButton(stringResource(R.string.add_short), onClick = { onOpenEdit(-1L) })
        }

        Spacer(Modifier.height(8.dp))

        // 网格主体：weight(1f) 占满剩余空间，给底部「其他课程」条留出高度
        if (term == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.no_term), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenImport) {
                        Text(stringResource(R.string.import_entry))
                    }
                }
            }
        } else {
            TimetableGrid(
                modifier = Modifier.weight(1f),
                term = term!!,
                week = week,
                courses = courses,
                sectionTimes = sectionTimes,
                today = today,
                currentWeek = currentWeek,
                onCourseClick = { selected = it },
                extended = extended,
                scheme = scheme
            )
        }

        // 其他课程
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OtherCoursesBar(
                courses = others,
                expanded = othersExpanded,
                onToggle = { othersExpanded = !othersExpanded },
                onCourseClick = { selected = it }
            )
        }
    }

        if (exporting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // 课表设置全屏覆盖（节次时间 / 学期设置，从设置页迁移而来）。
        // 必须走 FullScreenOverlay：壁纸模式下纸面是半透明的，
        // 直接叠加会让底层课表网格透出来形成重影。
        if (showTimetableSettings) {
            FullScreenOverlay {
                TimetableSettingsScreen(
                    container = container,
                    onBack = { showTimetableSettings = false }
                )
            }
        }
    }

    // 弹窗
    selected?.let { course ->
        CourseDetailSheet(
            course = course,
            sectionTimes = sectionTimes,
            onDismiss = { selected = null },
            onEdit = { selected = null; onOpenEdit(course.id) },
            onRecolor = { recolorTarget = course },
            onDelete = { deleteTarget = course },
            onSetReminder = { reminderTarget = course }
        )
    }

    recolorTarget?.let { course ->
        CourseColorPickerDialog(
            initialHex = course.colorHex,
            onPick = { hex ->
                scope.launch {
                    container.courseRepository.update(course.copy(colorHex = hex, updatedAt = System.currentTimeMillis()))
                    rescheduleAndRefreshWidgets(container, context)
                }
                recolorTarget = null
            },
            onDismiss = { recolorTarget = null }
        )
    }

    deleteTarget?.let { course ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_course_title)) },
            text = { Text(stringResource(R.string.delete_course_message, course.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.courseRepository.delete(course)
                        rescheduleAndRefreshWidgets(container, context)
                    }
                    deleteTarget = null
                    selected = null
                }) { Text(stringResource(R.string.action_delete), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    reminderTarget?.let { course ->
        ReminderSettingDialog(
            container = container,
            course = course,
            onDismiss = { reminderTarget = null }
        )
    }
}

/**
 * 课表网格：星期栏（置顶不滚动）+ N 个小节行（节次数量由设置决定）。
 *
 * 采用「背景表格层 + 绝对定位卡片层」的 overlay 布局：
 * - 背景层只负责等高行 / 时间列 / 星期栏，天然像素对齐，不会出现行列错位；
 * - 卡片层按 (x, y, width, height) 绝对摆放：跨小节课程高度按行累加，
 *   不再被固定行高的父格约束截断；同一格时间重叠的多门课（冲突课）经
 *   [placeDayCourses] 分配横向车道（lane）平分列宽，互不遮挡。
 *
 * 每个小节有期望最小行高 [PREFERRED_ROW_DP]：节次较多、总高超出视口时
 * 整表纵向滚动，行高不压缩；连堂课（相邻小节同一门课）会合并展示，
 * 合并后卡片足够容纳课程全名 / 上课地点。
 * 相邻小节若是同一门课（同名 / 同地点 / 同老师 / 同周次且小节首尾相接），
 * 在展示层合并为一张跨节卡片，底层数据不变。
 */
private const val PREFERRED_ROW_DP = 56

@Composable
private fun TimetableGrid(
    modifier: Modifier = Modifier,
    term: cn.sanxing.thrice.data.domain.model.Term,
    week: Int,
    courses: List<Course>,
    sectionTimes: List<cn.sanxing.thrice.data.domain.model.SectionTime>,
    today: LocalDate,
    currentWeek: Int,
    onCourseClick: (Course) -> Unit,
    extended: cn.sanxing.thrice.ui.theme.ExtendedColors,
    scheme: androidx.compose.material3.ColorScheme
) {
    val sectionCount = sectionTimes.size.coerceAtLeast(1)
    val placements = remember(courses, sectionCount) {
        courses
            .filter { it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 }
            .groupBy { it.dayOfWeek }
            .flatMap { (_, dayCourses) -> placeDayCourses(dayCourses, sectionCount) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val headerH = 36.dp
        val timeW = 44.dp
        // 视口装不下时按期望行高展开并滚动
        val rowH = maxOf((maxHeight - headerH) / sectionCount, PREFERRED_ROW_DP.dp)
        val bodyH = rowH * sectionCount
        val dayWidth = (maxWidth - timeW) / 7f
        val gridColor = Color.LightGray.copy(alpha = 0.35f)
        val showTodayCol = week == currentWeek && today.dayOfWeek.value in 1..7
        val scrollState = rememberScrollState()

        // ---- 可滚动表格主体（顶部留出置顶星期栏的位置） ----
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Spacer(Modifier.height(headerH))
            Box(modifier = Modifier.fillMaxWidth().height(bodyH)) {
                // 背景表格层：时间列 + 7 个天列，每行显式高度
                Row(modifier = Modifier.fillMaxSize()) {
                    // 时间列
                    Column(
                        modifier = Modifier
                            .width(timeW)
                            .fillMaxHeight()
                            .background(scheme.surface)
                    ) {
                        for (big in 1..sectionCount) {
                            val st = sectionTimes.firstOrNull { it.sectionIndex == big }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowH),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    UiUtils.sectionNumberLabel(big),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp
                                )
                                if (st != null) {
                                    Text(
                                        st.startTime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurface.copy(alpha = 0.45f),
                                        fontSize = 8.sp
                                    )
                                    Text(
                                        st.endTime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurface.copy(alpha = 0.45f),
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                    }

                    // 7 个天列
                    for (d in 1..7) {
                        val isToday = showTodayCol && d == today.dayOfWeek.value
                        Column(
                            modifier = Modifier
                                .width(dayWidth)
                                .fillMaxHeight()
                                .then(
                                    if (isToday) Modifier.background(scheme.primary.copy(alpha = 0.06f))
                                    else Modifier
                                )
                        ) {
                            for (big in 1..sectionCount) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(rowH)
                                )
                            }
                        }
                    }
                }

                // ---- 网格线层：每条分界线只画一次 ----
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 0.5.dp.toPx()
                    val lineColor = gridColor
                    val gridW = size.width - timeW.toPx()

                    fun hLine(y: Float, x0: Float = timeW.toPx(), x1: Float = size.width) {
                        drawLine(lineColor, Offset(x0, y), Offset(x1, y), strokeWidth = strokeWidth)
                    }
                    fun vLine(x: Float) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = strokeWidth)
                    }

                    // 小节分界线（含网格顶 / 底边）
                    for (k in 0..sectionCount) hLine(size.height * k / sectionCount.toFloat())
                    // 8 条竖线：时间列右缘 + 7 个天列分界
                    for (k in 0..7) vLine(timeW.toPx() + gridW * k / 7f)
                }

                // ---- 卡片层：绝对定位（父 Box 不裁剪，跨节卡片可向下延伸）----
                placements.forEach { p ->
                    val laneWidth = dayWidth / p.lanes
                    val cardX = timeW + dayWidth * (p.course.dayOfWeek - 1) + laneWidth * p.lane
                    val cardY = rowH * (p.first - 1)
                    CourseCard(
                        course = p.course,
                        rowHeight = rowH,
                        rowSpan = p.last - p.first + 1,
                        sectionTimes = sectionTimes,
                        onClick = { onCourseClick(p.course) },
                        onLongClick = { onCourseClick(p.course) },
                        modifier = Modifier
                            .offset(x = cardX, y = cardY)
                            .width(laneWidth)
                    )
                }
            }
        }

        // ---- 置顶星期栏（不随表格滚动） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerH)
                .background(scheme.surface)
        ) {
            Spacer(Modifier.width(timeW))
            for (d in 1..7) {
                val dateForDay = term.startDate
                    .plusDays(((week - 1) * 7 + (d - 1)).toLong())
                val isToday = showTodayCol && d == today.dayOfWeek.value
                Box(
                    modifier = Modifier
                        .width(dayWidth)
                        .height(headerH),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(UiUtils.dayLabelRes(d)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) scheme.primary else scheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            dateForDay.format(DateTimeFormatter.ofPattern("M.d")),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) scheme.primary else scheme.onSurface.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

/** 卡片在网格中的绝对摆放信息：[lane] 为横向车道序号，[lanes] 为该区域总车道数。 */
private data class GridPlacement(
    val course: Course,
    val first: Int,
    val last: Int,
    val lane: Int,
    val lanes: Int
)

/**
 * 为同一天的课程分配横向车道。先把每门课映射到小节闭区间 first..last，
 * 再将相邻小节的同一门课合并展示（同名 / 同地点 / 同老师 / 同上课周，
 * 且小节序号首尾相接）；随后：
 * - 区间重叠的课程贪心分到不同车道（按起始节排序，复用最早释放的车道）；
 * - 每门课的车道宽度取其覆盖区间内「最大并发数」的倒数。
 * 由此任何两张卡片在几何上都不重叠，无冲突的课仍然独占整列宽度。
 */
private fun placeDayCourses(dayCourses: List<Course>, sectionCount: Int): List<GridPlacement> {
    data class Interval(val course: Course, val first: Int, val last: Int)

    val raw = dayCourses.mapNotNull { c ->
        val start = c.startSection
        val end = c.endSection.coerceAtLeast(start)
        if (start <= 0) {
            null
        } else {
            Interval(
                course = c,
                first = start.coerceIn(1, sectionCount),
                last = end.coerceIn(1, sectionCount)
            )
        }
    }.sortedWith(compareBy({ it.first }, { it.course.startSection }, { it.last }))

    // 相邻小节同课合并（仅 UI 展示，不修改数据；点击打开合并区间内第一门课的详情）
    val intervals = ArrayList<Interval>()
    for (iv in raw) {
        val prev = intervals.lastOrNull()
        val sameCourse = prev != null &&
            prev.course.name == iv.course.name &&
            prev.course.location == iv.course.location &&
            prev.course.teacher == iv.course.teacher &&
            prev.course.weeks == iv.course.weeks
        if (sameCourse && prev.last + 1 == iv.first) {
            intervals[intervals.lastIndex] = prev.copy(last = iv.last)
        } else {
            intervals.add(iv)
        }
    }

    val laneEnds = ArrayList<Int>()
    val assigned = intervals.map { iv ->
        var lane = laneEnds.indexOfFirst { it < iv.first }
        if (lane < 0) {
            lane = laneEnds.size
            laneEnds.add(iv.last)
        } else {
            laneEnds[lane] = iv.last
        }
        iv to lane
    }

    return assigned.map { (iv, lane) ->
        val maxConcurrent = (iv.first..iv.last).maxOf { row ->
            intervals.count { it.first <= row && row <= it.last }
        }
        GridPlacement(
            course = iv.course,
            first = iv.first,
            last = iv.last,
            lane = lane,
            lanes = maxConcurrent.coerceAtLeast(1)
        )
    }
}

/** 单门课提醒设置 */
@Composable
private fun ReminderSettingDialog(
    container: AppContainer,
    course: Course,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reminder by container.reminderDao.observeByCourse(course.id).collectAsState(emptyList())
    val current = reminder.firstOrNull()
    var enabled by remember { mutableStateOf(current?.enabled ?: true) }
    var minutes by remember { mutableIntStateOf(current?.minutesBefore ?: 15) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_dialog_title, course.name)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.reminder_enable_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 30).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text(stringResource(R.string.minutes_format, m)) },
                            enabled = enabled
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val entity = Reminder(
                        id = current?.id ?: 0,
                        courseId = course.id,
                        minutesBefore = minutes,
                        enabled = enabled
                    )
                    if (current == null) container.reminderDao.insert(entity)
                    else container.reminderDao.update(entity)
                    rescheduleAndRefreshWidgets(container, context)
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 顶部控制栏的紧凑文字按钮：没有 Material 按钮的 58dp 最小宽度限制，
 * 水平内边距收紧，文字始终单行横排（窄屏上「新增」不再被挤成竖排）。
 */
@Composable
private fun CompactHeaderButton(
    text: String,
    onClick: () -> Unit,
    bold: Boolean = false
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    )
}