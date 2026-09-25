package cn.sanxing.thrice.parser.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 备份文件 schema（当前 schemaVersion = 5）。
 *
 * 全部用纯 Kotlin 可序列化 DTO 表示（日期为 ISO 字符串，周次为 Set<Int>），
 * 与 Room 实体一一对应，由 core:data 的 BackupManager 负责映射。
 * 放在纯 JVM 模块是为了让「备份 JSON 往返一致性」可以跑单元测试。
 */
object BackupSchema {
    /**
     * v5：增加笔记标签 / 任务标签（多对多）与 AI 对话、消息。
     * 历史版本：v4 增加随身记（笔记文件夹 / 笔记）。
     */
    const val CURRENT_VERSION = 5
}

@Serializable
data class BackupTeacherSegment(
    val teacher: String,
    val weeks: Set<Int> = emptySet(),
    val weeksRaw: String = ""
)

@Serializable
data class BackupTerm(
    val id: Long = 0,
    val name: String,
    val startDate: String,        // ISO yyyy-MM-dd
    val totalWeeks: Int,
    val isActive: Boolean = false
)

@Serializable
data class BackupCourse(
    val id: Long = 0,
    val termId: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val campus: String = "",
    val classGroup: String = "",
    val classMembers: String = "",
    val assessment: String = "",
    val remark: String = "",
    val hoursBreakdown: String = "",
    val weeklyHours: Int? = null,
    val totalHours: Int? = null,
    val credit: Double? = null,
    val category: String? = null,
    val colorHex: String = "",
    val weeks: Set<Int> = emptySet(),
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val note: String = "",
    val kind: String = "GRID",
    val teacherSegments: List<BackupTeacherSegment> = emptyList(),
    val weeksRaw: String = "",
    val overrideDate: String? = null,
    val overrideNote: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class BackupSectionTime(
    val id: Long = 0,
    val termId: Long,
    val sectionIndex: Int,
    val startTime: String,
    val endTime: String
)

@Serializable
data class BackupReminder(
    val id: Long = 0,
    val courseId: Long,
    val minutesBefore: Int,
    val enabled: Boolean
)

@Serializable
data class BackupExam(
    val id: Long = 0,
    val termId: Long,
    val courseName: String,
    val date: String,
    val time: String,
    val location: String = "",
    val note: String = ""
)

@Serializable
data class BackupAssignment(
    val id: Long = 0,
    val termId: Long,
    val courseName: String,
    val title: String,
    val dueDate: String,
    val note: String = ""
)

@Serializable
data class BackupTaskType(
    val name: String,
    val colorHex: String = "#2196F3",
    val sortOrder: Int = 0
)

@Serializable
data class BackupTask(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val type: String = "其他",
    val date: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val completed: Boolean = false,
    val reminderMinutes: Int? = null,
    val ruleJson: String? = null,
    val completedDates: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class BackupBillCategory(
    val name: String,
    val type: String = "EXPENSE",
    val colorHex: String = "#FF7043",
    val sortOrder: Int = 0
)

@Serializable
data class BackupBill(
    val id: Long = 0,
    val type: String = "EXPENSE",
    val amount: Double,
    val category: String,
    val date: String,
    val note: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class BackupFocusTag(
    val id: Long = 0,
    val name: String,
    val colorArgb: Int = 0xFF2E6DA4.toInt(),
    val sortOrder: Int = 0
)

@Serializable
data class BackupFocusSession(
    val id: Long = 0,
    val tagId: Long? = null,
    val mode: String = "STOPWATCH",
    val plannedSeconds: Long = 0,
    val startedAtEpochMs: Long = 0L,
    val endedAtEpochMs: Long? = null,
    val focusedSeconds: Long = 0L,
    val status: String = "COMPLETED",
    val createdAt: Long = 0L
)

@Serializable
data class BackupSleepRecord(
    val id: Long = 0,
    val kind: String = "NIGHT",
    val sleepAtEpochMs: Long = 0L,
    val wakeAtEpochMs: Long? = null,
    val minutes: Int? = null,
    val note: String = ""
)

@Serializable
data class BackupNoteFolder(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L
)

@Serializable
data class BackupNote(
    val id: Long = 0,
    val folderId: Long? = null,   // null = 未分类
    val title: String = "",
    val content: String = "",     // Markdown 子集纯文本
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val fontKey: String? = null,  // 单篇字体覆盖 "00".."32"；null = 跟随全局
    val fontSizeSp: Int? = null   // 单篇字号覆盖；null = 跟随全局
)

// ---------------- v5：笔记标签（多对多） ----------------

@Serializable
data class BackupNoteTag(
    val id: Long = 0,
    val name: String,
    val colorArgb: Int = 0xFF7E57C2.toInt(),
    val sortOrder: Int = 0,
    val createdAt: Long = 0L
)

@Serializable
data class BackupNoteTagRef(
    val noteId: Long,
    val tagId: Long
)

// ---------------- v5：任务标签（多对多） ----------------

@Serializable
data class BackupTaskTag(
    val id: Long = 0,
    val name: String,
    val colorHex: String = "#7E57C2",
    val sortOrder: Int = 0,
    val createdAt: Long = 0L
)

@Serializable
data class BackupTaskTagRef(
    val taskId: Long,
    val tagId: Long
)

// ---------------- v5：AI 对话 / 消息 ----------------

@Serializable
data class BackupAiConversation(
    val id: Long = 0,
    val title: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class BackupAiMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallsJson: String = "[]",
    val createdAt: Long = 0L,
    val orderIndex: Int = 0
)

@Serializable
data class BackupFile(
    val schemaVersion: Int = BackupSchema.CURRENT_VERSION,
    val app: String = "Sanxing",
    val exportedAt: String = "",   // ISO LocalDateTime
    val terms: List<BackupTerm> = emptyList(),
    val courses: List<BackupCourse> = emptyList(),
    val sectionTimes: List<BackupSectionTime> = emptyList(),
    val reminders: List<BackupReminder> = emptyList(),
    val exams: List<BackupExam> = emptyList(),
    val assignments: List<BackupAssignment> = emptyList(),
    val taskTypes: List<BackupTaskType> = emptyList(),
    val tasks: List<BackupTask> = emptyList(),
    val billCategories: List<BackupBillCategory> = emptyList(),
    val bills: List<BackupBill> = emptyList(),
    val focusTags: List<BackupFocusTag> = emptyList(),
    val focusSessions: List<BackupFocusSession> = emptyList(),
    val sleepRecords: List<BackupSleepRecord> = emptyList(),
    // v4：随身记。默认空表，旧版本（v1..v3）备份缺省这两个字段时可直接反序列化恢复。
    val noteFolders: List<BackupNoteFolder> = emptyList(),
    val notes: List<BackupNote> = emptyList(),
    // v5：笔记标签 / 任务标签（多对多）与 AI 对话。默认空列表，
    // 旧版本（v1..v4）备份缺省这些字段时仍可直接反序列化。
    val noteTags: List<BackupNoteTag> = emptyList(),
    val noteTagRefs: List<BackupNoteTagRef> = emptyList(),
    val taskTags: List<BackupTaskTag> = emptyList(),
    val taskTagRefs: List<BackupTaskTagRef> = emptyList(),
    val aiConversations: List<BackupAiConversation> = emptyList(),
    val aiMessages: List<BackupAiMessage> = emptyList()
) {
    val courseCount: Int get() = courses.size
}

/** 解析结果：成功带数据，失败带可读错误信息。 */
sealed interface BackupParseResult {
    data class Ok(val file: BackupFile) : BackupParseResult
    data class Error(val message: String) : BackupParseResult
}

object BackupCodec {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupParseResult = try {
        BackupParseResult.Ok(json.decodeFromString(BackupFile.serializer(), text))
    } catch (e: SerializationException) {
        BackupParseResult.Error("JSON 解析失败：${e.message}")
    } catch (e: IllegalArgumentException) {
        BackupParseResult.Error("JSON 解析失败：${e.message}")
    }

    /**
     * 恢复前的一致性校验。返回错误列表；空列表 = 通过。
     * 规则：版本 ≤ 当前、学期字段完整、周次 ⊆ 1..totalWeeks、星期/节次范围合法、外键可解析。
     */
    fun validate(file: BackupFile): List<String> {
        val errors = mutableListOf<String>()
        if (file.schemaVersion < 1 || file.schemaVersion > BackupSchema.CURRENT_VERSION) {
            errors.add("不支持的 schemaVersion=${file.schemaVersion}（当前支持 1..${BackupSchema.CURRENT_VERSION}）")
            return errors
        }
        val termIds = file.terms.map { it.id }.toSet()
        file.terms.forEachIndexed { i, t ->
            if (t.name.isBlank()) errors.add("学期 #${i + 1} 名称为空")
            if (t.totalWeeks !in 1..30) errors.add("学期「${t.name}」总周数 ${t.totalWeeks} 不在 1..30")
            runCatching { LocalDate.parse(t.startDate) }.onFailure {
                errors.add("学期「${t.name}」起始日期无法解析：${t.startDate}")
            }
        }
        file.courses.forEachIndexed { i, c ->
            val label = "课程 #${i + 1}「${c.name}」"
            if (c.name.isBlank()) errors.add("课程 #${i + 1} 名称为空")
            if (c.termId !in termIds) errors.add("$label 引用了不存在的学期 id=${c.termId}")
            if (c.dayOfWeek !in 0..7) errors.add("$label 星期 ${c.dayOfWeek} 不在 0..7")
            if (c.startSection !in 0..12 || c.endSection !in 0..12 || c.endSection < c.startSection) {
                errors.add("$label 节次 ${c.startSection}-${c.endSection} 非法")
            }
            val term = file.terms.firstOrNull { it.id == c.termId }
            if (term != null) {
                val bad = c.weeks.filter { it < 1 || it > term.totalWeeks }
                if (bad.isNotEmpty()) errors.add("$label 周次 ${bad.sorted()} 超出 1..${term.totalWeeks}")
            }
            c.overrideDate?.let {
                runCatching { LocalDate.parse(it) }.onFailure { _ ->
                    errors.add("$label 调课日期无法解析：$it")
                }
            }
        }
        file.sectionTimes.forEachIndexed { i, s ->
            if (s.termId !in termIds) errors.add("节次时间 #${i + 1} 引用了不存在的学期 id=${s.termId}")
            if (s.sectionIndex !in 1..30) errors.add("节次时间 #${i + 1} 小节序号 ${s.sectionIndex} 不在 1..30")
        }
        val courseIds = file.courses.map { it.id }.toSet()
        file.reminders.forEachIndexed { i, r ->
            if (r.courseId !in courseIds) errors.add("提醒 #${i + 1} 引用了不存在的课程 id=${r.courseId}")
            if (r.minutesBefore !in 1..120) errors.add("提醒 #${i + 1} 提前量 ${r.minutesBefore} 不在 1..120")
        }
        file.exams.forEachIndexed { i, e ->
            if (e.termId !in termIds) errors.add("考试 #${i + 1} 引用了不存在的学期 id=${e.termId}")
        }
        file.assignments.forEachIndexed { i, a ->
            if (a.termId !in termIds) errors.add("作业 #${i + 1} 引用了不存在的学期 id=${a.termId}")
        }
        file.tasks.forEachIndexed { i, t ->
            if (t.title.isBlank()) errors.add("任务 #${i + 1} 标题为空")
            t.date?.let { d ->
                runCatching { LocalDate.parse(d) }.onFailure {
                    errors.add("任务 #${i + 1} 日期无法解析：$d")
                }
            }
        }
        file.focusTags.forEachIndexed { i, t ->
            if (t.name.isBlank()) errors.add("专注标签 #${i + 1} 名称为空")
        }
        // 注：会话 tagId 悬挂（标签已删）不是硬错误——这是正常产品行为
        //（删标签后历史会话归入「未分类」），导入端会把找不到的 tagId 重映射为 null。
        file.focusSessions.forEachIndexed { i, s ->
            if (s.focusedSeconds < 0) errors.add("专注会话 #${i + 1} 专注秒数为负")
        }
        file.sleepRecords.forEachIndexed { i, r ->
            if (r.wakeAtEpochMs != null && r.wakeAtEpochMs < r.sleepAtEpochMs) {
                errors.add("睡眠记录 #${i + 1} 苏醒早于入睡")
            }
        }
        file.noteFolders.forEachIndexed { i, f ->
            if (f.name.isBlank()) errors.add("笔记文件夹 #${i + 1} 名称为空")
        }
        // 注：笔记 folderId 悬挂（文件夹已删）不是硬错误——删文件夹时产品侧会把笔记归入
        //「未分类」；导入端同样会把找不到的 folderId 重映射为 null。
        file.notes.forEachIndexed { i, n ->
            n.fontKey?.let { key ->
                if (key.length != 2 || key.any { it !in '0'..'9' }) {
                    errors.add("笔记 #${i + 1} 字体标识非法：$key")
                }
            }
            n.fontSizeSp?.let { sp ->
                if (sp !in 8..60) errors.add("笔记 #${i + 1} 字号 $sp 不在 8..60")
            }
        }
        // ---- v5：笔记标签 / 关联（悬挂关联视为硬错误，避免恢复后出现孤儿引用） ----
        file.noteTags.forEachIndexed { i, t ->
            if (t.name.isBlank()) errors.add("笔记标签 #${i + 1} 名称为空")
        }
        val noteTagIds = file.noteTags.map { it.id }.toSet()
        val noteIds = file.notes.map { it.id }.toSet()
        file.noteTagRefs.forEachIndexed { i, r ->
            if (r.noteId !in noteIds) {
                errors.add("笔记标签关联 #${i + 1} 引用了不存在的笔记 id=${r.noteId}")
            }
            if (r.tagId !in noteTagIds) {
                errors.add("笔记标签关联 #${i + 1} 引用了不存在的标签 id=${r.tagId}")
            }
        }
        // ---- v5：任务标签 / 关联 ----
        file.taskTags.forEachIndexed { i, t ->
            if (t.name.isBlank()) errors.add("任务标签 #${i + 1} 名称为空")
        }
        val taskTagIds = file.taskTags.map { it.id }.toSet()
        val taskIds = file.tasks.map { it.id }.toSet()
        file.taskTagRefs.forEachIndexed { i, r ->
            if (r.taskId !in taskIds) {
                errors.add("任务标签关联 #${i + 1} 引用了不存在的任务 id=${r.taskId}")
            }
            if (r.tagId !in taskTagIds) {
                errors.add("任务标签关联 #${i + 1} 引用了不存在的标签 id=${r.tagId}")
            }
        }
        // ---- v5：AI 消息必须能解析到同文件内的会话 ----
        val aiConversationIds = file.aiConversations.map { it.id }.toSet()
        file.aiMessages.forEachIndexed { i, m ->
            if (m.role.isBlank()) errors.add("AI 消息 #${i + 1} 角色为空")
            if (m.conversationId !in aiConversationIds) {
                errors.add("AI 消息 #${i + 1} 引用了不存在的会话 id=${m.conversationId}")
            }
        }
        file.bills.forEachIndexed { i, b ->
            if (b.category.isBlank()) errors.add("账单 #${i + 1} 分类为空")
            if (b.amount < 0.0) errors.add("账单 #${i + 1} 金额为负")
            runCatching { LocalDate.parse(b.date) }.onFailure {
                errors.add("账单 #${i + 1} 日期无法解析：${b.date}")
            }
        }
        return errors
    }
}
