package cn.sanxing.thrice.notification

/**
 * 通知 id / PendingIntent requestCode 的统一区段分配。
 *
 * 背景：`Intent.filterEquals` 不比较 extras，所以「requestCode + 组件」就决定了
 * 两个 PendingIntent 是否被视为同一个；通知 id 同理，同 id 的新通知会顶掉旧的。
 * 各模块若各自从几千开始编号，随着自增 id 增长（反复导入/删除课程）迟早会撞上：
 * 例如课程通知 id 曾用 `4200 + courseId`，而专注前台服务的常驻通知是 `9103`，
 * 于是 courseId = 4903 的课会**顶掉前台服务的常驻通知**。
 *
 * 这里给每个模块分配互不重叠的百万级区段，从根上避免这类“用久了才炸”的问题。
 */
object NotifIds {

    /** 上课提醒：`BASE + courseId`。 */
    const val COURSE_BASE = 10_000_000

    /** 任务提醒：`BASE + taskId`。 */
    const val TASK_BASE = 20_000_000

    /** 专注：前台服务常驻通知。 */
    const val FOCUS_FOREGROUND = 30_000_001

    /** 专注：结束通知。 */
    const val FOCUS_FINISHED = 30_000_002

    /** 睡眠：入睡提醒。 */
    const val SLEEP_BED = 40_000_001

    /** 睡眠：午睡提醒。 */
    const val SLEEP_NAP = 40_000_002

    /** 上课提醒通知 id。 */
    fun course(courseId: Long): Int = (COURSE_BASE + courseId).toInt()

    /** 任务提醒通知 id。 */
    fun task(taskId: Long): Int = (TASK_BASE + taskId).toInt()
}
