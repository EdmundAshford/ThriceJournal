package cn.sanxing.thrice.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * 数据变更后主动刷新两个小组件（导入 / 编辑 / 恢复 / 清空后调用）。
 */
object WidgetUpdater {

    suspend fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        runCatching { TodayCoursesWidget().updateAll(appContext) }
        runCatching { WeekMiniWidget().updateAll(appContext) }
    }
}
