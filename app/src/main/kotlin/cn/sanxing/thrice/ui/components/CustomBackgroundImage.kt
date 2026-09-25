package cn.sanxing.thrice.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 解码私有目录内的壁纸图片（自动降采样到屏幕尺寸，避免 OOM）。
 */
@Composable
fun rememberWallpaperBitmap(file: File): ImageBitmap? {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // 第一遍：只取尺寸，计算采样率
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val (w, h) = context.resources.displayMetrics.widthPixels to
                    context.resources.displayMetrics.heightPixels
                var sample = 1
                var halfW = bounds.outWidth / 2
                var halfH = bounds.outHeight / 2
                while (halfW >= w && halfH >= h) {
                    sample *= 2
                    halfW /= 2
                    halfH /= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * 壁纸统一几何模型（全屏裁剪器 / 主屏背景 / 设置页缩略预览三处共用，保证确认构图
 * 逐像素一致）。所有长度均为宿主（视口）像素：
 *
 * - 视口 vw×vh、位图 bw×bh；
 * - cover = max(vw/bw, vh/bh)：短边铺满，视口恒被填满，任何宽高比都无黑边；
 * - s=1 基准显示尺寸 dw=bw*cover、dh=bh*cover（必有 dw>=vw、dh>=vh）；
 * - 手势缩放 s>=1 后显示尺寸 dw*s × dh*s；
 * - 平移钳制 maxX=(dw*s-vw)/2、maxY=(dh*s-vh)/2（恒 >=0），
 *   translationX/Y ∈ [-maxX, maxX] / [-maxY, maxY]；
 * - 归一化平移 nx = translationX/maxX ∈ -1..1（当前缩放可溢出量为基准，分辨率无关）。
 *
 * 注意：缩放只体现在「位图绘制尺寸」上，绝不使用 ContentScale.Crop 布局尺寸叠加
 * graphicsLayer scaleX/scaleY，避免布局尺寸与显示尺寸量纲不一致。
 */
data class WallpaperGeometry(
    val displayW: Float, // 当前缩放下的位图显示宽（px）
    val displayH: Float, // 当前缩放下的位图显示高（px）
    val maxPanX: Float,  // 当前缩放下 X 向最大平移（px，= 水平半溢出量）
    val maxPanY: Float   // 当前缩放下 Y 向最大平移（px，= 垂直半溢出量）
) {
    companion object {
        /**
         * 按视口 / 位图实际尺寸计算几何；视口或位图尺寸无效时返回 null。
         * [scale] 会被夹到 >=1f。
         */
        fun calculate(
            viewportW: Float,
            viewportH: Float,
            bitmapW: Int,
            bitmapH: Int,
            scale: Float
        ): WallpaperGeometry? {
            if (viewportW <= 0f || viewportH <= 0f || bitmapW <= 0 || bitmapH <= 0) return null
            val s = scale.coerceAtLeast(1f)
            val cover = maxOf(viewportW / bitmapW.toFloat(), viewportH / bitmapH.toFloat())
            val dw = bitmapW * cover
            val dh = bitmapH * cover
            return WallpaperGeometry(
                displayW = dw * s,
                displayH = dh * s,
                maxPanX = (dw * s - viewportW) / 2f,
                maxPanY = (dh * s - viewportH) / 2f
            )
        }
    }
}

/**
 * 按已算好的 [geometry] 与**像素平移**绘制壁纸（裁剪界面用）。
 *
 * 目标矩形固定居中后只叠加平移；起止边分别向下 / 向上取整，杜绝取整误差在
 * 视口边缘露出 1px 黑边。
 */
fun DrawScope.drawWallpaper(
    bmp: ImageBitmap,
    geometry: WallpaperGeometry,
    panPxX: Float,
    panPxY: Float,
    alpha: Float = 1f
) {
    val left = floor((size.width - geometry.displayW) / 2f + panPxX)
    val top = floor((size.height - geometry.displayH) / 2f + panPxY)
    val right = ceil(left + geometry.displayW)
    val bottom = ceil(top + geometry.displayH)
    drawImage(
        image = bmp,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize((right - left).toInt(), (bottom - top).toInt()),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

/**
 * 按**归一化平移**（-1..1，相对当前缩放可溢出量）与缩放绘制壁纸
 * （主屏背景 / 设置缩略图用）。几何与裁剪界面完全同源。
 */
fun DrawScope.drawWallpaper(
    bmp: ImageBitmap,
    panX: Float,
    panY: Float,
    scale: Float,
    alpha: Float = 1f
) {
    val geometry = WallpaperGeometry.calculate(
        viewportW = size.width,
        viewportH = size.height,
        bitmapW = bmp.width,
        bitmapH = bmp.height,
        scale = scale
    ) ?: return
    drawWallpaper(
        bmp = bmp,
        geometry = geometry,
        panPxX = panX.coerceIn(-1f, 1f) * geometry.maxPanX,
        panPxY = panY.coerceIn(-1f, 1f) * geometry.maxPanY,
        alpha = alpha
    )
}

/**
 * 壁纸图层：在宿主自身绘制区域内以 [drawWallpaper] 纯 Canvas 自绘位图
 * （裁剪器 / 主屏背景 / 设置页缩略预览三处共用，保证构图一致）。
 *
 * - 宿主尺寸即视口（调用方以 fillMaxSize / 固定尺寸给定）；
 * - [extraScale] 为用户手势缩放 s（1f = cover 刚好填满，恒铺满无白边）；
 * - [panX]/[panY] 为归一化平移量 -1..1：实际位移 = pan × 当前缩放后半溢出量。
 *   因此无论宿主尺寸 / 分辨率如何，0.5 永远表示「从居中移到可移动范围的一半」，
 *   横图 / 竖图 / 方图都能由用户精确选择保留区域。
 */
fun Modifier.wallpaperLayer(
    bmp: ImageBitmap,
    panX: Float,
    panY: Float,
    extraScale: Float,
    alpha: Float = 1f
): Modifier = this.drawBehind {
    drawWallpaper(
        bmp = bmp,
        panX = panX,
        panY = panY,
        scale = extraScale,
        alpha = alpha
    )
}

/**
 * 自定义背景图层：从应用私有目录异步解码图片（自动降采样，避免 OOM）。
 *
 * @param file 私有目录内的图片文件
 * @param alphaPercent 不透明度百分比 0..100
 * @param panX / panY 归一化平移量 -1..1（见 [wallpaperLayer]）
 * @param scale 用户保存的缩放（1f = cover 填满）
 */
@Composable
fun CustomBackgroundImage(
    file: File,
    alphaPercent: Int,
    panX: Float = 0f,
    panY: Float = 0f,
    scale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val bmp = rememberWallpaperBitmap(file) ?: return
    // 空 Box 承载自绘图层：不再用 Image + ContentScale.Crop
    Box(
        modifier = modifier
            .fillMaxSize()
            .wallpaperLayer(
                bmp = bmp,
                panX = panX,
                panY = panY,
                extraScale = scale,
                alpha = alphaPercent.coerceIn(0, 100) / 100f
            )
    )
}

/**
 * 设置页壁纸缩略预览：与主屏共用 [wallpaperLayer] 几何（按预览框自身尺寸计算），
 * 构图与实际壁纸一致。点击预览进入裁剪界面。
 */
@Composable
fun WallpaperPreview(
    file: File,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val bmp = rememberWallpaperBitmap(file) ?: return
    Box(
        modifier = modifier
            .wallpaperLayer(bmp = bmp, panX = offsetX, panY = offsetY, extraScale = scale)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}
