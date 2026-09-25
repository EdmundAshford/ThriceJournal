package cn.sanxing.thrice.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.sanxing.thrice.R
import java.io.File

/** 手势缩放上上限：1f..此值 */
private const val WALLPAPER_MAX_SCALE = 6f

/**
 * 全屏壁纸取景界面：单指拖动、双指缩放；四角取景角标标示最终保留区域（整屏）。
 *
 * 几何与 [wallpaperLayer] 完全同源（见 [WallpaperGeometry]）：位图以 cover 基准显示
 * 尺寸 dw×dh 居中放置，双指缩放只改 s（显示尺寸变为 dw*s × dh*s），单指拖动只改
 * 像素平移并按当前半溢出量钳制 —— 视口始终被填满，横图 / 竖图 / 方图在 s=1 时都
 * 可沿溢出轴全程拖动、无黑边。确认时换算成分辨率无关的归一化平移量 -1..1 回传。
 */
@Composable
fun WallpaperCropDialog(
    file: File,
    initialPanX: Float,
    initialPanY: Float,
    initialScale: Float,
    onCancel: () -> Unit,
    onConfirm: (panX: Float, panY: Float, scale: Float) -> Unit
) {
    // 像素平移（视口像素）；initial* 为归一化值，视口尺寸就绪后按实际溢出量换算
    var panPxX by remember { mutableFloatStateOf(0f) }
    var panPxY by remember { mutableFloatStateOf(0f) }
    var scale by remember {
        mutableFloatStateOf(initialScale.coerceIn(1f, WALLPAPER_MAX_SCALE))
    }
    var initialized by remember { mutableStateOf(false) }
    var viewportW by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    val bmp = rememberWallpaperBitmap(file)

    // 当前视口 + 缩放下的几何（确认换算与初始平移恢复共用）
    val geometry = if (bmp != null && viewportW > 0f && viewportH > 0f) {
        WallpaperGeometry.calculate(
            viewportW = viewportW,
            viewportH = viewportH,
            bitmapW = bmp.width,
            bitmapH = bmp.height,
            scale = scale
        )
    } else {
        null
    }

    // 视口与图片都就绪后，把保存的归一化平移换算成像素平移（仅一次）
    LaunchedEffect(bmp, viewportW, viewportH) {
        if (!initialized && bmp != null && viewportW > 0f && viewportH > 0f) {
            val g = WallpaperGeometry.calculate(
                viewportW = viewportW,
                viewportH = viewportH,
                bitmapW = bmp.width,
                bitmapH = bmp.height,
                scale = scale
            )
            if (g != null) {
                panPxX = initialPanX.coerceIn(-1f, 1f) * g.maxPanX
                panPxY = initialPanY.coerceIn(-1f, 1f) * g.maxPanY
                initialized = true
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        viewportW = it.width.toFloat()
                        viewportH = it.height.toFloat()
                    }
            ) {
                // 图片层：纯自绘，固定显示尺寸居中 + 像素平移，超出视口部分裁掉
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(bmp) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (bmp == null) return@detectTransformGestures
                                val oldScale = scale
                                val newScale = (oldScale * zoom)
                                    .coerceIn(1f, WALLPAPER_MAX_SCALE)
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val oldG = WallpaperGeometry.calculate(
                                    w, h, bmp.width, bmp.height, oldScale
                                )
                                val newG = WallpaperGeometry.calculate(
                                    w, h, bmp.width, bmp.height, newScale
                                )
                                if (oldG == null || newG == null) return@detectTransformGestures
                                var newPanX = panPxX
                                var newPanY = panPxY
                                // 缩放时按比例迁移像素平移，保持归一化取景位置不变
                                // （固定中心缩放，画面不会因钳制跳动）
                                if (zoom != 1f) {
                                    newPanX = if (oldG.maxPanX > 0f) {
                                        newPanX / oldG.maxPanX * newG.maxPanX
                                    } else {
                                        0f
                                    }
                                    newPanY = if (oldG.maxPanY > 0f) {
                                        newPanY / oldG.maxPanY * newG.maxPanY
                                    } else {
                                        0f
                                    }
                                }
                                // 单指拖动：只叠加平移，按当前半溢出量钳制
                                panPxX = (newPanX + pan.x).coerceIn(-newG.maxPanX, newG.maxPanX)
                                panPxY = (newPanY + pan.y).coerceIn(-newG.maxPanY, newG.maxPanY)
                                scale = newScale
                            }
                        }
                ) {
                    val g = WallpaperGeometry.calculate(
                        viewportW = size.width,
                        viewportH = size.height,
                        bitmapW = bmp?.width ?: 0,
                        bitmapH = bmp?.height ?: 0,
                        scale = scale
                    )
                    if (bmp != null && g != null) {
                        drawWallpaper(
                            bmp = bmp,
                            geometry = g,
                            panPxX = panPxX,
                            panPxY = panPxY
                        )
                    }
                }

                // 四角取景角标：视口边界即最终保留区域（全屏）
                val cornerColor = Color.White.copy(alpha = 0.9f)
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    val len = 30.dp.toPx()
                    val w = 3.dp.toPx()
                    val rightEdge = size.width
                    val bottomEdge = size.height
                    // 左上
                    drawLine(cornerColor, Offset.Zero, Offset(len, 0f), w, StrokeCap.Round)
                    drawLine(cornerColor, Offset.Zero, Offset(0f, len), w, StrokeCap.Round)
                    // 右上
                    drawLine(cornerColor, Offset(rightEdge, 0f), Offset(rightEdge - len, 0f), w, StrokeCap.Round)
                    drawLine(cornerColor, Offset(rightEdge, 0f), Offset(rightEdge, len), w, StrokeCap.Round)
                    // 左下
                    drawLine(cornerColor, Offset(0f, bottomEdge), Offset(len, bottomEdge), w, StrokeCap.Round)
                    drawLine(cornerColor, Offset(0f, bottomEdge), Offset(0f, bottomEdge - len), w, StrokeCap.Round)
                    // 右下
                    drawLine(cornerColor, Offset(rightEdge, bottomEdge), Offset(rightEdge - len, bottomEdge), w, StrokeCap.Round)
                    drawLine(cornerColor, Offset(rightEdge, bottomEdge), Offset(rightEdge, bottomEdge - len), w, StrokeCap.Round)
                }

                // 顶部操作层：独立浮层 + 半透明 scrim，不参与图片布局，不可能被图片遮住
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.bg_adjust_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(start = 8.dp).weight(1f)
                        )
                        TextButton(onClick = {
                            // 重置：s=1、平移归零（画面回到 cover 居中）
                            panPxX = 0f
                            panPxY = 0f
                            scale = 1f
                        }) { Text(stringResource(R.string.action_reset)) }
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = {
                            // 像素平移 → 归一化（相对当前缩放的半溢出量）；该方向无溢出时为 0
                            val nx = if (geometry != null && geometry.maxPanX > 0f) {
                                (panPxX / geometry.maxPanX).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            val ny = if (geometry != null && geometry.maxPanY > 0f) {
                                (panPxY / geometry.maxPanY).coerceIn(-1f, 1f)
                            } else {
                                0f
                            }
                            onConfirm(nx, ny, scale)
                        }) { Text(stringResource(R.string.action_confirm)) }
                    }
                    Text(
                        stringResource(R.string.bg_adjust_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}
