package cn.sanxing.thrice.notification

import android.content.Context

/**
 * 专注倒计时完成调度：
 * - 开始倒计时即拉起 [FocusTimerService] 前台服务（服务内每秒校准精确闹钟）；
 * - 暂停 / 放弃 / 完成时撤掉 ACTION_FOCUS_DONE 闹钟并停止服务。
 *
 * 方法名与签名保持不变（调用方无需感知调度方式变化）；[delayMs] 仅为兼容旧签名，
 * 真正的触发时刻由前台服务按 FocusController 走时锚点每秒重排。
 */
object FocusScheduler {

    @Suppress("UNUSED_PARAMETER")
    fun scheduleCompletion(context: Context, delayMs: Long) {
        FocusTimerService.start(context)
    }

    fun cancelCompletion(context: Context) {
        ExactAlarms.cancel(
            context,
            ExactAlarms.FOCUS_CODE,
            ExactAlarms.ACTION_FOCUS_DONE
        )
        FocusTimerService.stop(context)
    }
}
