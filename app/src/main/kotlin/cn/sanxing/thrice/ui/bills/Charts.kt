package cn.sanxing.thrice.ui.bills

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val CHART_TEXT = TextStyle(fontSize = 10.sp)

/**
 * 近 7 天支出圆角柱形图：柱顶标金额，柱下标星期。
 */
@Composable
fun WeekBarChart(weekData: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val barColor = EXPENSE_RED
    val labelColor = Color(0xFF8A8A8A)
    // 星期标签需在组合期取资源，提前生成（Canvas 绘制作用域内不能调用 stringResource）
    val weekLabels = weekData.map { weekdayShort(it.first.dayOfWeek.value) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(132.dp)
    ) {
        if (weekData.isEmpty()) return@Canvas
        val maxV = (weekData.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(0.01)
        val slot = size.width / weekData.size
        val barW = (slot * 0.30f).coerceAtMost(26.dp.toPx())
        val topArea = 20.dp.toPx()
        val bottomArea = 18.dp.toPx()
        val chartH = size.height - topArea - bottomArea

        weekData.forEachIndexed { i, (date, value) ->
            val centerX = slot * i + slot / 2f
            val barH = if (value <= 0.0) 0f else (chartH * (value / maxV)).toFloat()
            val top = topArea + (chartH - barH)
            // 柱子
            if (barH > 0f) {
                val r = barW / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(centerX - barW / 2f, top),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(r, r)
                )
                // 柱顶金额
                val amountLayout = textMeasurer.measure(
                    compactMoney(value),
                    style = CHART_TEXT.copy(color = barColor, fontWeight = FontWeight.Medium)
                )
                drawText(
                    amountLayout,
                    topLeft = Offset(
                        centerX - amountLayout.size.width / 2f,
                        top - amountLayout.size.height - 2.dp.toPx()
                    )
                )
            }
            // 星期
            val weekName = weekLabels.getOrElse(i) { "" }
            val labelLayout = textMeasurer.measure(weekName, style = CHART_TEXT.copy(color = labelColor))
            drawText(
                labelLayout,
                topLeft = Offset(
                    centerX - labelLayout.size.width / 2f,
                    size.height - bottomArea + 2.dp.toPx()
                )
            )
        }
    }
}

/**
 * 整月每日支出柱形图（细圆角柱 + 首/中/末日日期标注）。
 */
@Composable
fun MonthDailyChart(
    days: Int,
    values: List<Double>,
    monthPrefix: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val barColor = EXPENSE_RED
    val labelColor = Color(0xFF8A8A8A)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        val maxV = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
        val slot = size.width / days
        val barW = (slot * 0.55f).coerceAtMost(14.dp.toPx())
        val topArea = 8.dp.toPx()
        val bottomArea = 20.dp.toPx()
        val chartH = size.height - topArea - bottomArea

        // 基线
        drawLine(
            color = labelColor.copy(alpha = 0.35f),
            start = Offset(0f, topArea + chartH),
            end = Offset(size.width, topArea + chartH),
            strokeWidth = 1.dp.toPx()
        )

        values.forEachIndexed { i, value ->
            if (value <= 0.0) return@forEachIndexed
            val centerX = slot * i + slot / 2f
            val barH = (chartH * (value / maxV)).toFloat()
            val top = topArea + (chartH - barH)
            val r = barW / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(centerX - barW / 2f, top),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(r, r)
            )
        }

        // 首 / 中 / 末日标签
        val labelTargets = listOf(0, days / 2, days - 1)
        labelTargets.forEach { dayIndex ->
            val text = "%s-%02d".format(monthPrefix, dayIndex + 1)
            val layout = textMeasurer.measure(text, style = CHART_TEXT.copy(color = labelColor))
            val x = when (dayIndex) {
                0 -> 0f
                days - 1 -> size.width - layout.size.width
                else -> slot * dayIndex + slot / 2f - layout.size.width / 2f
            }
            drawText(layout, topLeft = Offset(x, size.height - bottomArea + 4.dp.toPx()))
        }
    }
}

/**
 * 全年 12 个月支出圆角柱形图：柱下标 1..12（月）。
 */
@Composable
fun YearBarsChart(values: List<Double>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val barColor = EXPENSE_RED
    val labelColor = Color(0xFF8A8A8A)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {
        val n = 12
        val maxV = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
        val slot = size.width / n
        val barW = (slot * 0.55f).coerceAtMost(18.dp.toPx())
        val topArea = 10.dp.toPx()
        val bottomArea = 20.dp.toPx()
        val chartH = size.height - topArea - bottomArea

        drawLine(
            color = labelColor.copy(alpha = 0.35f),
            start = Offset(0f, topArea + chartH),
            end = Offset(size.width, topArea + chartH),
            strokeWidth = 1.dp.toPx()
        )

        values.forEachIndexed { i, value ->
            val centerX = slot * i + slot / 2f
            if (value > 0.0) {
                val barH = (chartH * (value / maxV)).toFloat()
                val top = topArea + (chartH - barH)
                val r = barW / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(centerX - barW / 2f, top),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(r, r)
                )
            }
            val labelLayout = textMeasurer.measure("${i + 1}", style = CHART_TEXT.copy(color = labelColor))
            drawText(
                labelLayout,
                topLeft = Offset(
                    centerX - labelLayout.size.width / 2f,
                    size.height - bottomArea + 4.dp.toPx()
                )
            )
        }
    }
}

/**
 * 环形饼图。[items] 为 (名称, 颜色, 金额)；中心绘制 [centerText]。
 * 空数据时画一圈灰底环。
 */
@Composable
fun DonutChart(
    items: List<Triple<String, Color, Double>>,
    centerText: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val trackColor = Color(0xFFE0E0E0)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val diameter = size.minDimension * 0.78f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val strokeWidth = 30.dp.toPx()

        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth)
        )

        val total = items.sumOf { it.third }
        if (total > 0.0) {
            var startAngle = -90f
            items.forEach { (_, color, value) ->
                val sweep = (value / total * 360.0).toFloat()
                drawArc(
                    color = color,
                    startAngle = startAngle + 1f,
                    sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }

        val centerLayout = textMeasurer.measure(
            centerText,
            style = TextStyle(fontSize = 13.sp, color = Color(0xFF666666))
        )
        drawText(
            centerLayout,
            topLeft = Offset(
                size.width / 2f - centerLayout.size.width / 2f,
                size.height / 2f - centerLayout.size.height / 2f
            )
        )
    }
}

/** 柱顶金额：尽量短（41 / 36.5 / 185.38）。 */
private fun compactMoney(v: Double): String {
    val s = "%.2f".format(v)
    return when {
        s.endsWith(".00") -> s.dropLast(3)
        s.endsWith("0") -> s.dropLast(1)
        else -> s
    }
}
