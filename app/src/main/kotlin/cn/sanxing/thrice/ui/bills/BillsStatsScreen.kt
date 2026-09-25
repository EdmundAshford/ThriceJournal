package cn.sanxing.thrice.ui.bills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import androidx.compose.ui.res.stringResource
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class StatsPeriod { MONTH, YEAR }

/**
 * 账单统计（独立全屏页，参考「钱迹」统计页）：
 * 默认按月构建饼图，可切换按年；饼图分类依据即账单分类（标签）。
 * 总览（收支/结余/日均）· 周期柱图（月=每日，年=每月）· 分类环形饼图 + 明细。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsStatsScreen(
    container: AppContainer,
    initialMonth: YearMonth,
    onBack: () -> Unit
) {
    val allBills by container.billDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.billCategoryDao.observeAll().collectAsState(initial = emptyList())

    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    var month by remember { mutableStateOf(initialMonth) }
    var year by remember { mutableStateOf(initialMonth.year) }
    var pieType by remember { mutableStateOf(BillType.EXPENSE) }

    val today = LocalDate.now()

    // 当前统计周期内的账单
    val periodBills: List<Bill> = when (period) {
        StatsPeriod.MONTH -> allBills.filter { it.date.startsWith(month.toString()) }
        StatsPeriod.YEAR -> allBills.filter { it.date.startsWith("$year-") }
    }
    val expense = periodBills.filter { it.type == BillType.EXPENSE }.sumOf { it.amount }
    val income = periodBills.filter { it.type == BillType.INCOME }.sumOf { it.amount }
    val balance = income - expense

    // 日均支出分母
    val daysInPeriod = when (period) {
        StatsPeriod.MONTH -> when {
            month == YearMonth.now() -> today.dayOfMonth
            month.isBefore(YearMonth.now()) -> month.lengthOfMonth()
            else -> 1
        }
        StatsPeriod.YEAR -> when {
            year == today.year -> today.dayOfYear
            year < today.year -> YearMonth.of(year, 12).let { ym ->
                (1..12).sumOf { YearMonth.of(year, it).lengthOfMonth() }
            }
            else -> 1
        }
    }
    val dailyAvg = expense / daysInPeriod

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bills_stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 月 / 年 切换
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StatsPeriod.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = period == p,
                        onClick = { period = p },
                        shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size)
                    ) { Text(if (p == StatsPeriod.MONTH) stringResource(R.string.stats_by_month) else stringResource(R.string.stats_by_year)) }
                }
            }

            // 周期切换
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (period == StatsPeriod.MONTH) month = month.minusMonths(1) else year--
                }) { Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.stats_prev_period_cd)) }
                Text(
                    when (period) {
                        StatsPeriod.MONTH -> month.format(DateTimeFormatter.ofPattern("yyyy-MM"))
                        StatsPeriod.YEAR -> stringResource(R.string.stats_year_fmt, year)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                IconButton(onClick = {
                    if (period == StatsPeriod.MONTH) month = month.plusMonths(1) else year++
                }) { Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.stats_next_period_cd)) }
            }

            // 总览
            StatsCard(title = stringResource(R.string.stats_overview)) {
                Row(Modifier.fillMaxWidth()) {
                    PreviewCell(stringResource(R.string.bill_expense), "¥${formatMoney(expense)}", EXPENSE_RED, Modifier.weight(1f))
                    PreviewCell(stringResource(R.string.bill_income), "¥${formatMoney(income)}", INCOME_GREEN, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    PreviewCell(
                        stringResource(R.string.bill_balance),
                        (if (balance >= 0) "¥" else "-¥") + formatMoney(kotlin.math.abs(balance)),
                        Color.Unspecified,
                        Modifier.weight(1f)
                    )
                    PreviewCell(stringResource(R.string.stats_daily_avg), "¥${formatMoney(dailyAvg)}", Color.Unspecified, Modifier.weight(1f))
                }
            }

            // 周期柱图
            StatsCard(title = if (period == StatsPeriod.MONTH) stringResource(R.string.stats_daily_expense) else stringResource(R.string.stats_monthly_expense)) {
                // 日期一律走 UiUtils.parseDateOrNull：账单可能来自用户导入的备份 JSON，
                // 里面的非法日期（空串、被截断的串）会让裸 parse / substring 直接崩溃
                val expenses = periodBills.filter { it.type == BillType.EXPENSE }
                if (period == StatsPeriod.MONTH) {
                    val dayCount = month.lengthOfMonth()
                    val spendByDay = expenses
                        .mapNotNull { b -> UiUtils.parseDateOrNull(b.date)?.dayOfMonth?.let { it to b.amount } }
                        .groupingBy { it.first }
                        .fold(0.0) { acc, (_, amount) -> acc + amount }
                    MonthDailyChart(
                        days = dayCount,
                        values = (1..dayCount).map { spendByDay[it] ?: 0.0 },
                        monthPrefix = "%02d".format(Locale.US, month.monthValue)
                    )
                } else {
                    val spendByMonth = expenses
                        .mapNotNull { b -> UiUtils.parseDateOrNull(b.date)?.monthValue?.let { it to b.amount } }
                        .groupingBy { it.first }
                        .fold(0.0) { acc, (_, amount) -> acc + amount }
                    YearBarsChart(values = (1..12).map { spendByMonth[it] ?: 0.0 })
                }
            }

            // 分类饼图（依据账单标签）
            StatsCard(title = stringResource(R.string.stats_category_share)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BillType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = pieType == t,
                            onClick = { pieType = t },
                            shape = SegmentedButtonDefaults.itemShape(i, BillType.entries.size)
                        ) { Text(if (t == BillType.EXPENSE) stringResource(R.string.bill_expense) else stringResource(R.string.bill_income)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val pieBills = periodBills.filter { it.type == pieType }
                val categoryAgg = pieBills
                    .groupBy { it.category }
                    .map { (name, list) ->
                        val cat = categories.firstOrNull { c -> c.name == name }
                        Triple(name, parseCategoryColor(cat?.colorHex), list.sumOf { it.amount })
                    }
                    .sortedByDescending { it.third }
                val pieTotal = categoryAgg.sumOf { it.third }

                if (pieTotal <= 0.0) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                R.string.stats_no_records_fmt,
                                if (pieType == BillType.EXPENSE) stringResource(R.string.bill_expense) else stringResource(R.string.bill_income)
                            ),
                            color = Color.Gray
                        )
                    }
                } else {
                    DonutChart(
                        items = categoryAgg,
                        centerText = if (pieType == BillType.EXPENSE) stringResource(R.string.stats_expense_share) else stringResource(R.string.stats_income_share)
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    categoryAgg.forEach { (name, color, value) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(12.dp).background(color, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(name, Modifier.weight(1f))
                            Text(
                                "%.1f%%".format(value / pieTotal * 100.0),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                "¥${formatMoney(value)}",
                                fontSize = 13.sp,
                                modifier = Modifier.width(90.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PreviewCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor
        )
    }
}
