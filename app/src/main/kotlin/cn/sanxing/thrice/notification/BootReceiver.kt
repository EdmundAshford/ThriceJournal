package cn.sanxing.thrice.notification

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.sanxing.thrice.SleepWorkerBridge
import cn.sanxing.thrice.util.runCatchingOrCancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新 / 系统时间变化后重排全部精确闹钟。
 *
 * Application.onCreate 一定先于本接收器执行并给 [SleepWorkerBridge] 赋值；
 * 极端情况下桥仍为空时，课程 / 任务提醒按默认提前量 15 分钟重排，睡眠提醒跳过
 * （等下一次应用启动时由 SanxingApplication 补排）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // 精确闹钟权限被授予 / 撤销：必须按新权限重排，否则提醒会一直停留在旧状态
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Unit
            else -> return
        }
        val ctx = context.applicationContext
        val pending = goAsync()
        val job = SupervisorJob()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                runCatchingOrCancel {
                    val repo = SleepWorkerBridge.get(ctx)
                    val minutes = repo?.reminderMinutes?.first() ?: DEFAULT_REMINDER_MINUTES
                    // 桥为空（极端冷启动）时退化为「按开关默认值 true」：
                    // 相比静默不排提醒，宁可沿用默认开启，用户可在设置页关闭。
                    val enabled = repo?.reminderEnabled?.first() ?: true
                    ReminderScheduler.rescheduleAll(ctx, minutes, enabled)
                }
                runCatchingOrCancel {
                    val repo = SleepWorkerBridge.get(ctx) ?: return@runCatchingOrCancel
                    val bedEnabled = repo.sleepBedReminderEnabled.first()
                    val bedMinute = repo.sleepBedReminderMinute.first()
                    SleepScheduler.reschedule(ctx, bedEnabled, bedMinute)

                    val napEnabled = repo.sleepNapEnabled.first()
                    val napReminderEnabled = repo.sleepNapReminderEnabled.first()
                    val napMinute = repo.sleepNapReminderMinute.first()
                    SleepScheduler.rescheduleNap(
                        ctx, napEnabled && napReminderEnabled, napMinute
                    )
                }
            } finally {
                pending.finish()
                job.cancel()
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        const val DEFAULT_REMINDER_MINUTES = 15
    }
}
