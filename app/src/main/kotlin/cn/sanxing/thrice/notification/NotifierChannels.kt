package cn.sanxing.thrice.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/**
 * 通知渠道统一构建：所有渠道（课程提醒 / 专注 / 睡眠）显式设置系统默认通知音，
 * 声音/振动开关来自 [ReminderPrefs]；渠道参数创建后不可变，选项变化或
 * 旧版本（无提示音）渠道存在时删旧重建（渠道 ID 升级为 *_v2）。
 */
internal object NotifierChannels {

    fun ensure(
        context: Context,
        channelId: String,
        legacyIds: List<String>,
        name: String,
        description: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 一次性删除旧版本同名渠道（已被 *_v2 取代）
        legacyIds.forEach { legacy ->
            if (manager.getNotificationChannel(legacy) != null) {
                runCatching { manager.deleteNotificationChannel(legacy) }
            }
        }
        val sound = ReminderPrefs.sound(context)
        val vibrate = ReminderPrefs.vibrate(context)
        val applied = ReminderPrefs.channelApplied(context, channelId)
        val exists = manager.getNotificationChannel(channelId) != null
        if (exists && applied == (sound to vibrate)) return
        if (exists) runCatching { manager.deleteNotificationChannel(channelId) }
        val importance = if (sound) NotificationManager.IMPORTANCE_HIGH
        else NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
            enableVibration(vibrate)
            if (!vibrate) vibrationPattern = longArrayOf(0L)
            setShowBadge(true)
            if (sound) {
                // 优先使用系统保证可解析的默认通知音 URI（content://settings/system/
                // notification_sound），在各厂商 ROM 上兼容性最好；再尝试用户实际配置
                // 的铃声（部分 ROM 可能返回失效资源），取不到回到默认。
                val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getActualDefaultRingtoneUri(
                        context, RingtoneManager.TYPE_NOTIFICATION
                    )
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else {
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(channel)
        ReminderPrefs.setChannelApplied(context, channelId, sound, vibrate)
    }

    /** 声音 / 振动设置变化后调用：各渠道按需重建。 */
    fun recreateAll(context: Context) {
        ReminderNotifier.ensureChannel(context)
        ReminderNotifier.ensureTaskChannel(context)
        FocusNotifier.ensureChannel(context)
        SleepNotifier.ensureChannel(context)
    }
}
