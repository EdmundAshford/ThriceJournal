package cn.sanxing.thrice.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// 蓝白主题
// ============================================================
val BlueLightPrimary = Color(0xFF1E88E5)
val BlueLightOnPrimary = Color(0xFFFFFFFF)
val BlueLightPrimaryContainer = Color(0xFFBBDEFB)
val BlueLightSecondary = Color(0xFF5C8BC0)
val BlueLightBackground = Color(0xFFF5F9FF)
val BlueLightSurface = Color(0xFFFFFFFF)
val BlueLightSurfaceVariant = Color(0xFFE3EFFA)
val BlueLightOutline = Color(0xFF7FA8CC)

val BlueDarkPrimary = Color(0xFF90CAF9)
val BlueDarkOnPrimary = Color(0xFF0D2A47)
val BlueDarkPrimaryContainer = Color(0xFF1D4E7E)
val BlueDarkSecondary = Color(0xFF7DA9D6)
val BlueDarkBackground = Color(0xFF0E1622)
val BlueDarkSurface = Color(0xFF1A2532)
val BlueDarkSurfaceVariant = Color(0xFF233243)
val BlueDarkOutline = Color(0xFF4A637E)

// ============================================================
// 粉红主题
// ============================================================
val PinkLightPrimary = Color(0xFFEC407A)
val PinkLightOnPrimary = Color(0xFFFFFFFF)
val PinkLightPrimaryContainer = Color(0xFFF8BBD0)
val PinkLightSecondary = Color(0xFFC05B7F)
val PinkLightBackground = Color(0xFFFFF5F8)
val PinkLightSurface = Color(0xFFFFFFFF)
val PinkLightSurfaceVariant = Color(0xFFFBE4EC)
val PinkLightOutline = Color(0xFFCC8FA6)

val PinkDarkPrimary = Color(0xFFF48FB1)
val PinkDarkOnPrimary = Color(0xFF47172B)
val PinkDarkPrimaryContainer = Color(0xFF7A2947)
val PinkDarkSecondary = Color(0xFFD084A4)
val PinkDarkBackground = Color(0xFF1C1216)
val PinkDarkSurface = Color(0xFF2A1D23)
val PinkDarkSurfaceVariant = Color(0xFF372630)
val PinkDarkOutline = Color(0xFF6E4E5C)

// ============================================================
// 黑白主题
// ============================================================
val MonoLightPrimary = Color(0xFF212121)
val MonoLightOnPrimary = Color(0xFFFFFFFF)
val MonoLightPrimaryContainer = Color(0xFFE0E0E0)
val MonoLightSecondary = Color(0xFF616161)
val MonoLightBackground = Color(0xFFFAFAFA)
val MonoLightSurface = Color(0xFFFFFFFF)
val MonoLightSurfaceVariant = Color(0xFFEEEEEE)
val MonoLightOutline = Color(0xFF9E9E9E)

val MonoDarkPrimary = Color(0xFFE0E0E0)
val MonoDarkOnPrimary = Color(0xFF1C1C1C)
val MonoDarkPrimaryContainer = Color(0xFF424242)
val MonoDarkSecondary = Color(0xFFBDBDBD)
val MonoDarkBackground = Color(0xFF101010)
val MonoDarkSurface = Color(0xFF1E1E1E)
val MonoDarkSurfaceVariant = Color(0xFF2A2A2A)
val MonoDarkOutline = Color(0xFF666666)

// ============================================================
// 深色兜底（上阶段兼容引用）
// ============================================================
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkPrimary = Color(0xFF90CAF9)

// ============================================================
// 课程 12 色预设色板（课名哈希默认取色）
// ============================================================
val CoursePalette = listOf(
    Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA),
    Color(0xFFE53935), Color(0xFF00897B), Color(0xFF3949AB), Color(0xFFD81B60),
    Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1), Color(0xFF6D4C41)
)

/** 课名哈希到 12 色色板。 */
fun paletteColorFor(name: String): Color =
    CoursePalette[((name.hashCode() % CoursePalette.size) + CoursePalette.size) % CoursePalette.size]
