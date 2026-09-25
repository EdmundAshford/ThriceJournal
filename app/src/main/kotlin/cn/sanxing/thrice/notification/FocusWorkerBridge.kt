package cn.sanxing.thrice.notification

import android.content.Context
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * 把 Hilt 注入的 [SettingsRepository] 暴露给无法走 Hilt 构造注入的
 * [FocusCompletionWorker]（对齐 SleepWorkerBridge 的桥接做法）。
 *
 * 无需 Application 手工赋值：Worker 内通过 [EntryPointAccessors] 从
 * @HiltAndroidApp 的 Application 取同一个 @Singleton 实例（与界面共享
 * 同一个 DataStore 文件，避免多实例冲突）。[settingsRepository] 仅作为
 * 测试 / 特殊场景下的手工覆盖入口。
 */
object FocusWorkerBridge {

    @Volatile
    var settingsRepository: SettingsRepository? = null

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FocusSettingsProvider {
        fun focusSettingsRepository(): SettingsRepository
    }

    private fun resolve(context: Context): SettingsRepository? {
        settingsRepository?.let { return it }
        return runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                FocusSettingsProvider::class.java
            ).focusSettingsRepository()
        }.getOrNull()
    }

    /**
     * 读取「专注结束通知」首值；任何异常（桥未就绪 / DataStore 读失败）默认 true，
     * 保持通知能力不被静默吞掉。
     */
    suspend fun isNotifyOnFinish(context: Context): Boolean =
        runCatching { resolve(context)?.focusNotifyOnFinish?.first() ?: true }.getOrDefault(true)
}
