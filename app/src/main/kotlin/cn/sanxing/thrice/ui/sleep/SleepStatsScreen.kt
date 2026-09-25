package cn.sanxing.thrice.ui.sleep

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.stats.BarChart
import cn.sanxing.thrice.ui.common.stats.BarPoint
import cn.sanxing.thrice.ui.common.stats.DonutPieChart
import cn.sanxing.thrice.ui.common.stats.PieSlice
import cn.sanxing.thrice.ui.common.stats.StatsBuckets
import cn.sanxing.thrice.ui.common.stats.StatsPeriod
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepStatsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val isZh = remember {
        context.resources.configuration.locales[0].language == Locale.CHINESE.language
    }

    val records by container.sleepRecordDao.observeAll().collectAsState(initial = emptyList())
    val settings = container.settingsRepository
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
    // 当前模式下生效的夜睡目标（入睡/起床模式按跨午夜环绕换算）
    val effectiveNightGoal =
        if (goalMode == SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE) {
            settings.nightGoalMinutes(bedMinute, wakeMinute)
        } else goalMin

    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val buckets = remember(period) {
        StatsBuckets.forPeriod(period, today) { p, t -> sleepBucketLabel(p, t, isZh) }
    }

    val closed = records.filter { it.minutes != null }
    val nightRecords = closed.filter { it.kind == SleepKind.NIGHT.name }
    val napRecords = closed.filter { it.kind == SleepKind.NAP.name }

    val nightValues = StatsBuckets.aggregate(
        buckets, nightRecords,
        timestampMs = { it.sleepAtEpochMs },
        value = { it.minutes!!.toDouble() },
        zone = zone
    )
    val napValues = StatsBuckets.aggregate(
        buckets, napRecords,
        timestampMs = { it.sleepAtEpochMs },
        value = { it.minutes!!.toDouble() },
        zone = zone
    )

    val inRange = closed.filter { rec ->
        buckets.any { it.containsEpochMs(rec.sleepAtEpochMs, zone) }
    }
    val nightsInRange = inRange.filter { it.kind == SleepKind.NIGHT.name }
    val napsInRange = inRange.filter { it.kind == SleepKind.NAP.name }
    val avgNight = nightsInRange.map { it.minutes!! }.averageOrZero()
    val avgNap = napsInRange.map { it.minutes!! }.averageOrZero()
    // 夜睡达成率：有夜睡记录的桶中，夜睡总时长 >= 当前模式有效目标的占比
    val goalHitBuckets = buckets.indices.count { i ->
        nightValues[i] >= effectiveNightGoal && nightValues[i] > 0
    }
    val nightBucketCount = buckets.indices.count { i -> nightValues[i] > 0 }
    val hitRate = if (nightBucketCount == 0) 0 else goalHitBuckets * 100 / nightBucketCount
    // 午休达成率：有午休记录的桶中，午休总时长 >= 午休目标的占比（与夜睡独立）
    val napHitBuckets = buckets.indices.count { i ->
        napValues[i] >= napGoalMin && napValues[i] > 0
    }
    val napBucketCount = buckets.indices.count { i -> napValues[i] > 0 }
    val napHitRate = if (napBucketCount == 0) 0 else napHitBuckets * 100 / napBucketCount

    val totalNight = nightRecords.sumOf { it.minutes!! }.toDouble()
    val totalNap = napRecords.sumOf { it.minutes!! }.toDouble()
    val rangeTotalNight = nightValues.sum()
    val rangeTotalNap = napValues.sum()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sleep_stats_title)) },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniCard(stringResource(R.string.sleep_stats_avg_night),
                        formatDurationMin(avgNight, isZh), Modifier.weight(1f))
                    MiniCard(stringResource(R.string.sleep_stats_avg_nap),
                        formatDurationMin(avgNap, isZh), Modifier.weight(1f))
                    MiniCard(stringResource(R.string.sleep_stats_night_goal_rate),
                        "$hitRate%", Modifier.weight(1f))
                    MiniCard(stringResource(R.string.sleep_stats_nap_goal_rate),
                        "$napHitRate%", Modifier.weight(1f))
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.sleep_chart_trend),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        BarChart(
                            points = buckets.mapIndexed { i, b ->
                                BarPoint(b.label, nightValues[i])
                            },
                            barColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            referenceValue = effectiveNightGoal.toDouble(),
                            referenceColor = MaterialTheme.colorScheme.tertiary,
                            formatValue = { it.toInt().toString() }
                        )
                        Spacer(Modifier.height(4.dp))
                        LegendDot(MaterialTheme.colorScheme.primary,
                            stringResource(R.string.sleep_kind_night))
                        Spacer(Modifier.height(2.dp))
                        LegendDot(MaterialTheme.colorScheme.tertiary,
                            stringResource(R.string.sleep_goal_title) + " " +
                                formatDurationMin(effectiveNightGoal, isZh))
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.sleep_stats_distribution),
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val slices = buildList {
                            if (rangeTotalNight > 0) add(
                                PieSlice(
                                    stringResource(R.string.sleep_kind_night),
                                    Color(0xFF5B8DEF),
                                    rangeTotalNight
                                )
                            )
                            if (rangeTotalNap > 0) add(
                                PieSlice(
                                    stringResource(R.string.sleep_kind_nap),
                                    Color(0xFFF2B65D),
                                    rangeTotalNap
                                )
                            )
                        }
                        if (slices.isEmpty()) {
                            Text(
                                stringResource(R.string.sleep_no_records),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        } else {
                            DonutPieChart(
                                slices = slices,
                                centerText = formatDurationMin(
                                                    (rangeTotalNight + rangeTotalNap).toInt(), isZh)
                            )
                            slices.forEach { s ->
                                val total = rangeTotalNight + rangeTotalNap
                                val pct = if (total <= 0) 0 else (s.value / total * 100).toInt()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    LegendDot(s.color, s.label)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${formatDurationMin(s.value.toInt(), isZh)}  $pct%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MiniCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
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
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun periodLabel(p: StatsPeriod): String = when (p) {
    StatsPeriod.DAY -> stringResource(R.string.sleep_period_day)
    StatsPeriod.WEEK -> stringResource(R.string.sleep_period_week)
    StatsPeriod.MONTH -> stringResource(R.string.sleep_period_month)
    StatsPeriod.YEAR -> stringResource(R.string.sleep_period_year)
}

private fun sleepBucketLabel(period: StatsPeriod, t: LocalDateTime, isZh: Boolean): String = when (period) {
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

private fun List<Int>.averageOrZero(): Int =
    if (isEmpty()) 0 else (sum().toDouble() / size).toInt()
