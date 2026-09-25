package cn.sanxing.thrice.notification

import android.content.Context
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 睡眠提醒调度：夜睡入睡提醒与午休提醒各为一条独立精确闹钟
 * （requestCode / action 不同，互不替换），到点由 [AppAlarmReceiver] 发通知后自链次日。
 * 未授予精确闹钟权限时由 [ExactAlarms] 自动降级非精确唤醒。
 */
object SleepScheduler {

    // ---------------- 夜睡入睡提醒 ----------------

    /** 按设置（开关 + 时刻分钟）重排；关闭则取消。 */
    fun reschedule(context: Context, enabled: Boolean, minuteOfDay: Int) {
        if (!enabled) {
            cancel(context)
        } else {
            scheduleNext(context, minuteOfDay)
        }
    }

    /** 排下一次夜睡提醒（今天时刻已过则明天）。 */
    fun scheduleNext(context: Context, minuteOfDay: Int) {
        enqueueNext(context, minuteOfDay, isNap = false)
    }

    fun cancel(context: Context) {
        ExactAlarms.cancel(context, ExactAlarms.SLEEP_BED_CODE, ExactAlarms.ACTION_SLEEP_BED)
    }

    // ---------------- 午休提醒 ----------------

    /** 按设置（午休总开关 / 午休提醒开关 + 时刻分钟）重排；关闭则取消。 */
    fun rescheduleNap(context: Context, enabled: Boolean, minuteOfDay: Int) {
        if (!enabled) {
            cancelNap(context)
        } else {
            scheduleNextNap(context, minuteOfDay)
        }
    }

    /** 排下一次午休提醒（今天时刻已过则明天）。 */
    fun scheduleNextNap(context: Context, minuteOfDay: Int) {
        enqueueNext(context, minuteOfDay, isNap = true)
    }

    fun cancelNap(context: Context) {
        ExactAlarms.cancel(context, ExactAlarms.SLEEP_NAP_CODE, ExactAlarms.ACTION_SLEEP_NAP)
    }

    // ---------------- 内部 ----------------

    private fun enqueueNext(
        context: Context,
        minuteOfDay: Int,
        isNap: Boolean
    ) {
        val hour = (minuteOfDay / 60).coerceIn(0, 23)
        val minute = (minuteOfDay % 60).coerceIn(0, 59)
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        if (!target.isAfter(now)) target = target.plusDays(1)
        var triggerMillis = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 边界保护：触发时刻至少在 1s 后
        val minTrigger = System.currentTimeMillis() + 1_000L
        if (triggerMillis < minTrigger) triggerMillis = minTrigger
        if (isNap) {
            ExactAlarms.set(
                context, ExactAlarms.SLEEP_NAP_CODE,
                ExactAlarms.ACTION_SLEEP_NAP, triggerMillis
            )
        } else {
            ExactAlarms.set(
                context, ExactAlarms.SLEEP_BED_CODE,
                ExactAlarms.ACTION_SLEEP_BED, triggerMillis
            )
        }
    }
}
