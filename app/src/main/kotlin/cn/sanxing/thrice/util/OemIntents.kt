package cn.sanxing.thrice.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 国产 ROM 后台保活 / 系统权限页跳转集合。
 *
 * 所有目标组件都先 resolveActivity 校验存在再启动；Android 11+ 包可见性受限，
 * 已在 AndroidManifest 的 <queries> 中声明这些厂商包名。
 * 全部方法失败返回 false，不抛异常。
 */
object OemIntents {

    /** 是否已加入电池优化白名单（API 23+；低版本视为 true）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 请求忽略电池优化：优先弹系统授权对话框；没有可处理的 Activity
     * （厂商裁剪 / 策略禁止）则打开电池优化设置列表。
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val pkg = context.packageName
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg")
        )
        if (resolveOk(context, direct)) return startActivity(context, direct)

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return startActivity(context, list)
    }

    /**
     * 打开国产 ROM 的自启动 / 后台保护管理页；全部不匹配时回落到应用详情页。
     * 不同品牌、不同系统版本的入口位置与组件名都可能变化，因此逐个尝试。
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            // 小米 / 红米（MIUI）
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.permission.ui.PermissionsEditorActivity"
                )
                putExtra("autoStart", true)
            },
            // 华为 / 荣耀（旧版 EMUI）
            Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.startup.StartupNormalActivity"
            ),
            // 华为兜底：受保护应用页
            Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            // OPPO / 一加（ColorOS）
            Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            // vivo（旧版 iManager）
            Intent().setClassName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            // vivo 新版后台启动管理
            Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            // 魅族（Flyme）
            Intent().setClassName(
                "com.meizu.safe",
                "com.meizu.safe.permission.SBGuanLiMainActivity"
            ),
            // 三星（One UI）
            Intent().setClassName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.ram.autorun.AutoRunActivity"
            )
        )
        candidates.forEach { intent ->
            if (resolveOk(context, intent) && startActivity(context, intent)) return true
        }
        return openAppDetails(context)
    }

    /** 打开系统「精确闹钟」授权页（API 31+）；无可用页面返回 false。 */
    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        )
        if (!resolveOk(context, intent)) return false
        return startActivity(context, intent)
    }

    /** 打开本应用的系统通知设置（API 26+ 直达渠道页，低版本回落应用详情页）。 */
    fun openNotificationSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (startActivity(context, intent)) return true
        }
        return openAppDetails(context)
    }

    private fun openAppDetails(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return startActivity(context, intent)
    }

    private fun resolveOk(context: Context, intent: Intent): Boolean =
        runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)

    private fun startActivity(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
