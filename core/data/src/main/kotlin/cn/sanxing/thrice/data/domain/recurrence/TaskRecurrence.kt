package cn.sanxing.thrice.data.domain.recurrence

import cn.sanxing.thrice.data.domain.model.Task
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 任务重复规则（R10）。序列化为 JSON 存入 Task.ruleJson。
 *
 * - [kind] 规则类型；[NONE] 等价于无规则（Task.ruleJson 应为 null）。
 * - [weekdays] WEEKLY 生效：ISO 周一=1 … 周日=7。
 * - [interval] INTERVAL 生效：每 N 天。
 * - [dates] DATES 生效：用户指定的一组具体日期（不依赖锚点）。
 * - MONTHLY / YEARLY 的「几日 / 几月几日」直接取锚点 Task.date，
 *   修改开始日期即整体平移，不额外存储。
 * - [endDate] / [maxCount] 对所有按锚点重复的类型生效，取更严格者。
 */
data class TaskRecurrence(
    val kind: Kind,
    val weekdays: Set<Int> = emptySet(),
    val interval: Int = 1,
    val dates: Set<LocalDate> = emptySet(),
    val endDate: LocalDate? = null,
    val maxCount: Int? = null
) {

    enum class Kind {
        NONE,
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY,
        LEGAL_WORKDAY,
        EBBINGHAUS,
        DATES,
        INTERVAL
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("k", kind.name)
        if (weekdays.isNotEmpty()) json.put("w", JSONArray(weekdays.sorted()))
        if (kind == Kind.INTERVAL) json.put("i", interval.coerceAtLeast(1))
        if (dates.isNotEmpty()) json.put("d", JSONArray(dates.sorted().map { it.toString() }))
        endDate?.let { json.put("e", it.toString()) }
        maxCount?.let { json.put("c", it) }
        return json.toString()
    }

    companion object {
        /** 艾宾浩斯复习点：学习当天 + 第 1/2/4/7/15/30 天。 */
        val EBBINGHAUS_OFFSETS: List<Long> = listOf(0L, 1, 2, 4, 7, 15, 30)

        fun fromJson(json: String?): TaskRecurrence? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                val kind = runCatching { Kind.valueOf(obj.optString("k")) }.getOrNull()
                    ?: return@runCatching null
                if (kind == Kind.NONE) return@runCatching null
                val weekdays = obj.optJSONArray("w")?.let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }.filter { it in 1..7 }.toSet()
                } ?: emptySet()
                val dates = obj.optJSONArray("d")?.let { arr ->
                    (0 until arr.length())
                        .mapNotNull { runCatching { LocalDate.parse(arr.getString(it)) }.getOrNull() }
                        .toSet()
                } ?: emptySet()
                TaskRecurrence(
                    kind = kind,
                    weekdays = weekdays,
                    interval = obj.optInt("i", 1).coerceAtLeast(1),
                    dates = dates,
                    endDate = obj.optString("e").takeIf { it.isNotBlank() }
                        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    maxCount = if (obj.has("c")) obj.getInt("c").takeIf { it > 0 } else null
                )
            }.getOrNull()
        }
    }
}

/** 中国大陆法定工作日判定（放假日 + 调休上班周末）。 */
object LegalHolidays {

    /**
     * 2026 年放假安排（依据《国务院办公厅关于 2026 年部分节假日安排的通知》
     * 国办发明电〔2025〕7号）：
     * 元旦 1/1-1/3；春节 2/15-2/23；清明 4/4-4/6；劳动节 5/1-5/5；
     * 端午 6/19-6/21；中秋 9/25-9/27；国庆 10/1-10/7。
     */
    private val HOLIDAYS_2026: Set<LocalDate> = buildSet {
        addAll(dateRange(2026, 1, 1..3))
        addAll(dateRange(2026, 2, 15..23))
        addAll(dateRange(2026, 4, 4..6))
        addAll(dateRange(2026, 5, 1..5))
        addAll(dateRange(2026, 6, 19..21))
        addAll(dateRange(2026, 9, 25..27))
        addAll(dateRange(2026, 10, 1..7))
    }

    /** 2026 年因调休需要上班的周末：1/4、2/14、2/28、5/9、9/20、10/10。 */
    private val WORK_WEEKENDS_2026: Set<LocalDate> = setOf(
        LocalDate.of(2026, 1, 4),
        LocalDate.of(2026, 2, 14),
        LocalDate.of(2026, 2, 28),
        LocalDate.of(2026, 5, 9),
        LocalDate.of(2026, 9, 20),
        LocalDate.of(2026, 10, 10)
    )

    private fun dateRange(year: Int, month: Int, days: IntRange): List<LocalDate> =
        days.map { LocalDate.of(year, month, it) }

    /**
     * 是否法定工作日：工作日（周一至周五）且非法定放假日；调休上班的周末算工作日。
     * 没有内置数据表的年份回落到「周一至周五」。
     */
    fun isWorkday(date: LocalDate): Boolean {
        if (date.year == 2026) {
            if (date in HOLIDAYS_2026) return false
            if (date in WORK_WEEKENDS_2026) return true
        }
        return date.dayOfWeek.value in 1..5
    }
}

// ----------------------------------------------------------------------
// Task 扩展：规则解析 / 发生日展开 / 分次完成
// ----------------------------------------------------------------------

/** 解析任务的重复规则；null 或非法 JSON 均视为不重复。 */
fun Task.parsedRecurrence(): TaskRecurrence? = TaskRecurrence.fromJson(ruleJson)

/** 该任务是否为重复任务。 */
val Task.isRecurring: Boolean
    get() = parsedRecurrence()?.let { it.kind != TaskRecurrence.Kind.NONE } == true

private fun parseTaskDate(value: String?): LocalDate? =
    value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun Task.completedDateSet(): Set<String> =
    completedDates.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** 该任务在 [date] 当天是否已完成：重复任务查分次记录，旧任务用整体 completed。 */
fun Task.isCompletedOn(date: LocalDate): Boolean {
    if (!isRecurring) return completed
    return date.toString() in completedDateSet()
}

/** 切换某一发生日的完成态，返回更新后的 Task（不写库）。 */
fun Task.toggleCompletionOn(date: LocalDate): Task {
    if (!isRecurring) return copy(completed = !completed)
    val set = completedDateSet().toMutableSet()
    val key = date.toString()
    if (!set.add(key)) set.remove(key)
    return copy(completedDates = set.sorted().joinToString(","))
}

private fun TaskRecurrence.matches(anchor: LocalDate, date: LocalDate): Boolean {
    if (date < anchor) return false
    return when (kind) {
        TaskRecurrence.Kind.DAILY -> true
        TaskRecurrence.Kind.INTERVAL -> {
            val n = interval.coerceAtLeast(1)
            (date.toEpochDay() - anchor.toEpochDay()) % n == 0L
        }
        TaskRecurrence.Kind.WEEKLY -> date.dayOfWeek.value in weekdays
        TaskRecurrence.Kind.MONTHLY -> date.dayOfMonth == anchor.dayOfMonth
        TaskRecurrence.Kind.YEARLY ->
            date.monthValue == anchor.monthValue && date.dayOfMonth == anchor.dayOfMonth
        TaskRecurrence.Kind.LEGAL_WORKDAY -> LegalHolidays.isWorkday(date)
        TaskRecurrence.Kind.EBBINGHAUS ->
            date.toEpochDay() - anchor.toEpochDay() in TaskRecurrence.EBBINGHAUS_OFFSETS
        TaskRecurrence.Kind.DATES -> date in dates
        TaskRecurrence.Kind.NONE -> date == anchor
    }
}

/**
 * 展开任务在闭区间 [[from], [to]] 内的全部发生日（已排序）。
 * - 无规则：锚点（任务日期）落在区间内则只有它自身；
 * - DATES：指定日期与区间的交集（可不设置锚点）；
 * - 其余规则：从锚点起逐日匹配，受 endDate / maxCount 约束。
 */
fun Task.occurrencesInRange(from: LocalDate, to: LocalDate): List<LocalDate> {
    val anchor = parseTaskDate(date)
    val rule = parsedRecurrence()
    if (rule == null || rule.kind == TaskRecurrence.Kind.NONE) {
        return listOfNotNull(anchor?.takeIf { it in from..to })
    }
    if (rule.kind == TaskRecurrence.Kind.DATES) {
        return rule.dates.filter { it in from..to }.sorted()
    }
    if (anchor == null) return emptyList()

    // 次数必须从锚点起累计，即使展开窗口晚于锚点；上界取 endDate 或锚点后 10 年
    val limit = rule.endDate ?: anchor.plusYears(10)
    val hardEnd = minOf(to, limit)
    if (maxOf(from, anchor) > hardEnd) return emptyList()

    val result = ArrayList<LocalDate>()
    var cursor: LocalDate = anchor
    var matchCount = 0
    while (cursor <= hardEnd) {
        if (rule.matches(anchor, cursor)) {
            matchCount++
            if (rule.maxCount != null && matchCount > rule.maxCount) break
            if (cursor >= from) result.add(cursor)
        }
        cursor = cursor.plusDays(1)
    }
    return result
}

/**
 * 找到不早于 [from] 的第一个发生日；无未来发生返回 null。
 * @param skipCompleted 跳过已记录完成的发生日（提醒排程用）。
 * 扫描上界：endDate，否则锚点后 10 年（纯内存逐日扫描，成本可接受）。
 */
fun Task.firstOccurrenceOnOrAfter(from: LocalDate, skipCompleted: Boolean = false): LocalDate? {
    val anchor = parseTaskDate(date)
    val rule = parsedRecurrence()
    if (rule == null || rule.kind == TaskRecurrence.Kind.NONE) {
        if (anchor == null || anchor < from) return null
        if (skipCompleted && completed) return null
        return anchor
    }
    if (rule.kind == TaskRecurrence.Kind.DATES) {
        return rule.dates.sorted().firstOrNull { it >= from && (!skipCompleted || !isCompletedOn(it)) }
    }
    if (anchor == null) return null

    val completed = completedDateSet()
    val limit = rule.endDate ?: anchor.plusYears(10)
    var cursor: LocalDate = anchor
    var matchCount = 0
    while (cursor <= limit) {
        if (rule.matches(anchor, cursor)) {
            matchCount++
            if (rule.maxCount != null && matchCount > rule.maxCount) return null
            if (cursor >= from && (!skipCompleted || cursor.toString() !in completed)) return cursor
        }
        cursor = cursor.plusDays(1)
    }
    return null
}
