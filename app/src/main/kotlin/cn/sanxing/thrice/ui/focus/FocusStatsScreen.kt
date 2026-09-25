package cn.sanxing.thrice.ui.focus

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusStatus
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.stats.BarChart
import cn.sanxing.thrice.ui.common.stats.BarPoint
import cn.sanxing.thrice.ui.common.stats.DonutPieChart
import cn.sanxing.thrice.ui.common.stats.PieSlice
import cn.sanxing.thrice.ui.common.stats.StatsBucket
import cn.sanxing.thrice.ui.common.stats.StatsBuckets
import cn.sanxing.thrice.ui.common.stats.StatsPeriod
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private const val FILTER_ALL = -2L
private const val FILTER_UNCLASSIFIED = -1L
private val UNCLASSIFIED_COLOR = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusStatsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isZh = remember {
        context.resources.configuration.locales[0].language == Locale.CHINESE.language
    }

    val sessions by container.focusSessionDao.observeAll().collectAsState(initial = emptyList())
    val tags by container.focusTagDao.observeAll().collectAsState(initial = emptyList())

    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var chartPie by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    var pendingDelete by remember { mutableStateOf<FocusSession?>(null) }

    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val buckets = remember(period) {
        StatsBuckets.forPeriod(period, today) { _, ldt -> bucketLabel(period, ldt, isZh) }
    }

    val completed = sessions.filter { it.status == FocusStatus.COMPLETED.name }
    val filtered = completed.filter { s ->
        when (filter) {
            FILTER_ALL -> true
            FILTER_UNCLASSIFIED -> s.tagId == null
            else -> s.tagId == filter
        }
    }

    val values = StatsBuckets.aggregate(
        buckets = buckets,
        items = filtered,
        timestampMs = { it.startedAtEpochMs },
        value = { it.focusedSeconds / 60.0 },
        zone = zone
    )
    val rangeMinutes = values.sum()
    val rangeSessions = filtered.count {
        buckets.any { b -> b.containsEpochMs(it.startedAtEpochMs, zone) }
    }
    val spanDays = when (period) {
        StatsPeriod.DAY -> 1
        StatsPeriod.WEEK -> 7
        StatsPeriod.MONTH -> today.lengthOfMonth()
        StatsPeriod.YEAR -> if (today.isLeapYear) 366 else 365
    }
    val dailyAvgMin = rangeMinutes / spanDays

    // 连续天数（截至今天 / 昨天，向前数有完成会话的自然日）
    val completedDays = completed.map {
        Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate()
    }.toSet()
    var streak = 0
    var cursor = today
    if (!completedDays.contains(cursor)) cursor = cursor.minusDays(1)
    while (completedDays.contains(cursor)) {
        streak++
        cursor = cursor.minusDays(1)
    }

    // 标签聚合（饼图）
    val tagNameById = tags.associate { it.id to it.name }
    val tagColorById = tags.associate { it.id to Color(it.colorArgb) }
    val byTag = filtered.groupBy { it.tagId }
        .map { (id, list) ->
            PieSlice(
                label = id?.let { tagNameById[it] } ?: stringResource(R.string.focus_tag_none),
                color = id?.let { tagColorById[it] } ?: UNCLASSIFIED_COLOR,
                value = list.sumOf { it.focusedSeconds / 60.0 }
            )
        }
        .filter { it.value > 0 }
        .sortedByDescending { it.value }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.focus_stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back))
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
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val periods = StatsPeriod.entries
                    periods.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = period == p,
                            onClick = { period = p },
                            shape = SegmentedButtonDefaults.itemShape(i, periods.size)
                        ) { Text(periodLabel(p)) }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard(
                        stringResource(R.string.focus_stats_total),
                        formatHours(rangeMinutes, isZh),
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.focus_stats_sessions),
                        rangeSessions.toString(),
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.focus_stats_daily_avg),
                        formatHours(dailyAvgMin, isZh),
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        stringResource(R.string.focus_stats_streak),
                        stringResource(R.string.focus_stats_streak_days, streak),
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                // 标签过滤
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = filter == FILTER_ALL,
                            onClick = { filter = FILTER_ALL },
                            label = { Text(stringResource(R.string.focus_filter_all)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == FILTER_UNCLASSIFIED,
                            onClick = { filter = FILTER_UNCLASSIFIED },
                            label = { Text(stringResource(R.string.focus_tag_none)) }
                        )
                    }
                    items(tags, key = { it.id }) { tag ->
                        FilterChip(
                            selected = filter == tag.id,
                            onClick = { filter = tag.id },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(Color(tag.colorArgb), CircleShape)
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(tag.name)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !chartPie,
                        onClick = { chartPie = false },
                        label = { Text(stringResource(R.string.focus_chart_bar)) }
                    )
                    FilterChip(
                        selected = chartPie,
                        onClick = { chartPie = true },
                        label = { Text(stringResource(R.string.focus_chart_pie)) }
                    )
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = focusCardColors()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (!chartPie) {
                            BarChart(
                                points = buckets.mapIndexed { i, b ->
                                    BarPoint(b.label, values[i])
                                },
                                barColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                formatValue = { it.toInt().toString() }
                            )
                        } else if (byTag.isNotEmpty()) {
                            DonutPieChart(
                                slices = byTag,
                                centerText = formatHours(rangeMinutes, isZh) + "\n" +
                                    stringResource(R.string.focus_stats_total)
                            )
                            Spacer(Modifier.height(6.dp))
                            byTag.forEach { s ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(s.color, CircleShape)
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(s.label, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f))
                                    Text(
                                        "${formatHours(s.value, isZh)}  " +
                                            "(${percentText(s.value, rangeMinutes, isZh)})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            EmptyHint()
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.focus_stats_sessions),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            val inRange = sessions
                .filter { buckets.any { b -> b.containsEpochMs(it.startedAtEpochMs, zone) } }
                .sortedByDescending { it.startedAtEpochMs }
            if (inRange.isEmpty()) {
                item { EmptyHint() }
            } else {
                items(inRange, key = { it.id }) { s ->
                    SessionRow(
                        session = s,
                        tagName = s.tagId?.let { tagNameById[it] },
                        tagColor = s.tagId?.let { tagColorById[it] },
                        isZh = isZh,
                        onDelete = { pendingDelete = s }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.focus_session_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.focusSessionDao.deleteById(s.id) }
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

/** 卡片底色跟随「主体界面浓度」（壁纸模式下透出壁纸）。 */
@Composable
private fun focusCardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current)
)

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = focusCardColors()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: FocusSession,
    tagName: String?,
    tagColor: Color?,
    isZh: Boolean,
    onDelete: () -> Unit
) {
    val dt = remember(session.startedAtEpochMs) {
        java.time.format.DateTimeFormatter
            .ofPattern(if (isZh) "MM-dd HH:mm" else "MMM d, HH:mm")
            .withLocale(if (isZh) Locale.CHINESE else Locale.ENGLISH)
            .format(Instant.ofEpochMilli(session.startedAtEpochMs).atZone(ZoneId.systemDefault()))
    }
    val statusText = when (runCatching { FocusStatus.valueOf(session.status) }.getOrNull()) {
        FocusStatus.COMPLETED -> stringResource(R.string.focus_status_completed)
        FocusStatus.FAILED -> stringResource(R.string.focus_status_failed)
        FocusStatus.ABANDONED -> stringResource(R.string.focus_status_abandoned)
        else -> session.status
    }
    val statusColor = when (runCatching { FocusStatus.valueOf(session.status) }.getOrNull()) {
        FocusStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        FocusStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), colors = focusCardColors()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tagColor != null) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(tagColor, CircleShape)
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        tagName ?: stringResource(R.string.focus_tag_none),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "$dt  ·  ${formatHmsLocal(session.focusedSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.focus_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun periodLabel(p: StatsPeriod): String = when (p) {
    StatsPeriod.DAY -> stringResource(R.string.focus_period_day)
    StatsPeriod.WEEK -> stringResource(R.string.focus_period_week)
    StatsPeriod.MONTH -> stringResource(R.string.focus_period_month)
    StatsPeriod.YEAR -> stringResource(R.string.focus_period_year)
}

private fun bucketLabel(period: StatsPeriod, t: LocalDateTime, isZh: Boolean): String = when (period) {
    StatsPeriod.DAY -> t.hour.toString()
    StatsPeriod.WEEK -> when (t.dayOfWeek) {
        DayOfWeek.MONDAY -> if (isZh) "一" else "Mon"
        DayOfWeek.TUESDAY -> if (isZh) "二" else "Tue"
        DayOfWeek.WEDNESDAY -> if (isZh) "三" else "Wed"
        DayOfWeek.THURSDAY -> if (isZh) "四" else "Thu"
        DayOfWeek.FRIDAY -> if (isZh) "五" else "Fri"
        DayOfWeek.SATURDAY -> if (isZh) "六" else "Sat"
        DayOfWeek.SUNDAY -> if (isZh) "日" else "Sun"
        null -> ""
    }
    StatsPeriod.MONTH -> t.dayOfMonth.toString()
    StatsPeriod.YEAR -> if (isZh) "${t.monthValue}月"
    else t.month.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH)
}

/** 分钟 → 中文 "1.2 小时"/"45 分"；英文 "1.2 h"/"45 m"。 */
private fun formatHours(min: Double, isZh: Boolean): String =
    if (min >= 60.0) String.format(Locale.US, "%.1f", min / 60.0) +
        (if (isZh) " 小时" else " h")
    else "${min.toInt()}" + (if (isZh) " 分" else " m")

private fun percentText(value: Double, total: Double, isZh: Boolean): String {
    val p = if (total <= 0) 0 else (value / total * 100).toInt()
    return if (isZh) "$p%" else "$p%"
}

private fun formatHmsLocal(total: Long): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
