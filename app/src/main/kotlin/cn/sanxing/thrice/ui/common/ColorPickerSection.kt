package cn.sanxing.thrice.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R

/**
 * 通用颜色选择器：预设色板 + 自定义 RGB 滑块 + `#RRGGBB` 代码输入，三者双向联动。
 *
 * - 点预设圆点 / 拖滑块 / 直接改 hex 文本都会通过 [onColorChange] 回传 `#RRGGBB`；
 * - hex 非法时输入框标红提示，滑块与预览色保持上一次的合法值；
 * - [presetColors] 为空时不渲染预设行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSection(
    colorHex: String,
    onColorChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    presetColors: List<String> = emptyList()
) {
    val parsed = UiUtils.parseHex(colorHex)
    val valid = parsed != null
    val preview = parsed ?: Color(0xFF2196F3)

    var r by remember { mutableIntStateOf((preview.red * 255).toInt()) }
    var g by remember { mutableIntStateOf((preview.green * 255).toInt()) }
    var b by remember { mutableIntStateOf((preview.blue * 255).toInt()) }

    // 外部（预设点击、初始值）变化时，把滑块同步到新的合法色
    LaunchedEffect(colorHex) {
        parsed?.let {
            r = (it.red * 255).toInt()
            g = (it.green * 255).toInt()
            b = (it.blue * 255).toInt()
        }
    }

    fun emitRgb() = onColorChange("#%02X%02X%02X".format(r, g, b))

    Column(modifier) {
        if (presetColors.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                presetColors.forEach { hex ->
                    val dotColor = UiUtils.parseHex(hex) ?: return@forEach
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                            .border(
                                width = if (hex.equals(colorHex, ignoreCase = true)) 3.dp else 1.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                shape = CircleShape
                            )
                            .clickable { onColorChange(hex.uppercase()) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.color_custom_section),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 实时预览色块；hex 非法时红框提示
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(preview)
                    .border(
                        width = 1.5.dp,
                        color = if (valid) MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(10.dp)
                    )
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = colorHex,
                onValueChange = { raw ->
                    // 只保留十六进制字符与 #，并自动补前导 #
                    val filtered = raw.filter { it.isLetterOrDigit() || it == '#' }.uppercase()
                    onColorChange(if (filtered.startsWith("#")) filtered else "#$filtered")
                },
                label = { Text(stringResource(R.string.field_color_custom)) },
                singleLine = true,
                isError = !valid,
                supportingText = if (!valid) {
                    { Text(stringResource(R.string.invalid_color)) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(2.dp))
        ColorChannelSlider(stringResource(R.string.rgb_r_label), r, Color(0xFFE53935)) {
            r = it; emitRgb()
        }
        ColorChannelSlider(stringResource(R.string.rgb_g_label), g, Color(0xFF43A047)) {
            g = it; emitRgb()
        }
        ColorChannelSlider(stringResource(R.string.rgb_b_label), b, Color(0xFF1E88E5)) {
            b = it; emitRgb()
        }
    }
}

/** 单条 RGB 通道滑块（0..255 整数步进）。 */
@Composable
private fun ColorChannelSlider(label: String, value: Int, accent: Color, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(46.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            steps = 254,
            modifier = Modifier.weight(1f)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp)
        )
    }
}
