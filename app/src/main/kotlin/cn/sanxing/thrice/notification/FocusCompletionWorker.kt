package cn.sanxing.thrice.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.sanxing.thrice.ui.focus.FocusController

/**
 * 倒计时到点兜底 Worker：即使界面在后台 / 重组停止 tick，
 * 到点仍由 WorkManager 触发完成落库与通知（幂等，界面已完成则不重复处理）。
 */
class FocusCompletionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        FocusController.attach(applicationContext)
        if (FocusController.completeIfDue()) {
            // 用户关闭「专注结束通知」时只落库结算，不发通知。
            if (FocusWorkerBridge.isNotifyOnFinish(applicationContext)) {
                val seconds = FocusController.state.value.focusedSeconds
                FocusNotifier.showFinished(applicationContext, formatDuration(seconds))
            }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "focus_completion"

        /** 秒 → H:MM:SS / MM:SS 简洁文本（系统默认语言环境）。 */
        fun formatDuration(totalSeconds: Long): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%02d:%02d", m, s)
        }
    }
}
