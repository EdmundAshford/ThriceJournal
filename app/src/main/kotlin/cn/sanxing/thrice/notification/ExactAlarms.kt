package cn.sanxing.thrice.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * 精确闹钟统一入口（RTC_WAKEUP）：
 * - API 31+ 且用户授予精确闹钟权限时走 [AlarmManager.setExactAndAllowWhileIdle]，
 *   Doze 待机也能准时唤醒；未授权时降级 [AlarmManager.setAndAllowWhileIdle] 兜底；
 * - PendingIntent 以 requestCode + action + data 三元组保证唯一，
 *   每秒重复 set 即等价于 REPLACE 校准；
 * - 全部调用 runCatching：厂商 ROM 禁用闹钟 / 权限缺失时静默失败，不崩溃。
 */
object ExactAlarms {

    const val ACTION_REMINDER_COURSE = "cn.sanxing.thrice.alarm.REMINDER_COURSE"
    const val ACTION_REMINDER_TASK = "cn.sanxing.thrice.alarm.REMINDER_TASK"
    const val ACTION_SLEEP_BED = "cn.sanxing.thrice.alarm.SLEEP_BED"
    const val ACTION_SLEEP_NAP = "cn.sanxing.thrice.alarm.SLEEP_NAP"
    const val ACTION_FOCUS_DONE = "cn.sanxing.thrice.alarm.FOCUS_DONE"

    const val SLEEP_BED_CODE = 910001
    const val SLEEP_NAP_CODE = 910002
    const val FOCUS_CODE = 910003

    /** 是否具备精确闹钟调度能力（API 31 以下默认允许）。 */
    fun canSchedule(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * 排一条唤醒闹钟；同一 (requestCode, action) 重复调用即覆盖校准。
     * @param triggerAtMillis RTC 墙钟触发时刻
     */
    fun set(
        context: Context,
        requestCode: Int,
        action: String,
        triggerAtMillis: Long,
        extras: Bundle = Bundle.EMPTY
    ) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AppAlarmReceiver::class.java).apply {
                this.action = action
                putExtras(extras)
                // data 参与 Intent.filterEquals，保证不同 requestCode 的 PendingIntent 唯一
                data = Uri.parse("thrice://alarm/$requestCode")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (canSchedule(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                )
            } else {
                // 未授予精确闹钟：退化为非精确唤醒，仍优于纯 WorkManager
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                )
            }
        }
    }

    /** 取消闹钟；PendingIntent 不存在（从未排过）时直接返回。 */
    fun cancel(context: Context, requestCode: Int, action: String) {
        runCatching {
            val intent = Intent(context, AppAlarmReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("thrice://alarm/$requestCode")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return@runCatching
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    /** 课程 id → 闹钟 requestCode（与任务 action 不同，同值也互不冲突）。 */
    fun courseCode(id: Long): Int = (id % 1_900_000_000L).toInt()

    /** 任务 id → 闹钟 requestCode。 */
    fun taskCode(id: Long): Int = (id % 1_900_000_000L).toInt()
}
