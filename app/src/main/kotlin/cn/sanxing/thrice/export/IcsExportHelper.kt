package cn.sanxing.thrice.export

import android.content.Context
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.parser.ics.IcsCourse
import cn.sanxing.thrice.parser.ics.IcsExporter
import cn.sanxing.thrice.parser.ics.IcsSectionTime
import cn.sanxing.thrice.parser.ics.IcsTerm
import cn.sanxing.thrice.parser.reminder.ReminderCalculator
import java.io.File
import java.io.FileWriter
import java.time.LocalDate

/**
 * ICS 导出封装：读 Room → 交给 core:parser 的纯函数 IcsExporter 生成文本 →
 * 写到 cacheDir/shared/schedule.ics，供 FileProvider + ACTION_SEND(text/calendar) 分享，
 * 或由 CreateDocument("text/calendar") 另存。
 */
object IcsExportHelper {

    /** 全学期导出；[onlyWeek] 非空 = 仅本周。返回 .ics 文件。 */
    suspend fun export(context: Context, onlyWeek: Int? = null): File {
        val db = DatabaseProvider.get(context)
        val term = db.termDao().getActive()
            ?: error("No active term, cannot export ICS")
        val courses = db.courseDao().getByTerm(term.id)
        val sectionTimes = db.sectionTimeDao().getByTerm(term.id)

        val resolvedWeek = onlyWeek ?: run {
            val w = ReminderCalculator.weekOf(term.startDate, LocalDate.now())
            if (w in 1..term.totalWeeks) w else 1
        }

        val ics = IcsExporter.export(
            term = IcsTerm(term.name, term.startDate, term.totalWeeks),
            courses = courses.filter { it.kind == CourseKind.GRID }.map {
                IcsCourse(
                    id = it.id, name = it.name, teacher = it.teacher, location = it.location,
                    weeks = it.weeks, dayOfWeek = it.dayOfWeek,
                    startSection = it.startSection, endSection = it.endSection,
                    note = it.note
                )
            },
            sectionTimes = sectionTimes.map { IcsSectionTime(it.sectionIndex, it.startTime, it.endTime) },
            onlyWeek = onlyWeek
        )

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val name = if (onlyWeek != null) "thrice_schedule_week_$resolvedWeek.ics" else "thrice_schedule_full.ics"
        val file = File(dir, name)
        FileWriter(file, Charsets.UTF_8).use { it.write(ics) }
        return file
    }
}
