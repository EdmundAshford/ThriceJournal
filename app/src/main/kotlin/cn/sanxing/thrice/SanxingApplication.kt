package cn.sanxing.thrice

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.usecase.SeedSampleTermUseCase
import cn.sanxing.thrice.notification.ExactAlarms
import cn.sanxing.thrice.notification.FocusCompletionWorker
import cn.sanxing.thrice.notification.FocusNotifier
import cn.sanxing.thrice.notification.FocusTimerService
import cn.sanxing.thrice.notification.FocusWorkerBridge
import cn.sanxing.thrice.notification.ReminderNotifier
import cn.sanxing.thrice.notification.ReminderScheduler
import cn.sanxing.thrice.notification.SleepNotifier
import cn.sanxing.thrice.notification.SleepScheduler
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.ui.focus.FocusController
import cn.sanxing.thrice.util.CrashRecorder
import cn.sanxing.thrice.util.runCatchingOrCancel
import cn.sanxing.thrice.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 应用入口：预置学期（幂等）→ 应用语言偏好 → 建通知渠道 → 重排提醒 → 刷新小组件。 */
@HiltAndroidApp
class SanxingApplication : Application() {

    @Inject
    lateinit var seedUseCase: SeedSampleTermUseCase

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        // 冷启动同步恢复语言（DataStore 首读在 IO 线程，崩溃重启后首帧可能先显示英文）：
        // 设置页切换语言时会把选择镜像到该 SharedPreferences，这里在任何 Activity 创建前应用。
        runCatching {
            val mirrored = getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .getString(LOCALE_KEY_LANG, null)
            if (!mirrored.isNullOrBlank()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(mirrored))
            }
        }
        ReminderNotifier.ensureChannel(this)
        ReminderNotifier.ensureTaskChannel(this)
        FocusNotifier.ensureChannel(this)
        FocusNotifier.ensureStatusChannel(this)
        SleepNotifier.ensureChannel(this)
        // 读磁盘快照恢复被杀前的专注会话：重排完成闹钟 / 重启前台服务 / 补结算到点会话
        val focusRecovery = FocusController.attach(this)
        SleepWorkerBridge.settingsRepository = settingsRepository
        appScope.launch {
            runCatchingOrCancel { handleFocusRecovery(focusRecovery) }
        }
        appScope.launch {
            // 冷启动先恢复应用内语言（Android 13+ 由系统持久化；低版本由 appcompat 回退）
            runCatchingOrCancel {
                val lang = settingsRepository.appLanguage.first()
                if (!lang.isNullOrBlank()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
                    // 同步写镜像，供下次进程启动（含崩溃重启）在首帧前恢复
                    getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE).edit()
                        .putString(LOCALE_KEY_LANG, lang).apply()
                }
            }
            runCatchingOrCancel { seedUseCase() }
            runCatchingOrCancel {
                // R13 起提醒改由 AlarmManager + 前台服务派发；一次性取消旧版残留在
                // WorkManager 数据库里的待触发任务，避免升级后新旧通道双发通知。
                val prefs = getSharedPreferences("app_migration", MODE_PRIVATE)
                if (!prefs.getBoolean("wm_cleanup_r13", false)) {
                    androidx.work.WorkManager.getInstance(this@SanxingApplication).cancelAllWork()
                    prefs.edit().putBoolean("wm_cleanup_r13", true).apply()
                }
            }
            runCatchingOrCancel {
                val minutes = settingsRepository.reminderMinutes.first()
                val enabled = settingsRepository.reminderEnabled.first()
                ReminderScheduler.rescheduleAll(this@SanxingApplication, minutes, enabled)
                WidgetUpdater.refreshAll(this@SanxingApplication)
            }
            runCatchingOrCancel {
                val enabled = settingsRepository.sleepBedReminderEnabled.first()
                val minute = settingsRepository.sleepBedReminderMinute.first()
                SleepScheduler.reschedule(this@SanxingApplication, enabled, minute)
            }
            runCatchingOrCancel {
                val napEnabled = settingsRepository.sleepNapEnabled.first()
                val reminderEnabled = settingsRepository.sleepNapReminderEnabled.first()
                val minute = settingsRepository.sleepNapReminderMinute.first()
                SleepScheduler.rescheduleNap(
                    this@SanxingApplication, napEnabled && reminderEnabled, minute
                )
            }
        }
    }

    /**
     * 专注会话跨进程死亡恢复后的调度：
     * - RUNNING 倒计时未到点：按剩余时间重排精确闹钟并重启常驻前台服务；
     * - RUNNING 正计时：仅重启前台服务（通知继续走时）；
     * - 恢复时发现倒计时已过点（已在 FocusController 内补结算落库）：补发展束通知；
     * - 暂停态：无闹钟无服务，界面打开即呈暂停。
     * 任何一步失败都不影响应用其余启动流程。
     */
    private suspend fun handleFocusRecovery(rec: FocusController.Recovery?) {
        rec ?: return
        when (rec.phase) {
            FocusController.Phase.RUNNING -> {
                if (rec.mode == FocusMode.COUNTDOWN && rec.plannedSeconds > 0) {
                    ExactAlarms.set(
                        this,
                        ExactAlarms.FOCUS_CODE,
                        ExactAlarms.ACTION_FOCUS_DONE,
                        System.currentTimeMillis() + rec.remainingMs
                    )
                }
                // 由开机广播等后台场景拉起进程时，FGS 后台启动可能被系统拒绝（start 内已吞异常），
                // 此时精确闹钟仍在，到点照常由 AppAlarmReceiver 结算。
                FocusTimerService.start(this)
            }

            FocusController.Phase.FINISHED -> {
                if (rec.causedCompletion && FocusWorkerBridge.isNotifyOnFinish(this)) {
                    FocusNotifier.showFinished(
                        this, FocusCompletionWorker.formatDuration(rec.focusedSeconds)
                    )
                }
                runCatching {
                    ExactAlarms.cancel(
                        this, ExactAlarms.FOCUS_CODE, ExactAlarms.ACTION_FOCUS_DONE
                    )
                }
            }

            else -> Unit
        }
    }

    companion object {
        const val LOCALE_PREFS = "app_locale"
        const val LOCALE_KEY_LANG = "lang"
    }
}
