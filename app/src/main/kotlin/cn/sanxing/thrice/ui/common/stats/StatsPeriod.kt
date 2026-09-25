package cn.sanxing.thrice.ui.common.stats

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 统计维度：天（24 小时桶）/ 周（7 天桶，周一起）/ 月（按天桶）/ 年（12 月桶）。 */
enum class StatsPeriod { DAY, WEEK, MONTH, YEAR }

/**
 * 一个统计桶：[start] 含、[end] 不含；[label] 为短标签（调用方按语言生成后回填）。
 */
data class StatsBucket(
    val index: Int,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val label: String
) {
    fun containsEpochMs(epochMs: Long, zone: ZoneId): Boolean {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        return !t.isBefore(start) && t.isBefore(end)
    }
}

object StatsBuckets {

    /**
     * 生成 [anchor] 所在周期的全部分桶：
     * - DAY：当日 00:00 起 24 个小时桶；
     * - WEEK：周一起 7 个天桶；
     * - MONTH：当月 1 号起按天桶（28..31 个）；
     * - YEAR：12 个月桶。
     *
     * [labelOf] 由调用方提供（便于双语），参数为桶起始 LocalDateTime。
     */
    fun forPeriod(
        period: StatsPeriod,
        anchor: LocalDate,
        labelOf: (StatsPeriod, LocalDateTime) -> String
    ): List<StatsBucket> {
        var idx = 0
        fun bucket(start: LocalDateTime, end: LocalDateTime) =
            StatsBucket(idx++, start, end, labelOf(period, start))

        return when (period) {
            StatsPeriod.DAY -> {
                val base = anchor.atStartOfDay()
                (0 until 24).map { h ->
                    bucket(base.withHour(h), base.withHour(h).plusHours(1))
                }
            }
            StatsPeriod.WEEK -> {
                // ISO 周一 = 1，换算成本周周一
                val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
                (0 until 7).map { d ->
                    val day = monday.plusDays(d.toLong())
                    bucket(day.atStartOfDay(), day.plusDays(1).atStartOfDay())
                }
            }
            StatsPeriod.MONTH -> {
                val first = anchor.withDayOfMonth(1)
                val days = first.lengthOfMonth()
                (0 until days).map { d ->
                    val day = first.plusDays(d.toLong())
                    bucket(day.atStartOfDay(), day.plusDays(1).atStartOfDay())
                }
            }
            StatsPeriod.YEAR -> {
                val first = anchor.withDayOfYear(1).atStartOfDay()
                (0 until 12).map { m ->
                    val start = first.plusMonths(m.toLong())
                    bucket(start, start.plusMonths(1))
                }
            }
        }
    }

    /**
     * 把带时间戳的条目聚合进桶（时间戳落在桶区间内即计入）。
     * 返回与 [buckets] 等长、同序的聚合值数组。
     */
    fun <T> aggregate(
        buckets: List<StatsBucket>,
        items: List<T>,
        timestampMs: (T) -> Long,
        value: (T) -> Double,
        zone: ZoneId = ZoneId.systemDefault()
    ): DoubleArray {
        val out = DoubleArray(buckets.size)
        if (items.isEmpty()) return out
        items.forEach { item ->
            val ms = timestampMs(item)
            // 二分定位（桶连续有序）
            val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
            val idx = buckets.indexOfFirst { !t.isBefore(it.start) && t.isBefore(it.end) }
            if (idx in buckets.indices) out[idx] += value(item)
        }
        return out
    }
}
