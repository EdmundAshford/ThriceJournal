package cn.sanxing.thrice.notification

import android.content.Context

/**
 * 通知渠道选项（铃声 / 振动）。
 *
 * 通知在 Alarm 接收器中发出，无法直接访问 DataStore/Hilt，
 * 因此用一个独立的 SharedPreferences 文件保存这两项设置；
 * 设置页修改时同步写入，[ReminderNotifier] 创建/更新渠道时读取。
 */
object ReminderPrefs {

    private const val FILE = "reminder_prefs"
    private const val KEY_SOUND = "sound"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_APPLIED_SOUND = "applied_sound"
    private const val KEY_APPLIED_VIBRATE = "applied_vibrate"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun sound(context: Context): Boolean = prefs(context).getBoolean(KEY_SOUND, true)

    fun vibrate(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)

    fun setOptions(context: Context, sound: Boolean, vibrate: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SOUND, sound)
            .putBoolean(KEY_VIBRATE, vibrate)
            .apply()
    }

    /** 记录渠道当前已应用的选项，用于检测设置是否变化。 */
    fun appliedOptions(context: Context): Pair<Boolean, Boolean> =
        channelApplied(context, "course_reminder")

    fun setAppliedOptions(context: Context, sound: Boolean, vibrate: Boolean) {
        setChannelApplied(context, "course_reminder", sound, vibrate)
    }

    /** 每个渠道各自记录已应用的声音/振动选项（渠道参数创建后不可变，变化时需删旧重建）。 */
    fun channelApplied(context: Context, channelId: String): Pair<Boolean, Boolean> =
        prefs(context).getBoolean("${KEY_APPLIED_SOUND}_$channelId", true) to
            prefs(context).getBoolean("${KEY_APPLIED_VIBRATE}_$channelId", true)

    fun setChannelApplied(context: Context, channelId: String, sound: Boolean, vibrate: Boolean) {
        prefs(context).edit()
            .putBoolean("${KEY_APPLIED_SOUND}_$channelId", sound)
            .putBoolean("${KEY_APPLIED_VIBRATE}_$channelId", vibrate)
            .apply()
    }
}
