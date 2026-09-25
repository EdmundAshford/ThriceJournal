package cn.sanxing.thrice.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.size
import androidx.glance.layout.width
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
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.parser.reminder.ReminderCalculator
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 今日课程小组件：列出今天的课（时间 / 课名 / 地点），空则显示「今天没课」。
 * 点击单条课打开 App 并定位到该课；点「刷新」手动更新。
 *
 * 深色模式：文案/底色一律由 WidgetColors.xxx / xxxDark 按 isDark 显式二选一，GlanceTheme 用内置调色板兜底。
 */
class TodayCoursesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = DatabaseProvider.get(context)
        val term = db.termDao().getActive()
        val today = LocalDate.now()
        val dayOfWeek = today.dayOfWeek.value
        val week = term?.let { ReminderCalculator.weekOf(it.startDate, today) }

        val courses: List<Course>
        val sectionTimes: List<SectionTime>
        if (term != null && week != null && week in 1..term.totalWeeks) {
            courses = db.courseDao().getByTerm(term.id)
                .filter { it.kind == CourseKind.GRID && it.dayOfWeek == dayOfWeek && week in it.weeks }
                .sortedBy { it.startSection }
            sectionTimes = db.sectionTimeDao().getByTerm(term.id)
        } else {
            courses = emptyList()
            sectionTimes = emptyList()
        }

        val title = context.getString(R.string.widget_today_title)
        val emptyText = context.getString(R.string.widget_today_empty)
        val refreshText = context.getString(R.string.widget_refresh)
        val dateLabel = today.format(DateTimeFormatter.ofPattern("MM-dd")) + " " + weekdayFull(context, dayOfWeek)

        provideContent {
            GlanceTheme {
                val isDark = LocalContext.current.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val bg = if (isDark) WidgetColors.bgDark else WidgetColors.bg
                Box(GlanceModifier.fillMaxSize().background(bg).padding(12.dp)) {
                    Column(GlanceModifier.fillMaxSize()) {
                        Row(
                            GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    title,
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ColorProvider(if (isDark) WidgetColors.primaryDark else WidgetColors.primary)
                                    )
                                )
                                Text(
                                    dateLabel,
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        color = ColorProvider(if (isDark) WidgetColors.mutedDark else WidgetColors.muted)
                                    )
                                )
                            }
                            Text(
                                refreshText,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = ColorProvider(if (isDark) WidgetColors.primaryDark else WidgetColors.primary)
                                ),
                                modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>())
                            )
                        }
                        Spacer(GlanceModifier.height(8.dp))

                        if (courses.isEmpty()) {
                            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    emptyText,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        color = ColorProvider(if (isDark) WidgetColors.mutedDark else WidgetColors.muted)
                                    )
                                )
                            }
                        } else {
                            courses.forEach { course ->
                                CourseRow(course, sectionTimes, isDark)
                                Spacer(GlanceModifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CourseRow(
        course: Course,
        sectionTimes: List<SectionTime>,
        isDark: Boolean
    ) {
        val context = LocalContext.current
        val fg = if (isDark) WidgetColors.onBgDark else WidgetColors.onBg
        val muted = if (isDark) WidgetColors.mutedDark else WidgetColors.muted
        val accent = if (isDark) WidgetColors.primaryDark else WidgetColors.primary
        val time = timeLabel(context, sectionTimes, course)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_COURSE_ID, course.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        Row(
            GlanceModifier.fillMaxWidth().clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧色条（与 App 内课程卡片一致的粗线条）
            Spacer(
                GlanceModifier.size(4.dp, 36.dp)
                    .background(WidgetColors.courseColor(course.colorHex, course.name))
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                time,
                style = TextStyle(fontSize = 12.sp, color = ColorProvider(accent), textAlign = TextAlign.Start),
                modifier = GlanceModifier.width(88.dp)
            )
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    course.name,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorProvider(fg)),
                    maxLines = 1
                )
                val sub = listOfNotNull(
                    course.location.takeIf { it.isNotBlank() },
                    course.teacher.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = TextStyle(fontSize = 11.sp, color = ColorProvider(muted)),
                        maxLines = 1
                    )
                }
            }
        }
    }

    private fun timeLabel(context: Context, sectionTimes: List<SectionTime>, course: Course): String {
        val start = sectionTimes.firstOrNull { it.sectionIndex == course.startSection }?.startTime
        val end = sectionTimes.firstOrNull { it.sectionIndex == course.endSection }?.endTime
        return if (start != null && end != null) "$start-$end"
        else context.getString(R.string.sections_range_format, course.startSection, course.endSection)
    }

    companion object {
        /** 星期全名（周一…周日），跟随系统语言。 */
        fun weekdayFull(context: Context, dayOfWeek: Int): String = context.getString(
            when (dayOfWeek) {
                1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu
                5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun
            }
        )

        /** 星期单字母（小组件表头用）。 */
        fun weekdayLetter(context: Context, dayOfWeek: Int): String = context.getString(
            when (dayOfWeek) {
                1 -> R.string.day_mon_letter; 2 -> R.string.day_tue_letter; 3 -> R.string.day_wed_letter
                4 -> R.string.day_thu_letter; 5 -> R.string.day_fri_letter; 6 -> R.string.day_sat_letter
                else -> R.string.day_sun_letter
            }
        )
    }
}
