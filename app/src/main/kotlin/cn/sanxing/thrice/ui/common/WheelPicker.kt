@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package cn.sanxing.thrice.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 竖向数字滚轮（闹钟风格）：居中高亮、上下渐隐、fling 吸附。
 *
 * @param range 可选整数序列（连续或带步长，如 0..23 / 0..59 step 5）
 * @param format 数字展示格式（如补零）
 * @param itemFontFamily 逐项字体（如字体编号滑轮：每一行用其自身字体渲染），null 用全局字体
 */
@Composable
fun WheelNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntProgression,
    modifier: Modifier = Modifier,
    format: (Int) -> String = { it.toString() },
    itemFontFamily: (@Composable (Int) -> FontFamily?)? = null,
) {
    // 行高 46dp：26sp 数字（字形约 19~22dp 高，且不同字体度量不一）上下需各留
    // 约 12dp 余量，避免数字贴住 / 穿过居中指示框的两条分隔线。
    val itemHeight = 46.dp
    val visibleCount = 5
    val listHeight = itemHeight * visibleCount
    val itemHeightPx = with(LocalDensity.current) { itemHeight.roundToPx() }
    val itemCount = range.count()
    // 空 range 时 itemCount - 1 == -1，coerceIn(0, -1) 会抛 IllegalArgumentException
    if (itemCount <= 0) return
    val valueIndex = ((value - range.first) / range.step).coerceIn(0, itemCount - 1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = valueIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()

    /**
     * 「哪一行在中心」以**布局几何**为准，而不是 `firstVisibleItemIndex`。
     *
     * 后者在带 contentPadding 的列表里语义微妙（取决于 padding 是否计入可视区），
     * 一旦判断偏了，回写给调用方的编号就会与用户看到的居中行差 1~2 位 ——
     * 表现就是「明明选中的是它，确认后却不是」。
     * 这里直接取与视口中心重叠的那一行，所见即所得。
     */
    fun centeredIndex(layout: LazyListLayoutInfo): Int? {
        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
        return layout.visibleItemsInfo
            .firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?.index
    }

    val centered = centeredIndex(state.layoutInfo)

    // 滚动停止并吸附后，把**居中项**回写给调用方
    LaunchedEffect(state, range.first, range.step) {
        snapshotFlow {
            if (!state.isScrollInProgress && state.firstVisibleItemScrollOffset == 0) {
                centeredIndex(state.layoutInfo)
            } else {
                null
            }
        }
            .filter { it != null }
            .distinctUntilChanged()
            .collect { idx ->
                val target = range.first + idx!! * range.step
                if (target != value) onValueChange(target)
            }
    }

    // 外部 value 变化（初始值/对话框重置）时同步滚轮位置
    LaunchedEffect(value) {
        if (valueIndex != state.firstVisibleItemIndex && !state.isScrollInProgress) {
            state.animateScrollToItem(valueIndex)
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    // clipToBounds 必不可少：滚轮行是 clickable 的，一旦内容画到容器之外，
    // 就会盖住并抢走相邻控件（如弹窗的「确认」按钮）的点击。
    BoxWithConstraints(modifier.height(listHeight).clipToBounds()) {
        // 上下留白按**实际**可用高度计算：调用方可能用 Modifier.height() 限定了
        // 比 listHeight 更矮的高度（弹窗里历来如此），写死 itemHeight*2 会让
        // 选中行与指示线一起偏离中心 —— 这正是 R21 修过的「数字压线」问题。
        val viewportHeight = maxHeight
        val edgePadding = ((viewportHeight - itemHeight) / 2).coerceAtLeast(0.dp)
        val edgePaddingPx = with(LocalDensity.current) { edgePadding.toPx() }

        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 吸附时 firstVisibleItemIndex 即居中项（留白 = (可视高 - 行高) / 2）
            contentPadding = PaddingValues(vertical = edgePadding)
        ) {
            items(count = itemCount, key = { range.first + it * range.step }) { idx ->
                val itemValue = range.first + idx * range.step
                // 读取 layoutInfo，滚动时随距中心距离改变透明度与缩放（上下渐隐效果）
                val layout = state.layoutInfo
                val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                val info = layout.visibleItemsInfo.firstOrNull { it.index == idx }
                val (alpha, scale) = if (info == null) {
                    0.2f to 0.9f
                } else {
                    val distance = abs(info.offset + info.size / 2f - viewportCenter)
                    val a = (1.15f - distance / (itemHeightPx * 2.2f)).coerceIn(0.18f, 1f)
                    val s = 1f - (distance / (itemHeightPx * 6f)).coerceAtMost(0.18f)
                    a to s
                }
                val selected = idx == centered
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable { scope.launch { state.animateScrollToItem(idx) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = format(itemValue),
                        fontSize = 26.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = itemFontFamily?.invoke(itemValue),
                        color = if (selected) primaryColor else MaterialTheme.colorScheme.onSurface,
                        // 关闭字体自带的不对称 fontPadding 并按行盒居中，保证字形视觉中心
                        // 严格对齐行中心（即指示框中心），不会因字体度量偏上 / 偏下压线。
                        style = LocalTextStyle.current.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 28.sp,
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            )
                        ),
                        modifier = Modifier
                            .alpha(alpha)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                }
            }
        }

        // 居中选中行的上下分隔线
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 24.dp)
                .graphicsLayer { translationY = edgePaddingPx }
        ) {
            HorizontalDivider(
                Modifier.align(Alignment.TopCenter),
                thickness = 1.dp,
                color = primaryColor.copy(alpha = 0.35f)
            )
            HorizontalDivider(
                Modifier.align(Alignment.BottomCenter),
                thickness = 1.dp,
                color = primaryColor.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * 时 / 分双列时间滚轮。minuteStep 可设置分钟步长（如 5）。
 */
@Composable
fun WheelTimePicker(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hourLabel: String = "",
    minuteLabel: String = "",
    minuteStep: Int = 1,
) {
    val step = minuteStep.coerceAtLeast(1)
    val minutes = remember(step) { (0..59 step step).toList() }
    val safeMinute = (minute / step) * step
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hourLabel.isNotEmpty()) {
                Text(
                    hourLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            WheelNumberPicker(
                value = hour.coerceIn(0, 23),
                onValueChange = onHourChange,
                range = 0..23,
                format = { it.toString().padStart(2, '0') }
            )
        }
        Text(
            ":",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (minuteLabel.isNotEmpty()) {
                Text(
                    minuteLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            WheelNumberPicker(
                value = safeMinute,
                onValueChange = { onMinuteChange(it.coerceIn(0, 59)) },
                range = minutes.first()..minutes.last() step step,
                format = { it.toString().padStart(2, '0') }
            )
        }
    }
}

/**
 * 小时 / 分钟双列时长滚轮（用于无上限的倒计时选择）。
 * 小时范围默认 0..999（最大 999 小时 59 分），总值需 ≥1 分钟由调用方校验。
 */
@Composable
fun WheelDurationPicker(
    totalMinutes: Int,
    onTotalChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hourRange: IntRange = 0..999,
    hourLabel: String = "",
    minuteLabel: String = "",
) {
    val safeTotal = totalMinutes.coerceAtLeast(0)
    val hour = (safeTotal / 60).coerceIn(hourRange.first, hourRange.last)
    val minute = safeTotal % 60
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (hourLabel.isNotEmpty()) {
                Text(
                    hourLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            WheelNumberPicker(
                value = hour,
                onValueChange = { onTotalChange(it * 60 + minute) },
                range = hourRange,
                format = { it.toString().padStart(2, '0') }
            )
        }
        Text(
            ":",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (minuteLabel.isNotEmpty()) {
                Text(
                    minuteLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            WheelNumberPicker(
                value = minute,
                onValueChange = { onTotalChange(hour * 60 + it) },
                range = 0..59,
                format = { it.toString().padStart(2, '0') }
            )
        }
    }
}
