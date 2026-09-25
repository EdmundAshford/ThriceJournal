package cn.sanxing.thrice.ui.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** 排水动画最短时长（空瓶），约 0.9s。 */
private const val DRAIN_DURATION_MIN_MS = 900
/** 排水动画最长时长（满瓶），约 2.4s。 */
private const val DRAIN_DURATION_MAX_MS = 2400

private enum class SphereVisual { NORMAL, DRAINING }

/**
 * 玻璃水球：玻璃瓶质感圆球 + 双层正弦波水面 + 顶部滴水。
 *
 * @param fill 0..1 充水比例；[drip] 为 true 时播放水滴下落动画；
 * 水波自行无限平移。颜色随主题传入。
 * @param shatter true 时播放一次「放弃专注」动画（瓶身始终完好）：触发瞬间锁定瓶中水量，
 * 瓶内水位匀速下降；同时从球的最底点垂直流出一束水流——水流粗细与动画时长
 * 均由放弃瞬间的水量决定（水越多流越粗、耗时越久），结束回调 [onShatterEnd]；
 * 从 true 切回 false 时恢复为完整空球（用于下一轮专注）。
 */
@Composable
fun WaterSphere(
    fill: Float,
    modifier: Modifier = Modifier,
    waterColor: Color = Color(0xFF4FA8E0),
    waterDeepColor: Color = Color(0xFF2E7DB8),
    glassTint: Color = Color(0xCCFFFFFF),
    glassBorder: Color = Color(0x99FFFFFF),
    drip: Boolean = false,
    shatter: Boolean = false,
    onShatterEnd: () -> Unit = {},
    diameter: Dp = 260.dp
) {
    val transition = rememberInfiniteTransition(label = "water")
    // 两层波相位（速度 / 振幅不同，制造折射层次感）
    val phase1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave1"
    )
    val phase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Restart),
        label = "wave2"
    )
    // 水滴循环下落（1.4s 一轮）
    val drop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "drop"
    )

    val clampedFill = fill.coerceIn(0f, 1f)

    // 排水动画本地状态机：进入 DRAINING 后按触发瞬间水量决定的时长播放，结束回 NORMAL
    // （此时外部水位已归 0，呈空瓶）；shatter 回 false（新一轮 / reset）恢复 NORMAL。
    var visual by remember { mutableStateOf(SphereVisual.NORMAL) }
    // 放弃瞬间锁定的瓶中水量（0..1）：水流粗细 / 动画时长 / 瓶内水位全部以此为快照，
    // 不受动画期间外部 fill 变化影响。
    var drainInitialFill by remember { mutableStateOf(0f) }
    val drainProgress = remember { Animatable(0f) }
    LaunchedEffect(shatter) {
        if (shatter) {
            val initialFill = clampedFill
            drainInitialFill = initialFill
            visual = SphereVisual.DRAINING
            drainProgress.snapTo(0f)
            val durationMs = (DRAIN_DURATION_MIN_MS +
                (DRAIN_DURATION_MAX_MS - DRAIN_DURATION_MIN_MS) * initialFill).toInt()
            drainProgress.animateTo(1f, tween(durationMs, easing = LinearEasing))
            visual = SphereVisual.NORMAL
            onShatterEnd()
        } else if (visual != SphereVisual.NORMAL) {
            visual = SphereVisual.NORMAL
            drainProgress.snapTo(0f)
        }
    }

    val density = LocalDensity.current
    val d = with(density) { diameter.toPx() }

    // 画布比球高：下方预留约 0.3 个球位给排出的水流；球垂直居中绘制（top = (H-d)/2），
    // 球心仍在整个组件的中心，与 FocusScreen 叠在组件中心的计时文字保持对齐。
    Canvas(modifier.size(width = diameter, height = diameter * 1.6f)) {
        val left = (size.width - d) / 2f
        val top = (size.height - d) / 2f
        val r = d / 2f
        val cx = left + r
        val cy = top + r

        val circlePath = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(left, top, left + d, top + d))
        }

        // 排水中：瓶身保持完整，水位按放弃瞬间的水量随进度匀速下降至 0
        val drawnFill = if (visual == SphereVisual.DRAINING) {
            val drainT = easeInOut((drainProgress.value / 0.62f).coerceIn(0f, 1f))
            drainInitialFill * (1f - drainT)
        } else {
            clampedFill
        }

        drawNormalSphere(
            circlePath = circlePath, left = left, top = top, d = d, r = r, cx = cx, cy = cy,
            fill = drawnFill, phase1 = phase1, phase2 = phase2,
            waterColor = waterColor, waterDeepColor = waterDeepColor,
            glassTint = glassTint, glassBorder = glassBorder,
            drip = drip && visual == SphereVisual.NORMAL, drop = drop
        )

        if (visual == SphereVisual.DRAINING) {
            drawSingleDrainStream(
                initialFill = drainInitialFill,
                d = d, r = r, cx = cx, cy = cy,
                waterColor = waterColor, waterDeepColor = waterDeepColor,
                progress = drainProgress.value
            )
        }
    }
}

/** 常态：玻璃瓶体 + 双层波水体 + 滴水 + 描边高光。 */
private fun DrawScope.drawNormalSphere(
    circlePath: Path,
    left: Float, top: Float, d: Float, r: Float, cx: Float, cy: Float,
    fill: Float, phase1: Float, phase2: Float,
    waterColor: Color, waterDeepColor: Color, glassTint: Color, glassBorder: Color,
    drip: Boolean, drop: Float
) {
    // ---- 玻璃瓶体（径向渐变玻璃底） ----
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glassTint.copy(alpha = 0.55f),
                glassTint.copy(alpha = 0.18f),
                glassTint.copy(alpha = 0.05f)
            ),
            center = Offset(cx - r * 0.25f, cy - r * 0.3f),
            radius = d
        ),
        radius = r,
        center = Offset(cx, cy)
    )

    // ---- 水体（裁在圆内，双层正弦波） ----
    clipPath(circlePath) {
        drawWaterLayer(
            left = left, top = top, d = d, r = r,
            fill = fill, phase = phase2,
            amplitudeRatio = 0.022f,
            color = waterDeepColor.copy(alpha = 0.55f),
            waveCount = 1.6f
        )
        drawWaterLayer(
            left = left, top = top, d = d, r = r,
            fill = fill, phase = phase1,
            amplitudeRatio = 0.016f,
            color = waterColor.copy(alpha = 0.8f),
            waveCount = 1.2f
        )
    }

    // ---- 顶部下落水滴 ----
    if (drip && fill < 0.985f) {
        val surfaceY = cy + r - fill * d
        val dropStartY = top + d * 0.10f
        val dy = dropStartY + (surfaceY - dropStartY) * drop
        val alpha = if (drop > 0.85f) 1f - (drop - 0.85f) / 0.15f else 1f
        val dropR = d * 0.018f * (1f - drop * 0.35f)
        drawCircle(
            color = waterColor.copy(alpha = 0.9f * alpha),
            radius = dropR,
            center = Offset(cx + d * 0.06f, dy)
        )
    }

    // ---- 玻璃边缘厚描边 ----
    drawCircle(
        color = glassBorder.copy(alpha = 0.75f),
        radius = r - 1.5f,
        center = Offset(cx, cy),
        style = Stroke(width = d * 0.012f)
    )

    // ---- 高光斑（左上大光斑 + 右上小光斑） ----
    drawOval(
        color = Color.White.copy(alpha = 0.28f),
        topLeft = Offset(cx - r * 0.62f, cy - r * 0.78f),
        size = Size(d * 0.30f, d * 0.16f)
    )
    drawOval(
        color = Color.White.copy(alpha = 0.18f),
        topLeft = Offset(cx + r * 0.28f, cy - r * 0.6f),
        size = Size(d * 0.16f, d * 0.08f)
    )
}

/**
 * 排水过程（progress 0..1）：从玻璃球的最底点 (cx, cy+r) 垂直流出的【单束水流】。
 * 水束粗细与放弃瞬间的瓶中水量 [initialFill]（0..1）成正比——水越多束越粗、
 * 排水耗时也越长（时长在 [WaterSphere] 中确定）；排水过程中水束随余水减少变细，
 * 瓶水将尽时整体淡出。水量过半时束边额外溅出少量水滴，水滴大小同样随水量。
 */
private fun DrawScope.drawSingleDrainStream(
    initialFill: Float,
    d: Float, r: Float, cx: Float, cy: Float,
    waterColor: Color, waterDeepColor: Color,
    progress: Float
) {
    val p = progress.coerceIn(0f, 1f)
    val f0 = initialFill.coerceIn(0f, 1f)
    if (f0 <= 0.004f || p < 0.02f) return

    // 出发点：球的最底点（略向内收，避免与球面间出现缝隙）
    val startX = cx
    val startY = cy + r - d * 0.004f

    // 瓶内剩余比例（与瓶内水位下降节奏一致：progress 0..0.62 排完）
    val drainT = easeInOut((p / 0.62f).coerceIn(0f, 1f))
    val remainRatio = (1f - drainT).coerceIn(0f, 1f)

    // 末段整体淡出
    val fade = 1f - ((p - 0.88f) / 0.12f).coerceIn(0f, 1f)
    if (fade <= 0f) return

    // 水束宽度：由放弃瞬间的水量决定（约为球径的 0.8%~4.8%），排水中随余水变细
    val wMax = d * (0.008f + 0.040f * f0)
    val wTop = wMax * (0.30f + 0.70f * remainRatio)
    if (wTop <= 0.4f) return

    // 水束前端推进（easeOut：快速伸长后趋稳，模拟自由落水）
    val headT = easeOutCubic(((p - 0.02f) / 0.34f).coerceIn(0f, 1f))
    val maxLen = d * 0.28f
    val headY = startY + maxLen * headT

    // 轻微摆动，避免水带像完全僵硬的直线
    val swayAmp = d * 0.004f + wTop * 0.12f
    val phase = p * 7.5f
    val steps = 10
    fun xAt(f: Float) = startX + sin(f * 4.5f + phase) * swayAmp

    // 上宽下窄的连续水带：顶部贴着球底，尾部收成细束
    val path = Path()
    path.moveTo(xAt(0f) - wTop, startY)
    for (i in 0..steps) {
        val f = i / steps.toFloat()
        val w = wTop * (1f - f * 0.52f)
        path.lineTo(xAt(f) + w, startY + (headY - startY) * f)
    }
    for (i in steps downTo 0) {
        val f = i / steps.toFloat()
        val w = wTop * (1f - f * 0.52f)
        path.lineTo(xAt(f) - w, startY + (headY - startY) * f)
    }
    path.close()
    drawPath(path, color = waterColor.copy(alpha = 0.85f * fade))

    // 前端圆润水头（推进阶段可见，伸长结束后并入水带）
    if (headT < 1f) {
        drawCircle(
            color = waterDeepColor.copy(alpha = 0.9f * fade),
            radius = wTop * (0.62f - 0.20f * headT),
            center = Offset(xAt(1f), headY)
        )
    }

    // 水量较大（>45%）时束边溅出少量水滴：数量固定、轨迹确定，半径随水量
    if (f0 > 0.45f) {
        val dropCount = 3
        val dropBaseR = d * 0.006f * (0.4f + 0.6f * f0)
        for (k in 0 until dropCount) {
            val local = ((p * 1.55f + k * 0.33f) % 1f)
            if (local < 0.12f) continue
            val tt = (local - 0.12f) / 0.88f
            val side = if (k % 2 == 0) 1f else -1f
            val dx = side * (wTop + d * 0.012f) * tt + sin(tt * 5f + k) * d * 0.004f
            val dy = maxLen * (0.35f + 0.65f * tt) * tt
            val a = (1f - ((tt - 0.75f) / 0.25f).coerceIn(0f, 1f)) * fade
            drawCircle(
                color = waterDeepColor.copy(alpha = 0.8f * a),
                radius = dropBaseR * (1f - tt * 0.35f),
                center = Offset(startX + dx, startY + dy)
            )
        }
    }
}

/** 画一层正弦波水体。波面随相位横移，水面下整体填色到底。 */
private fun DrawScope.drawWaterLayer(
    left: Float,
    top: Float,
    d: Float,
    r: Float,
    fill: Float,
    phase: Float,
    amplitudeRatio: Float,
    color: Color,
    waveCount: Float
) {
    val cy = top + r
    val baseSurfaceY = cy + r - fill * d
    val amp = d * amplitudeRatio
    val path = Path()
    val steps = 48
    path.moveTo(left, top + d)
    for (i in 0..steps) {
        val x = left + d * i / steps
        val k = (i.toFloat() / steps) * (2 * PI).toFloat() * waveCount + phase
        val y = baseSurfaceY + amp * sin(k)
        path.lineTo(x, y)
    }
    path.lineTo(left + d, top + d)
    path.close()
    drawPath(path, color = color)
}

private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

private fun easeInOut(t: Float): Float = if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f
