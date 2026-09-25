package cn.sanxing.thrice.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.backup.BackupCategory
import cn.sanxing.thrice.data.data.backup.ImportMode
import cn.sanxing.thrice.data.data.backup.ImportPreview
import cn.sanxing.thrice.data.data.export.DataExporter
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.export.IcsExportHelper
import cn.sanxing.thrice.export.ShareHelper
import cn.sanxing.thrice.notification.ExactAlarms
import cn.sanxing.thrice.notification.NotifierChannels
import cn.sanxing.thrice.notification.ReminderPrefs
import cn.sanxing.thrice.notification.ReminderScheduler
import cn.sanxing.thrice.rescheduleAndRefreshWidgets
import cn.sanxing.thrice.util.OemIntents
import cn.sanxing.thrice.ui.common.WheelNumberPicker
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.components.PostcardFrame
import cn.sanxing.thrice.ui.navigation.BottomNavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * 设置页（分组卡片）：语言 / 导航 / 外观 / 提醒（权限）/ 后台可靠性 / 数据 / 关于。
 * 节次时间与学期设置已迁移到课表页右上角「课表设置」入口（TimetableSettingsScreen）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenReward: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeColor by container.settingsRepository.themeColor
        .collectAsState(SettingsRepository.THEME_COLOR_BLUE)
    val themeMode by container.settingsRepository.themeMode
        .collectAsState(SettingsRepository.THEME_MODE_SYSTEM)
    val dynamicColor by container.settingsRepository.dynamicColor.collectAsState(false)
    val backgroundAnimation by container.settingsRepository.backgroundAnimation.collectAsState(true)
    val reminderEnabled by container.settingsRepository.reminderEnabled.collectAsState(true)
    val reminderMinutes by container.settingsRepository.reminderMinutes.collectAsState(15)
    val notifSound by container.settingsRepository.notificationSound.collectAsState(true)
    val notifVibrate by container.settingsRepository.notificationVibrate.collectAsState(true)
    val customColorHex by container.settingsRepository.customColor
        .collectAsState(SettingsRepository.DEFAULT_CUSTOM_COLOR)
    val customBgPathState by container.settingsRepository.customBgPath.collectAsState(null)
    val customBgAlphaState by container.settingsRepository.customBgAlpha.collectAsState(80)
    val uiMaskAlphaState by container.settingsRepository.uiMaskAlpha.collectAsState(62)
    val appLanguage by container.settingsRepository.appLanguage.collectAsState(null)
    val appFont by container.settingsRepository.appFont.collectAsState(SettingsRepository.APP_FONT_SYSTEM)
    val bgOffsetX by container.settingsRepository.bgOffsetX.collectAsState(0f)
    val bgOffsetY by container.settingsRepository.bgOffsetY.collectAsState(0f)
    val bgScale by container.settingsRepository.bgScale.collectAsState(1f)

    // 底部导航：完整顺序 + 隐藏集合（编辑态本地维护，每次改动即时持久化）
    val navTabOrder by container.settingsRepository.navTabOrder
        .collectAsState(SettingsRepository.DEFAULT_NAV_TABS)
    val navTabHidden by container.settingsRepository.hiddenNavTabs.collectAsState(emptySet())
    var navOrder by remember { mutableStateOf(navTabOrder) }
    var navHidden by remember { mutableStateOf(navTabHidden) }
    // DataStore 真实值到达后同步一次（内容相同时 LaunchedEffect 不会重启，无覆盖风险）
    LaunchedEffect(navTabOrder, navTabHidden) {
        navOrder = navTabOrder
        navHidden = navTabHidden
    }

    var notice by remember { mutableStateOf<Int?>(null) }
    var pendingPreview by remember { mutableStateOf<ImportPreview?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearCategory by remember { mutableStateOf(BackupCategory.ALL) }
    var showClearAiConfirm by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }
    var showShareChooser by remember { mutableStateOf(false) }
    var exportCategory by remember { mutableStateOf(BackupCategory.ALL) }
    var busy by remember { mutableStateOf(false) }
    var showWallpaperCrop by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    // 可读格式导出：null = 空闲；否则为正在执行的导出类型
    var readableExport by remember { mutableStateOf<ReadableExportKind?>(null) }

    // 可读格式导出器：直接构造（不经 AppContainer），复用进程级数据库单例
    val dataExporter = remember {
        DataExporter(
            context.applicationContext,
            DatabaseProvider.get(context),
            container.settingsRepository,
            container.notesRepository
        )
    }

    // 备份文件名前缀
    fun filePrefix(category: BackupCategory): String = when (category) {
        BackupCategory.TIMETABLE -> "timetable"
        BackupCategory.BILLS -> "bills"
        BackupCategory.TASKS -> "tasks"
        BackupCategory.FOCUS -> "focus"
        BackupCategory.SLEEP -> "sleep"
        BackupCategory.NOTES -> "notes"
        BackupCategory.ALL -> "all"
    }

    // ---------------- 备份 / 恢复 / 分享（SAF + FileProvider） ----------------

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val category = exportCategory
        scope.launch {
            busy = true
            runCatching {
                val json = container.backupManager.exportJson(category)
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("Cannot write to the selected location")
            }.onSuccess {
                notice = R.string.backup_exported
            }.onFailure {
                Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Cannot read the selected file")
                container.backupManager.prepareImport(text)
            }.onSuccess { preview ->
                if (preview.valid) pendingPreview = preview
                else Toast.makeText(
                    context,
                    preview.errors.firstOrNull() ?: context.getString(R.string.backup_invalid),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    // 自定义背景图选图：立即复制进应用私有目录（GetContent 的 URI 不保证长期可读）
    val bgImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
                    // 扩展名按 MIME 推断，未知一律 jpg
                    val mime = context.contentResolver.getType(uri)
                    val ext = when {
                        mime?.contains("png") == true -> "png"
                        mime?.contains("webp") == true -> "webp"
                        mime?.contains("gif") == true -> "gif"
                        else -> "jpg"
                    }
                    val target = java.io.File(dir, "bg_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot read the selected image")
                    // 删除旧背景文件
                    customBgPathState?.let { java.io.File(it) }
                        ?.takeIf { it.exists() }?.delete()
                    target.absolutePath
                }
            }.onSuccess { path ->
                container.settingsRepository.setCustomBgPath(path)
                // 换新图后重置拖动 / 缩放
                container.settingsRepository.setBgTransform(0f, 0f, 1f)
                // 选图后立即进入裁剪定位，由用户自选保留区域（非标准竖屏图不会被强行截断）
                showWallpaperCrop = true
            }.onFailure {
                Toast.makeText(context, R.string.bg_import_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    // Android 13+ 通知运行时权限
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        if (!granted) notice = R.string.reminder_permission_denied
    }

    // 后台可靠性权限状态：从系统设置页返回（ON_RESUME）时刷新
    var batteryOptimizationIgnored by remember {
        mutableStateOf(OemIntents.isIgnoringBatteryOptimizations(context))
    }
    var exactAlarmGranted by remember {
        mutableStateOf(ExactAlarms.canSchedule(context))
    }
    val bgLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(bgLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored =
                    OemIntents.isIgnoringBatteryOptimizations(context)
                exactAlarmGranted = ExactAlarms.canSchedule(context)
            }
        }
        bgLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { bgLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun applyImport(mode: ImportMode) {
        val preview = pendingPreview ?: return
        pendingPreview = null
        scope.launch {
            busy = true
            container.backupManager.applyImport(preview, mode)
                .onSuccess {
                    notice = R.string.backup_restored
                    rescheduleAndRefreshWidgets(container, context)
                }
                .onFailure {
                    Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            busy = false
        }
    }

    fun shareBackupJson(category: BackupCategory) {
        scope.launch {
            busy = true
            runCatching {
                val json = container.backupManager.exportJson(category)
                val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                val stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                val file = java.io.File(dir, "Thrice-${filePrefix(category)}-$stamp.json")
                file.writeText(json)
                context.startActivity(
                    ShareHelper.shareFile(context, file, "application/json",
                        context.getString(R.string.backup_share_chooser))
                )
            }.onFailure {
                Toast.makeText(context, R.string.backup_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    fun shareIcs(onlyWeek: Int?) {
        scope.launch {
            busy = true
            runCatching {
                val file = withContext(Dispatchers.IO) { IcsExportHelper.export(context, onlyWeek) }
                context.startActivity(
                    ShareHelper.shareFile(context, file, "text/calendar",
                        context.getString(R.string.ics_share_chooser))
                )
            }.onFailure {
                Toast.makeText(context, R.string.share_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    // ---------------- 可读格式导出（CSV / Markdown / JSON / 全部 ZIP） ----------------

    fun shareReadableExport(kind: ReadableExportKind) {
        if (readableExport != null) return
        scope.launch {
            readableExport = kind
            runCatching {
                // DataExporter 内部已切到 Dispatchers.IO
                when (kind) {
                    ReadableExportKind.TABLES_CSV -> dataExporter.exportTablesZip()
                    ReadableExportKind.NOTES_MD -> dataExporter.exportNotesZip()
                    ReadableExportKind.JSON_DATA -> dataExporter.exportJsonZip()
                    ReadableExportKind.ALL_ZIP -> dataExporter.exportAllZip()
                }
            }.onSuccess { file ->
                context.startActivity(
                    ShareHelper.shareFile(
                        context, file, "application/zip",
                        context.getString(R.string.export_share_chooser)
                    )
                )
            }.onFailure {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            }
            readableExport = null
        }
    }

    PostcardFrame(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        fillHeight = true
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            notice?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---------------- 语言（设置第一项） ----------------
            SettingsGroup(stringResource(R.string.settings_language)) {
                val currentLang = appLanguage
                    ?: if (java.util.Locale.getDefault().language.startsWith("zh"))
                        SettingsRepository.APP_LANGUAGE_ZH else SettingsRepository.APP_LANGUAGE_EN
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(
                        SettingsRepository.APP_LANGUAGE_ZH to R.string.lang_chinese,
                        SettingsRepository.APP_LANGUAGE_EN to R.string.lang_english
                    ).forEach { (tag, labelRes) ->
                        FilterChip(
                            selected = currentLang == tag,
                            onClick = {
                                scope.launch {
                                    container.settingsRepository.setAppLanguage(tag)
                                }
                                // 同步写语言镜像，保证崩溃 / 进程重启后首帧前恢复
                                runCatching {
                                    context.getSharedPreferences(
                                        cn.sanxing.thrice.SanxingApplication.LOCALE_PREFS,
                                        android.content.Context.MODE_PRIVATE
                                    ).edit().putString(
                                        cn.sanxing.thrice.SanxingApplication.LOCALE_KEY_LANG, tag
                                    ).apply()
                                }
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                                )
                            },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
            }

            // ---------------- 底部导航（显隐 + 排序） ----------------
            SettingsGroup(stringResource(R.string.settings_nav_tabs)) {
                Text(
                    stringResource(R.string.nav_tabs_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                val visibleNavCount = navOrder.count { it !in navHidden }
                navOrder.forEachIndexed { index, name ->
                    val tab = BottomNavItem.entries.firstOrNull { it.name == name }
                        ?: return@forEachIndexed
                    val isHidden = name in navHidden
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(tab.labelRes),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isHidden) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        IconButton(
                            onClick = {
                                navOrder = navOrder.toMutableList().apply {
                                    add(index - 1, removeAt(index))
                                }
                                scope.launch {
                                    container.settingsRepository.setNavTabs(navOrder, navHidden)
                                }
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.cd_move_up),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                navOrder = navOrder.toMutableList().apply {
                                    add(index + 1, removeAt(index))
                                }
                                scope.launch {
                                    container.settingsRepository.setNavTabs(navOrder, navHidden)
                                }
                            },
                            enabled = index < navOrder.lastIndex,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.cd_move_down),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Switch(
                            checked = !isHidden,
                            // 至少保留一个界面：最后一个可见项不允许关闭
                            enabled = isHidden || visibleNavCount > 1,
                            onCheckedChange = { show ->
                                navHidden = navHidden.toMutableSet().apply {
                                    if (show) remove(name) else add(name)
                                }
                                scope.launch {
                                    container.settingsRepository.setNavTabs(navOrder, navHidden)
                                }
                            }
                        )
                    }
                }
            }

            // ---------------- 外观 ----------------
            SettingsGroup(stringResource(R.string.settings_appearance)) {
                Text(
                    stringResource(R.string.theme_color_label),
                    style = MaterialTheme.typography.bodySmall
                )
                // 预设主题色（色点 + 名称，自动换行）
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val presets = listOf(
                        SettingsRepository.THEME_COLOR_BLUE to R.string.theme_blue,
                        SettingsRepository.THEME_COLOR_PINK to R.string.theme_pink,
                        SettingsRepository.THEME_COLOR_MONO to R.string.theme_mono,
                        SettingsRepository.THEME_COLOR_MINT to R.string.theme_mint,
                        SettingsRepository.THEME_COLOR_GRAPE to R.string.theme_grape,
                        SettingsRepository.THEME_COLOR_SUNSET to R.string.theme_sunset,
                        SettingsRepository.THEME_COLOR_TEAL to R.string.theme_teal,
                        SettingsRepository.THEME_COLOR_CRIMSON to R.string.theme_crimson,
                        SettingsRepository.THEME_COLOR_INDIGO to R.string.theme_indigo,
                        SettingsRepository.THEME_COLOR_MOCHA to R.string.theme_mocha,
                        SettingsRepository.THEME_COLOR_CUSTOM to R.string.theme_custom
                    )
                    presets.forEach { (key, labelRes) ->
                        FilterChip(
                            selected = themeColor == key,
                            onClick = { scope.launch { container.settingsRepository.setThemeColor(key) } },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    cn.sanxing.thrice.ui.theme.ThemeColorOption.entries
                                        .firstOrNull { it.key == key }
                                        ?.let { opt ->
                                            val dotColor = when {
                                                opt.seed != null -> opt.seed
                                                key == SettingsRepository.THEME_COLOR_CUSTOM ->
                                                    cn.sanxing.thrice.ui.theme.parseHexColor(customColorHex)
                                                // 三套精修色板：圆点固定为各自标志色，不随当前主题变化
                                                key == SettingsRepository.THEME_COLOR_BLUE ->
                                                    cn.sanxing.thrice.ui.theme.BlueLightPrimary
                                                key == SettingsRepository.THEME_COLOR_PINK ->
                                                    cn.sanxing.thrice.ui.theme.PinkLightPrimary
                                                key == SettingsRepository.THEME_COLOR_MONO ->
                                                    cn.sanxing.thrice.ui.theme.MonoLightPrimary
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                                            Box(
                                                Modifier
                                                    .padding(end = 6.dp)
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(dotColor)
                                            )
                                        }
                                    Text(stringResource(labelRes))
                                }
                            }
                        )
                    }
                }
                // 自定义三原色
                if (themeColor == SettingsRepository.THEME_COLOR_CUSTOM) {
                    val customColor = cn.sanxing.thrice.ui.theme.parseHexColor(customColorHex)
                    var r by remember(customColorHex) { mutableIntStateOf((customColor.red * 255).toInt()) }
                    var g by remember(customColorHex) { mutableIntStateOf((customColor.green * 255).toInt()) }
                    var b by remember(customColorHex) { mutableIntStateOf((customColor.blue * 255).toInt()) }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(r, g, b))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                R.string.theme_custom_rgb,
                                "#%02X%02X%02X".format(r, g, b)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    RgbSlider(stringResource(R.string.rgb_r_label), r, Color(0xFFE53935)) {
                        r = it
                        scope.launch {
                            container.settingsRepository.setCustomColor("#%02X%02X%02X".format(r, g, b))
                        }
                    }
                    RgbSlider(stringResource(R.string.rgb_g_label), g, Color(0xFF43A047)) {
                        g = it
                        scope.launch {
                            container.settingsRepository.setCustomColor("#%02X%02X%02X".format(r, g, b))
                        }
                    }
                    RgbSlider(stringResource(R.string.rgb_b_label), b, Color(0xFF1E88E5)) {
                        b = it
                        scope.launch {
                            container.settingsRepository.setCustomColor("#%02X%02X%02X".format(r, g, b))
                        }
                    }
                }
                HorizontalDivider()
                ChipRow(stringResource(R.string.dark_mode_label)) {
                    listOf(
                        SettingsRepository.THEME_MODE_SYSTEM to R.string.dark_system,
                        SettingsRepository.THEME_MODE_LIGHT to R.string.dark_light,
                        SettingsRepository.THEME_MODE_DARK to R.string.dark_dark
                    ).forEach { (key, labelRes) ->
                        FilterChip(
                            selected = themeMode == key,
                            onClick = { scope.launch { container.settingsRepository.setThemeMode(key) } },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
                HorizontalDivider()
                // 夹到包内实际存在的最大编号：字体数量调整过（如 32 → 12）时，
                // 旧设置里的编号已无对应文件，直接显示会造成「显示 16 实际用系统字体」的错觉。
                val fontNumber = (appFont.toIntOrNull() ?: 0)
                    .coerceIn(0, cn.sanxing.thrice.ui.theme.AppFontOption
                        .maxAvailableNumber(LocalContext.current))
                val currentFontLabel = if (fontNumber == 0) {
                    stringResource(R.string.font_system)
                } else {
                    stringResource(R.string.font_numbered_fmt, fontNumber)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFontPicker = true }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_font),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        currentFontLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (showFontPicker) {
                    // 字体不随仓库分发：只列出当前包内实际存在的编号，缺失编号不暴露
                    val maxFont = cn.sanxing.thrice.ui.theme.AppFontOption
                        .maxAvailableNumber(LocalContext.current)
                    var fontDraft by remember(fontNumber, maxFont) {
                        mutableIntStateOf(fontNumber.coerceAtMost(maxFont))
                    }
                    val fontLabels = (0..maxFont).map { n ->
                        if (n == 0) stringResource(R.string.font_system)
                        else stringResource(R.string.font_numbered_fmt, n)
                    }
                    AlertDialog(
                        onDismissRequest = { showFontPicker = false },
                        title = { Text(stringResource(R.string.font_picker_title)) },
                        text = {
                            WheelNumberPicker(
                                value = fontDraft,
                                onValueChange = { fontDraft = it },
                                range = 0..maxFont,
                                // 不传固定高度：组件内部已按 5 行行高定尺寸，
                                // 外部再压一次高度会让选中行与指示线错位。
                                modifier = Modifier.fillMaxWidth(),
                                format = { n -> fontLabels.getOrElse(n) { "" } },
                                // 每个编号行用其自身对应的字体渲染示例（缺失时内部回落系统字体）
                                itemFontFamily = { n ->
                                    cn.sanxing.thrice.ui.theme.AppFontOption.entries
                                        .getOrNull(n)?.fontFamily()
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    container.settingsRepository.setAppFont("%02d".format(fontDraft))
                                }
                                showFontPicker = false
                            }) { Text(stringResource(R.string.action_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFontPicker = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.dynamic_color_label),
                    desc = stringResource(R.string.dynamic_color_desc),
                    checked = dynamicColor,
                    onChange = { scope.launch { container.settingsRepository.setDynamicColor(it) } }
                )
                ToggleRow(
                    label = stringResource(R.string.background_animation_label),
                    desc = stringResource(R.string.background_animation_desc),
                    checked = backgroundAnimation,
                    onChange = { scope.launch { container.settingsRepository.setBackgroundAnimation(it) } }
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.bg_wallpaper_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { bgImageLauncher.launch("image/*") }
                    ) {
                        Text(
                            if (customBgPathState == null) stringResource(R.string.bg_import)
                            else stringResource(R.string.bg_change)
                        )
                    }
                    if (customBgPathState != null) {
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                customBgPathState?.let { java.io.File(it) }
                                    ?.takeIf { f -> f.exists() }?.delete()
                                container.settingsRepository.setCustomBgPath(null)
                            }
                        }) { Text(stringResource(R.string.bg_remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
                // 已选背景图缩略预览：填满原有的空白区域，点击进入拖动/缩放定位
                customBgPathState?.let { path ->
                    val bgFile = remember(path) { java.io.File(path) }
                    if (bgFile.exists()) {
                        cn.sanxing.thrice.ui.components.WallpaperPreview(
                            file = bgFile,
                            offsetX = bgOffsetX,
                            offsetY = bgOffsetY,
                            scale = bgScale,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                ),
                            onClick = { showWallpaperCrop = true }
                        )
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { showWallpaperCrop = true }
                        ) { Text(stringResource(R.string.bg_adjust_position)) }
                    }
                }
                if (customBgPathState != null) {
                    Text(
                        stringResource(R.string.bg_image_alpha, customBgAlphaState),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = customBgAlphaState.toFloat(),
                        onValueChange = { v ->
                            scope.launch {
                                container.settingsRepository.setCustomBgAlpha(v.toInt())
                            }
                        },
                        valueRange = 10f..100f,
                        steps = 89
                    )
                    Text(
                        stringResource(R.string.bg_ui_alpha, uiMaskAlphaState),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = uiMaskAlphaState.toFloat(),
                        onValueChange = { v ->
                            scope.launch {
                                container.settingsRepository.setUiMaskAlpha(v.toInt())
                            }
                        },
                        valueRange = 15f..100f,
                        steps = 84
                    )
                }
            }

            // ---------------- 提醒 ----------------
            SettingsGroup(stringResource(R.string.settings_reminder)) {
                ToggleRow(
                    label = stringResource(R.string.course_reminder_enabled),
                    desc = stringResource(R.string.course_reminder_enabled_desc),
                    checked = reminderEnabled,
                    onChange = { enabled ->
                        scope.launch {
                            container.settingsRepository.setReminderEnabled(enabled)
                            if (enabled) {
                                val minutes = container.settingsRepository.reminderMinutes.first()
                                ReminderScheduler.rescheduleAll(context, minutes)
                            } else {
                                ReminderScheduler.cancelAll(context)
                            }
                        }
                    }
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.reminder_minutes_label) +
                        "：" + stringResource(R.string.reminder_slider_hint, reminderMinutes),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = reminderMinutes.toFloat(),
                    onValueChange = { v ->
                        val m = v.toInt().coerceIn(1, 120)
                        scope.launch {
                            container.settingsRepository.setReminderMinutes(m)
                            if (reminderEnabled) {
                                ReminderScheduler.rescheduleAll(context, m)
                            }
                        }
                    },
                    onValueChangeFinished = {
                        scope.launch {
                            if (reminderEnabled) {
                                val minutes = container.settingsRepository.reminderMinutes.first()
                                ReminderScheduler.rescheduleAll(context, minutes)
                            }
                        }
                    },
                    valueRange = 1f..120f,
                    steps = 118
                )
                HorizontalDivider()
                ToggleRow(
                    label = stringResource(R.string.notif_sound_label),
                    desc = stringResource(R.string.notif_sound_desc),
                    checked = notifSound,
                    onChange = { value ->
                        scope.launch {
                            container.settingsRepository.setNotificationSound(value)
                            ReminderPrefs.setOptions(context, value, notifVibrate)
                            NotifierChannels.recreateAll(context)
                        }
                    }
                )
                ToggleRow(
                    label = stringResource(R.string.notif_vibrate_label),
                    desc = stringResource(R.string.notif_vibrate_desc),
                    checked = notifVibrate,
                    onChange = { value ->
                        scope.launch {
                            container.settingsRepository.setNotificationVibrate(value)
                            ReminderPrefs.setOptions(context, notifSound, value)
                            NotifierChannels.recreateAll(context)
                        }
                    }
                )
                HorizontalDivider()
                PermissionRow(
                    label = stringResource(R.string.reminder_notif_permission),
                    granted = notificationsGranted,
                    buttonText = stringResource(
                        if (notificationsGranted) R.string.reminder_permission_granted
                        else R.string.reminder_request_permission
                    ),
                    onButton = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                if (!notificationsGranted) {
                    Text(
                        stringResource(R.string.reminder_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ---------------- 后台运行与提醒可靠性 ----------------
            SettingsGroup(stringResource(R.string.bg_group_title)) {
                BgPermissionRow(
                    label = stringResource(R.string.bg_battery_ignore),
                    desc = stringResource(R.string.bg_battery_ignore_desc),
                    buttonText = stringResource(
                        if (batteryOptimizationIgnored) R.string.bg_granted
                        else R.string.bg_open
                    ),
                    buttonEnabled = !batteryOptimizationIgnored,
                    onButton = { OemIntents.requestIgnoreBatteryOptimizations(context) }
                )
                HorizontalDivider()
                BgPermissionRow(
                    label = stringResource(R.string.bg_autostart),
                    desc = stringResource(R.string.bg_autostart_desc),
                    buttonText = stringResource(R.string.bg_open),
                    onButton = { OemIntents.openAutostartSettings(context) }
                )
                // 精确闹钟权限只在 API 31+ 存在，低版本整行不显示
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider()
                    BgPermissionRow(
                        label = stringResource(R.string.bg_exact_alarm),
                        desc = stringResource(R.string.bg_exact_alarm_desc),
                        buttonText = stringResource(
                            if (exactAlarmGranted) R.string.bg_granted
                            else R.string.bg_open
                        ),
                        buttonEnabled = !exactAlarmGranted,
                        onButton = { OemIntents.openExactAlarmSettings(context) }
                    )
                }
                HorizontalDivider()
                BgPermissionRow(
                    label = stringResource(R.string.bg_notif_settings),
                    desc = stringResource(R.string.bg_notif_settings_desc),
                    buttonText = stringResource(R.string.bg_open),
                    onButton = { OemIntents.openNotificationSettings(context) }
                )
            }

            SettingsGroup(stringResource(R.string.settings_data)) {
                Text(
                    stringResource(R.string.data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = { showExportChooser = true }
                    ) { Text(stringResource(R.string.backup_label)) }
                    OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                        Text(stringResource(R.string.restore_label))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { showShareChooser = true }) {
                        Text(stringResource(R.string.backup_share))
                    }
                    OutlinedButton(enabled = !busy, onClick = { shareIcs(null) }) {
                        Text(stringResource(R.string.ics_export_full))
                    }
                    OutlinedButton(enabled = !busy, onClick = { shareIcs(0) }) {
                        Text(stringResource(R.string.ics_export_week))
                    }
                }
                Text(
                    stringResource(R.string.ics_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )

                // ---------------- 可读格式导出（CSV / Markdown / JSON / 全部） ----------------
                HorizontalDivider()
                Text(
                    stringResource(R.string.export_readable_section),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(R.string.export_readable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                ReadableExportRow(
                    title = stringResource(R.string.export_tables_csv),
                    desc = stringResource(R.string.export_tables_csv_desc),
                    busy = readableExport == ReadableExportKind.TABLES_CSV,
                    enabled = readableExport == null,
                    onClick = { shareReadableExport(ReadableExportKind.TABLES_CSV) }
                )
                ReadableExportRow(
                    title = stringResource(R.string.export_notes_md),
                    desc = stringResource(R.string.export_notes_md_desc),
                    busy = readableExport == ReadableExportKind.NOTES_MD,
                    enabled = readableExport == null,
                    onClick = { shareReadableExport(ReadableExportKind.NOTES_MD) }
                )
                ReadableExportRow(
                    title = stringResource(R.string.export_json_data),
                    desc = stringResource(R.string.export_json_data_desc),
                    busy = readableExport == ReadableExportKind.JSON_DATA,
                    enabled = readableExport == null,
                    onClick = { shareReadableExport(ReadableExportKind.JSON_DATA) }
                )
                ReadableExportRow(
                    title = stringResource(R.string.export_all_zip),
                    desc = stringResource(R.string.export_all_zip_desc),
                    busy = readableExport == ReadableExportKind.ALL_ZIP,
                    enabled = readableExport == null,
                    onClick = { shareReadableExport(ReadableExportKind.ALL_ZIP) }
                )
                readableExport?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            stringResource(R.string.export_progress),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.data_delete_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        BackupCategory.TIMETABLE to R.string.data_delete_timetable,
                        BackupCategory.BILLS to R.string.data_delete_bills,
                        BackupCategory.TASKS to R.string.data_delete_tasks,
                        BackupCategory.FOCUS to R.string.data_delete_focus,
                        BackupCategory.SLEEP to R.string.data_delete_sleep,
                        BackupCategory.NOTES to R.string.data_delete_notes,
                        BackupCategory.ALL to R.string.data_delete_all
                    ).forEach { (cat, labelRes) ->
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                clearCategory = cat
                                showClearConfirm = true
                            }
                        ) { Text(stringResource(labelRes), color = MaterialTheme.colorScheme.error) }
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { showClearAiConfirm = true }
                    ) { Text(stringResource(R.string.data_delete_ai_config), color = MaterialTheme.colorScheme.error) }
                }
            }

            // ---------------- 关于 ----------------
            SettingsGroup(stringResource(R.string.settings_about)) {
                val versionName = remember {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "—"
                }
                Text(
                    stringResource(R.string.app_name) + " · v$versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.about_intro_line1) + "\n" +
                        stringResource(R.string.about_feature_schedule) + "\n" +
                        stringResource(R.string.about_feature_tasks) + "\n" +
                        stringResource(R.string.about_feature_bills) + "\n" +
                        stringResource(R.string.about_feature_focus) + "\n" +
                        stringResource(R.string.about_feature_sleep) + "\n" +
                        stringResource(R.string.about_feature_notes) + "\n" +
                        stringResource(R.string.about_feature_ai) + "\n" +
                        stringResource(R.string.about_feature_personal) + "\n" +
                        stringResource(R.string.about_local_only),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.about_author_suffix),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Text(
                    stringResource(R.string.about_open_source_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                        )
                    }
                }) {
                    Text(stringResource(R.string.about_star_button))
                }
                Text(
                    GITHUB_REPO_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                // 打赏作者：与 GitHub 按钮同款 OutlinedButton，下方附鼓励语；
                // 点击进入独立收款码页（RewardScreen）。
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onOpenReward) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_reward_author))
                }
                Text(
                    stringResource(R.string.reward_encouragement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                // 联系作者（开源后的反馈入口，置于设置最末端）
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_contact_author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }

    // ---------------- 导出类别选择（保存到文件 / 分享共用） ----------------

    if (showExportChooser) {
        BackupCategoryDialog(
            title = stringResource(R.string.export_chooser_title),
            onDismiss = { showExportChooser = false },
            onSelect = { category ->
                showExportChooser = false
                exportCategory = category
                val stamp = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                exportLauncher.launch("Thrice-${filePrefix(category)}-$stamp.json")
            }
        )
    }
    if (showShareChooser) {
        BackupCategoryDialog(
            title = stringResource(R.string.share_chooser_title),
            onDismiss = { showShareChooser = false },
            onSelect = { category ->
                showShareChooser = false
                shareBackupJson(category)
            }
        )
    }

    // ---------------- 导入预览对话框 ----------------

    pendingPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { pendingPreview = null },
            title = { Text(stringResource(R.string.restore_preview_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.restore_preview_message,
                        preview.importCourses,
                        preview.importTerms,
                        preview.currentCourses
                    ) + "\n" + stringResource(
                        R.string.restore_preview_extra,
                        preview.importTasks,
                        preview.importBills
                    )
                )
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { applyImport(ImportMode.OVERWRITE) }) {
                        Text(
                            stringResource(R.string.restore_overwrite),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { applyImport(ImportMode.MERGE) }) {
                        Text(stringResource(R.string.restore_merge))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreview = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearConfirm) {
        val catName = stringResource(backupCatShortNameRes(clearCategory))
        val catDetail = stringResource(backupCatDetailRes(clearCategory))
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_cat_title, catName)) },
            text = {
                Text(stringResource(R.string.clear_cat_message, catName, catDetail))
            },
            confirmButton = {
                TextButton(onClick = {
                    val category = clearCategory
                    showClearConfirm = false
                    scope.launch {
                        container.backupManager.clearData(category)
                        notice = R.string.cleared_all
                        rescheduleAndRefreshWidgets(container, context)
                    }
                }) { Text(stringResource(R.string.clear_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearAiConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAiConfirm = false },
            title = { Text(stringResource(R.string.clear_ai_config_title)) },
            text = { Text(stringResource(R.string.clear_ai_config_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAiConfirm = false
                    scope.launch {
                        container.aiSettingsRepository.clearAll()
                        notice = R.string.cleared_all
                    }
                }) { Text(stringResource(R.string.clear_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAiConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ---------------- 背景图拖动 / 缩放定位 ----------------
    if (showWallpaperCrop) {
        customBgPathState?.let { path ->
            val bgFile = java.io.File(path)
            if (bgFile.exists()) {
                cn.sanxing.thrice.ui.components.WallpaperCropDialog(
                    file = bgFile,
                    initialPanX = bgOffsetX,
                    initialPanY = bgOffsetY,
                    initialScale = bgScale,
                    onCancel = { showWallpaperCrop = false },
                    onConfirm = { ox, oy, s ->
                        showWallpaperCrop = false
                        scope.launch {
                            container.settingsRepository.setBgTransform(ox, oy, s)
                        }
                    }
                )
            }
        }
    }
}

/** 备份类别 → 名词型短名称资源（导出按钮、清除确认标题/正文拼接用）。 */
private fun backupCatShortNameRes(category: BackupCategory): Int = when (category) {
    BackupCategory.TIMETABLE -> R.string.data_noun_timetable
    BackupCategory.BILLS -> R.string.data_noun_bills
    BackupCategory.TASKS -> R.string.data_noun_tasks
    BackupCategory.FOCUS -> R.string.data_noun_focus
    BackupCategory.SLEEP -> R.string.data_noun_sleep
    BackupCategory.NOTES -> R.string.data_noun_notes
    BackupCategory.ALL -> R.string.data_noun_all
}

/** 备份类别 → 详细说明资源。 */
private fun backupCatDetailRes(category: BackupCategory): Int = when (category) {
    BackupCategory.TIMETABLE -> R.string.clear_timetable_detail
    BackupCategory.BILLS -> R.string.clear_bills_detail
    BackupCategory.TASKS -> R.string.clear_tasks_detail
    BackupCategory.FOCUS -> R.string.clear_focus_detail
    BackupCategory.SLEEP -> R.string.clear_sleep_detail
    BackupCategory.NOTES -> R.string.clear_notes_detail
    BackupCategory.ALL -> R.string.clear_all_detail
}

/** 备份类别 → 类别描述资源。 */
private fun backupCatDescRes(category: BackupCategory): Int = when (category) {
    BackupCategory.TIMETABLE -> R.string.cat_timetable_desc
    BackupCategory.BILLS -> R.string.cat_bills_desc
    BackupCategory.TASKS -> R.string.cat_tasks_desc
    BackupCategory.FOCUS -> R.string.cat_focus_desc
    BackupCategory.SLEEP -> R.string.cat_sleep_desc
    BackupCategory.NOTES -> R.string.cat_notes_desc
    BackupCategory.ALL -> R.string.cat_all_desc
}

/** 开源仓库地址（关于页展示与「Star」按钮共用）。 */
private const val GITHUB_REPO_URL = "https://github.com/EdmundAshford/Thrice"

/** 导出 / 分享类别选择对话框。 */
@Composable
private fun BackupCategoryDialog(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (BackupCategory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        text = {
            Column {
                BackupCategory.entries.forEach { category ->
                    Text(
                        stringResource(backupCatDescRes(category)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { onSelect(category) }) {
                        Text(stringResource(R.string.cat_export_action, stringResource(backupCatShortNameRes(category))))
                    }
                    HorizontalDivider()
                }
            }
        }
    )
}

/** 可读格式导出的四个入口。 */
private enum class ReadableExportKind { TABLES_CSV, NOTES_MD, JSON_DATA, ALL_ZIP }

/** 可读格式导出列表项：标题 + 描述，整行点击；进行中显示小转圈。 */
@Composable
private fun ReadableExportRow(
    title: String,
    desc: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

// ---------------- 小组件 ----------------

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    // 与其他页面一致：卡片不透明度跟随「背景不透明度」设置（壁纸模式下生效）
    val paperAlpha = cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = paperAlpha)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, chips: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) { chips() }
    }
}

/** 自定义主题色的 RGB 通道滑块（0..255）。 */
@Composable
private fun RgbSlider(label: String, value: Int, accent: Color, onChange: (Int) -> Unit) {
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
            modifier = Modifier.width(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, buttonText: String, onButton: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(if (granted) R.string.reminder_status_granted else R.string.reminder_status_denied),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        TextButton(onClick = onButton) { Text(buttonText) }
    }
}

/** 后台可靠性设置行：标题 + 说明 + 右侧操作按钮（已授予时按钮禁用）。 */
@Composable
private fun BgPermissionRow(
    label: String,
    desc: String,
    buttonText: String,
    buttonEnabled: Boolean = true,
    onButton: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        TextButton(onClick = onButton, enabled = buttonEnabled) { Text(buttonText) }
    }
}

private fun isValidTime(s: String): Boolean =
    s.length == 5 && s[2] == ':' && s.substring(0, 2).toIntOrNull() in 0..23 &&
        s.substring(3, 5).toIntOrNull() in 0..59


