package cn.sanxing.thrice.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 一次性提醒 Worker（替代旧的精确闹钟广播接收器）。
 *
 * 到点后只做「发通知消息」：
 * - [TYPE_COURSE]：发出上课通知，并把该课的下一次提醒重新入队（自愈链条）；
 * - [TYPE_TASK]：发出任务提醒通知；重复任务随后把下一次发生重新入队。
 *
 * 调度由 WorkManager 持久化，应用重启 / 设备重启后仍会触发（不保证精确到秒，
 * 系统按省电策略合并唤醒，符合"只发消息提醒"的产品定位）。
 */
class ReminderNotifyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val id = inputData.getLong(KEY_ID, -1L)
            if (id <= 0) return Result.success()
            when (inputData.getString(KEY_TYPE)) {
                TYPE_TASK -> {
                    val occurrenceDate = inputData.getString(KEY_DATE)
                    ReminderNotifier.showTask(applicationContext, id, occurrenceDate)
                    // 无论本次通知是否展示（该次可能已被完成），都排下一次发生
                    ReminderScheduler.rescheduleTask(applicationContext, id)
                }
                else -> {
                    ReminderNotifier.show(applicationContext, id)
                    ReminderScheduler.rescheduleCourse(applicationContext, id)
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        }
    }

    companion object {
        const val KEY_TYPE = "reminder_type"
        const val KEY_ID = "reminder_id"
        const val KEY_DATE = "reminder_date" // 重复任务的本次发生日 yyyy-MM-dd
        const val TYPE_COURSE = "course"
        const val TYPE_TASK = "task"

        fun courseWorkName(courseId: Long) = "course_reminder_$courseId"
        fun taskWorkName(taskId: Long) = "task_reminder_$taskId"
    }
}
