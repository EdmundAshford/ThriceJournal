package cn.sanxing.thrice.ui.common.stats

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
import kotlin.math.roundToInt

/** 柱图数据点：标签（柱下）、数值、柱色（null=主题主色）。 */
data class BarPoint(
    val label: String,
    val value: Double,
    val color: Color? = null
)

/**
 * 通用圆角柱形图。
 * - 自动按最大值归一化；
 * - 桶数 ≤12 时柱顶标注数值（[formatValue]）；
 * - [referenceValue] > 0 时绘制虚线参考线（如睡眠目标）；
 * - 空数据（全 0）仅显示基线，不报错。
 */
@Composable
fun BarChart(
    points: List<BarPoint>,
    modifier: Modifier = Modifier,
    barColor: Color,
    labelColor: Color,
    height: androidx.compose.ui.unit.Dp = 170.dp,
    formatValue: (Double) -> String = { it.roundToInt().toString() },
    referenceValue: Double = 0.0,
    referenceColor: Color = barColor
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (points.isEmpty()) return@Canvas
        val topArea = 18.dp.toPx()
        val bottomArea = 18.dp.toPx()
        val chartH = size.height - topArea - bottomArea
        val baseline = topArea + chartH
        val maxData = (points.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.01)
        val maxV = if (referenceValue > 0) maxOf(maxData, referenceValue) else maxData
        val slot = size.width / points.size
        val barW = (slot * if (points.size > 16) 0.62f else 0.5f).coerceAtMost(22.dp.toPx())

        // 基线
        drawLine(
            color = labelColor.copy(alpha = 0.30f),
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1.dp.toPx()
        )

        // 目标参考线
        if (referenceValue > 0) {
            val y = baseline - (chartH * (referenceValue / maxV)).toFloat()
            drawLine(
                color = referenceColor.copy(alpha = 0.75f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(10f, 8f), 0f
                )
            )
        }

        val showValues = points.size <= 12
        val labelStep = if (points.size <= 12) 1 else if (points.size <= 31) 5 else 1

        points.forEachIndexed { i, p ->
            val centerX = slot * i + slot / 2f
            if (p.value > 0.0) {
                val barH = (chartH * (p.value / maxV)).toFloat()
                val top = baseline - barH
                drawRoundRect(
                    color = p.color ?: barColor,
                    topLeft = Offset(centerX - barW / 2f, top),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
                if (showValues) {
                    val layout = textMeasurer.measure(
                        formatValue(p.value),
                        style = TextStyle(
                            fontSize = 9.sp,
                            color = p.color ?: barColor,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    drawText(
                        layout,
                        topLeft = Offset(
                            centerX - layout.size.width / 2f,
                            top - layout.size.height - 2.dp.toPx()
                        )
                    )
                }
            }
            if (i % labelStep == 0 || i == points.lastIndex) {
                val layout = textMeasurer.measure(
                    p.label,
                    style = TextStyle(fontSize = 9.sp, color = labelColor)
                )
                val x = when (i) {
                    points.lastIndex -> (centerX - layout.size.width / 2f)
                        .coerceAtMost(size.width - layout.size.width)
                    else -> centerX - layout.size.width / 2f
                }.coerceAtLeast(0f)
                drawText(layout, topLeft = Offset(x, baseline + 3.dp.toPx()))
            }
        }
    }
}

/** 饼图扇区：名称、颜色、数值。 */
data class PieSlice(
    val label: String,
    val color: Color,
    val value: Double
)

/**
 * 环形饼图：空数据画灰底环 + 居中 [centerText]。
 * 图例由调用方在 Composable 中自行排版（颜色点 + 名称 + 百分比）。
 */
@Composable
fun DonutPieChart(
    slices: List<PieSlice>,
    centerText: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 210.dp,
    centerColor: Color = Color(0xFF666666)
) {
    val textMeasurer = rememberTextMeasurer()
    val trackColor = Color(0xFFE0E0E0)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val diameter = size.minDimension * 0.82f
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

        val total = slices.sumOf { it.value }
        if (total > 0.0) {
            var startAngle = -90f
            slices.forEach { s ->
                val sweep = (s.value / total * 360.0).toFloat()
                drawArc(
                    color = s.color,
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

        val lines = centerText.split("\n")
        val layouts = lines.map {
            textMeasurer.measure(
                it,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = centerColor,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        val totalH = layouts.sumOf { it.size.height }
        var y = size.height / 2f - totalH / 2f
        layouts.forEach { layout ->
            drawText(
                layout,
                topLeft = Offset(size.width / 2f - layout.size.width / 2f, y)
            )
            y += layout.size.height
        }
    }
}
