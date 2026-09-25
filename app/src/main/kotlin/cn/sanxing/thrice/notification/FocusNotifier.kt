package cn.sanxing.thrice.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.sanxing.thrice.MainActivity
import cn.sanxing.thrice.R

/** 专注模块通知：独立渠道 focus；不申请新权限，震动跟随系统默认。 */
object FocusNotifier {

    const val CHANNEL_ID = "focus_v4"

    /** 专注前台服务常驻通知专用静默渠道（独立于响铃的结束提醒渠道）。 */
    const val STATUS_CHANNEL_ID = "focus_status_v1"
    private val LEGACY_CHANNEL_IDS = listOf("focus", "focus_v2", "focus_v3")

    fun ensureChannel(context: Context) {
        NotifierChannels.ensure(
            context,
            channelId = CHANNEL_ID,
            legacyIds = LEGACY_CHANNEL_IDS,
            name = context.getString(R.string.focus_channel_name),
            description = context.getString(R.string.focus_channel_desc)
        )
    }

    /**
     * 常驻状态渠道：IMPORTANCE_LOW、无声、无震动。不走 [NotifierChannels]，
     * 因为该渠道必须始终静默，且不能被声音 / 震动设置重建；渠道已存在则跳过。
     */
    fun ensureStatusChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(STATUS_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            context.getString(R.string.focus_status_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.focus_status_channel_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun build(context: Context, title: String, text: String, notifId: Int, tag: String) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("thrice://open/$tag"))
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 同 id 重新通知前先取消，确保系统重新播放铃声 / 振动（更新通知默认静音）
            manager.cancel(notifId)
            manager.notify(notifId, notification)
        }
    }

    fun showFinished(context: Context, durationText: String) {
        build(
            context,
            context.getString(R.string.focus_notif_finished_title),
            context.getString(R.string.focus_notif_finished_text, durationText),
            NotifIds.FOCUS_FINISHED,
            "focus/finished"
        )
    }

    fun showFailed(context: Context) {
        build(
            context,
            context.getString(R.string.focus_notif_failed_title),
            context.getString(R.string.focus_notif_failed_text),
            NotifIds.FOCUS_FINISHED,
            "focus/failed"
        )
    }
}
