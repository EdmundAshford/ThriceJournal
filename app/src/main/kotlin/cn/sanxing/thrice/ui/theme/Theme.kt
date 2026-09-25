package cn.sanxing.thrice.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「几何艺术」专用扩展色：线条色、几何块色、邮戳色、纸面底色。
 * 不进 MaterialTheme.colorScheme，走 CompositionLocal。
 */
data class ExtendedColors(
    val lineColor: Color,
    val geoBlockColor: Color,
    val postmarkColor: Color,
    val paperColor: Color
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        lineColor = BlueLightPrimary,
        geoBlockColor = BlueLightPrimaryContainer,
        postmarkColor = BlueLightPrimary,
        paperColor = BlueLightBackground
    )
}

/**
 * 纸面（PostcardFrame）不透明度：无壁纸时为固定的 0.95（近似不透明）；
 * 壁纸激活时由 SanxingTheme 注入用户设置的「主体界面浓度」，让日程 / 设置页与
 * 账单 / 任务 / 课表页受同一个透明度控制。
 */
val LocalUiMaskAlpha = staticCompositionLocalOf { 0.95f }

/**
 * 应用背景层（自定义壁纸或几何线条）的渲染器，由 `AppRoot` 注入。
 *
 * 用途：**全屏覆盖页**（笔记编辑页、文件夹覆盖层等）需要在自己的不透明层上重绘一遍背景，
 * 再叠加半透明纸面 —— 否则只能二者取一：
 * - 直接用 `MaterialTheme.colorScheme.surface`：壁纸模式下它是**半透明**的
 *   （跟随「主体界面浓度」），被覆盖的页面会透出来形成**重影**；
 * - 用不透明色：壁纸完全被盖住，与其他页面观感不一致。
 *
 * 默认实现画空（预览 / 测试场景），真实实现见 `AppRoot`。
 */
val LocalAppBackground = staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { } }

/**
 * 应用字体：00 = 系统默认；01..12 = 编号字体（res/font/f01..f12）。
 *
 * 全部内置字体均为 **SIL OFL 1.1** 授权、可自由再分发；界面只展示编号而不展示字体名，
 * 这既是为了不暴露字体来源，也是 OFL「保留字体名（Reserved Font Name）」条款的要求——
 * 我们分发的是**子集化后的修改版本**，不得再使用原字体的保留名。
 * 字体名与署名见仓库根的 `THIRD_PARTY_FONTS.md`。
 *
 * 字体资源按名在运行时解析（[resolveFontResId]）：资源缺失时回落系统默认，
 * 因此增减字体只需改本枚举与 res/font 下的文件，无需改调用方。
 */
enum class AppFontOption(val key: String) {
    SYSTEM("00"),
    F01("01"),
    F02("02"),
    F03("03"),
    F04("04"),
    F05("05"),
    F06("06"),
    F07("07"),
    F08("08"),
    F09("09"),
    F10("10"),
    F11("11"),
    F12("12");

    /** res/font 下的资源名（f01..f12）；SYSTEM 不对应字体文件。 */
    private val fontResName: String? get() = if (this == SYSTEM) null else "f$key"

    /**
     * 按资源名在运行时解析字体 ID；当前包内不存在该字体（仓库未附带）时返回 null。
     */
    fun resolveFontResId(context: Context): Int? = fontResName?.let { name ->
        context.resources.getIdentifier(name, "font", context.packageName).takeIf { it != 0 }
    }

    /** 该编号字体在当前安装包中是否可用（SYSTEM 始终可用）。 */
    fun isAvailable(context: Context): Boolean = this == SYSTEM || resolveFontResId(context) != null

    /** 该编号对应的 [FontFamily]；字体缺失时回落系统无衬线字体。 */
    @Composable
    fun fontFamily(): FontFamily {
        if (this == SYSTEM) return FontFamily.SansSerif
        val resId = resolveFontResId(LocalContext.current) ?: return FontFamily.SansSerif
        return FontFamily(Font(resId))
    }

    companion object {
        fun fromKey(key: String?): AppFontOption =
            entries.firstOrNull { it.key == key } ?: SYSTEM

        /** 供原生 Canvas 导出（非 Composable）按字体编号取 Typeface，失败回落系统默认。 */
        fun typefaceFor(context: Context, key: String?): Typeface {
            val resId = fromKey(key).resolveFontResId(context) ?: return Typeface.DEFAULT
            return runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: Typeface.DEFAULT
        }

        /**
         * 当前包内实际可用的最大字体编号（字体由脚本连续编号 f01..fNN 生成）。
         * 0 表示仅系统默认可用——字体选择器据此收窄范围，不暴露缺失编号。
         */
        fun maxAvailableNumber(context: Context): Int =
            entries.drop(1).count { it.resolveFontResId(context) != null }
    }
}

/** 当前选中的正文字体族（供不便直接取 Typography 的硬编码样式点统一替换）。 */
val LocalAppFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

/** 主题色枚举（与 SettingsRepository 的字符串值一一对应）。
 *  BLUE/PINK/MONO 带精修色板；其余为种子色，由 [seededLightScheme]/[seededDarkScheme] 派生；
 *  CUSTOM 使用用户自定义 RGB。 */
enum class ThemeColorOption(val key: String, val seed: Color? = null) {
    BLUE("BLUE"),
    PINK("PINK"),
    MONO("MONO"),
    MINT("MINT", Color(0xFF2E9E72)),       // 薄荷绿
    GRAPE("GRAPE", Color(0xFF7E57C2)),     // 葡萄紫
    SUNSET("SUNSET", Color(0xFFF07A1A)),   // 日落橙
    TEAL("TEAL", Color(0xFF008B80)),       // 青瓷
    CRIMSON("CRIMSON", Color(0xFFD23A3A)), // 中国红
    INDIGO("INDIGO", Color(0xFF3D55C4)),   // 靛青
    MOCHA("MOCHA", Color(0xFF7A5230)),     // 摩卡棕
    CUSTOM("CUSTOM")
}

private fun lightScheme(color: ThemeColorOption, customSeed: Color? = null): ColorScheme = when (color) {
    ThemeColorOption.BLUE -> lightColorScheme(
        primary = BlueLightPrimary, onPrimary = BlueLightOnPrimary,
        primaryContainer = BlueLightPrimaryContainer, onPrimaryContainer = Color(0xFF0D2A47),
        secondary = BlueLightSecondary, background = BlueLightBackground, onBackground = Color(0xFF16283A),
        surface = BlueLightSurface, onSurface = Color(0xFF16283A),
        surfaceVariant = BlueLightSurfaceVariant, onSurfaceVariant = Color(0xFF33506B),
        outline = BlueLightOutline
    )
    ThemeColorOption.PINK -> lightColorScheme(
        primary = PinkLightPrimary, onPrimary = PinkLightOnPrimary,
        primaryContainer = PinkLightPrimaryContainer, onPrimaryContainer = Color(0xFF47172B),
        secondary = PinkLightSecondary, background = PinkLightBackground, onBackground = Color(0xFF3B1F29),
        surface = PinkLightSurface, onSurface = Color(0xFF3B1F29),
        surfaceVariant = PinkLightSurfaceVariant, onSurfaceVariant = Color(0xFF5E3544),
        outline = PinkLightOutline
    )
    ThemeColorOption.MONO -> lightColorScheme(
        primary = MonoLightPrimary, onPrimary = MonoLightOnPrimary,
        primaryContainer = MonoLightPrimaryContainer, onPrimaryContainer = Color(0xFF1C1C1C),
        secondary = MonoLightSecondary, background = MonoLightBackground, onBackground = Color(0xFF212121),
        surface = MonoLightSurface, onSurface = Color(0xFF212121),
        surfaceVariant = MonoLightSurfaceVariant, onSurfaceVariant = Color(0xFF424242),
        outline = MonoLightOutline
    )
    else -> seededLightScheme(color.seed ?: customSeed ?: BlueLightPrimary)
}

private fun darkScheme(color: ThemeColorOption, customSeed: Color? = null): ColorScheme = when (color) {
    ThemeColorOption.BLUE -> darkColorScheme(
        primary = BlueDarkPrimary, onPrimary = BlueDarkOnPrimary,
        primaryContainer = BlueDarkPrimaryContainer, onPrimaryContainer = Color(0xFFD6E9FB),
        secondary = BlueDarkSecondary, background = BlueDarkBackground, onBackground = Color(0xFFD6E3F0),
        surface = BlueDarkSurface, onSurface = Color(0xFFD6E3F0),
        surfaceVariant = BlueDarkSurfaceVariant, onSurfaceVariant = Color(0xFFB5C6D9),
        outline = BlueDarkOutline
    )
    ThemeColorOption.PINK -> darkColorScheme(
        primary = PinkDarkPrimary, onPrimary = PinkDarkOnPrimary,
        primaryContainer = PinkDarkPrimaryContainer, onPrimaryContainer = Color(0xFFFBD9E5),
        secondary = PinkDarkSecondary, background = PinkDarkBackground, onBackground = Color(0xFFF0DCE2),
        surface = PinkDarkSurface, onSurface = Color(0xFFF0DCE2),
        surfaceVariant = PinkDarkSurfaceVariant, onSurfaceVariant = Color(0xFFD5BCC7),
        outline = PinkDarkOutline
    )
    ThemeColorOption.MONO -> darkColorScheme(
        primary = MonoDarkPrimary, onPrimary = MonoDarkOnPrimary,
        primaryContainer = MonoDarkPrimaryContainer, onPrimaryContainer = Color(0xFFEAEAEA),
        secondary = MonoDarkSecondary, background = MonoDarkBackground, onBackground = Color(0xFFE0E0E0),
        surface = MonoDarkSurface, onSurface = Color(0xFFE0E0E0),
        surfaceVariant = MonoDarkSurfaceVariant, onSurfaceVariant = Color(0xFFC7C7C7),
        outline = MonoDarkOutline
    )
    else -> seededDarkScheme(color.seed ?: customSeed ?: BlueLightPrimary)
}

// ------------------------------------------------------------------
// 由种子色派生整套 Material 配色（预设与自定义 RGB 共用）
// ------------------------------------------------------------------

/** RGB 线性混色：fraction=0 为本色，1 为 [other]。 */
private fun Color.mix(other: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * f,
        green = green + (other.green - green) * f,
        blue = blue + (other.blue - blue) * f,
        alpha = 1f
    )
}

private val Color.perceptualLuminance: Float
    get() = 0.299f * red + 0.587f * green + 0.114f * blue

/** 深底色上用白字、浅底色上用近黑字。 */
private fun Color.contrastingContent(): Color =
    if (perceptualLuminance > 0.55f) Color(0xFF1A1A1A) else Color.White

private fun seededLightScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = seed.contrastingContent(),
    primaryContainer = seed.mix(Color.White, 0.84f),
    onPrimaryContainer = seed.mix(Color.Black, 0.72f),
    secondary = seed.mix(Color(0xFF616161), 0.35f),
    onSecondary = Color.White,
    secondaryContainer = seed.mix(Color.White, 0.9f),
    onSecondaryContainer = seed.mix(Color.Black, 0.6f),
    background = seed.mix(Color.White, 0.975f),
    onBackground = seed.mix(Color.Black, 0.82f),
    surface = Color.White,
    onSurface = seed.mix(Color.Black, 0.82f),
    surfaceVariant = seed.mix(Color.White, 0.9f),
    onSurfaceVariant = seed.mix(Color.Black, 0.58f),
    outline = seed.mix(Color(0xFF9E9E9E), 0.35f),
    outlineVariant = seed.mix(Color.White, 0.7f)
)

private fun seededDarkScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed.mix(Color.White, 0.28f),
    onPrimary = seed.mix(Color.White, 0.28f).contrastingContent(),
    primaryContainer = seed.mix(Color.Black, 0.62f),
    onPrimaryContainer = seed.mix(Color.White, 0.82f),
    secondary = seed.mix(Color(0xFFBDBDBD), 0.25f),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = seed.mix(Color.Black, 0.7f),
    onSecondaryContainer = seed.mix(Color.White, 0.75f),
    background = seed.mix(Color.Black, 0.94f),
    onBackground = seed.mix(Color.White, 0.88f),
    surface = seed.mix(Color.Black, 0.89f),
    onSurface = seed.mix(Color.White, 0.88f),
    surfaceVariant = seed.mix(Color.Black, 0.78f),
    onSurfaceVariant = seed.mix(Color.White, 0.65f),
    outline = seed.mix(Color(0xFF888888), 0.35f),
    outlineVariant = seed.mix(Color.Black, 0.55f)
)

/** "#RRGGBB" → Color；非法回退到蓝色。 */
internal fun parseHexColor(hex: String?): Color {
    if (hex == null) return BlueLightPrimary
    val v = hex.trim().removePrefix("#")
    if (v.length != 6) return BlueLightPrimary
    return runCatching { Color(0xFF000000 + v.toLong(16)) }.getOrDefault(BlueLightPrimary)
}

private fun extendedLight(color: ThemeColorOption, customSeed: Color? = null): ExtendedColors {
    val s = color.seed ?: customSeed
    return when (color) {
        ThemeColorOption.BLUE -> ExtendedColors(BlueLightPrimary, BlueLightPrimaryContainer, BlueLightPrimary, BlueLightBackground)
        ThemeColorOption.PINK -> ExtendedColors(PinkLightPrimary, PinkLightPrimaryContainer, PinkLightPrimary, PinkLightBackground)
        ThemeColorOption.MONO -> ExtendedColors(MonoLightPrimary, MonoLightPrimaryContainer, MonoLightPrimary, MonoLightBackground)
        else -> {
            val seed = s ?: BlueLightPrimary
            ExtendedColors(seed, seed.mix(Color.White, 0.84f), seed, seed.mix(Color.White, 0.975f))
        }
    }
}

private fun extendedDark(color: ThemeColorOption, customSeed: Color? = null): ExtendedColors {
    val s = color.seed ?: customSeed
    return when (color) {
        ThemeColorOption.BLUE -> ExtendedColors(BlueDarkPrimary, BlueDarkPrimaryContainer, BlueDarkPrimary, BlueDarkBackground)
        ThemeColorOption.PINK -> ExtendedColors(PinkDarkPrimary, PinkDarkPrimaryContainer, PinkDarkPrimary, PinkDarkBackground)
        ThemeColorOption.MONO -> ExtendedColors(MonoDarkPrimary, MonoDarkPrimaryContainer, MonoDarkPrimary, MonoDarkBackground)
        else -> {
            val seed = s ?: BlueLightPrimary
            val p = seed.mix(Color.White, 0.28f)
            ExtendedColors(p, seed.mix(Color.Black, 0.62f), p, seed.mix(Color.Black, 0.94f))
        }
    }
}

private fun buildTypography(fontFamily: FontFamily): Typography {
    fun style(size: Int, weight: FontWeight? = null, lineHeight: Int? = null) = TextStyle(
        fontFamily = fontFamily,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (lineHeight ?: (size * 1.4f).toInt()).sp
    )
    return Typography(
        displayLarge = style(57),
        displayMedium = style(45),
        displaySmall = style(36),
        headlineLarge = style(32, FontWeight.Bold),
        headlineMedium = style(28, FontWeight.Bold),
        headlineSmall = style(24, FontWeight.Bold),
        titleLarge = style(20, FontWeight.Bold),
        titleMedium = style(16, FontWeight.Bold),
        titleSmall = style(14, FontWeight.Medium),
        bodyLarge = style(16),
        bodyMedium = style(14),
        bodySmall = style(11),
        labelLarge = style(14, FontWeight.Medium),
        labelMedium = style(12, FontWeight.Medium),
        // 节次 / 周次 / 时间数字同样跟随应用字体，保证设置字体后全局一致
        labelSmall = style(11)
    )
}

/**
 * 应用主题：多套主题色（含自定义 RGB）× 深色三档（跟随系统/强制浅/强制深）。
 * 动态取色仅在 Android 12+ 且用户开关打开时启用（默认关）。
 *
 * @param wallpaperActive 自定义壁纸是否生效；生效时 surface / background 按 [surfaceAlpha]
 *        变半透明，让底层背景图透出来（手机桌面壁纸效果）。
 */
@Composable
fun SanxingTheme(
    themeColorKey: String = ThemeColorOption.BLUE.key,
    darkMode: String = "SYSTEM",
    dynamicColorEnabled: Boolean = false,
    customColorHex: String? = null,
    wallpaperActive: Boolean = false,
    surfaceAlpha: Float = 1f,
    appFontKey: String = AppFontOption.SYSTEM.key,
    content: @Composable () -> Unit
) {
    val colorOption = ThemeColorOption.entries.firstOrNull { it.key == themeColorKey } ?: ThemeColorOption.BLUE
    val customSeed = parseHexColor(customColorHex)
    val darkTheme = when (darkMode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val useDynamic = dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var colorScheme = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        darkTheme -> darkScheme(colorOption, customSeed)
        else -> lightScheme(colorOption, customSeed)
    }
    if (wallpaperActive) {
        val a = surfaceAlpha.coerceIn(0.15f, 1f)
        colorScheme = colorScheme.copy(
            surface = colorScheme.surface.copy(alpha = a),
            background = colorScheme.background.copy(alpha = a)
        )
    }
    val extended = if (darkTheme) extendedDark(colorOption, customSeed) else extendedLight(colorOption, customSeed)
    val paperAlpha = if (wallpaperActive) surfaceAlpha.coerceIn(0.15f, 1f) else 0.95f
    val appFontOption = AppFontOption.fromKey(appFontKey)
    val appFontFamily = appFontOption.fontFamily()
    val appTypography = buildTypography(appFontFamily)

    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalUiMaskAlpha provides paperAlpha,
        LocalAppFontFamily provides appFontFamily
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography,
            content = content
        )
    }
}
