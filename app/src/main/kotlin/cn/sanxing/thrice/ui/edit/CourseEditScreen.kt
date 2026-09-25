package cn.sanxing.thrice.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import cn.sanxing.thrice.ui.theme.LocalAppFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.ColorPickerSection
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.components.PostcardFrame
import cn.sanxing.thrice.ui.theme.CoursePalette
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 新增 / 编辑课程（全屏路由）。
 *
 * - 字段：课名、教师、地点、校区、学分、类别（网格/其他）、星期、节次起止、
 *   周次（1..20 点选网格 + 全选/单周/双周/清空）、颜色（12 色板 + 自定义 hex）、
 *   备注、教学班、教学班组成、考核方式、选课备注、课程学时组成、周/总学时；
 * - 保存前冲突检测（同天同节次且周次有交集，排除自身）→ 二次确认；
 * - 删除：确认对话框，只删该课程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen(
    container: AppContainer,
    courseId: Long,
    onFinished: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // 冲突条目文案在协程内拼接，需在组合期预取格式串
    val conflictItemFmt = stringResource(R.string.import_conflict_item)
    val term by container.termRepository.observeActive().collectAsState(initial = null)
    // 节次上限跟随设置中的小节数量，默认 12
    val sectionTimes by (term?.let { container.sectionTimeDao.observeByTerm(it.id) }
        ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val maxSection = sectionTimes.size.coerceAtLeast(2)

    // ---- 表单状态 ----
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var campus by remember { mutableStateOf("") }
    var credit by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(CourseKind.GRID) }
    var day by remember { mutableIntStateOf(1) }
    var startSection by remember { mutableIntStateOf(1) }
    var endSection by remember { mutableIntStateOf(2) }
    var weeks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 多时间段支持：每个 Slot 是一个独立的 (day, start, end, weeks) 组合
    data class Slot(val day: Int, val start: Int, val end: Int, val weeks: Set<Int>)
    var slots by remember { mutableStateOf<List<Slot>>(emptyList()) }
    var colorHex by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var classGroup by remember { mutableStateOf("") }
    var classMembers by remember { mutableStateOf("") }
    var assessment by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var hoursBreakdown by remember { mutableStateOf("") }
    var weeklyHours by remember { mutableStateOf("") }
    var totalHours by remember { mutableStateOf("") }
    var overrideDate by remember { mutableStateOf<LocalDate?>(null) }
    var overrideNote by remember { mutableStateOf("") }
    var createdAt by remember { mutableLongStateOf(0L) }

    var nameError by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Course?>(null) }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }

    // ---- 载入已有课程 ----
    LaunchedEffect(courseId) {
        if (courseId > 0) {
            val c = container.courseRepository.get(courseId)
            if (c != null) {
                name = c.name
                teacher = c.teacher
                location = c.location
                campus = c.campus
                credit = c.credit?.toString() ?: ""
                category = c.category ?: ""
                kind = c.kind
                day = c.dayOfWeek.coerceIn(1, 7)
                startSection = c.startSection.coerceAtLeast(1)
                endSection = c.endSection.coerceAtLeast(startSection)
                weeks = c.weeks.ifEmpty { setOf(1) }
                colorHex = c.colorHex
                note = c.note
                classGroup = c.classGroup
                classMembers = c.classMembers
                assessment = c.assessment
                remark = c.remark
                hoursBreakdown = c.hoursBreakdown
                weeklyHours = c.weeklyHours?.toString() ?: ""
                totalHours = c.totalHours?.toString() ?: ""
                overrideDate = c.overrideDate
                overrideNote = c.overrideNote
                createdAt = c.createdAt
            }
        }
        loaded = true
    }

    fun buildCourse(): Course? {
        val t = term ?: return null
        if (name.isBlank()) {
            nameError = true
            return null
        }
        if (colorHex.isNotBlank() && UiUtils.parseHex(colorHex) == null) {
            // 颜色选择器自身会标红提示，这里阻止非法值保存
            return null
        }
        val isGrid = kind == CourseKind.GRID
        val now = System.currentTimeMillis()
        return Course(
            id = if (courseId > 0) courseId else 0,
            termId = t.id,
            name = name.trim(),
            teacher = teacher.trim(),
            location = location.trim(),
            campus = campus.trim(),
            classGroup = classGroup.trim(),
            classMembers = classMembers.trim(),
            assessment = assessment.trim(),
            remark = remark.trim(),
            hoursBreakdown = hoursBreakdown.trim(),
            weeklyHours = weeklyHours.toIntOrNull(),
            totalHours = totalHours.toIntOrNull(),
            credit = credit.toDoubleOrNull(),
            category = category.trim().ifBlank { null },
            colorHex = colorHex.trim(),
            weeks = weeks,
            dayOfWeek = if (isGrid) day else 0,
            startSection = if (isGrid) startSection else 0,
            endSection = if (isGrid) endSection else 0,
            note = note.trim(),
            kind = kind,
            weeksRaw = UiUtils.formatWeeks(weeks),
            overrideDate = overrideDate,
            overrideNote = overrideNote.trim(),
            createdAt = if (courseId > 0) createdAt else now,
            updatedAt = now
        )
    }

    /** 多时间段保存：删除旧的关联记录，为每个 slot 插入新记录。 */
    fun saveMultiSlot(withConflictConfirm: Boolean) {
        val t = term ?: return
        if (name.isBlank()) { nameError = true; return }
        if (colorHex.isNotBlank() && UiUtils.parseHex(colorHex) == null) return
        val now = System.currentTimeMillis()
        val newCourses = slots.map { slot ->
            Course(
                id = 0, // 新插入
                termId = t.id,
                name = name.trim(),
                teacher = teacher.trim(),
                location = location.trim(),
                campus = campus.trim(),
                classGroup = classGroup.trim(),
                classMembers = classMembers.trim(),
                assessment = assessment.trim(),
                remark = remark.trim(),
                hoursBreakdown = hoursBreakdown.trim(),
                weeklyHours = weeklyHours.toIntOrNull(),
                totalHours = totalHours.toIntOrNull(),
                credit = credit.toDoubleOrNull(),
                category = category.trim().ifBlank { null },
                colorHex = colorHex.trim(),
                weeks = slot.weeks,
                dayOfWeek = slot.day,
                startSection = slot.start,
                endSection = slot.end,
                note = note.trim(),
                kind = kind,
                weeksRaw = UiUtils.formatWeeks(slot.weeks),
                overrideDate = overrideDate,
                overrideNote = overrideNote.trim(),
                createdAt = now,
                updatedAt = now
            )
        }
        scope.launch {
            // 编辑模式：删除旧的同名课程记录（同一门课拆出的多条），再重新插入
            if (courseId > 0) {
                val existing = container.courseRepository.getByTerm(t.id)
                val toDelete = existing.filter {
                    it.id == courseId || (it.name == name.trim() && it.kind == kind &&
                        it.teacher == teacher.trim() && it.id != courseId)
                }
                toDelete.forEach { container.courseRepository.delete(it) }
            }
            newCourses.forEach { container.courseRepository.insert(it) }
            onFinished()
        }
    }

    /** 保存：先冲突检测，有冲突二次确认。多时间段时拆成多条 Course 记录。 */
    fun save(withConflictConfirm: Boolean) {
        // 多时间段模式：slots 非空时，为每个 slot 创建一条 Course
        val isMultiSlot = kind == CourseKind.GRID && slots.isNotEmpty()
        if (isMultiSlot) {
            saveMultiSlot(withConflictConfirm)
            return
        }
        val course = buildCourse() ?: return
        scope.launch {
            val existing = container.courseRepository.getByTerm(course.termId)
                .filter { it.id != course.id && it.kind == CourseKind.GRID && course.kind == CourseKind.GRID }
            val found = container.detectConflicts(existing + course)
                .filter { it.a.id == course.id || it.b.id == course.id }
            if (found.isNotEmpty() && !withConflictConfirm) {
                conflicts = found.map {
                    conflictItemFmt.format(
                        it.a.name, it.b.name, it.a.dayOfWeek,
                        maxOf(it.a.startSection, it.b.startSection),
                        minOf(it.a.endSection, it.b.endSection),
                        UiUtils.formatWeeks(it.overlappingWeeks)
                    )
                }
                pendingSave = course
                return@launch
            }
            if (course.id > 0) container.courseRepository.update(course)
            else container.courseRepository.insert(course)
            onFinished()
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
        // ---- 顶栏（edge-to-edge 下需让出系统栏，避免与状态栏重叠） ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onFinished) { Text(stringResource(R.string.action_close)) }
            Text(
                text = stringResource(if (courseId > 0) R.string.edit_title_edit else R.string.edit_title_new),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (courseId > 0) {
                TextButton(onClick = { deleteConfirm = true }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = { save(false) }) { Text(stringResource(R.string.action_save)) }
        }

        if (!loaded) return@Column

        PostcardFrame(
            title = stringResource(if (courseId > 0) R.string.edit_title_edit else R.string.edit_title_new),
            modifier = Modifier.fillMaxWidth()
        ) {
            FormField(R.string.field_name, name, nameError) { name = it; nameError = false }
            FormField(R.string.field_teacher, teacher) { teacher = it }
            FormField(R.string.field_location, location) { location = it }
            FormField(R.string.field_campus, campus) { campus = it }
            FormField(R.string.field_credit, credit) { credit = it.filter { ch -> ch.isDigit() || ch == '.' } }
            FormField(R.string.field_category, category) { category = it }

            // 类别
            SectionText(R.string.field_kind)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == CourseKind.GRID,
                    onClick = { kind = CourseKind.GRID },
                    label = { Text(stringResource(R.string.kind_grid)) }
                )
                FilterChip(
                    selected = kind == CourseKind.OTHER,
                    onClick = { kind = CourseKind.OTHER },
                    label = { Text(stringResource(R.string.kind_other)) }
                )
            }
            Spacer(Modifier.height(10.dp))

            if (kind == CourseKind.GRID) {
                // 多时间段支持
                SectionText(R.string.field_time_slots)
                
                // 已添加的时间段列表
                if (slots.isNotEmpty()) {
                    slots.forEachIndexed { index, slot ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.edit_slot_summary,
                                    dayLabel(slot.day), slot.start, slot.end, UiUtils.formatWeeks(slot.weeks)
                                ),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { slots = slots.toMutableList().also { it.removeAt(index) } }) {
                                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                
                // 当前编辑的时间段
                SectionText(R.string.field_day)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = day == d,
                            onClick = { day = d },
                            label = { Text(dayLabel(d)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 节次起止
                Row {
                    NumberDropdown(
                        label = stringResource(R.string.field_section_start),
                        value = startSection,
                        range = 1..maxSection,
                        onChange = {
                            startSection = it
                            if (endSection < it) endSection = it
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    NumberDropdown(
                        label = stringResource(R.string.field_section_end),
                        value = endSection,
                        range = startSection..maxSection,
                        onChange = { endSection = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                
                // 周次点选网格 + 快捷按钮
                SectionText(R.string.field_weeks)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { weeks = (1..20).toSet() }) { Text(stringResource(R.string.weeks_all)) }
                    OutlinedButton(onClick = { weeks = (1..20).filter { it % 2 == 1 }.toSet() }) { Text(stringResource(R.string.weeks_odd)) }
                    OutlinedButton(onClick = { weeks = (1..20).filter { it % 2 == 0 }.toSet() }) { Text(stringResource(R.string.weeks_even)) }
                    OutlinedButton(onClick = { weeks = emptySet() }) { Text(stringResource(R.string.weeks_clear)) }
                }
                Spacer(Modifier.height(6.dp))
                WeekGridPicker(weeks) { weeks = it }
                if (weeks.isNotEmpty()) {
                    Text(
                        text = UiUtils.formatWeeks(weeks),
                        fontSize = 10.sp,
                        fontFamily = LocalAppFontFamily.current,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                
                // 添加当前时间段按钮
                Button(
                    onClick = {
                        if (weeks.isNotEmpty()) {
                            slots = slots + Slot(day, startSection, endSection, weeks)
                            weeks = emptySet() // 清空周次，准备添加下一个
                        }
                    },
                    enabled = weeks.isNotEmpty()
                ) { Text(stringResource(R.string.edit_add_slot)) }
                Spacer(Modifier.height(10.dp))
            }

            // 颜色：预设色板 + RGB 滑块 + 自定义 hex
            SectionText(R.string.field_color)
            ColorPickerSection(
                colorHex = colorHex,
                onColorChange = { colorHex = it },
                presetColors = CoursePalette.map { UiUtils.colorToHex(it) }
            )
            Spacer(Modifier.height(6.dp))

            FormField(R.string.field_class_group, classGroup) { classGroup = it }
            FormField(R.string.field_class_members, classMembers) { classMembers = it }
            FormField(R.string.field_assessment, assessment) { assessment = it }
            FormField(R.string.field_remark, remark) { remark = it }
            FormField(R.string.field_hours_breakdown, hoursBreakdown) { hoursBreakdown = it }
            FormField(R.string.field_weekly_hours, weeklyHours) { weeklyHours = it.filter(Char::isDigit) }
            FormField(R.string.field_total_hours, totalHours) { totalHours = it.filter(Char::isDigit) }
            FormField(R.string.field_note, note) { note = it }

            // 原「例外（调课/停课/补课/临时地点）· 钉到日期」区块已按需求移除：
            // 调课/停课/补课/临时地点等信息直接写进上方「备注」即可。
            // 已存课程的 override 数据仍原样保留（载入后静默回写，不做破坏性清除）。
            Spacer(Modifier.height(12.dp))
            Button(onClick = { save(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    // ---- 删除确认（只删该课程） ----
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.delete_course_title)) },
            text = { Text(stringResource(R.string.delete_course_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.courseRepository.get(courseId)?.let { container.courseRepository.delete(it) }
                        onFinished()
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ---- 冲突二次确认 ----
    pendingSave?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingSave = null },
            title = { Text(stringResource(R.string.conflict_found_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.conflict_found_message, conflicts.size))
                    conflicts.forEach { Text("• $it", fontSize = 11.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (course.id > 0) container.courseRepository.update(course)
                        else container.courseRepository.insert(course)
                        onFinished()
                    }
                }) { Text(stringResource(R.string.save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSave = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ---- 例外日期选择器已随「钉到日期」功能一并移除（改用备注） ----
}

@Composable
private fun SectionText(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalAppFontFamily.current,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun FormField(
    labelRes: Int,
    value: String,
    isError: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError && labelRes == R.string.field_name) {
            { Text(stringResource(R.string.invalid_name)) }
        } else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

/** 数字下拉（节次等）：点击弹简单选择对话框，避免依赖 ExposedDropdownMenu 实验 API。 */
@Composable
private fun NumberDropdown(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value.toString(),
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().clickable { open = true }
        )
        // 覆盖点击（TextField 默认拦截）：整框可点
        Box(
            Modifier
                .matchParentSize()
                .clickable { open = true }
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    range.forEach { v ->
                        Text(
                            text = v.toString(),
                            fontFamily = LocalAppFontFamily.current,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(v); open = false }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** 1..20 周点选网格。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekGridPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (w in 1..20) {
            val on = w in selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .border(
                        1.dp,
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onChange(if (on) selected - w else selected + w) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = w.toString(),
                    fontSize = 11.sp,
                    fontFamily = LocalAppFontFamily.current,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 星期几文案。 */
@Composable
private fun dayLabel(day: Int): String = stringResource(when (day) {
    1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu
    5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun
})
