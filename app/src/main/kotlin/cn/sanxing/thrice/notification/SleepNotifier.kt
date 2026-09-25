package cn.sanxing.thrice.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.sanxing.thrice.MainActivity
import cn.sanxing.thrice.R

/** 睡眠模块通知：渠道 sleep。 */
object SleepNotifier {

    const val CHANNEL_ID = "sleep_v4"
    private val LEGACY_CHANNEL_IDS = listOf("sleep", "sleep_v2", "sleep_v3")

    fun ensureChannel(context: Context) {
        NotifierChannels.ensure(
            context,
            channelId = CHANNEL_ID,
            legacyIds = LEGACY_CHANNEL_IDS,
            name = context.getString(R.string.sleep_channel_name),
            description = context.getString(R.string.sleep_channel_desc)
        )
    }

    fun showBedReminder(context: Context) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("thrice://open/sleep/bed"))
        val pendingIntent = PendingIntent.getActivity(
            context, NotifIds.SLEEP_BED, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sleep_bed_notif_title))
            .setContentText(context.getString(R.string.sleep_bed_notif_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NotifIds.SLEEP_BED)
            manager.notify(NotifIds.SLEEP_BED, notification)
        }
    }

    fun showNapReminder(context: Context) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("thrice://open/sleep/nap"))
        val pendingIntent = PendingIntent.getActivity(
            context, NotifIds.SLEEP_NAP, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sleep_nap_notif_title))
            .setContentText(context.getString(R.string.sleep_nap_notif_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NotifIds.SLEEP_NAP)
            manager.notify(NotifIds.SLEEP_NAP, notification)
        }
    }
}
