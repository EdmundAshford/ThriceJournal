package cn.sanxing.thrice.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.sanxing.thrice.SleepWorkerBridge
import cn.sanxing.thrice.ui.focus.FocusController
import cn.sanxing.thrice.util.runCatchingOrCancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 精确闹钟统一接收者（exported=false，仅本应用 [ExactAlarms] 发出的 PendingIntent 可达）。
 *
 * 到点逻辑对齐旧 Worker：发通知 + 排下一次（自愈链条）。
 * 用 [goAsync] + IO 协程承载数据库 / DataStore 读取，每个分支独立 runCatching，
 * 任何异常都不影响其它分支，最终一定 finish()。
 */
class AppAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        val job = SupervisorJob()
        CoroutineScope(job + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ExactAlarms.ACTION_REMINDER_COURSE -> handleCourse(ctx, intent)
                    ExactAlarms.ACTION_REMINDER_TASK -> handleTask(ctx, intent)
                    ExactAlarms.ACTION_SLEEP_BED -> handleSleepBed(ctx)
                    ExactAlarms.ACTION_SLEEP_NAP -> handleSleepNap(ctx)
                    ExactAlarms.ACTION_FOCUS_DONE -> handleFocusDone(ctx)
                }
            } finally {
                pending.finish()
                job.cancel()
            }
        }
    }

    private suspend fun handleCourse(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifyWorker.KEY_ID, -1L)
        if (id <= 0) return
        // 切方案后的残留闹钟：课程已不属于活动学期，取消且不发通知、不续链。
        // 取值失败（DB 未就绪 / 瞬时 IO 错误）必须按「非陈旧」处理：
        // 课程提醒是「到点 → 发通知 → 排下一次」的单链，若把异常当成陈旧直接 return，
        // 一次瞬时错误就会让这门课此后长期不再提醒（只能等用户打开 App 全量重排）。
        val stale = runCatchingOrCancel {
            ReminderScheduler.cancelIfCourseNotInActiveTerm(context, id)
        }.getOrDefault(false)
        if (stale) return
        runCatchingOrCancel { ReminderNotifier.show(context, id) }
        runCatchingOrCancel { ReminderScheduler.rescheduleCourse(context, id) }
    }

    private suspend fun handleTask(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifyWorker.KEY_ID, -1L)
        if (id <= 0) return
        val occurrenceDate = intent.getStringExtra(ReminderNotifyWorker.KEY_DATE)
        runCatchingOrCancel { ReminderNotifier.showTask(context, id, occurrenceDate) }
        // 无论本次通知是否展示（该次可能已完成），都排下一次发生
        runCatchingOrCancel { ReminderScheduler.rescheduleTask(context, id) }
    }

    private suspend fun handleSleepBed(context: Context) {
        runCatchingOrCancel { SleepNotifier.showBedReminder(context) }
        runCatchingOrCancel {
            val repo = SleepWorkerBridge.get(context) ?: return@runCatchingOrCancel
            val enabled = repo.sleepBedReminderEnabled.first()
            val minute = repo.sleepBedReminderMinute.first()
            if (enabled) SleepScheduler.scheduleNext(context, minute)
            else SleepScheduler.cancel(context)
        }
    }

    private suspend fun handleSleepNap(context: Context) {
        runCatchingOrCancel { SleepNotifier.showNapReminder(context) }
        runCatchingOrCancel {
            val repo = SleepWorkerBridge.get(context) ?: return@runCatchingOrCancel
            val napEnabled = repo.sleepNapEnabled.first()
            val reminderEnabled = repo.sleepNapReminderEnabled.first()
            val minute = repo.sleepNapReminderMinute.first()
            if (napEnabled && reminderEnabled) SleepScheduler.scheduleNextNap(context, minute)
            else SleepScheduler.cancelNap(context)
        }
    }

    private suspend fun handleFocusDone(context: Context) {
        runCatchingOrCancel {
            FocusController.attach(context)
            if (FocusController.completeIfDue()) {
                // 用户关闭「专注结束通知」时只落库结算，不发通知
                if (FocusWorkerBridge.isNotifyOnFinish(context)) {
                    val seconds = FocusController.state.value.focusedSeconds
                    FocusNotifier.showFinished(
                        context, FocusCompletionWorker.formatDuration(seconds)
                    )
                }
            }
        }
        // 闹钟到点：无论是否本次完成，都停掉常驻前台服务
        runCatchingOrCancel { FocusTimerService.stop(context) }
    }
}
