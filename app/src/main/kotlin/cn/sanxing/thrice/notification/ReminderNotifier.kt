package cn.sanxing.thrice.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.sanxing.thrice.MainActivity
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.recurrence.isCompletedOn
import cn.sanxing.thrice.data.domain.recurrence.isRecurring
import cn.sanxing.thrice.parser.reminder.ReminderCalculator

/**
 * 上课通知：渠道创建 + 通知发出。
 * 点击通知打开 MainActivity 并带上 courseId，由导航层定位到该课。
 *
 * R17：任务提醒从上课渠道拆出独立「任务提醒」渠道——此前任务到期通知复用
 * course_reminder_v4，系统通知设置里看不到任务类别，用户无法单独开关。
 */
object ReminderNotifier {

    const val CHANNEL_ID = "course_reminder_v4"
    private val LEGACY_CHANNEL_IDS = listOf("course_reminder", "course_reminder_v2", "course_reminder_v3")

    /** 自定义任务到期提醒渠道（R17 新增，独立于上课提醒）。 */
    const val TASK_CHANNEL_ID = "task_reminder_v1"

    fun ensureChannel(context: Context) {
        NotifierChannels.ensure(
            context,
            channelId = CHANNEL_ID,
            legacyIds = LEGACY_CHANNEL_IDS,
            name = context.getString(R.string.reminder_channel_name),
            description = context.getString(R.string.reminder_channel_desc)
        )
    }

    /** 任务提醒渠道：铃声 / 振动策略与上课提醒一致（ReminderPrefs 全局设置）。 */
    fun ensureTaskChannel(context: Context) {
        NotifierChannels.ensure(
            context,
            channelId = TASK_CHANNEL_ID,
            legacyIds = emptyList(),
            name = context.getString(R.string.task_reminder_channel_name),
            description = context.getString(R.string.task_reminder_channel_desc)
        )
    }

    /** 发出某门课的上课通知；课程不存在时静默返回（返回 false）。 */
    suspend fun show(context: Context, courseId: Long): Boolean {
        ensureChannel(context)
        val db = DatabaseProvider.get(context)
        val course = db.courseDao().get(courseId) ?: return false
        if (course.kind != CourseKind.GRID) return false
        val term = db.termDao().get(course.termId) ?: return false
        val sectionTimes = db.sectionTimeDao().getByTerm(term.id)
        val now = java.time.LocalDateTime.now()

        // 找触发的这一次（最近的一次未来/当下课程时刻），用于文案
        val trigger = ReminderCalculator.nextReminder(
            termStart = term.startDate,
            totalWeeks = term.totalWeeks,
            weeks = course.weeks,
            dayOfWeek = course.dayOfWeek,
            courseStartTime = ReminderCalculator.parseTime(startTimeOf(sectionTimes, course.startSection))
                ?: java.time.LocalTime.of(8, 0),
            minutesBefore = 0,
            now = now.minusMinutes(1)
        )
        val timeText = if (trigger != null) {
            val start = trigger.plusMinutes(0)
            // 显式 Locale.US：阿拉伯语等 locale 下 %d 会输出非 ASCII 数字
            String.format(java.util.Locale.US, "%02d:%02d", start.hour, start.minute)
        } else {
            sectionTimeText(sectionTimes, course.startSection, course.endSection)
        }

        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_COURSE_ID, courseId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // data 参与 filterEquals：没有它，「课程通知」与「专注通知」这类
            // 同为 MainActivity 目标的 PendingIntent 会仅靠 requestCode 区分
            .setData(android.net.Uri.parse("thrice://open/course/$courseId"))
        val pendingIntent = PendingIntent.getActivity(
            context, NotifIds.course(courseId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val location = course.location.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.reminder_at, it) } ?: ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title, course.name))
            .setContentText(
                listOf(timeText, location).filter { it.isNotBlank() }.joinToString(" · ")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NotifIds.course(courseId)
        runCatching {
            // 同 id 重新通知前先取消，确保系统重新播放铃声 / 振动
            manager.cancel(notifId)
            manager.notify(notifId, notification)
        }
        return true
    }

    fun cancel(context: Context, courseId: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NotifIds.course(courseId))
    }

    /**
     * 任务提醒通知（任务到点）。任务不存在 / 已完成 / 已删提醒则静默不发。
     * @param occurrenceDate 重复任务的本次发生日（yyyy-MM-dd）；该次已被勾选完成则不发。
     */
    suspend fun showTask(context: Context, taskId: Long, occurrenceDate: String? = null): Boolean {
        ensureTaskChannel(context)
        val db = DatabaseProvider.get(context)
        val task = db.taskDao().getById(taskId) ?: return false
        if (task.reminderMinutes == null) return false
        // 完成态：重复任务按发生日判定，旧任务用整体 completed
        if (task.isRecurring) {
            val day = occurrenceDate?.let {
                runCatching { java.time.LocalDate.parse(it) }.getOrNull()
            }
            if (day != null && task.isCompletedOn(day)) return false
        } else if (task.completed) {
            return false
        }

        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(android.net.Uri.parse("thrice://open/task/$taskId"))
        val pendingIntent = PendingIntent.getActivity(
            context, NotifIds.task(taskId), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dateText = occurrenceDate ?: task.date.orEmpty()
        val timeText = dateText + (task.startTime?.let { " $it" } ?: "")
        val desc = listOf(timeText, task.type.takeIf { it.isNotBlank() })
            .filter { !it.isNullOrBlank() }.joinToString(" · ")

        val notification = NotificationCompat.Builder(context, TASK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_task_title, task.title))
            .setContentText(desc.ifBlank { context.getString(R.string.notif_task_fallback) })
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NotifIds.task(taskId)
        runCatching {
            manager.cancel(notifId)
            manager.notify(notifId, notification)
        }
        return true
    }

    private fun startTimeOf(sectionTimes: List<SectionTime>, startSection: Int): String? =
        sectionTimes.firstOrNull { it.sectionIndex == startSection }?.startTime

    private fun sectionTimeText(sectionTimes: List<SectionTime>, startSection: Int, endSection: Int): String {
        val start = startTimeOf(sectionTimes, startSection)
        val end = sectionTimes.firstOrNull { it.sectionIndex == endSection }?.endTime
        return if (start != null && end != null) "$start-$end" else ""
    }
}
