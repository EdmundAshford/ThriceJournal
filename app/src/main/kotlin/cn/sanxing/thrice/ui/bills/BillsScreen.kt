package cn.sanxing.thrice.ui.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val EXPENSE_RED = Color(0xFFE53935)
internal val INCOME_GREEN = Color(0xFF43A047)
internal val CARD_SHAPE = RoundedCornerShape(14.dp)

/** 单笔金额上限：防止极大输入变成 Infinity/NaN 污染统计与图表。 */
private const val MAX_BILL_AMOUNT = 1e9

/** 金额展示：¥12.3 / ¥12.30（去掉多余尾零但保留金额感）。 */
fun formatMoney(v: Double): String {
    // 显式 Locale.US：不指定时 %.2f 在德语等 locale 下输出 "12,30"
    val s = "%.2f".format(Locale.US, v)
    return if (s.endsWith(".00")) s.dropLast(3) else s
}

/** 解析 #RRGGBB；非法时回退灰色。 */
fun parseCategoryColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "")) }.getOrDefault(Color(0xFF9E9E9E))

/** 星期几的短名（跟随系统语言）。 */
@Composable
internal fun weekdayShort(dayOfWeek: Int): String = stringResource(when (dayOfWeek) {
    1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu
    5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun
})

/**
 * 账单主页（参考「钱迹」）：
 * 月份切换 · 当月收支汇总 · 近 7 天柱形图 · 账单按日期倒序分组 · 记一笔/编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    container: AppContainer,
    onOpenStats: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val allBills by container.billDao.observeAll().collectAsState(initial = emptyList())
    val categories by container.billCategoryDao.observeAll().collectAsState(initial = emptyList())

    var month by remember { mutableStateOf(YearMonth.now()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Bill?>(null) }
    var manageCats by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val monthPrefix = month.toString() // yyyy-MM
    val monthBills = allBills.filter { it.date.startsWith(monthPrefix) }
    val monthExpense = monthBills.filter { it.type == BillType.EXPENSE }.sumOf { it.amount }
    val monthIncome = monthBills.filter { it.type == BillType.INCOME }.sumOf { it.amount }

    // 近 7 天（含今天）支出
    val weekStart = today.minusDays(6)
    val weekDays = (0..6).map { weekStart.plusDays(it.toLong()) }
    val weekSpendByDate = allBills
        .filter { it.type == BillType.EXPENSE }
        .groupingBy { it.date }
        .fold(0.0) { acc, b -> acc + b.amount }
    val weekData = weekDays.map { d -> d to (weekSpendByDate[d.toString()] ?: 0.0) }
    val weekTotal = weekData.sumOf { it.second }

    val grouped = monthBills.groupBy { it.date }

    // 兜底分类名（删除分类时把账单迁移过去）
    val fallbackExpense = stringResource(R.string.bill_other_expense)
    val fallbackIncome = stringResource(R.string.bill_other_income)

    Scaffold(
        // 不透明 surface 底色，盖住全局 GeometricBackground（与课表页一致）
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.task_month_prev_cd))
                        }
                        Text(
                            month.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { month = YearMonth.now() }
                        )
                        IconButton(onClick = { month = month.plusMonths(1) }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.task_month_next_cd))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { manageCats = true }) {
                        Icon(Icons.Filled.Category, contentDescription = stringResource(R.string.bills_cd_category))
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.bills_cd_stats))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.bills_cd_add))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MonthSummaryCard(
                    expense = monthExpense,
                    income = monthIncome
                )
            }
            item {
                WeekBarsCard(weekData = weekData, total = weekTotal)
            }

            if (grouped.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bills_empty_month),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            grouped.forEach { (date, bills) ->
                // 导入的备份可能带非法日期；解析不出就跳过该组，不要崩整个账单页
                val parsed = UiUtils.parseDateOrNull(date) ?: return@forEach
                item(key = "day-$date") {
                    DayBillCard(
                        date = parsed,
                        today = today,
                        bills = bills,
                        colorOf = { name -> categories.firstOrNull { it.name == name }?.colorHex },
                        onClick = { editing = it }
                    )
                }
            }
        }
    }

    if (showAdd) {
        BillEditDialog(
            existing = null,
            categories = categories,
            defaultDate = if (month == YearMonth.now()) today else month.atDay(1),
            onDismiss = { showAdd = false },
            onSave = { bill ->
                scope.launch { container.billDao.upsert(bill) }
                showAdd = false
            }
        )
    }
    editing?.let { bill ->
        BillEditDialog(
            existing = bill,
            categories = categories,
            defaultDate = LocalDate.parse(bill.date),
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch { container.billDao.upsert(updated) }
                editing = null
            },
            onDelete = {
                scope.launch { container.billDao.delete(bill) }
                editing = null
            }
        )
    }
    if (manageCats) {
        CategoryManagerDialog(
            categories = categories,
            onDismiss = { manageCats = false },
            onAdd = { cat -> scope.launch { container.billCategoryDao.upsert(cat) } },
            onSave = { existing, newName, newColor ->
                scope.launch {
                    if (existing.name != newName) {
                        container.billCategoryDao.rename(existing.name, newName)
                        container.billDao.reparentCategory(existing.name, newName)
                    }
                    container.billCategoryDao.upsert(existing.copy(name = newName, colorHex = newColor))
                }
            },
            onDelete = { cat ->
                scope.launch {
                    // 先把该分类下的账单挂到「其他」兜底分类，再删除
                    val fallback = if (cat.type == BillType.EXPENSE) fallbackExpense else fallbackIncome
                    if (fallback != cat.name) {
                        runCatching {
                            container.billDao.reparentCategory(cat.name, fallback)
                        }
                    }
                    container.billCategoryDao.delete(cat)
                }
            }
        )
    }
}

/** 当月支出 / 收入 / 结余卡片。 */
@Composable
private fun MonthSummaryCard(expense: Double, income: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.bills_month_expense), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(
                "¥${formatMoney(expense)}",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = EXPENSE_RED
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.bill_income), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥${formatMoney(income)}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = INCOME_GREEN)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.bill_balance), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val balance = income - expense
                    Text(
                        (if (balance >= 0) "¥" else "-¥") + formatMoney(kotlin.math.abs(balance)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** 近 7 天支出柱形图卡片。 */
@Composable
private fun WeekBarsCard(weekData: List<Pair<LocalDate, Double>>, total: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.bills_last_7_days), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.bills_total_fmt, formatMoney(total)), color = EXPENSE_RED, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            WeekBarChart(weekData)
        }
    }
}

/** 某一天的账单卡片：头部日期 + 当日支出，下方逐条流水。 */
@Composable
private fun DayBillCard(
    date: LocalDate,
    today: LocalDate,
    bills: List<Bill>,
    colorOf: (String) -> String?,
    onClick: (Bill) -> Unit
) {
    val daySpend = bills.filter { it.type == BillType.EXPENSE }.sumOf { it.amount }
    val dayIncome = bills.filter { it.type == BillType.INCOME }.sumOf { it.amount }
    val label = when (date) {
        today -> stringResource(R.string.week_today)
        today.minusDays(1) -> stringResource(R.string.date_yesterday)
        else -> weekdayShort(date.dayOfWeek.value)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${date.monthValue.toString().padStart(2, '0')}.${date.dayOfMonth.toString().padStart(2, '0')}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (daySpend > 0) {
                    Text(stringResource(R.string.bills_day_expense_fmt, formatMoney(daySpend)), color = EXPENSE_RED, fontSize = 13.sp)
                }
                if (dayIncome > 0) {
                    if (daySpend > 0) Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.bills_day_income_fmt, formatMoney(dayIncome)), color = INCOME_GREEN, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            bills.forEach { bill ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick(bill) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(parseCategoryColor(colorOf(bill.category)), CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bill.category, fontSize = 15.sp)
                        if (bill.note.isNotBlank()) {
                            Text(
                                bill.note,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        (if (bill.type == BillType.EXPENSE) "-" else "+") + formatMoney(bill.amount),
                        color = if (bill.type == BillType.EXPENSE) EXPENSE_RED else INCOME_GREEN,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * 新增 / 编辑一笔账单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillEditDialog(
    existing: Bill?,
    categories: List<BillCategory>,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Bill) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var type by remember { mutableStateOf(existing?.type ?: BillType.EXPENSE) }
    var amountText by remember { mutableStateOf(existing?.let { formatMoney(it.amount) } ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var date by remember { mutableStateOf(defaultDate) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }

    val typeCats = categories.filter { it.type == type }
    // 切换收支类型后，若当前分类不在新类型里，自动选第一个
    LaunchedEffect(type, typeCats) {
        if (category !in typeCats.map { it.name }) {
            category = typeCats.firstOrNull()?.name ?: ""
        }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    // 必须是有限值且有上界：极大数会变成 Infinity / NaN 落入数据库，
    // 之后 sumOf 得到 Infinity、图表归一化（value / maxV）得到 NaN，整页图表错乱。
    val valid = amount > 0 && amount.isFinite() && amount <= MAX_BILL_AMOUNT && category.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) stringResource(R.string.bill_add_title) else stringResource(R.string.bill_edit_title)) },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    (existing ?: Bill(
                        amount = 0.0,
                        category = "",
                        date = date.toString()
                    )).copy(
                        type = type,
                        amount = amount,
                        category = category,
                        date = date.toString(),
                        note = note.trim()
                    )
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (existing != null && onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = EXPENSE_RED) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        text = {
            Column {
                // 支出 / 收入 切换
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BillType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, BillType.entries.size)
                        ) { Text(if (t == BillType.EXPENSE) stringResource(R.string.bill_expense) else stringResource(R.string.bill_income)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { s ->
                        // 只允许数字和小数点（最多两位）
                        val filtered = s.filter { it.isDigit() || it == '.' }
                        val normalized = when {
                            filtered.isEmpty() -> ""
                            filtered.count { it == '.' } > 1 -> filtered.dropLast(1)
                            else -> filtered
                        }
                        if (normalized == "" || normalized.matches(Regex("""\d*\.?\d{0,2}"""))) {
                            amountText = normalized
                        }
                    },
                    label = { Text(stringResource(R.string.bill_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // 分类 chips
                if (typeCats.isEmpty()) {
                    Text(stringResource(R.string.bill_no_category), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(typeCats, key = { it.name }) { cat ->
                            FilterChip(
                                selected = category == cat.name,
                                onClick = { category = cat.name },
                                label = { Text(cat.name) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(parseCategoryColor(cat.colorHex), CircleShape)
                                    )
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.bill_note_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )

    if (showDatePicker) {
        // DatePicker 以 UTC 零点归一 millis，初值与回读都按 UTC，避免东八区错位一天
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
