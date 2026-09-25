package cn.sanxing.thrice.ui.timetable

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.rescheduleAndRefreshWidgets
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.WheelTimePicker
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 课表设置（全屏覆盖层）：节次时间 + 学期设置。
 *
 * 从设置页迁移而来，功能与原设置项完全一致：
 * - 节次的增删改（滚轮精确到分钟）与保存；
 * - 当前学期的名称 / 开始日期 / 总周数编辑与新建；
 * - 课表方案管理弹窗（新建空白方案 / 切换 / 复制 / 删除）。
 *
 * 依赖通过参数注入（与 NoteFoldersOverlay 等其它全屏覆盖层一致），
 * 因为项目中不存在 LocalAppContainer。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val term by container.termRepository.observeActive().collectAsState(null)
    val allTerms by container.termRepository.observeAll().collectAsState(emptyList())
    val sectionTimes by (term?.let { container.sectionTimeDao.observeByTerm(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(emptyList())

    var notice by remember { mutableStateOf<Int?>(null) }
    var showSchemeManager by remember { mutableStateOf(false) }
    var pendingDeleteTerm by remember { mutableStateOf<cn.sanxing.thrice.data.domain.model.Term?>(null) }

    Scaffold(
        // 独立全屏页必须用不透明底色，否则底层课表会透出来形成重影
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timetable_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            notice?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---------------- 节次时间（以小节为单位） ----------------
            SettingsGroup(stringResource(R.string.settings_section_times)) {
                Text(
                    stringResource(R.string.section_slider_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                val defaultsMin = listOf(
                    8 * 60 to 8 * 60 + 45,
                    8 * 60 + 55 to 9 * 60 + 40,
                    10 * 60 + 10 to 10 * 60 + 55,
                    11 * 60 + 5 to 11 * 60 + 50,
                    14 * 60 to 14 * 60 + 45,
                    14 * 60 + 55 to 15 * 60 + 40,
                    16 * 60 + 10 to 16 * 60 + 55,
                    17 * 60 + 5 to 17 * 60 + 50,
                    19 * 60 to 19 * 60 + 45,
                    19 * 60 + 55 to 20 * 60 + 40,
                    20 * 60 + 50 to 21 * 60 + 35,
                    21 * 60 + 45 to 22 * 60 + 30
                )
                // 节次数量不固定：以已保存的小节数据为准（首次使用回落到 12 个默认小节）
                var minutePairs by remember(sectionTimes) {
                    val saved = sectionTimes.sortedBy { it.sectionIndex }
                    mutableStateOf(
                        if (saved.isEmpty()) {
                            defaultsMin
                        } else {
                            saved.mapIndexed { i, st ->
                                val s = st.startTime?.let { timeToMinutes(it) }
                                    ?: defaultsMin[i % defaultsMin.size].first
                                val e = st.endTime?.let { timeToMinutes(it) }
                                    ?: defaultsMin[i % defaultsMin.size].second
                                s to e
                            }
                        }
                    )
                }
                Text(
                    stringResource(R.string.section_count_hint, minutePairs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                // 当前正在用滚轮编辑的小节序号
                var editingSection by remember { mutableStateOf<Int?>(null) }
                minutePairs.forEachIndexed { i, (start, end) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { editingSection = i },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.section_n_label, i + 1),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${minutesToTime(start)} — ${minutesToTime(end)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // 至少保留 1 个小节
                        if (minutePairs.size > 1) {
                            TextButton(
                                onClick = {
                                    minutePairs = minutePairs.toMutableList().also { it.removeAt(i) }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.section_delete_n, i + 1),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    // 新小节默认接在最后一节结束 10 分钟后，时长 45 分钟
                    val lastEnd = minutePairs.lastOrNull()?.second ?: (8 * 60)
                    val newStart = (lastEnd + 10).coerceAtMost(22 * 60 + 15)
                    val newEnd = minOf(23 * 60, newStart + 45)
                    minutePairs = minutePairs + (newStart to newEnd)
                }) { Text(stringResource(R.string.section_add)) }
                Button(onClick = {
                    val t = term ?: return@Button
                    scope.launch {
                        container.sectionTimeDao.deleteByTerm(t.id)
                        container.sectionTimeDao.insertAll(
                            minutePairs.mapIndexed { i, (s, e) ->
                                cn.sanxing.thrice.data.domain.model.SectionTime(
                                    termId = t.id, sectionIndex = i + 1,
                                    startTime = minutesToTime(s), endTime = minutesToTime(e)
                                )
                            }
                        )
                        notice = R.string.section_times_saved
                        rescheduleAndRefreshWidgets(container, context)
                    }
                }) { Text(stringResource(R.string.save_section_times)) }

                // 滚轮精确编辑某一节的上下课时间
                val editing = editingSection
                if (editing != null && editing in minutePairs.indices) {
                    val (initialStart, initialEnd) = minutePairs[editing]
                    var draftStartMin by remember(editing) { mutableIntStateOf(initialStart) }
                    var draftEndMin by remember(editing) { mutableIntStateOf(initialEnd) }
                    val timeValid = draftEndMin > draftStartMin
                    AlertDialog(
                        onDismissRequest = { editingSection = null },
                        title = { Text(stringResource(R.string.section_edit_title, editing + 1)) },
                        text = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "${minutesToTime(draftStartMin)} — ${minutesToTime(draftEndMin)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (timeValid) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(8.dp))
                                WheelTimePicker(
                                    hour = draftStartMin / 60,
                                    minute = draftStartMin % 60,
                                    onHourChange = { h ->
                                        draftStartMin = h * 60 + draftStartMin % 60
                                    },
                                    onMinuteChange = { m ->
                                        draftStartMin = (draftStartMin / 60) * 60 + m
                                    },
                                    hourLabel = stringResource(R.string.section_class_time),
                                    minuteLabel = ""
                                )
                                WheelTimePicker(
                                    hour = draftEndMin / 60,
                                    minute = draftEndMin % 60,
                                    onHourChange = { h ->
                                        draftEndMin = h * 60 + draftEndMin % 60
                                    },
                                    onMinuteChange = { m ->
                                        draftEndMin = (draftEndMin / 60) * 60 + m
                                    },
                                    hourLabel = stringResource(R.string.section_class_end),
                                    minuteLabel = ""
                                )
                                if (!timeValid) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        stringResource(R.string.section_time_order_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = timeValid,
                                onClick = {
                                    minutePairs = minutePairs.toMutableList().also {
                                        it[editing] = draftStartMin to draftEndMin
                                    }
                                    editingSection = null
                                }
                            ) { Text(stringResource(R.string.action_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingSection = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
            }

            // ---------------- 学期设置 ----------------
            SettingsGroup(stringResource(R.string.settings_term)) {
                var name by remember(term) { mutableStateOf(term?.name ?: "") }
                var startDate by remember(term) { mutableStateOf(term?.startDate?.toString() ?: "") }
                var totalWeeks by remember(term) { mutableStateOf(term?.totalWeeks?.toString() ?: "20") }
                var showDatePicker by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.term_name_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = startDate, onValueChange = { startDate = it },
                        label = { Text(stringResource(R.string.term_start_date_label)) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("YYYY-MM-DD") }
                    )
                    TextButton(onClick = { showDatePicker = true }) {
                        Text(stringResource(R.string.date_pick))
                    }
                }
                if (showDatePicker) {
                    // DatePicker 以 UTC 零点归一 millis，初值与回读统一 UTC
                    val datePickerState = androidx.compose.material3.rememberDatePickerState(
                        initialSelectedDateMillis = runCatching {
                            LocalDate.parse(startDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                        }.getOrNull() ?: System.currentTimeMillis()
                    )
                    androidx.compose.material3.DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    startDate = java.time.Instant.ofEpochMilli(millis)
                                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
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
                        androidx.compose.material3.DatePicker(state = datePickerState)
                    }
                }
                OutlinedTextField(
                    value = totalWeeks, onValueChange = { totalWeeks = it },
                    label = { Text(stringResource(R.string.term_total_weeks_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    val weeks = totalWeeks.toIntOrNull()
                    val parsed = runCatching { LocalDate.parse(startDate) }.getOrNull()
                    if (name.isBlank() || parsed == null || weeks == null || weeks !in 1..30) {
                        Toast.makeText(
                            context,
                            if (weeks == null || weeks !in 1..30) R.string.invalid_weeks else R.string.invalid_time,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    // 周次/节次与 ICS 导出均以「第 1 周周一 = 开学日」计算，
                    // 选了非周一时自动对齐到当周周一并提示，避免周历整体错位。
                    val date = parsed.with(java.time.DayOfWeek.MONDAY)
                    if (date != parsed) {
                        startDate = date.toString()
                        Toast.makeText(context, R.string.term_start_aligned_monday, Toast.LENGTH_SHORT).show()
                    }
                    scope.launch {
                        val t = term
                        if (t == null) {
                            val id = container.termRepository.insert(
                                cn.sanxing.thrice.data.domain.model.Term(
                                    name = name, startDate = date, totalWeeks = weeks, isActive = true
                                )
                            )
                            container.termRepository.setActive(id)
                        } else {
                            container.termRepository.update(
                                t.copy(name = name, startDate = date, totalWeeks = weeks)
                            )
                        }
                        notice = R.string.term_saved
                        rescheduleAndRefreshWidgets(container, context)
                    }
                }) { Text(stringResource(R.string.action_save)) }

                HorizontalDivider()
                Text(
                    stringResource(R.string.scheme_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedButton(onClick = { showSchemeManager = true }) {
                    Text(stringResource(R.string.scheme_manage, allTerms.size))
                }
            }
        }
    }

    // ---------------- 课表方案管理 ----------------

    if (showSchemeManager) {
        AlertDialog(
            onDismissRequest = { showSchemeManager = false },
            title = { Text(stringResource(R.string.scheme_manager_title)) },
            confirmButton = {
                TextButton(onClick = { showSchemeManager = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.scheme_create_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    val newSchemeName = stringResource(R.string.scheme_default_name, allTerms.size + 1)
                    val copySuffix = stringResource(R.string.scheme_duplicate)
                    OutlinedButton(onClick = {
                        scope.launch {
                            val monday = java.time.LocalDate.now()
                                .with(java.time.DayOfWeek.MONDAY)
                            container.termRepository.createBlankScheme(
                                name = newSchemeName,
                                startDate = monday,
                                totalWeeks = 20
                            )
                            showSchemeManager = false
                            rescheduleAndRefreshWidgets(container, context)
                        }
                    }) { Text(stringResource(R.string.scheme_new_blank)) }
                    HorizontalDivider()
                    allTerms.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name + if (t.isActive) stringResource(R.string.scheme_active_suffix) else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (t.isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.scheme_weeks_range, t.startDate.toString(), t.totalWeeks),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            if (!t.isActive) {
                                TextButton(onClick = {
                                    scope.launch {
                                        container.termRepository.setActive(t.id)
                                        showSchemeManager = false
                                        rescheduleAndRefreshWidgets(container, context)
                                    }
                                }) { Text(stringResource(R.string.scheme_switch)) }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    container.termRepository.duplicateScheme(
                                        t.id,
                                        t.name + " " + copySuffix
                                    )
                                    rescheduleAndRefreshWidgets(container, context)
                                }
                            }) { Text(stringResource(R.string.scheme_duplicate)) }
                            TextButton(onClick = { pendingDeleteTerm = t }) {
                                Text(stringResource(R.string.scheme_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        )
    }

    pendingDeleteTerm?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTerm = null },
            title = { Text(stringResource(R.string.scheme_delete_title)) },
            text = {
                Text(stringResource(R.string.scheme_delete_message, t.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = container.termRepository.deleteScheme(t.id)
                        if (!ok) Toast.makeText(context, R.string.scheme_keep_one, Toast.LENGTH_SHORT).show()
                        rescheduleAndRefreshWidgets(container, context)
                    }
                    pendingDeleteTerm = null
                }) { Text(stringResource(R.string.clear_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTerm = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    // 与其他页面一致：卡片不透明度跟随「背景不透明度」设置（壁纸模式下生效）
    val paperAlpha = cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = paperAlpha)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** "HH:mm" → 分钟数；非法返回 8:00。 */
private fun timeToMinutes(s: String): Int {
    val parts = s.split(":")
    if (parts.size != 2) return 8 * 60
    val h = parts[0].toIntOrNull() ?: return 8 * 60
    val m = parts[1].toIntOrNull() ?: return 8 * 60
    return h * 60 + m
}

/** 分钟数 → "HH:mm"。 */
private fun minutesToTime(v: Int): String =
    "%02d:%02d".format((v / 60).coerceIn(0, 23), (v % 60).coerceIn(0, 59))
