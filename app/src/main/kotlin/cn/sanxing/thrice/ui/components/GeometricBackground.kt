package cn.sanxing.thrice.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.ui.theme.LocalExtendedColors
import kotlin.random.Random

/**
 * 几何抽象背景：圆、三角、矩形、复杂凌乱线条（固定随机种子 → 每次绘制一致，不闪烁）。
 *
 * 性能要点：
 * - 所有几何元素在 onDraw 之外 remember 预构建，绘制帧内只做 draw；
 * - 动画仅是 graphicsLayer 的缓慢平移（周期 24s 往返，幅度 ~20dp），无逐帧重计算；
 * - [animated] = false 时不创建无限动画（设置页「背景动画」开关）。
 */
@Composable
fun GeometricBackground(
    modifier: Modifier = Modifier,
    seed: Int = 20260831,
    animated: Boolean = true
) {
    val extended = LocalExtendedColors.current
    val amplitudePx = with(LocalDensity.current) { 20.dp.toPx() }

    val drift: Float = if (animated) {
        val transition = rememberInfiniteTransition(label = "geoBg")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 24_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "geoDrift"
        )
        value
    } else {
        0f
    }

    // 预构建几何元素（种子固定；主题色变化时重建）
    val shapes = remember(seed, extended.lineColor, extended.geoBlockColor) {
        buildShapes(seed, extended.lineColor, extended.geoBlockColor)
    }

    Canvas(
        modifier = modifier.graphicsLayer {
            translationX = (drift * 2f - 1f) * amplitudePx
            translationY = (1f - drift * 2f) * amplitudePx * 0.5f
        }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        withTransform({ scale(w / 1000f, h / 1000f, pivot = Offset.Zero) }) {
            for (s in shapes) {
                when (s) {
                    is GeoShape.Circle -> drawCircle(s.color, radius = s.radius, center = s.center, style = s.stroke)
                    is GeoShape.Rect -> drawRect(s.color, topLeft = s.topLeft, size = s.size, style = s.stroke)
                    is GeoShape.Triangle -> drawPath(s.path, s.color, style = s.stroke)
                    is GeoShape.Line -> drawPath(s.path, s.color, style = s.stroke)
                }
            }
        }
    }
}

private sealed class GeoShape {
    data class Circle(val center: Offset, val radius: Float, val color: Color, val stroke: Stroke) : GeoShape()
    data class Rect(val topLeft: Offset, val size: Size, val color: Color, val stroke: Stroke) : GeoShape()
    data class Triangle(val path: Path, val color: Color, val stroke: Stroke) : GeoShape()
    data class Line(val path: Path, val color: Color, val stroke: Stroke) : GeoShape()
}

/** 在 1000×1000 逻辑坐标系内布置形状，绘制时按实际画布大小整体缩放。 */
private fun buildShapes(seed: Int, lineColor: Color, blockColor: Color): List<GeoShape> {
    val rnd = Random(seed)
    val shapes = mutableListOf<GeoShape>()
    val alphaSpan = 0.12f - 0.04f
    fun alpha() = 0.04f + rnd.nextFloat() * alphaSpan

    // 圆：6 个
    repeat(6) {
        shapes.add(
            GeoShape.Circle(
                center = Offset(rnd.nextFloat() * 1000f, rnd.nextFloat() * 1000f),
                radius = 40f + rnd.nextFloat() * 130f,
                color = (if (it % 2 == 0) lineColor else blockColor).copy(alpha = alpha()),
                stroke = Stroke(width = 2f + rnd.nextFloat() * 4f)
            )
        )
    }

    // 矩形：6 个
    repeat(6) {
        shapes.add(
            GeoShape.Rect(
                topLeft = Offset(rnd.nextFloat() * 900f, rnd.nextFloat() * 900f),
                size = Size(60f + rnd.nextFloat() * 200f, 40f + rnd.nextFloat() * 160f),
                color = (if (it % 2 == 0) blockColor else lineColor).copy(alpha = alpha()),
                stroke = Stroke(width = 2f + rnd.nextFloat() * 3f)
            )
        )
    }

    // 三角形：5 个
    repeat(5) {
        val x = rnd.nextFloat() * 900f
        val y = rnd.nextFloat() * 900f
        val s = 70f + rnd.nextFloat() * 160f
        val p = Path().apply {
            moveTo(x, y)
            lineTo(x + s, y + rnd.nextFloat() * s * 0.6f)
            lineTo(x + s * 0.4f, y + s)
            close()
        }
        shapes.add(GeoShape.Triangle(p, lineColor.copy(alpha = alpha()), Stroke(width = 2f + rnd.nextFloat() * 3f)))
    }

    // 复杂凌乱线条：14 条折线（长短不一、随机折点，"有秩序的凌乱"）
    repeat(14) {
        val p = Path()
        var x = rnd.nextFloat() * 1000f
        var y = rnd.nextFloat() * 1000f
        p.moveTo(x, y)
        repeat(3 + rnd.nextInt(6)) {
            x = (x + (rnd.nextFloat() - 0.5f) * 360f).coerceIn(0f, 1000f)
            y = (y + (rnd.nextFloat() - 0.5f) * 360f).coerceIn(0f, 1000f)
            p.lineTo(x, y)
        }
        shapes.add(GeoShape.Line(p, lineColor.copy(alpha = alpha()), Stroke(width = 1.5f + rnd.nextFloat() * 2.5f)))
    }
    return shapes
}
