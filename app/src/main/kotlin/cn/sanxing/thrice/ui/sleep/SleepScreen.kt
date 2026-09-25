package cn.sanxing.thrice.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.notification.SleepScheduler
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.WheelNumberPicker
import cn.sanxing.thrice.ui.common.WheelTimePicker
import cn.sanxing.thrice.ui.common.stats.BarChart
import cn.sanxing.thrice.ui.common.stats.BarPoint
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository
    val zone = remember { ZoneId.systemDefault() }
    val isZh = remember {
        context.resources.configuration.locales[0].language == Locale.CHINESE.language
    }

    val records by container.sleepRecordDao.observeAll().collectAsState(initial = emptyList())
    val napEnabled by settings.sleepNapEnabled.collectAsState(initial = false)
    val goalMode by settings.sleepGoalMode.collectAsState(
        initial = SettingsRepository.SLEEP_GOAL_MODE_DURATION
    )
    val goalMin by settings.sleepNightGoalMinutes.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_GOAL_MIN
    )
    val bedMinute by settings.sleepBedTimeMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_BED_TIME_MINUTE
    )
    val wakeMinute by settings.sleepWakeTimeMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_WAKE_TIME_MINUTE
    )
    val napGoalMin by settings.sleepNapEffectiveGoalMinutes.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_NAP_GOAL_MIN
    )
    // 当前模式下生效的夜睡目标：总时长模式取时长；入睡/起床模式按跨午夜环绕换算
    val effectiveNightGoal = if (goalMode == SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE) {
        settings.nightGoalMinutes(bedMinute, wakeMinute)
    } else goalMin

    var nightOpen by remember { mutableStateOf<SleepRecord?>(null) }
    var napOpen by remember { mutableStateOf<SleepRecord?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SleepRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SleepRecord?>(null) }

    // 跨进程恢复：进入页面时拾取未闭合记录
    LaunchedEffect(Unit) {
        nightOpen = container.sleepRecordDao.findOpen(SleepKind.NIGHT.name)
        napOpen = container.sleepRecordDao.findOpen(SleepKind.NAP.name)
    }

    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nightOpen, napOpen) {
        while (isActive) {
            if (nightOpen != null || napOpen != null) nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    if (showStats) {
        SleepStatsScreen(container, onBack = { showStats = false })
        return
    }

    val lastNight = remember(records) {
        records.firstOrNull { it.kind == SleepKind.NIGHT.name && it.minutes != null }
    }
    val lastNap = remember(records) {
        records.firstOrNull { it.kind == SleepKind.NAP.name && it.minutes != null }
    }

    // 近 7 日总睡眠（按入睡日归属）
    val today = LocalDate.now()
    val weekDays = remember { (6 downTo 0).map { today.minusDays(it.toLong()) } }
    val minutesByDay = records
        .filter { it.minutes != null }
        .groupBy {
            Instant.ofEpochMilli(it.sleepAtEpochMs).atZone(zone).toLocalDate()
        }
        .mapValues { e -> e.value.sumOf { it.minutes!! } }
    val weekPoints = weekDays.map { d ->
        BarPoint(
            label = if (isZh) "${d.monthValue}/${d.dayOfMonth}" else "${d.monthValue}/${d.dayOfMonth}",
            value = (minutesByDay[d] ?: 0).toDouble()
        )
    }

    fun startSleep(kind: SleepKind) {
        scope.launch {
            val rec = SleepRecord(kind = kind.name, sleepAtEpochMs = System.currentTimeMillis())
            val id = container.sleepRecordDao.insert(rec)
            val open = rec.copy(id = id)
            if (kind == SleepKind.NIGHT) nightOpen = open else napOpen = open
        }
    }

    fun wakeUp(open: SleepRecord, setter: (SleepRecord?) -> Unit) {
        val wake = System.currentTimeMillis()
        val minutes = ((wake - open.sleepAtEpochMs) / 60_000L).toInt().coerceAtLeast(0)
        scope.launch {
            container.sleepRecordDao.update(
                open.copy(wakeAtEpochMs = wake, minutes = minutes)
            )
            setter(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sleep_title)) },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sleep_add_manual))
                    }
                    IconButton(onClick = { showStats = true }) {
                        Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.sleep_stats_entry))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.sleep_settings_entry))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SleepKindCard(
                    kind = SleepKind.NIGHT,
                    open = nightOpen,
                    nowTick = nowTick,
                    last = lastNight,
                    goalMin = effectiveNightGoal,
                    isZh = isZh,
                    onStart = { startSleep(SleepKind.NIGHT) },
                    onWake = { nightOpen?.let { wakeUp(it) { v -> nightOpen = v } } }
                )
            }
            if (napEnabled) {
                item {
                    SleepKindCard(
                        kind = SleepKind.NAP,
                        open = napOpen,
                        nowTick = nowTick,
                        last = lastNap,
                        goalMin = napGoalMin,
                        isZh = isZh,
                        onStart = { startSleep(SleepKind.NAP) },
                        onWake = { napOpen?.let { wakeUp(it) { v -> napOpen = v } } }
                    )
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = sleepCardColors()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.sleep_recent7),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        BarChart(
                            points = weekPoints,
                            barColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            referenceValue = effectiveNightGoal.toDouble(),
                            referenceColor = MaterialTheme.colorScheme.tertiary,
                            formatValue = { it.toInt().toString() }
                        )
                    }
                }
            }

            item {
                Text(stringResource(R.string.sleep_history_title),
                    style = MaterialTheme.typography.titleSmall)
            }
            // 进行中（未闭合）记录由上方状态卡管理，历史列表不重复展示，
            // 避免在列表里把进行中记录编辑成已闭合而与状态卡失配。
            val openIds = listOfNotNull(nightOpen?.id, napOpen?.id).toSet()
            val history = records.filter { it.id !in openIds }.take(15)
            if (history.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sleep_no_records),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
            } else {
                items(history, key = { it.id }) { rec ->
                    SleepHistoryRow(
                        rec = rec,
                        isZh = isZh,
                        onEdit = { editing = rec },
                        onDelete = { pendingDelete = rec }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showSettings) {
        SleepSettingsDialog(container = container, onDismiss = { showSettings = false })
    }
    if (creating) {
        SleepRecordDialog(
            initial = null,
            isZh = isZh,
            onDismiss = { creating = false },
            onSave = {
                scope.launch { container.sleepRecordDao.insert(it) }
                creating = false
            },
            onDelete = { }
        )
    }
    editing?.let { rec ->
        SleepRecordDialog(
            initial = rec,
            isZh = isZh,
            onDismiss = { editing = null },
            onSave = {
                scope.launch { container.sleepRecordDao.update(it) }
                editing = null
            },
            onDelete = {
                scope.launch { container.sleepRecordDao.deleteById(it.id) }
                editing = null
            }
        )
    }
    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.sleep_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.sleepRecordDao.deleteById(rec.id) }
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 睡眠页卡片底色：跟随「主体界面浓度」（壁纸模式 15%..100%）实时变化。 */
@Composable
private fun sleepCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current)
)

@Composable
private fun SleepKindCard(
    kind: SleepKind,
    open: SleepRecord?,
    nowTick: Long,
    last: SleepRecord?,
    goalMin: Int?,
    isZh: Boolean,
    onStart: () -> Unit,
    onWake: () -> Unit
) {
    val title = if (kind == SleepKind.NIGHT) stringResource(R.string.sleep_kind_night)
    else stringResource(R.string.sleep_kind_nap)
    val activeMin = open?.let {
        ((nowTick - it.sleepAtEpochMs) / 60_000L).toInt().coerceAtLeast(0)
    }
    // 进行中取当前时长，否则取上次时长；相对各自目标（夜睡 / 午休独立）
    val ringActual = activeMin ?: last?.minutes
    Card(
        Modifier.fillMaxWidth(),
        colors = sleepCardColors()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (kind == SleepKind.NIGHT) Icons.Filled.Bedtime else Icons.Filled.WbSunny,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ringActual != null && goalMin != null) {
                    GoalRing(ringActual, goalMin, 46.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (open != null) {
                val elapsedMin = activeMin ?: 0
                val since = remember(open.sleepAtEpochMs) {
                    DateTimeFormatter.ofPattern(if (isZh) "HH:mm" else "HH:mm")
                        .format(Instant.ofEpochMilli(open.sleepAtEpochMs).atZone(ZoneId.systemDefault()))
                }
                Text(
                    if (kind == SleepKind.NIGHT)
                        stringResource(R.string.sleep_sleeping_since, since)
                    else stringResource(R.string.sleep_napping_since, since),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDurationMin(elapsedMin, isZh),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light)
                )
                Button(
                    onClick = onWake,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(stringResource(R.string.sleep_wake)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (last != null) {
                            val label = if (kind == SleepKind.NIGHT)
                                stringResource(R.string.sleep_last_night)
                            else stringResource(R.string.sleep_last_nap)
                            Text(
                                "$label  ${formatDurationMin(last.minutes ?: 0, isZh)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (goalMin != null) {
                                val diff = (last.minutes ?: 0) - goalMin
                                Text(
                                    when {
                                        diff >= 0 -> stringResource(R.string.sleep_summary_reached)
                                        else -> stringResource(
                                            R.string.sleep_summary_short,
                                            formatDurationMin(-diff, isZh)
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Button(onClick = onStart) {
                        Text(
                            if (kind == SleepKind.NIGHT) stringResource(R.string.sleep_start_night)
                            else stringResource(R.string.sleep_start_nap)
                        )
                    }
                }
            }
        }
    }
}

/** 目标完成度小环。 */
@Composable
private fun GoalRing(actualMin: Int, goalMin: Int, diameter: androidx.compose.ui.unit.Dp) {
    val fraction = (actualMin.toFloat() / goalMin).coerceIn(0f, 1f)
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val pct = (fraction * 100).toInt()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(diameter)) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = fraction * 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            "$pct%",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}

@Composable
private fun SleepHistoryRow(
    rec: SleepRecord,
    isZh: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val kindText = if (rec.kind == SleepKind.NAP.name)
        stringResource(R.string.sleep_kind_nap) else stringResource(R.string.sleep_kind_night)
    val fmt = remember(isZh) {
        DateTimeFormatter.ofPattern(
            if (isZh) "MM-dd HH:mm" else "MMM d, HH:mm",
            if (isZh) Locale.CHINESE else Locale.ENGLISH
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = sleepCardColors()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$kindText · ${fmt.format(Instant.ofEpochMilli(rec.sleepAtEpochMs).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    rec.minutes?.let { formatDurationMin(it, isZh) }
                        ?: stringResource(R.string.sleep_ongoing_night),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_back))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }
}

/**
 * 睡眠设置：
 * - 午休总开关（关闭时同步取消午休提醒 Work）
 * - 午休段（总开关开启后显示）：午休目标时长（5 分步进）+ 午休提醒开关与时刻
 * - 夜睡目标双模式：总时长（时/分滚轮）/ 入睡·起床时刻（实时换算目标，跨午夜环绕），
 *   两模式的值分别持久化，切换模式互不重置
 * - 夜睡入睡提醒开关 + 时刻
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSettingsDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository
    val isZh = remember {
        context.resources.configuration.locales[0].language == Locale.CHINESE.language
    }

    val napEnabled by settings.sleepNapEnabled.collectAsState(initial = false)
    val goalMode by settings.sleepGoalMode.collectAsState(
        initial = SettingsRepository.SLEEP_GOAL_MODE_DURATION
    )
    val goalMin by settings.sleepNightGoalMinutes.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_GOAL_MIN
    )
    val bedMinute by settings.sleepBedTimeMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_BED_TIME_MINUTE
    )
    val wakeMinute by settings.sleepWakeTimeMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_WAKE_TIME_MINUTE
    )
    val napGoalMin by settings.sleepNapGoalMinutes.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_NAP_GOAL_MIN
    )
    val napGoalMode by settings.sleepNapGoalMode.collectAsState(
        initial = SettingsRepository.SLEEP_GOAL_MODE_DURATION
    )
    val napStartMinute by settings.sleepNapStartMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_NAP_START_MINUTE
    )
    val napEndMinute by settings.sleepNapEndMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_NAP_END_MINUTE
    )
    val isNapDurationMode = napGoalMode != SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE
    val napReminderEnabled by settings.sleepNapReminderEnabled.collectAsState(initial = false)
    val napReminderMinute by settings.sleepNapReminderMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_NAP_REMINDER_MINUTE
    )
    val reminderEnabled by settings.sleepBedReminderEnabled.collectAsState(initial = false)
    val reminderMinute by settings.sleepBedReminderMinute.collectAsState(
        initial = SettingsRepository.SLEEP_DEFAULT_REMINDER_MINUTE
    )
    val isDurationMode = goalMode != SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_settings_entry)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 午休总开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.sleep_nap_enabled),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = napEnabled,
                        onCheckedChange = { v ->
                            scope.launch {
                                settings.setSleepNapEnabled(v)
                                // 关闭午休：午休提醒链一并取消；重新开启且提醒开关仍开则恢复排期
                                if (v) {
                                    if (napReminderEnabled) {
                                        SleepScheduler.rescheduleNap(context, true, napReminderMinute)
                                    }
                                } else {
                                    SleepScheduler.cancelNap(context)
                                }
                            }
                        }
                    )
                }

                // 午休设置段（仅总开关开启时显示）
                if (napEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sleep_nap_goal),
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = isNapDurationMode,
                            onClick = {
                                scope.launch {
                                    settings.setSleepNapGoalMode(SettingsRepository.SLEEP_GOAL_MODE_DURATION)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.sleep_goal_mode_duration)) }
                        SegmentedButton(
                            selected = !isNapDurationMode,
                            onClick = {
                                scope.launch {
                                    settings.setSleepNapGoalMode(SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.sleep_nap_goal_mode_bedwake)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (isNapDurationMode) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WheelNumberPicker(
                                value = (napGoalMin / 5) * 5,
                                onValueChange = { v ->
                                    scope.launch { settings.setSleepNapGoalMinutes(v) }
                                },
                                range = 5..180 step 5,
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(R.string.sleep_wheel_minute))
                        }
                    } else {
                        Text(stringResource(R.string.sleep_nap_start_time),
                            style = MaterialTheme.typography.titleSmall)
                        WheelTimePicker(
                            hour = napStartMinute / 60,
                            minute = napStartMinute % 60,
                            onHourChange = { h ->
                                val v = h * 60 + (napStartMinute % 60) / 5 * 5
                                scope.launch { settings.setSleepNapStartMinute(v) }
                            },
                            onMinuteChange = { m ->
                                scope.launch { settings.setSleepNapStartMinute(napStartMinute / 60 * 60 + m) }
                            },
                            minuteStep = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.sleep_nap_end_time),
                            style = MaterialTheme.typography.titleSmall)
                        WheelTimePicker(
                            hour = napEndMinute / 60,
                            minute = napEndMinute % 60,
                            onHourChange = { h ->
                                val v = h * 60 + (napEndMinute % 60) / 5 * 5
                                scope.launch { settings.setSleepNapEndMinute(v) }
                            },
                            onMinuteChange = { m ->
                                scope.launch { settings.setSleepNapEndMinute(napEndMinute / 60 * 60 + m) }
                            },
                            minuteStep = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(
                                R.string.sleep_goal_equals,
                                formatDurationMin(settings.nightGoalMinutes(napStartMinute, napEndMinute), isZh)
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Alarm, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.sleep_nap_reminder_enabled),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = napReminderEnabled,
                            onCheckedChange = { v ->
                                scope.launch {
                                    settings.setSleepNapReminderEnabled(v)
                                    SleepScheduler.rescheduleNap(context, v, napReminderMinute)
                                }
                            }
                        )
                    }
                    if (napReminderEnabled) {
                        Text(stringResource(R.string.sleep_nap_reminder_time),
                            style = MaterialTheme.typography.titleSmall)
                        WheelTimePicker(
                            hour = napReminderMinute / 60,
                            minute = napReminderMinute % 60,
                            onHourChange = { h ->
                                val v = h * 60 + (napReminderMinute % 60) / 5 * 5
                                scope.launch {
                                    settings.setSleepNapReminderMinute(v)
                                    SleepScheduler.rescheduleNap(context, true, v)
                                }
                            },
                            onMinuteChange = { m ->
                                val v = napReminderMinute / 60 * 60 + m
                                scope.launch {
                                    settings.setSleepNapReminderMinute(v)
                                    SleepScheduler.rescheduleNap(context, true, v)
                                }
                            },
                            minuteStep = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sleep_night_goal),
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isDurationMode,
                        onClick = {
                            scope.launch {
                                settings.setSleepGoalMode(SettingsRepository.SLEEP_GOAL_MODE_DURATION)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(stringResource(R.string.sleep_goal_mode_duration)) }
                    SegmentedButton(
                        selected = !isDurationMode,
                        onClick = {
                            scope.launch {
                                settings.setSleepGoalMode(SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(stringResource(R.string.sleep_goal_mode_bedwake)) }
                }
                Spacer(Modifier.height(8.dp))
                if (isDurationMode) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WheelNumberPicker(
                            value = goalMin / 60,
                            onValueChange = { h ->
                                val m = goalMin % 60
                                scope.launch { settings.setSleepNightGoalMinutes(h * 60 + m) }
                            },
                            range = 3..15,
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(R.string.sleep_wheel_hour))
                        WheelNumberPicker(
                            value = goalMin % 60,
                            onValueChange = { m ->
                                val h = goalMin / 60
                                scope.launch { settings.setSleepNightGoalMinutes(h * 60 + m) }
                            },
                            range = 0..55 step 5,
                                format = { "%02d".format(it) },
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(R.string.sleep_wheel_minute))
                    }
                } else {
                    Text(stringResource(R.string.sleep_bed_time),
                        style = MaterialTheme.typography.titleSmall)
                    WheelTimePicker(
                        hour = bedMinute / 60,
                        minute = bedMinute % 60,
                        onHourChange = { h ->
                            val v = h * 60 + (bedMinute % 60) / 5 * 5
                            scope.launch { settings.setSleepBedTimeMinute(v) }
                        },
                        onMinuteChange = { m ->
                            scope.launch { settings.setSleepBedTimeMinute(bedMinute / 60 * 60 + m) }
                        },
                        minuteStep = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sleep_wake_time),
                        style = MaterialTheme.typography.titleSmall)
                    WheelTimePicker(
                        hour = wakeMinute / 60,
                        minute = wakeMinute % 60,
                        onHourChange = { h ->
                            val v = h * 60 + (wakeMinute % 60) / 5 * 5
                            scope.launch { settings.setSleepWakeTimeMinute(v) }
                        },
                        onMinuteChange = { m ->
                            scope.launch { settings.setSleepWakeTimeMinute(wakeMinute / 60 * 60 + m) }
                        },
                        minuteStep = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 实时换算目标（跨午夜环绕）
                    Text(
                        stringResource(
                            R.string.sleep_goal_equals,
                            formatDurationMin(settings.nightGoalMinutes(bedMinute, wakeMinute), isZh)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.Alarm, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sleep_bed_reminder_enabled),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { v ->
                            scope.launch {
                                settings.setSleepBedReminderEnabled(v)
                                SleepScheduler.reschedule(context, v, reminderMinute)
                            }
                        }
                    )
                }
                if (reminderEnabled) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WheelNumberPicker(
                            value = reminderMinute / 60,
                            onValueChange = { h ->
                                val m = reminderMinute % 60
                                val v = h * 60 + m
                                scope.launch {
                                    settings.setSleepBedReminderMinute(v)
                                    SleepScheduler.reschedule(context, true, v)
                                }
                            },
                            range = 0..23,
                            format = { "%02d".format(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Text(":")
                        WheelNumberPicker(
                            value = reminderMinute % 60,
                            onValueChange = { m ->
                                val v = reminderMinute / 60 * 60 + m
                                scope.launch {
                                    settings.setSleepBedReminderMinute(v)
                                    SleepScheduler.reschedule(context, true, v)
                                }
                            },
                            range = 0..59,
                            format = { "%02d".format(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

/** 分钟 → "7小时30分" / "45分" / "7h30m"。 */
internal fun formatDurationMin(totalMin: Int, isZh: Boolean): String {
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        isZh && h > 0 -> "${h}小时${m}分"
        isZh -> "${m}分"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}
