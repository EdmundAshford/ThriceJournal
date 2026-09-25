package cn.sanxing.thrice.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.MainActivity
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.parser.reminder.ReminderCalculator
import java.time.LocalDate

/**
 * 本周课表迷你小组件：7 列迷你网格，显示本周每天有课的色块（颜色取 Course.colorHex），
 * 今天高亮；点标题打开 App，点「刷新」手动更新。
 */
class WeekMiniWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = DatabaseProvider.get(context)
        val term = db.termDao().getActive()
        val today = LocalDate.now()
        val todayDow = today.dayOfWeek.value
        val week = term?.let { ReminderCalculator.weekOf(it.startDate, today) }

        val weekCourses: List<Course> = if (term != null && week != null && week in 1..term.totalWeeks) {
            db.courseDao().getByTerm(term.id)
                .filter { it.kind == CourseKind.GRID && week in it.weeks && it.dayOfWeek in 1..7 }
        } else emptyList()

        val title = context.getString(R.string.widget_week_title)
        val weekLabel = if (week != null && week in 1..(term?.totalWeeks ?: 0)) {
            context.getString(R.string.week_format, week)
        } else context.getString(R.string.widget_week_no_term)

        provideContent {
            GlanceTheme {
                val isDark = LocalContext.current.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val bg = if (isDark) WidgetColors.bgDark else WidgetColors.bg
                val fg = if (isDark) WidgetColors.onBgDark else WidgetColors.onBg
                val muted = if (isDark) WidgetColors.mutedDark else WidgetColors.muted
                val accent = if (isDark) WidgetColors.primaryDark else WidgetColors.primary

                val openApp = Intent(LocalContext.current, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

                Box(GlanceModifier.fillMaxSize().background(bg).padding(10.dp)) {
                    Column(GlanceModifier.fillMaxSize()) {
                        Row(
                            GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                GlanceModifier.defaultWeight()
                                    .clickable(actionStartActivity(openApp))
                            ) {
                                Text(
                                    title,
                                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorProvider(accent))
                                )
                                Text(
                                    weekLabel,
                                    style = TextStyle(fontSize = 11.sp, color = ColorProvider(muted))
                                )
                            }
                            Text(
                                LocalContext.current.getString(R.string.widget_refresh),
                                style = TextStyle(fontSize = 12.sp, color = ColorProvider(accent)),
                                modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>())
                            )
                        }
                        Spacer(GlanceModifier.height(8.dp))

                        // 星期栏
                        Row(GlanceModifier.fillMaxWidth()) {
                            (1..7).forEach { dow ->
                                Text(
                                    text = TodayCoursesWidget.weekdayLetter(LocalContext.current, dow),
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        fontWeight = if (dow == todayDow) FontWeight.Bold else FontWeight.Normal,
                                        color = ColorProvider(if (dow == todayDow) accent else muted),
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = GlanceModifier.defaultWeight()
                                )
                            }
                        }
                        Spacer(GlanceModifier.height(4.dp))

                        // 7 列迷你网格：每天有课的色块（多门课纵向堆叠）
                        Row(
                            GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            for (dow in 1..7) {
                                val dayCourses = weekCourses.filter { it.dayOfWeek == dow }
                                    .sortedBy { it.startSection }
                                Column(
                                    GlanceModifier.defaultWeight().padding(horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (dow == todayDow) {
                                        // 今天高亮：整列背景
                                        Box(
                                            GlanceModifier.fillMaxSize().background(accent.copy(alpha = 0.12f))
                                        ) {}
                                    }
                                    dayCourses.forEach { course ->
                                        val span = coveredSections(course.startSection, course.endSection).size.coerceAtLeast(1)
                                        Box(
                                            GlanceModifier
                                                .fillMaxWidth()
                                                .height((9 * span).dp)
                                                .background(WidgetColors.courseColor(course.colorHex, course.name))
                                        ) {}
                                        Spacer(GlanceModifier.height(2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun coveredSections(startSection: Int, endSection: Int): List<Int> {
        if (startSection <= 0 || endSection < startSection) return emptyList()
        return (startSection..endSection).toList()
    }
}
