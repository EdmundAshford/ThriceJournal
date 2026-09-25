package cn.sanxing.thrice

import android.content.Context
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 把 Hilt 注入的 [SettingsRepository] 暴露给无法走 Hilt 构造注入的调用方
 * （[cn.sanxing.thrice.notification.AppAlarmReceiver]、[cn.sanxing.thrice.notification.BootReceiver]）。
 *
 * 优先使用 [settingsRepository]（由 SanxingApplication 启动时赋值）；未赋值时经
 * Hilt [EntryPointAccessors] 兜底取同一个 @Singleton 实例。
 *
 * 兜底是必要的：睡眠 / 课程提醒靠「到点 → 发通知 → 排下一次」自链，
 * 一旦闹钟在进程冷启动时拉起而桥恰好为空，链条就会断掉且直到用户下次打开
 * App 才恢复（表现为「睡眠提醒用着用着就没了」）。
 */
object SleepWorkerBridge {

    @Volatile
    var settingsRepository: SettingsRepository? = null

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SleepSettingsProvider {
        fun sleepSettingsRepository(): SettingsRepository
    }

    /** 取得仓库实例；彻底取不到（极端冷启动 + Hilt 未就绪）时返回 null，由调用方降级。 */
    fun get(context: Context): SettingsRepository? =
        settingsRepository ?: runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SleepSettingsProvider::class.java
            ).sleepSettingsRepository()
        }.getOrNull()
}
