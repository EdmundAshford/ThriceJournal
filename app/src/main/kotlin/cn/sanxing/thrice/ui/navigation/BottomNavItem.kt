package cn.sanxing.thrice.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.ui.graphics.vector.ImageVector
import cn.sanxing.thrice.R

/**
 * 主页底部导航项。
 *
 * R10 起，除设置外每个界面均可在设置页中显隐与排序（顺序即 HorizontalPager 页序，
 * 可左右滑动切换）；[SETTINGS] 固定显示且固定在底栏末尾。底栏可横向滑动，
 * 便于后续承载更多界面。默认顺序：日程 → 账单 → 任务 → 课表 → 设置。
 */
enum class BottomNavItem(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /** 是否可在设置中隐藏 / 排序。设置页不可配置（固定显示、固定末尾）。 */
    val configurable: Boolean = true
) {
    SCHEDULE(R.string.nav_schedule, Icons.Filled.Schedule),
    BILLS(R.string.nav_bills, Icons.Filled.AccountBalanceWallet),
    TASKS(R.string.nav_tasks, Icons.Filled.Checklist),
    TIMETABLE(R.string.nav_timetable, Icons.Filled.DateRange),
    FOCUS(R.string.nav_focus, Icons.Filled.HourglassTop),
    SLEEP(R.string.nav_sleep, Icons.Filled.Bedtime),
    NOTES(R.string.nav_notes, Icons.Filled.Description),
    // name 固定为 "AI"，与 SettingsRepository.NAV_TAB_AI 一致
    AI(R.string.nav_ai, Icons.Filled.SmartToy),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings, configurable = false)
}
