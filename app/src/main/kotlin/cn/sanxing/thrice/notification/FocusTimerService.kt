package cn.sanxing.thrice.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.sanxing.thrice.MainActivity
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.ui.focus.FocusController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 专注倒计时前台服务（普通 Service，不经 Hilt）：
 * - 常驻静默通知，文本每秒随 [FocusController] 状态刷新（剩余 / 暂停 / 已专注）；
 * - 倒计时运行中每秒用 [ExactAlarms] 校准 ACTION_FOCUS_DONE 精确闹钟，
 *   到点由服务自身（或闹钟接收者）幂等结算并发结束通知；
 * - 暂停态保留服务、撤掉闹钟；终态 / 空闲自动撤闹钟并 stopSelf。
 *
 * R15 起 FocusController 将运行快照持久化：进程被杀 / 手动清后台后再次进入应用，
 * Application 恢复会话（重启后用墙钟时间戳兜底），START_STICKY 重启或冷启动
 * 重新 start 本服务时都能接上恢复后的状态继续走时 / 到点结算。
 */
class FocusTimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 走时协程。每次 start 都重建，避免「上一轮已退出但服务还活着」时不再走时。 */
    private var tickJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FocusNotifier.ensureStatusChannel(this)
        // 与 AppAlarmReceiver / FocusCompletionWorker 保持一致：服务自己也要确保状态已挂载，
        // 否则冷启动拉起时读到的可能是空状态。attach 是幂等的。
        FocusController.attach(this)

        // startForeground 失败必须**停止服务**而不是继续跑：API 31+ 一旦用
        // startForegroundService() 拉起却在 5 秒内没真正进前台，系统会抛
        // ForegroundServiceDidNotStartInTimeException 直接杀进程。
        // 后台场景（开机广播等）被拒是预期的，此时依赖 ACTION_FOCUS_DONE 精确闹钟结算即可。
        val started = runCatching {
            val notification = buildNotification(statusText())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotifIds.FOCUS_FOREGROUND,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotifIds.FOCUS_FOREGROUND, notification)
            }
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        startTicking()
        return START_STICKY
    }

    private fun startTicking() {
        // 上一轮若已结束（终态 return@launch）但 onDestroy 还没跑到，
        // 这里重建即可恢复走时；配合 invokeOnCompletion 复位，不再依赖 onDestroy。
        tickJob?.cancel()
        tickJob = scope.launch {
            var ticksSinceAlarm = 0
            while (isActive) {
                val state = FocusController.state.value
                when (state.phase) {
                    FocusController.Phase.IDLE,
                    FocusController.Phase.FINISHED,
                    FocusController.Phase.FAILED -> {
                        cancelFocusAlarm()
                        stopSelf()
                        return@launch
                    }

                    FocusController.Phase.PAUSED -> {
                        // 暂停期间不需要闹钟；服务保留，通知保持暂停文案
                        cancelFocusAlarm()
                        notify(getString(R.string.focus_status_paused))
                    }

                    FocusController.Phase.RUNNING -> {
                        if (state.mode == FocusMode.COUNTDOWN && state.plannedSeconds > 0) {
                            if (FocusController.completeIfDue()) {
                                if (FocusWorkerBridge.isNotifyOnFinish(applicationContext)) {
                                    val seconds = FocusController.state.value.focusedSeconds
                                    FocusNotifier.showFinished(
                                        applicationContext,
                                        FocusCompletionWorker.formatDuration(seconds)
                                    )
                                }
                                cancelFocusAlarm()
                                stopSelf()
                                return@launch
                            }
                            val remainingMs =
                                (state.plannedSeconds * 1000L - FocusController.activeMs())
                                    .coerceAtLeast(0L)
                            // 只在「快到点」或每 ALARM_RECALIBRATE_TICKS 秒重排一次闹钟。
                            // 每秒 setExactAndAllowWhileIdle 会 1) 明显耗电，
                            // 2) 吃掉系统给每个应用的 allow-while-idle 配额，
                            //    进而推迟课程 / 睡眠提醒的同类型闹钟。
                            ticksSinceAlarm++
                            if (remainingMs <= ALARM_TIGHTEN_WINDOW_MS ||
                                ticksSinceAlarm >= ALARM_RECALIBRATE_TICKS
                            ) {
                                ticksSinceAlarm = 0
                                ExactAlarms.set(
                                    applicationContext,
                                    ExactAlarms.FOCUS_CODE,
                                    ExactAlarms.ACTION_FOCUS_DONE,
                                    System.currentTimeMillis() + remainingMs
                                )
                            }
                            notify(FocusCompletionWorker.formatDuration(remainingMs / 1000L))
                        } else {
                            // 正计时：无到点闹钟，仅刷新已专注时长
                            notify(
                                FocusCompletionWorker.formatDuration(FocusController.activeSeconds())
                            )
                        }
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun statusText(): String {
        val state = FocusController.state.value
        return when (state.phase) {
            FocusController.Phase.PAUSED -> getString(R.string.focus_status_paused)
            FocusController.Phase.RUNNING -> {
                if (state.mode == FocusMode.COUNTDOWN && state.plannedSeconds > 0) {
                    val remainingMs =
                        (state.plannedSeconds * 1000L - FocusController.activeMs())
                            .coerceAtLeast(0L)
                    FocusCompletionWorker.formatDuration(remainingMs / 1000L)
                } else {
                    FocusCompletionWorker.formatDuration(FocusController.activeSeconds())
                }
            }
            else -> FocusCompletionWorker.formatDuration(0L)
        }
    }

    private fun cancelFocusAlarm() {
        ExactAlarms.cancel(
            applicationContext,
            ExactAlarms.FOCUS_CODE,
            ExactAlarms.ACTION_FOCUS_DONE
        )
    }

    private fun notify(text: String) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            manager.notify(NotifIds.FOCUS_FOREGROUND, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("thrice://open/focus"))
        val pendingIntent = PendingIntent.getActivity(
            this,
            NotifIds.FOCUS_FOREGROUND,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FocusNotifier.STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.focus_status_notif_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        // 只取消 job，不 cancel 整个 scope：scope 被 cancel 后 launch 即为空操作，
        // 会让这个「还活着」的服务再也走不了时。
        tickJob?.cancel()
        tickJob = null
        cancelFocusAlarm()
        super.onDestroy()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1000L

        /** 剩余时间进入这个窗口后，每秒校准一次到点闹钟（保证准时结算）。 */
        private const val ALARM_TIGHTEN_WINDOW_MS = 60_000L

        /** 距离到点还早时，每 N 秒校准一次即可（省电 + 不抢占 allow-while-idle 配额）。 */
        private const val ALARM_RECALIBRATE_TICKS = 30

        fun start(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val intent = Intent(appContext, FocusTimerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, FocusTimerService::class.java))
            }
        }
    }
}
