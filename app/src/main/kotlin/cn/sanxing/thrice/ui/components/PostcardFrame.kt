package cn.sanxing.thrice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.ui.theme.LocalExtendedColors
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha

/**
 * 明信片构图外壳：全 App 主页面通用。
 *
 * 结构：纸面底色 → 双线边框 → 标题区（大标题 + 副标题 + 横线）→ 邮戳（右上虚线圆 + 短文字）
 * → 低透明度信纸横线（内容层之下，不干扰阅读）→ 内容。
 *
 * @param fillHeight true 时纸面撑满调用方给出的高度，内容区获得剩余空间
 *   （调用方可在 content 内用 `Modifier.weight(1f)`，如课表页的网格）。
 * @param header 标题区右侧附加内容（如周次切换按钮）；不传则只显示邮戳。
 */
@Composable
fun PostcardFrame(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    postmarkTop: String? = null,
    postmarkBottom: String? = null,
    letterLines: Boolean = false,
    fillHeight: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val extended = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme
    val paperAlpha = LocalUiMaskAlpha.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .padding(10.dp)
            .background(extended.paperColor.copy(alpha = paperAlpha), RoundedCornerShape(6.dp))
            .border(1.dp, extended.lineColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(12.dp)
    ) {
        Column(
            if (fillHeight) Modifier.fillMaxWidth().fillMaxHeight() else Modifier.fillMaxWidth()
        ) {
            // 标题区
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }
                header?.invoke()
                if (postmarkTop != null || postmarkBottom != null) {
                    Postmark(top = postmarkTop, bottom = postmarkBottom)
                }
            }
            Spacer(Modifier.height(6.dp))

            // 内容区
            Column(
                modifier = if (fillHeight) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                },
                content = content
            )
        }
    }
}

/** 邮戳：右上角圆形虚线框 + 两行短文字。 */
@Composable
private fun Postmark(top: String?, bottom: String?) {
    val extended = LocalExtendedColors.current
    Box(
        modifier = Modifier
            .size(54.dp)
            .drawBehind {
                drawCircle(
                    color = extended.postmarkColor.copy(alpha = 0.75f),
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (top != null) {
                Text(
                    text = top,
                    style = MaterialTheme.typography.labelSmall,
                    color = extended.postmarkColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (top != null && bottom != null) {
                Box(
                    Modifier
                        .width(26.dp)
                        .height(1.dp)
                        .background(extended.postmarkColor.copy(alpha = 0.5f))
                )
            }
            if (bottom != null) {
                Text(
                    text = bottom,
                    style = MaterialTheme.typography.labelSmall,
                    color = extended.postmarkColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
