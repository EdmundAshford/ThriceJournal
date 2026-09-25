package cn.sanxing.thrice.data.data.export

import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.model.Assignment
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.Exam
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusStatus
import cn.sanxing.thrice.data.domain.model.FocusTag
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.Reminder
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.model.Term
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 可读格式导出的内容构造器：CSV（UTF-8 BOM）/ 笔记 Markdown / 可读 JSON。
 *
 * 时间单位约定（与实体一致）：所有 epoch 字段均为本地时区解释的毫秒时间戳，
 * 统一格式化为 yyyy-MM-dd HH:mm；枚举值一律映射为中文。
 */
internal object ExportFormats {

    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.CHINA)
    private val ZONE: ZoneId = ZoneId.systemDefault()

    private val WEEKDAYS_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun timestampDirName(): String =
        java.time.LocalDateTime.now().format(STAMP_FORMAT)

    fun formatMillis(ms: Long?): String =
        if (ms == null) "" else Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDateTime().format(DATE_TIME)

    fun formatDateMillis(ms: Long?): String =
        if (ms == null) "" else Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate().toString()

    /** "HH:mm"（一日内的分钟数）。 */
    fun formatMinuteOfDay(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun weekdayCn(dayOfWeek: Int): String =
        if (dayOfWeek in 1..7) WEEKDAYS_CN[dayOfWeek - 1] else "无固定时间"

    // ---------------------------------------------------------------- CSV

    /** 新建一份带 UTF-8 BOM 的 CSV 写入器（逗号分隔，CRLF 行尾，Excel 可直接识别编码）。 */
    fun csvWriter(file: File): BufferedWriter {
        file.parentFile?.mkdirs()
        val out = file.outputStream()
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        return BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
    }

    fun BufferedWriter.writeCsvRow(cells: List<Any?>) {
        write(cells.joinToString(",") { csvCell(it) })
        write("\r\n")
    }

    /** 含逗号 / 引号 / 换行的单元格用双引号包裹，内部双引号翻倍。 */
    private fun csvCell(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    fun writeTasksCsv(file: File, tasks: List<Task>) {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("标题", "备注", "类型", "截止时间", "完成", "创建时间"))
            tasks.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { t ->
                val due = when {
                    t.date.isNullOrBlank() -> ""
                    t.startTime.isNullOrBlank() -> t.date
                    else -> "${t.date} ${t.startTime}"
                }
                w.writeCsvRow(
                    listOf(
                        t.title,
                        t.description,
                        t.type,
                        due,
                        if (t.completed) "是" else "否",
                        formatMillis(t.createdAt)
                    )
                )
            }
        }
    }

    fun writeBillsCsv(file: File, bills: List<Bill>) {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("时间", "金额", "类别", "收支", "备注", "地点"))
            bills.sortedWith(compareBy({ it.date }, { it.createdAt })).forEach { b ->
                w.writeCsvRow(
                    listOf(
                        b.date,
                        formatAmount(b.amount),
                        b.category,
                        billTypeCn(b.type),
                        b.note,
                        // 账单实体当前不记录地点，保留表头与空值，便于手工补录
                        ""
                    )
                )
            }
        }
    }

    fun writeFocusSessionsCsv(file: File, sessions: List<FocusSession>, tags: List<FocusTag>) {
        val tagNames = tags.associate { it.id to it.name }
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("开始", "结束", "时长分钟", "计划分钟", "状态", "标签", "完成方式"))
            sessions.forEach { s ->
                w.writeCsvRow(
                    listOf(
                        formatMillis(s.startedAtEpochMs),
                        formatMillis(s.endedAtEpochMs),
                        minutesFromSeconds(s.focusedSeconds),
                        if (s.plannedSeconds > 0) minutesFromSeconds(s.plannedSeconds) else "",
                        focusStatusCn(s.status),
                        s.tagId?.let { tagNames[it] } ?: "",
                        focusModeCn(s.mode)
                    )
                )
            }
        }
    }

    fun writeSleepRecordsCsv(
        file: File,
        records: List<SleepRecord>,
        nightGoalMinutes: Int,
        napGoalMinutes: Int
    ) {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("日期", "类型", "时长分钟", "目标分钟", "是否达成", "开始时刻"))
            records.forEach { r ->
                val goal = if (r.kind == SleepKind.NAP.name) napGoalMinutes else nightGoalMinutes
                val achieved = r.minutes?.let { if (it >= goal) "是" else "否" } ?: ""
                w.writeCsvRow(
                    listOf(
                        formatDateMillis(r.sleepAtEpochMs),
                        sleepKindCn(r.kind),
                        r.minutes?.toString() ?: "",
                        goal.toString(),
                        achieved,
                        formatMillis(r.sleepAtEpochMs)
                    )
                )
            }
        }
    }

    /** 秒 → 分钟：整除输出整数，否则保留一位小数。 */
    private fun minutesFromSeconds(seconds: Long): String {
        if (seconds <= 0) return "0"
        return if (seconds % 60L == 0L) (seconds / 60).toString()
        else String.format(Locale.US, "%.1f", seconds / 60.0)
    }

    private fun formatAmount(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)

    fun billTypeCn(type: BillType): String = if (type == BillType.INCOME) "收入" else "支出"

    fun focusStatusCn(raw: String?): String = when (raw) {
        FocusStatus.COMPLETED.name -> "完成"
        FocusStatus.FAILED.name -> "失败"
        FocusStatus.ABANDONED.name -> "已放弃"
        else -> raw ?: ""
    }

    fun focusModeCn(raw: String?): String = when (raw) {
        FocusMode.STOPWATCH.name -> "正计时"
        FocusMode.COUNTDOWN.name -> "倒计时"
        else -> raw ?: ""
    }

    fun sleepKindCn(raw: String?): String = if (raw == SleepKind.NAP.name) "午休" else "夜睡"

    // ----------------------------------------------------- 笔记 Markdown

    /**
     * 输出一篇笔记的 .md 文件内容。
     * YAML front matter：id / folder / title / created / updated / pinned / fontKey / fontSizeSp；
     * 其后为笔记正文原文。
     */
    fun buildNoteMarkdown(note: Note, folderName: String?): String = buildString {
        append("---\n")
        append("id: ").append(note.id).append('\n')
        append("folder: ").append(yamlString(folderName ?: "未分类")).append('\n')
        append("title: ").append(yamlString(note.title)).append('\n')
        append("created: ").append(yamlString(formatMillis(note.createdAt))).append('\n')
        append("updated: ").append(yamlString(formatMillis(note.updatedAt))).append('\n')
        append("pinned: ").append(note.pinned).append('\n')
        append("fontKey: ").append(yamlString(note.fontKey ?: "")).append('\n')
        append("fontSizeSp: ").append(note.fontSizeSp?.toString() ?: "").append('\n')
        append("---\n\n")
        append(note.content)
        if (!note.content.endsWith("\n")) append("\n")
    }

    /** 双引号 YAML 标量，转义反斜杠与双引号；非 ASCII 字符原样保留（UTF-8）。 */
    private fun yamlString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** 文件名安全化：去除路径 / 通配等非法字符，限制长度。 */
    fun safeFileName(title: String): String =
        title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(40)
            .trim()
            .trimEnd('.')

    fun noteFileName(note: Note): String {
        val safe = safeFileName(note.title)
        return if (safe.isEmpty()) "${note.id}.md" else "${note.id}-$safe.md"
    }

    fun buildNotesIndex(
        notes: List<Note>,
        folders: List<NoteFolder>,
        folderNameById: Map<Long, String>,
        fileNameById: Map<Long, String>,
        exportedAt: String
    ): JsonObject = buildJsonObject {
        put("exportedAt", exportedAt)
        put("noteCount", notes.size)
        put("folders", buildJsonArray {
            folders.forEach { f ->
                add(buildJsonObject {
                    put("id", f.id)
                    put("name", f.name)
                    put("sortOrder", f.sortOrder)
                    put("createdAt", formatMillis(f.createdAt))
                    put("noteCount", notes.count { it.folderId == f.id })
                })
            }
            // 未分类（folderId = null）固定在末尾，即使为空也体现
            add(buildJsonObject {
                put("id", JsonNull)
                put("name", "未分类")
                put("noteCount", notes.count { it.folderId == null })
            })
        })
        put("notes", buildJsonArray {
            notes.sortedWith(
                compareByDescending<Note> { it.pinned }
                    .thenBy { it.sortOrder }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            ).forEach { n ->
                add(buildJsonObject {
                    put("id", n.id)
                    put("title", n.title)
                    put("folder", n.folderId?.let { folderNameById[it] } ?: "未分类")
                    put("file", "notes/" + (fileNameById[n.id] ?: "${n.id}.md"))
                    put("created", formatMillis(n.createdAt))
                    put("updated", formatMillis(n.updatedAt))
                    put("pinned", n.pinned)
                })
            }
        })
    }

    // ----------------------------------------------------------- JSON

    fun buildScheduleJson(
        terms: List<Term>,
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        assignments: List<Assignment>,
        exams: List<Exam>,
        reminders: List<Reminder>,
        exportedAt: String
    ): JsonObject {
        val termNames = terms.associate { it.id to it.name }
        val courseNames = courses.associate { it.id to it.name }
        return buildJsonObject {
            put("导出时间", exportedAt)
            put("学期", buildJsonArray {
                terms.sortedWith(compareBy<Term> { !it.isActive }.thenBy { it.id }).forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("名称", t.name)
                        put("开始日期", t.startDate.toString())
                        put("总周数", t.totalWeeks)
                        put("是否当前学期", t.isActive)
                    })
                }
            })
            put("课程", buildJsonArray {
                courses.sortedWith(compareBy({ it.termId }, { it.dayOfWeek }, { it.startSection }, { it.id }))
                    .forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("学期ID", c.termId)
                            put("学期", termNames[c.termId] ?: "")
                            put("课程名", c.name)
                            put("教师", c.teacher)
                            put("教室", c.location)
                            put("校区", c.campus)
                            put("教学班", c.classGroup)
                            put("班级成员", c.classMembers)
                            put("考核方式", c.assessment)
                            put("说明", c.remark)
                            put("学时构成", c.hoursBreakdown)
                            put("周学时", c.weeklyHours)
                            put("总学时", c.totalHours)
                            put("学分", c.credit)
                            put("课程分类", c.category)
                            put("颜色", c.colorHex)
                            put("周次", buildJsonArray { c.weeks.sorted().forEach { add(it) } })
                            put("星期几", weekdayCn(c.dayOfWeek))
                            put("起始小节", c.startSection)
                            put("结束小节", c.endSection)
                            put("格子备注", c.note)
                            put("课程类型", if (c.kind == CourseKind.OTHER) "其他课程" else "网格课程")
                            put("教师分段", buildJsonArray {
                                c.teacherSegments.forEach { seg ->
                                    add(buildJsonObject {
                                        put("教师", seg.teacher)
                                        put("周次", buildJsonArray { seg.weeks.sorted().forEach { add(it) } })
                                        if (seg.weeksRaw.isNotBlank()) put("周次原文", seg.weeksRaw)
                                    })
                                }
                            })
                            if (c.weeksRaw.isNotBlank()) put("周次原文", c.weeksRaw)
                            put("调课日期", c.overrideDate?.toString() ?: "")
                            put("调课说明", c.overrideNote)
                            put("创建时间", formatMillis(c.createdAt))
                            put("更新时间", formatMillis(c.updatedAt))
                        })
                    }
            })
            put("节次时间", buildJsonArray {
                sectionTimes.sortedWith(compareBy({ it.termId }, { it.sectionIndex })).forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("学期", termNames[s.termId] ?: "")
                        put("小节序号", s.sectionIndex)
                        put("开始时间", s.startTime)
                        put("结束时间", s.endTime)
                    })
                }
            })
            put("作业", buildJsonArray {
                assignments.sortedWith(compareBy({ it.termId }, { it.dueDate }, { it.id })).forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        put("学期", termNames[a.termId] ?: "")
                        put("课程名", a.courseName)
                        put("标题", a.title)
                        put("截止日期", a.dueDate)
                        put("备注", a.note)
                    })
                }
            })
            put("考试", buildJsonArray {
                exams.sortedWith(compareBy({ it.termId }, { it.date }, { it.id })).forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("学期", termNames[e.termId] ?: "")
                        put("课程名", e.courseName)
                        put("日期", e.date)
                        put("时间", e.time)
                        put("地点", e.location)
                        put("备注", e.note)
                    })
                }
            })
            put("提醒", buildJsonArray {
                reminders.sortedBy { it.id }.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("课程ID", r.courseId)
                        put("课程名", courseNames[r.courseId] ?: "")
                        put("提前分钟", r.minutesBefore)
                        put("是否启用", r.enabled)
                    })
                }
            })
        }
    }

    /**
     * 用户设置快照（中文 key，保留布尔 / 数值 / 字符串类型）。
     *
     * 安全约束：DataStore 中所有 ai_ 前缀键（含 ai_api_key）均不在此枚举，
     * 物理上不会进入导出内容。
     */
    suspend fun buildSettingsJson(sr: SettingsRepository, exportedAt: String): JsonObject {
        val themeMode = sr.themeMode.first()
        val themeColor = sr.themeColor.first()
        val appLanguage = sr.appLanguage.first()
        val appFont = sr.appFont.first()
        val bgPath = sr.customBgPath.first()
        val navOrder = sr.navTabOrder.first()
        val navHidden = sr.hiddenNavTabs.first()
        val leaveBehavior = sr.focusLeaveBehavior.first()
        val goalMode = sr.sleepGoalMode.first()
        val lastTagId = sr.focusLastTagId.first()

        return buildJsonObject {
            put("导出时间", exportedAt)
            put("外观与个性化", buildJsonObject {
                put("主题模式", THEME_MODE_CN[themeMode] ?: themeMode)
                put("主题色", THEME_COLOR_CN[themeColor] ?: themeColor)
                put("自定义颜色", sr.customColor.first())
                put("动态取色", sr.dynamicColor.first())
                put("背景动画", sr.backgroundAnimation.first())
                put("应用语言", languageCn(appLanguage))
                put("应用字体", if (appFont == SettingsRepository.APP_FONT_SYSTEM) "系统默认" else "内置字体 $appFont")
                // 只导出「是否设置」，不导出文件绝对路径：
                // 路径属于应用私有目录，外发会泄露本机目录结构，对导入方也无意义。
                put("自定义背景图", if (bgPath.isNullOrBlank()) "未设置" else "已设置")
                put("背景图不透明度百分比", sr.customBgAlpha.first())
                put("主体界面浓度百分比", sr.uiMaskAlpha.first())
                put("背景平移X", sr.bgOffsetX.first())
                put("背景平移Y", sr.bgOffsetY.first())
                put("背景缩放", sr.bgScale.first())
            })
            put("提醒与通知", buildJsonObject {
                put("课程提醒总开关", sr.reminderEnabled.first())
                put("默认提前提醒分钟", sr.reminderMinutes.first())
                put("通知铃声", sr.notificationSound.first())
                put("通知振动", sr.notificationVibrate.first())
            })
            put("底部导航", buildJsonObject {
                put("标签顺序", buildJsonArray { navOrder.forEach { add(NAV_TAB_CN[it] ?: it) } })
                put("隐藏标签", buildJsonArray { navHidden.forEach { add(NAV_TAB_CN[it] ?: it) } })
            })
            put("专注", buildJsonObject {
                put("保持屏幕常亮", sr.focusKeepScreenOn.first())
                put("离开页面策略", LEAVE_BEHAVIOR_CN[leaveBehavior] ?: leaveBehavior)
                put("默认倒计时分钟", sr.focusCountdownMinutes.first())
                put("结束通知", sr.focusNotifyOnFinish.first())
                if (lastTagId == null) put("上次使用标签ID", JsonNull) else put("上次使用标签ID", lastTagId)
            })
            put("睡眠", buildJsonObject {
                put("启用午休", sr.sleepNapEnabled.first())
                put("目标模式", GOAL_MODE_CN[goalMode] ?: goalMode)
                put("夜睡目标分钟", sr.sleepNightGoalMinutes.first())
                put("目标入睡时刻", formatMinuteOfDay(sr.sleepBedTimeMinute.first()))
                put("目标起床时刻", formatMinuteOfDay(sr.sleepWakeTimeMinute.first()))
                put("入睡提醒开关", sr.sleepBedReminderEnabled.first())
                put("入睡提醒时刻", formatMinuteOfDay(sr.sleepBedReminderMinute.first()))
                put("午休目标分钟", sr.sleepNapGoalMinutes.first())
                put("午休提醒开关", sr.sleepNapReminderEnabled.first())
                put("午休提醒时刻", formatMinuteOfDay(sr.sleepNapReminderMinute.first()))
            })
        }
    }

    private fun languageCn(tag: String?): String = when (tag) {
        SettingsRepository.APP_LANGUAGE_ZH -> "中文"
        SettingsRepository.APP_LANGUAGE_EN -> "English"
        else -> "跟随系统"
    }

    private val THEME_MODE_CN = mapOf(
        SettingsRepository.THEME_MODE_SYSTEM to "跟随系统",
        SettingsRepository.THEME_MODE_LIGHT to "浅色",
        SettingsRepository.THEME_MODE_DARK to "深色"
    )

    private val THEME_COLOR_CN = mapOf(
        SettingsRepository.THEME_COLOR_BLUE to "蓝白",
        SettingsRepository.THEME_COLOR_PINK to "樱花粉",
        SettingsRepository.THEME_COLOR_MONO to "极简黑",
        SettingsRepository.THEME_COLOR_MINT to "薄荷绿",
        SettingsRepository.THEME_COLOR_GRAPE to "葡萄紫",
        SettingsRepository.THEME_COLOR_SUNSET to "日落橙",
        SettingsRepository.THEME_COLOR_TEAL to "青瓷",
        SettingsRepository.THEME_COLOR_CRIMSON to "中国红",
        SettingsRepository.THEME_COLOR_INDIGO to "靛青",
        SettingsRepository.THEME_COLOR_MOCHA to "摩卡棕",
        SettingsRepository.THEME_COLOR_CUSTOM to "自定义"
    )

    private val LEAVE_BEHAVIOR_CN = mapOf(
        SettingsRepository.FOCUS_LEAVE_PAUSE to "暂停计时",
        SettingsRepository.FOCUS_LEAVE_FAIL to "判定专注失败",
        SettingsRepository.FOCUS_LEAVE_KEEP to "保持计时"
    )

    private val GOAL_MODE_CN = mapOf(
        SettingsRepository.SLEEP_GOAL_MODE_DURATION to "按睡眠时长",
        SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE to "按入睡与起床时刻"
    )

    private val NAV_TAB_CN = mapOf(
        SettingsRepository.NAV_TAB_SCHEDULE to "日程",
        SettingsRepository.NAV_TAB_BILLS to "账单",
        SettingsRepository.NAV_TAB_TASKS to "任务",
        SettingsRepository.NAV_TAB_TIMETABLE to "课表",
        SettingsRepository.NAV_TAB_FOCUS to "专注",
        SettingsRepository.NAV_TAB_SLEEP to "睡眠",
        SettingsRepository.NAV_TAB_NOTES to "随身记",
        SettingsRepository.NAV_TAB_AI to "AI 问"
    )
}
