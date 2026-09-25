package cn.sanxing.thrice.data.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置（DataStore Preferences）：主题模式 / 主题色（预设+自定义 RGB）/ 动态取色 /
 * 背景动画 / 自定义背景壁纸与主体浓度 / 提醒提前量 / 通知选项。
 * 节次时间表与学期起始日存于 Room（见 SectionTime / Term），由对应 Repository 管理。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")     // SYSTEM / LIGHT / DARK
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")   // 见 THEME_COLOR_* / CUSTOM
        val KEY_CUSTOM_COLOR = stringPreferencesKey("custom_color") // #RRGGBB
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_BACKGROUND_ANIMATION = booleanPreferencesKey("background_animation")
        val KEY_REMINDER_MINUTES = intPreferencesKey("reminder_minutes") // 1..120，自定义
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled") // 全局提醒开关
        val KEY_NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound") // 通知铃声
        val KEY_NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate") // 通知振动
        val KEY_BG_IMAGE_PATH = stringPreferencesKey("bg_image_path") // 自定义背景图（filesDir 内）
        val KEY_BG_IMAGE_ALPHA = intPreferencesKey("bg_image_alpha") // 背景图不透明度 0..100
        val KEY_UI_MASK_ALPHA = intPreferencesKey("ui_mask_alpha") // 壁纸模式下主体界面浓度 0..100
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language") // 应用内语言：zh / en
        val KEY_APP_FONT = stringPreferencesKey("app_font") // 应用字体：见 AppFontOption.key
        val KEY_BG_OFFSET_X = floatPreferencesKey("bg_offset_x") // 背景图平移 X：归一化 -1..1（相对当前缩放的可溢出量，分辨率无关）
        val KEY_BG_OFFSET_Y = floatPreferencesKey("bg_offset_y") // 背景图平移 Y：归一化 -1..1
        val KEY_BG_SCALE = floatPreferencesKey("bg_scale") // 背景图缩放（>=1f，叠加在 ContentScale.Crop 之上）
        val KEY_NAV_TAB_ORDER = stringPreferencesKey("nav_tab_order") // 底部导航：全部可配置项的完整顺序
        val KEY_NAV_TAB_HIDDEN = stringPreferencesKey("nav_tab_hidden") // 底部导航：被隐藏项 name 逗号分隔（设置页不参与）

        // 计时（专注）
        val KEY_FOCUS_KEEP_SCREEN_ON = booleanPreferencesKey("focus_keep_screen_on")
        val KEY_FOCUS_LEAVE_BEHAVIOR = stringPreferencesKey("focus_leave_behavior") // PAUSE / FAIL / KEEP
        val KEY_FOCUS_COUNTDOWN_MIN = intPreferencesKey("focus_countdown_min") // 默认倒计时分钟
        val KEY_FOCUS_NOTIFY_ON_FINISH = booleanPreferencesKey("focus_notify_on_finish") // 结束通知开关
        val KEY_FOCUS_LAST_TAG = longPreferencesKey("focus_last_tag") // 上次选中标签

        // 睡眠
        val KEY_SLEEP_NAP_ENABLED = booleanPreferencesKey("sleep_nap_enabled")
        val KEY_SLEEP_GOAL_MODE = stringPreferencesKey("sleep_goal_mode") // DURATION / BED_WAKE
        val KEY_SLEEP_NIGHT_GOAL_MIN = intPreferencesKey("sleep_night_goal_min") // 夜睡目标分钟
        val KEY_SLEEP_BED_TIME_MINUTE = intPreferencesKey("sleep_bed_time_minute") // 目标入睡时刻（分钟）
        val KEY_SLEEP_WAKE_TIME_MINUTE = intPreferencesKey("sleep_wake_time_minute") // 目标起床时刻（分钟）
        val KEY_SLEEP_BED_REMINDER_ENABLED = booleanPreferencesKey("sleep_bed_reminder_enabled")
        val KEY_SLEEP_BED_REMINDER_MINUTE = intPreferencesKey("sleep_bed_reminder_minute") // 入睡提醒时刻（分钟）
        val KEY_SLEEP_NAP_GOAL_MIN = intPreferencesKey("sleep_nap_goal_min") // 午休目标分钟
        val KEY_SLEEP_NAP_GOAL_MODE = stringPreferencesKey("sleep_nap_goal_mode") // 午休目标模式 DURATION / BED_WAKE
        val KEY_SLEEP_NAP_START_MINUTE = intPreferencesKey("sleep_nap_start_minute") // 午休开始时刻（分钟）
        val KEY_SLEEP_NAP_END_MINUTE = intPreferencesKey("sleep_nap_end_minute") // 午休结束时刻（分钟）
        val KEY_SLEEP_NAP_REMINDER_ENABLED = booleanPreferencesKey("sleep_nap_reminder_enabled")
        val KEY_SLEEP_NAP_REMINDER_MINUTE = intPreferencesKey("sleep_nap_reminder_minute") // 午休提醒时刻（分钟）

        const val FOCUS_LEAVE_PAUSE = "PAUSE"
        const val FOCUS_LEAVE_FAIL = "FAIL"
        const val FOCUS_LEAVE_KEEP = "KEEP"
        const val FOCUS_DEFAULT_COUNTDOWN_MIN = 25
        // 倒计时上限：999 小时 59 分
        const val FOCUS_MAX_COUNTDOWN_MIN = 999 * 60 + 59
        const val SLEEP_GOAL_MODE_DURATION = "DURATION"
        const val SLEEP_GOAL_MODE_BED_WAKE = "BED_WAKE"
        const val SLEEP_DEFAULT_GOAL_MIN = 8 * 60
        const val SLEEP_DEFAULT_BED_TIME_MINUTE = 23 * 60 + 30
        const val SLEEP_DEFAULT_WAKE_TIME_MINUTE = 7 * 60
        const val SLEEP_DEFAULT_REMINDER_MINUTE = 23 * 60
        const val SLEEP_DEFAULT_NAP_GOAL_MIN = 30
        const val SLEEP_DEFAULT_NAP_START_MINUTE = 13 * 60
        const val SLEEP_DEFAULT_NAP_END_MINUTE = 13 * 60 + 30
        const val SLEEP_DEFAULT_NAP_REMINDER_MINUTE = 13 * 60

        const val APP_LANGUAGE_ZH = "zh"
        const val APP_LANGUAGE_EN = "en"

        const val APP_FONT_SYSTEM = "00"   // 系统默认；01..32 为内置编号字体（旧键 SYSTEM/SERIF/… 回落 00）

        const val THEME_MODE_SYSTEM = "SYSTEM"
        const val THEME_MODE_LIGHT = "LIGHT"
        const val THEME_MODE_DARK = "DARK"

        // 预设主题色（Theme.kt 中带精修色板的三套）
        const val THEME_COLOR_BLUE = "BLUE"
        const val THEME_COLOR_PINK = "PINK"
        const val THEME_COLOR_MONO = "MONO"
        // 种子色派生的预设（ThemeColorOption.seed）
        const val THEME_COLOR_MINT = "MINT"
        const val THEME_COLOR_GRAPE = "GRAPE"
        const val THEME_COLOR_SUNSET = "SUNSET"
        const val THEME_COLOR_TEAL = "TEAL"
        const val THEME_COLOR_CRIMSON = "CRIMSON"
        const val THEME_COLOR_INDIGO = "INDIGO"
        const val THEME_COLOR_MOCHA = "MOCHA"
        const val THEME_COLOR_CUSTOM = "CUSTOM"

        const val DEFAULT_CUSTOM_COLOR = "#1E88E5"

        // 底部导航可配置项（name 即持久化标识；设置页固定显示、固定在末尾，不在此列）
        const val NAV_TAB_SCHEDULE = "SCHEDULE"
        const val NAV_TAB_BILLS = "BILLS"
        const val NAV_TAB_TASKS = "TASKS"
        const val NAV_TAB_TIMETABLE = "TIMETABLE"
        const val NAV_TAB_FOCUS = "FOCUS"
        const val NAV_TAB_SLEEP = "SLEEP"
        const val NAV_TAB_NOTES = "NOTES"
        const val NAV_TAB_AI = "AI"

        /** 可配置项的默认顺序，也是旧版本升级后的回落顺序。 */
        val DEFAULT_NAV_TABS: List<String> = listOf(
            NAV_TAB_SCHEDULE, NAV_TAB_BILLS, NAV_TAB_TASKS, NAV_TAB_TIMETABLE,
            NAV_TAB_FOCUS, NAV_TAB_SLEEP, NAV_TAB_NOTES, NAV_TAB_AI
        )
    }

    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME_MODE] ?: THEME_MODE_SYSTEM }

    val themeColor: Flow<String> = dataStore.data.map { it[KEY_THEME_COLOR] ?: THEME_COLOR_BLUE }

    /** 自定义主题色（#RRGGBB）。 */
    val customColor: Flow<String> = dataStore.data.map { it[KEY_CUSTOM_COLOR] ?: DEFAULT_CUSTOM_COLOR }

    /** Android 12+ 动态取色（默认关：手设主题是主视觉）。 */
    val dynamicColor: Flow<Boolean> = dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: false }

    /** 几何背景动画开关（默认开；关闭后背景静止，性能敏感设备可关）。 */
    val backgroundAnimation: Flow<Boolean> = dataStore.data.map { it[KEY_BACKGROUND_ANIMATION] ?: true }

    val reminderMinutes: Flow<Int> = dataStore.data.map { it[KEY_REMINDER_MINUTES] ?: 15 }

    val reminderEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_REMINDER_ENABLED] ?: true }

    /** 通知铃声（默认开）。 */
    val notificationSound: Flow<Boolean> = dataStore.data.map { it[KEY_NOTIFICATION_SOUND] ?: true }

    /** 通知振动（默认开）。 */
    val notificationVibrate: Flow<Boolean> = dataStore.data.map { it[KEY_NOTIFICATION_VIBRATE] ?: true }

    /** 自定义背景图相对 filesDir 的路径，null 表示未设置。 */
    val customBgPath: Flow<String?> = dataStore.data.map { it[KEY_BG_IMAGE_PATH] }

    /** 自定义背景图不透明度百分比 0..100，默认 80。 */
    val customBgAlpha: Flow<Int> = dataStore.data.map { it[KEY_BG_IMAGE_ALPHA] ?: 80 }

    /** 壁纸模式下主体界面浓度（百分比），越小越透；默认 62。 */
    val uiMaskAlpha: Flow<Int> = dataStore.data.map { it[KEY_UI_MASK_ALPHA] ?: 62 }

    /** 应用内语言（zh / en）；null 表示尚未选择（跟随系统）。 */
    val appLanguage: Flow<String?> = dataStore.data.map { it[KEY_APP_LANGUAGE] }

    /** 应用字体 key（见 AppFontOption），默认 SYSTEM。 */
    val appFont: Flow<String> = dataStore.data.map { it[KEY_APP_FONT] ?: APP_FONT_SYSTEM }

    /** 背景图水平平移归一化偏移（-1f 最左 … 0 居中 … 1f 最右）。 */
    val bgOffsetX: Flow<Float> = dataStore.data.map { it[KEY_BG_OFFSET_X] ?: 0f }

    /** 背景图垂直平移归一化偏移（-1f 最上 … 0 居中 … 1f 最下）。 */
    val bgOffsetY: Flow<Float> = dataStore.data.map { it[KEY_BG_OFFSET_Y] ?: 0f }

    /** 背景图额外缩放（1f = cover 填满裁剪，>1f 继续放大，范围 1..6）。 */
    val bgScale: Flow<Float> = dataStore.data.map { it[KEY_BG_SCALE] ?: 1f }

    /**
     * 底部导航全部可配置项的完整顺序（含被隐藏项）。
     * 未配置时返回默认顺序；旧值中的非法标识会被剔除，后续版本新增项自动追加在末尾。
     */
    val navTabOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_NAV_TAB_ORDER]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in DEFAULT_NAV_TABS }
            ?.distinct()
        if (stored.isNullOrEmpty()) {
            DEFAULT_NAV_TABS
        } else {
            stored + DEFAULT_NAV_TABS.filter { it !in stored }
        }
    }

    /** 底部导航中被用户隐藏的可配置项（空集 = 全部显示）。 */
    val hiddenNavTabs: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_NAV_TAB_HIDDEN]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in DEFAULT_NAV_TABS }
            ?.toSet()
            ?: emptySet()
    }

    // ---------------- 计时（专注） ----------------

    val focusKeepScreenOn: Flow<Boolean> = dataStore.data.map { it[KEY_FOCUS_KEEP_SCREEN_ON] ?: true }
    val focusLeaveBehavior: Flow<String> =
        dataStore.data.map { it[KEY_FOCUS_LEAVE_BEHAVIOR] ?: FOCUS_LEAVE_PAUSE }
    val focusCountdownMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_FOCUS_COUNTDOWN_MIN] ?: FOCUS_DEFAULT_COUNTDOWN_MIN }
    val focusNotifyOnFinish: Flow<Boolean> =
        dataStore.data.map { it[KEY_FOCUS_NOTIFY_ON_FINISH] ?: true }
    val focusLastTagId: Flow<Long?> = dataStore.data.map { it[KEY_FOCUS_LAST_TAG] }

    suspend fun setFocusKeepScreenOn(value: Boolean) {
        dataStore.edit { it[KEY_FOCUS_KEEP_SCREEN_ON] = value }
    }

    suspend fun setFocusLeaveBehavior(value: String) {
        dataStore.edit {
            it[KEY_FOCUS_LEAVE_BEHAVIOR] = when (value) {
                FOCUS_LEAVE_FAIL -> FOCUS_LEAVE_FAIL
                FOCUS_LEAVE_KEEP -> FOCUS_LEAVE_KEEP
                else -> FOCUS_LEAVE_PAUSE
            }
        }
    }

    suspend fun setFocusCountdownMinutes(value: Int) {
        dataStore.edit { it[KEY_FOCUS_COUNTDOWN_MIN] = value.coerceIn(1, FOCUS_MAX_COUNTDOWN_MIN) }
    }

    suspend fun setFocusNotifyOnFinish(value: Boolean) {
        dataStore.edit { it[KEY_FOCUS_NOTIFY_ON_FINISH] = value }
    }

    suspend fun setFocusLastTag(value: Long?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_FOCUS_LAST_TAG) else prefs[KEY_FOCUS_LAST_TAG] = value
        }
    }

    // ---------------- 睡眠 ----------------

    val sleepNapEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_SLEEP_NAP_ENABLED] ?: false }
    val sleepGoalMode: Flow<String> =
        dataStore.data.map { it[KEY_SLEEP_GOAL_MODE] ?: SLEEP_GOAL_MODE_DURATION }
    val sleepNightGoalMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_NIGHT_GOAL_MIN] ?: SLEEP_DEFAULT_GOAL_MIN }
    val sleepBedTimeMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_BED_TIME_MINUTE] ?: SLEEP_DEFAULT_BED_TIME_MINUTE }
    val sleepWakeTimeMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_WAKE_TIME_MINUTE] ?: SLEEP_DEFAULT_WAKE_TIME_MINUTE }
    val sleepBedReminderEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_BED_REMINDER_ENABLED] ?: false }
    val sleepBedReminderMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_BED_REMINDER_MINUTE] ?: SLEEP_DEFAULT_REMINDER_MINUTE }
    val sleepNapGoalMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_NAP_GOAL_MIN] ?: SLEEP_DEFAULT_NAP_GOAL_MIN }
    val sleepNapGoalMode: Flow<String> =
        dataStore.data.map { it[KEY_SLEEP_NAP_GOAL_MODE] ?: SLEEP_GOAL_MODE_DURATION }
    val sleepNapStartMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_NAP_START_MINUTE] ?: SLEEP_DEFAULT_NAP_START_MINUTE }
    val sleepNapEndMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_NAP_END_MINUTE] ?: SLEEP_DEFAULT_NAP_END_MINUTE }
    /** 午休生效目标分钟：时刻模式下由开始/结束时刻环绕差值自动换算。 */
    val sleepNapEffectiveGoalMinutes: Flow<Int> = combine(
        sleepNapGoalMode, sleepNapGoalMinutes, sleepNapStartMinute, sleepNapEndMinute
    ) { mode, duration, start, end ->
        if (mode == SLEEP_GOAL_MODE_BED_WAKE) nightGoalMinutes(start, end) else duration
    }
    val sleepNapReminderEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SLEEP_NAP_REMINDER_ENABLED] ?: false }
    val sleepNapReminderMinute: Flow<Int> =
        dataStore.data.map { it[KEY_SLEEP_NAP_REMINDER_MINUTE] ?: SLEEP_DEFAULT_NAP_REMINDER_MINUTE }

    suspend fun setSleepNapEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SLEEP_NAP_ENABLED] = value }
    }

    suspend fun setSleepGoalMode(value: String) {
        dataStore.edit {
            it[KEY_SLEEP_GOAL_MODE] =
                if (value == SLEEP_GOAL_MODE_BED_WAKE) SLEEP_GOAL_MODE_BED_WAKE
                else SLEEP_GOAL_MODE_DURATION
        }
    }

    suspend fun setSleepNightGoalMinutes(value: Int) {
        dataStore.edit { it[KEY_SLEEP_NIGHT_GOAL_MIN] = value.coerceIn(1, 24 * 60 - 1) }
    }

    suspend fun setSleepBedTimeMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_BED_TIME_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSleepWakeTimeMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_WAKE_TIME_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSleepBedReminderEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SLEEP_BED_REMINDER_ENABLED] = value }
    }

    suspend fun setSleepBedReminderMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_BED_REMINDER_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSleepNapGoalMinutes(value: Int) {
        dataStore.edit { it[KEY_SLEEP_NAP_GOAL_MIN] = value.coerceIn(1, 24 * 60 - 1) }
    }

    suspend fun setSleepNapGoalMode(value: String) {
        dataStore.edit {
            it[KEY_SLEEP_NAP_GOAL_MODE] =
                if (value == SLEEP_GOAL_MODE_BED_WAKE) SLEEP_GOAL_MODE_BED_WAKE
                else SLEEP_GOAL_MODE_DURATION
        }
    }

    suspend fun setSleepNapStartMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_NAP_START_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSleepNapEndMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_NAP_END_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setSleepNapReminderEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SLEEP_NAP_REMINDER_ENABLED] = value }
    }

    suspend fun setSleepNapReminderMinute(value: Int) {
        dataStore.edit { it[KEY_SLEEP_NAP_REMINDER_MINUTE] = value.coerceIn(0, 24 * 60 - 1) }
    }

    /**
     * 由目标入睡 / 起床时刻计算目标分钟数：结束早于开始按跨午夜环绕；
     * 二者相等返回 0（旧实现把 0 也环绕成 24 小时，午休起止相同时会显示荒谬的 24:00）。
     */
    fun nightGoalMinutes(bedTimeMinute: Int, wakeTimeMinute: Int): Int {
        val raw = wakeTimeMinute - bedTimeMinute
        return if (raw < 0) raw + 24 * 60 else raw
    }

    suspend fun setThemeMode(value: String) {
        dataStore.edit { it[KEY_THEME_MODE] = value }
    }

    suspend fun setThemeColor(value: String) {
        dataStore.edit { it[KEY_THEME_COLOR] = value }
    }

    suspend fun setCustomColor(value: String) {
        dataStore.edit { it[KEY_CUSTOM_COLOR] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setBackgroundAnimation(value: Boolean) {
        dataStore.edit { it[KEY_BACKGROUND_ANIMATION] = value }
    }

    suspend fun setReminderMinutes(value: Int) {
        dataStore.edit { it[KEY_REMINDER_MINUTES] = value }
    }

    suspend fun setReminderEnabled(value: Boolean) {
        dataStore.edit { it[KEY_REMINDER_ENABLED] = value }
    }

    suspend fun setNotificationSound(value: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATION_SOUND] = value }
    }

    suspend fun setNotificationVibrate(value: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATION_VIBRATE] = value }
    }

    suspend fun setCustomBgPath(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_BG_IMAGE_PATH) else prefs[KEY_BG_IMAGE_PATH] = value
        }
    }

    suspend fun setCustomBgAlpha(value: Int) {
        dataStore.edit { it[KEY_BG_IMAGE_ALPHA] = value.coerceIn(0, 100) }
    }

    suspend fun setUiMaskAlpha(value: Int) {
        dataStore.edit { it[KEY_UI_MASK_ALPHA] = value.coerceIn(15, 100) }
    }

    suspend fun setAppLanguage(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_APP_LANGUAGE) else prefs[KEY_APP_LANGUAGE] = value
        }
    }

    suspend fun setAppFont(value: String) {
        dataStore.edit { it[KEY_APP_FONT] = value }
    }

    suspend fun setBgTransform(offsetX: Float, offsetY: Float, scale: Float) {
        dataStore.edit {
            it[KEY_BG_OFFSET_X] = offsetX.coerceIn(-1f, 1f)
            it[KEY_BG_OFFSET_Y] = offsetY.coerceIn(-1f, 1f)
            it[KEY_BG_SCALE] = scale.coerceAtLeast(1f)
        }
    }

    /** 同时保存底部导航的完整顺序与隐藏集合（同一事务，避免中间态）。 */
    suspend fun setNavTabs(order: List<String>, hidden: Set<String>) {
        val safeOrder = order.filter { it in DEFAULT_NAV_TABS }.distinct()
            .let { it + DEFAULT_NAV_TABS.filter { name -> name !in it } }
        val safeHidden = hidden.filter { it in DEFAULT_NAV_TABS }.toSet()
        dataStore.edit {
            it[KEY_NAV_TAB_ORDER] = safeOrder.joinToString(",")
            it[KEY_NAV_TAB_HIDDEN] = safeHidden.joinToString(",")
        }
    }
}
