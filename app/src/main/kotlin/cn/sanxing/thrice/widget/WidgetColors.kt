package cn.sanxing.thrice.widget

import androidx.compose.ui.graphics.Color

/**
 * 小组件配色。
 *
 * 色值以 Compose Color 常量给出；小组件里每处用色都按 isDark 显式二选一，
 * 所以不需要自定义 GlanceTheme 调色板（GlanceTheme 内置调色板本身就是 day/night 自适应的）。
 */
object WidgetColors {

    val bg = Color(0xFFF4F8FC)
    val bgDark = Color(0xFF101820)
    val onBg = Color(0xFF16283A)
    val onBgDark = Color(0xFFD6E3F0)
    val primary = Color(0xFF2E6DA4)
    val primaryDark = Color(0xFF9FC5EC)
    val line = Color(0xFF33506B)
    val lineDark = Color(0xFF7E97AC)
    val muted = Color(0xFF5C7285)
    val mutedDark = Color(0xFF9DB2C4)
    val todayHighlight = Color(0xFF2E6DA4)
    val todayHighlightDark = Color(0xFF9FC5EC)

    /** 课程色板（与 app 内 12 色保持一致，供 colorHex 为空时按课名兜底）。 */
    val palette = listOf(
        0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFE53935,
        0xFF8E24AA, 0xFF00ACC1, 0xFFFDD835, 0xFF6D4C41,
        0xFFEC407A, 0xFF5E35B1, 0xFF00897B, 0xFF7CB342
    ).map { Color(it) }

    fun courseColor(colorHex: String, name: String): Color {
        parseHex(colorHex)?.let { return it }
        var hash = 0
        for (c in name) hash = 31 * hash + c.code
        return palette[(hash and Int.MAX_VALUE) % palette.size]
    }

    private fun parseHex(hex: String): Color? {
        val s = hex.trim()
        if (!s.startsWith('#') || (s.length != 7 && s.length != 9)) return null
        return runCatching {
            val v = s.removePrefix("#").toLong(16)
            if (s.length == 7) Color(0xFF000000 or v) else Color(v)
        }.getOrNull()
    }
}
