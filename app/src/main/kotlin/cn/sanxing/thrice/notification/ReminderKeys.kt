package cn.sanxing.thrice.notification

/**
 * 提醒 Intent / DataStore 里用到的键名与类型常量。
 *
 * 原先挂在 `ReminderNotifyWorker` 的 companion 上，但 R13 起提醒已改由
 * AlarmManager（[ExactAlarms] + [AppAlarmReceiver]）派发，那个 Worker 已不再入队；
 * 常量单独抽出来，避免为了几个字符串保留一整个死类。
 */
object ReminderKeys {

    const val KEY_TYPE = "reminder_type"
    const val KEY_ID = "reminder_id"

    /** 重复任务的本次发生日，格式 yyyy-MM-dd。 */
    const val KEY_DATE = "reminder_date"

    const val TYPE_COURSE = "course"
    const val TYPE_TASK = "task"
}
