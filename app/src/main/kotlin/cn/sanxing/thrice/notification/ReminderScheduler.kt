package cn.sanxing.thrice.notification

import android.content.Context
import android.os.Bundle
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.Reminder
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.recurrence.firstOccurrenceOnOrAfter
import cn.sanxing.thrice.data.domain.recurrence.isRecurring
import cn.sanxing.thrice.parser.reminder.ReminderCalculator
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒调度器：把「下一次提醒时刻」排进系统精确闹钟（[ExactAlarms]）。
 *
 * 1. 每条提醒 = 一个唯一 RTC_WAKEUP 闹钟（[AppAlarmReceiver] 接收），
 *    到点发通知，课程 / 重复任务再把下一次入排，形成自愈链条；
 * 2. 开机 / 应用更新 / 时间变更由 [BootReceiver] 重排，应用启动与数据变更后
 *    调 [rescheduleAll]；
 * 3. 用户未授予精确闹钟权限时自动降级为非精确唤醒（[ExactAlarms.set]）。
 *
 * 周次判定：只有 [Course.weeks] 含该周才排（[ReminderCalculator] 保证）。
 *
 * 默认提前量：应用启动 / 设置页读取 DataStore 后经 [rescheduleAll] 传入并缓存，
 * 无法访问 DataStore 的地方用缓存值（进程冷启动兜底 15）。
 */
object ReminderScheduler {

    @Volatile
    private var lastKnownDefaultMinutes: Int = 15

    private fun enqueueReminder(
        context: Context,
        type: String,
        id: Long,
        triggerAt: LocalDateTime,
        occurrenceDate: String? = null
    ) {
        // 闹钟时刻必须在未来。
        // 允许 60s 内的微小回拨（调度瞬间跨过触发点的竞态），但**不做无条件提升**：
        // 否则上游一旦算出过去时刻，用户就会立刻收到一条莫名其妙的提醒，
        // 把逻辑错误转嫁成可见噪声。明显过期则直接丢弃，交给下一次全量重排。
        val now = System.currentTimeMillis()
        val rawMillis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (rawMillis < now - 60_000L) return
        val triggerMillis = rawMillis.coerceAtLeast(now + 1_000L)
        val extras = Bundle().apply {
            putLong(ReminderNotifyWorker.KEY_ID, id)
            // 重复任务：把本次发生日带给接收者，用于完成态判定与通知文案
            putString(ReminderNotifyWorker.KEY_DATE, occurrenceDate)
        }
        if (type == ReminderNotifyWorker.TYPE_TASK) {
            ExactAlarms.set(
                context, ExactAlarms.taskCode(id),
                ExactAlarms.ACTION_REMINDER_TASK, triggerMillis, extras
            )
        } else {
            ExactAlarms.set(
                context, ExactAlarms.courseCode(id),
                ExactAlarms.ACTION_REMINDER_COURSE, triggerMillis, extras
            )
        }
    }

    private fun cancelCourseWork(context: Context, courseId: Long) {
        ExactAlarms.cancel(
            context, ExactAlarms.courseCode(courseId), ExactAlarms.ACTION_REMINDER_COURSE
        )
    }

    private fun cancelTaskWork(context: Context, taskId: Long) {
        ExactAlarms.cancel(
            context, ExactAlarms.taskCode(taskId), ExactAlarms.ACTION_REMINDER_TASK
        )
    }

    /**
     * 排一门课的下一次提醒。
     * @param triggerAt 由 [ReminderCalculator.nextReminder] 算出的触发时刻；null = 取消
     */
    fun scheduleCourse(context: Context, course: Course, triggerAt: LocalDateTime?) {
        if (triggerAt == null) {
            cancelCourseWork(context, course.id)
            ReminderNotifier.cancel(context, course.id)
            return
        }
        enqueueReminder(context, ReminderNotifyWorker.TYPE_COURSE, course.id, triggerAt)
    }

    /**
     * 重排全部提醒（应用启动 / 数据变更后调用）。
     * 包含：课程提醒（按周次）+ 任务提醒（按日期时刻）。
     *
     * @param defaultMinutes 全局默认提前量（DataStore 设置，由调用方读取传入）
     * @param enabled        课程提醒总开关（DataStore `reminderEnabled`）。
     *   为 false 时一门课都不排，并清掉残留通知——本函数是全量重排的唯一入口，
     *   不读这个开关的话用户关掉总开关后只要回到前台就会被重新排上。
     *   任务提醒不受它影响（任务的开关在任务自身）。
     */
    suspend fun rescheduleAll(context: Context, defaultMinutes: Int, enabled: Boolean = true) {
        lastKnownDefaultMinutes = defaultMinutes
        val db = DatabaseProvider.get(context)
        val term = db.termDao().getActive()
        val reminders: List<Reminder> = db.reminderDao().getAll()

        // 先取消「所有学期全部课程」的已排闹钟，再只为当前活动学期重排。
        // 否则切换学期方案后，旧方案课程的闹钟仍残留在 AlarmManager 中，
        // 到点不但会发错通知，还会经 rescheduleCourse 无限自链。
        db.courseDao().getAll().forEach { runCatching { cancelCourseWork(context, it.id) } }
        rescheduleTasks(context)

        // 总开关关闭：清掉课程通知残留后直接返回，一门都不排
        if (!enabled) {
            db.courseDao().getAll().forEach { ReminderNotifier.cancel(context, it.id) }
            return
        }

        if (term == null) return

        val courses = db.courseDao().getByTerm(term.id)
            .filter { it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 }
        val sectionTimes = db.sectionTimeDao().getByTerm(term.id)
        val now = LocalDateTime.now()

        for (course in courses) {
            val reminder = reminders.firstOrNull { it.courseId == course.id }
            val enabled = reminder?.enabled ?: true // 未单独设置过 = 跟随全局默认
            if (!enabled) {
                ReminderNotifier.cancel(context, course.id)
                continue
            }
            val minutesBefore = reminder?.minutesBefore ?: defaultMinutes
            val startTime = ReminderCalculator.parseTime(
                sectionTimes.firstOrNull { it.sectionIndex == course.startSection }?.startTime
            ) ?: continue
            val trigger = ReminderCalculator.nextReminder(
                termStart = term.startDate,
                totalWeeks = term.totalWeeks,
                weeks = course.weeks,
                dayOfWeek = course.dayOfWeek,
                courseStartTime = startTime,
                minutesBefore = minutesBefore,
                now = now,
                overrideDate = course.overrideDate
            )
            scheduleCourse(context, course, trigger)
        }
    }

    /**
     * 防御性校验：课程已删除，或所属学期不是当前活动学期（典型场景：切换方案后
     * 旧闹钟恰好在全量重排前到点）。命中则取消该闹钟，调用方不应再发通知 / 续排。
     */
    suspend fun cancelIfCourseNotInActiveTerm(context: Context, courseId: Long): Boolean {
        val db = DatabaseProvider.get(context)
        val course = db.courseDao().get(courseId)
        val activeTermId = db.termDao().getActive()?.id
        val stale = course == null || course.termId != activeTermId
        if (stale) {
            runCatching { cancelCourseWork(context, courseId) }
            runCatching { ReminderNotifier.cancel(context, courseId) }
        }
        return stale
    }

    /** 单门课重排（到点触发后调用：发完通知排下一次）。 */
    suspend fun rescheduleCourse(context: Context, courseId: Long) {
        val db = DatabaseProvider.get(context)
        val course = db.courseDao().get(courseId) ?: run {
            // 课程已删除：取消它的提醒与通知
            runCatching { cancelCourseWork(context, courseId) }
            ReminderNotifier.cancel(context, courseId)
            return
        }
        val term = db.termDao().get(course.termId) ?: return
        val reminder = db.reminderDao().getByCourse(courseId)
        if (reminder?.enabled == false) {
            scheduleCourse(context, course, null)
            return
        }
        val sectionTimes = db.sectionTimeDao().getByTerm(term.id)
        val startTime = ReminderCalculator.parseTime(
            sectionTimes.firstOrNull { it.sectionIndex == course.startSection }?.startTime
        ) ?: return
        val trigger = ReminderCalculator.nextReminder(
            termStart = term.startDate,
            totalWeeks = term.totalWeeks,
            weeks = course.weeks,
            dayOfWeek = course.dayOfWeek,
            courseStartTime = startTime,
            minutesBefore = reminder?.minutesBefore ?: lastKnownDefaultMinutes,
            now = LocalDateTime.now(),
            overrideDate = course.overrideDate
        )
        scheduleCourse(context, course, trigger)
    }

    // ------------------------------------------------------------------
    // 任务提醒
    // ------------------------------------------------------------------

    /**
     * 计算任务提醒时刻：date + startTime（无时刻按 09:00）− reminderMinutes。
     * 无日期 / 已过期 / 未开提醒 → null。
     */
    fun taskTriggerTime(
        date: String?,
        startTime: String?,
        reminderMinutes: Int?,
        now: LocalDateTime = LocalDateTime.now()
    ): LocalDateTime? {
        if (reminderMinutes == null || date == null) return null
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val time = ReminderCalculator.parseTime(startTime) ?: java.time.LocalTime.of(9, 0)
        val trigger = day.atTime(time).minusMinutes(reminderMinutes.toLong())
        return trigger.takeIf { it.isAfter(now) }
    }

    /**
     * 任务的下一次有效提醒（发生日 + 触发时刻）。
     * - 无规则任务：仅锚点当天一次，整体完成则无；
     * - 重复任务：跳过已分次完成的发生日；今天的提醒时刻已过则顺延到之后的发生日。
     */
    fun nextTaskTrigger(
        task: Task,
        now: LocalDateTime = LocalDateTime.now()
    ): Pair<LocalDate, LocalDateTime>? {
        val minutes = task.reminderMinutes ?: return null
        val recurring = task.isRecurring
        val time = ReminderCalculator.parseTime(task.startTime) ?: java.time.LocalTime.of(9, 0)

        var occurrence = task.firstOccurrenceOnOrAfter(now.toLocalDate(), skipCompleted = recurring)
            ?: return null
        // 无规则任务的整体完成态由 firstOccurrenceOnOrAfter(skipCompleted=false) 不感知，
        // 这里统一兜底
        if (!recurring && task.completed) return null

        repeat(10_000) {
            val trigger = occurrence.atTime(time).minusMinutes(minutes.toLong())
            if (trigger.isAfter(now)) return occurrence to trigger
            // 本次发生的提醒时刻已过：找下一个发生日
            occurrence = task.firstOccurrenceOnOrAfter(
                occurrence.plusDays(1),
                skipCompleted = recurring
            ) ?: return null
        }
        return null
    }

    /** 排/取消单个任务提醒。 */
    fun scheduleTask(
        context: Context,
        taskId: Long,
        triggerAt: LocalDateTime?,
        occurrenceDate: String? = null
    ) {
        if (triggerAt == null) {
            cancelTaskWork(context, taskId)
            return
        }
        enqueueReminder(
            context, ReminderNotifyWorker.TYPE_TASK, taskId, triggerAt, occurrenceDate
        )
    }

    /** 取消单个任务提醒（删除任务时调用）。 */
    fun cancelTask(context: Context, taskId: Long) {
        cancelTaskWork(context, taskId)
    }

    /** 重排全部任务提醒：先全量取消再按库内任务重排（重复任务排下一次未完成发生）。 */
    suspend fun rescheduleTasks(context: Context) {
        val db = DatabaseProvider.get(context)
        db.taskDao().getAll().forEach { t ->
            runCatching { cancelTaskWork(context, t.id) }
            val next = nextTaskTrigger(t)
            if (next != null) {
                scheduleTask(context, t.id, next.second, next.first.toString())
            }
        }
    }

    /** 单任务重排（重复任务到点发出通知后调用：把下一次发生重新入排）。 */
    suspend fun rescheduleTask(context: Context, taskId: Long) {
        val db = DatabaseProvider.get(context)
        val task = db.taskDao().getById(taskId) ?: run {
            cancelTask(context, taskId)
            return
        }
        runCatching { cancelTaskWork(context, taskId) }
        val next = nextTaskTrigger(task)
        if (next != null) {
            scheduleTask(context, taskId, next.second, next.first.toString())
        }
    }

    /**
     * 取消所有课程提醒（全局关闭提醒时调用）。
     *
     * 必须遍历**课程表**而不是 reminders 表：`Reminder` 行只在用户给某门课单独设过
     * 提醒时才存在，绝大多数课程跟随全局默认、没有对应行——只遍历 reminders 会
     * 让这些课程的闹钟当场残留。
     */
    suspend fun cancelAll(context: Context) {
        val db = DatabaseProvider.get(context)
        db.courseDao().getAll().forEach { course ->
            runCatching { cancelCourseWork(context, course.id) }
            ReminderNotifier.cancel(context, course.id)
        }
        // 任务提醒不受"课程提醒总开关"影响，任务开关在任务自身上，因此这里不取消任务
    }
}
