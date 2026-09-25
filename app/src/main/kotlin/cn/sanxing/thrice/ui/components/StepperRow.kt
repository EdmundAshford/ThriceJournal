package cn.sanxing.thrice.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.ui.theme.LocalExtendedColors

/**
 * 四步步骤条（导入页）：圆点 + 连接线 + 当前步高亮。
 */
@Composable
fun StepperRow(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, label ->
            val active = index == currentStep
            val done = index < currentStep
            // 圆点
            Canvas(Modifier.size(18.dp)) {
                val r = size.minDimension / 2f
                drawCircle(
                    color = when {
                        active -> extended.lineColor
                        done -> extended.lineColor.copy(alpha = 0.6f)
                        else -> extended.lineColor.copy(alpha = 0.22f)
                    }
                )
                if (active) {
                    drawCircle(
                        color = extended.paperColor,
                        radius = r * 0.38f
                    )
                }
                if (done) {
                    drawCircle(
                        color = extended.paperColor,
                        radius = r * 0.30f
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) scheme.onBackground else scheme.onBackground.copy(alpha = 0.55f)
            )
            if (index != steps.lastIndex) {
                Spacer(Modifier.width(6.dp))
                // 连接线
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                ) {
                    drawLine(
                        color = if (done) extended.lineColor else extended.lineColor.copy(alpha = 0.22f),
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Square
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}
