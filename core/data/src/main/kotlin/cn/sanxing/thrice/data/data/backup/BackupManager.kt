package cn.sanxing.thrice.data.data.backup

import androidx.room.withTransaction
import cn.sanxing.thrice.data.data.local.AppDatabase
import cn.sanxing.thrice.data.domain.model.Assignment
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.Exam
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusStatus
import cn.sanxing.thrice.data.domain.model.FocusTag
import cn.sanxing.thrice.data.domain.model.AiConversation
import cn.sanxing.thrice.data.domain.model.AiMessage
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.data.domain.model.NoteTagCrossRef
import cn.sanxing.thrice.data.domain.model.Reminder
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.model.TaskTag
import cn.sanxing.thrice.data.domain.model.TaskTagCrossRef
import cn.sanxing.thrice.data.domain.model.TaskType
import cn.sanxing.thrice.data.domain.model.TeacherSegment
import cn.sanxing.thrice.data.domain.model.Term
import cn.sanxing.thrice.parser.backup.BackupAssignment
import cn.sanxing.thrice.parser.backup.BackupAiConversation
import cn.sanxing.thrice.parser.backup.BackupAiMessage
import cn.sanxing.thrice.parser.backup.BackupBill
import cn.sanxing.thrice.parser.backup.BackupBillCategory
import cn.sanxing.thrice.parser.backup.BackupCodec
import cn.sanxing.thrice.parser.backup.BackupCourse
import cn.sanxing.thrice.parser.backup.BackupExam
import cn.sanxing.thrice.parser.backup.BackupFile
import cn.sanxing.thrice.parser.backup.BackupFocusSession
import cn.sanxing.thrice.parser.backup.BackupFocusTag
import cn.sanxing.thrice.parser.backup.BackupNote
import cn.sanxing.thrice.parser.backup.BackupNoteFolder
import cn.sanxing.thrice.parser.backup.BackupNoteTag
import cn.sanxing.thrice.parser.backup.BackupNoteTagRef
import cn.sanxing.thrice.parser.backup.BackupSleepRecord
import cn.sanxing.thrice.parser.backup.BackupParseResult
import cn.sanxing.thrice.parser.backup.BackupReminder
import cn.sanxing.thrice.parser.backup.BackupSchema
import cn.sanxing.thrice.parser.backup.BackupSectionTime
import cn.sanxing.thrice.parser.backup.BackupTask
import cn.sanxing.thrice.parser.backup.BackupTaskTag
import cn.sanxing.thrice.parser.backup.BackupTaskTagRef
import cn.sanxing.thrice.parser.backup.BackupTaskType
import cn.sanxing.thrice.parser.backup.BackupTeacherSegment
import cn.sanxing.thrice.parser.backup.BackupTerm
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** 导入方式：覆盖（清空后原样恢复）或合并（按名称去重、补充缺失）。 */
enum class ImportMode { OVERWRITE, MERGE }

/** 可单独导出 / 删除的数据类别。 */
enum class BackupCategory(val displayName: String) {
    TIMETABLE("课表数据"),
    BILLS("账单数据"),
    TASKS("任务数据"),
    FOCUS("专注数据"),
    SLEEP("睡眠数据"),
    NOTES("随身记数据"),
    ALL("所有数据")
}

/** 恢复前给用户的预览信息。 */
data class ImportPreview(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val file: BackupFile? = null,
    val importCourses: Int = 0,
    val importTerms: Int = 0,
    val importTasks: Int = 0,
    val importBills: Int = 0,
    val importNotes: Int = 0,
    val importNoteFolders: Int = 0,
    val currentCourses: Int = 0,
    val currentTerms: Int = 0
)

/**
 * 备份 / 恢复（JSON）：把 Term / Course / SectionTime / Reminder / Exam / Assignment
 * 全量序列化为带 schemaVersion 与导出时间的 JSON；导入前先校验（版本、字段完整性、
 * 周次 ⊆ 1..totalWeeks、外键可解析），再按用户选择的覆盖 / 合并落库。
 *
 * DTO 与编解码在 core:parser（纯 Kotlin），往返一致性有单元测试保证。
 */
@Singleton
class BackupManager @Inject constructor(private val db: AppDatabase) {

    // ---------------- 导出 ----------------

    /** 按类别导出 JSON（课表 / 账单 / 任务 / 所有）。 */
    suspend fun exportJson(category: BackupCategory = BackupCategory.ALL): String {
        val now = LocalDateTime.now().toString()
        val includeTimetable = category == BackupCategory.TIMETABLE || category == BackupCategory.ALL
        val includeBills = category == BackupCategory.BILLS || category == BackupCategory.ALL
        val includeTasks = category == BackupCategory.TASKS || category == BackupCategory.ALL
        val includeFocus = category == BackupCategory.FOCUS || category == BackupCategory.ALL
        val includeSleep = category == BackupCategory.SLEEP || category == BackupCategory.ALL
        val includeNotes = category == BackupCategory.NOTES || category == BackupCategory.ALL
        // AI 对话包含用户全部提问，仅随「所有数据」全量导出，不提供单类导出
        val includeAi = category == BackupCategory.ALL
        val file = BackupFile(
            schemaVersion = BackupSchema.CURRENT_VERSION,
            exportedAt = now,
            terms = if (includeTimetable) db.termDao().getAll().map { it.toDto() } else emptyList(),
            courses = if (includeTimetable) db.courseDao().getAll().map { it.toDto() } else emptyList(),
            sectionTimes = if (includeTimetable) db.sectionTimeDao().getAll().map { it.toDto() } else emptyList(),
            reminders = if (includeTimetable) db.reminderDao().getAll().map { it.toDto() } else emptyList(),
            exams = if (includeTimetable) db.examDao().getAll().map { it.toDto() } else emptyList(),
            assignments = if (includeTimetable) db.assignmentDao().getAll().map { it.toDto() } else emptyList(),
            taskTypes = if (includeTasks) db.taskTypeDao().getAll().map { it.toDto() } else emptyList(),
            tasks = if (includeTasks) db.taskDao().getAll().map { it.toDto() } else emptyList(),
            taskTags = if (includeTasks) db.taskTagDao().getAll().map { it.toDto() } else emptyList(),
            taskTagRefs = if (includeTasks) db.taskTagDao().getAllRefs().map { it.toDto() } else emptyList(),
            billCategories = if (includeBills) db.billCategoryDao().getAll().map { it.toDto() } else emptyList(),
            bills = if (includeBills) db.billDao().getAll().map { it.toDto() } else emptyList(),
            focusTags = if (includeFocus) db.focusTagDao().getAll().map { it.toDto() } else emptyList(),
            focusSessions = if (includeFocus) db.focusSessionDao().getAll().map { it.toDto() } else emptyList(),
            sleepRecords = if (includeSleep) db.sleepRecordDao().getAll().map { it.toDto() } else emptyList(),
            noteFolders = if (includeNotes) db.noteFolderDao().getAll().map { it.toDto() } else emptyList(),
            notes = if (includeNotes) db.noteDao().getAll().map { it.toDto() } else emptyList(),
            noteTags = if (includeNotes) db.noteTagDao().getAll().map { it.toDto() } else emptyList(),
            noteTagRefs = if (includeNotes) db.noteTagDao().getAllRefs().map { it.toDto() } else emptyList(),
            aiConversations = if (includeAi) db.aiChatDao().getAllConversations().map { it.toDto() } else emptyList(),
            aiMessages = if (includeAi) db.aiChatDao().getAllMessages().map { it.toDto() } else emptyList()
        )
        return BackupCodec.encode(file)
    }

    // ---------------- 导入（先解析 + 校验 + 预览，不写库） ----------------

    suspend fun prepareImport(text: String): ImportPreview {
        val parsed = BackupCodec.decode(text)
        if (parsed is BackupParseResult.Error) {
            return ImportPreview(valid = false, errors = listOf(parsed.message))
        }
        val file = (parsed as BackupParseResult.Ok).file
        val errors = BackupCodec.validate(file)
        if (errors.isNotEmpty()) return ImportPreview(valid = false, errors = errors)
        return ImportPreview(
            valid = true,
            file = file,
            importCourses = file.courses.size,
            importTerms = file.terms.size,
            importTasks = file.tasks.size,
            importBills = file.bills.size,
            importNotes = file.notes.size,
            importNoteFolders = file.noteFolders.size,
            currentCourses = db.courseDao().getAll().size,
            currentTerms = db.termDao().getAll().size
        )
    }

    // ---------------- 导入（确认后落库） ----------------

    suspend fun applyImport(preview: ImportPreview, mode: ImportMode): Result<Unit> = runCatching {
        val file = preview.file ?: error("备份数据为空")
        // 提醒也归入课表段：否则「只有提醒」的备份会被整段跳过，一条都导不进来
        val hasTimetable = file.terms.isNotEmpty() || file.courses.isNotEmpty() ||
            file.sectionTimes.isNotEmpty() || file.exams.isNotEmpty() ||
            file.assignments.isNotEmpty() || file.reminders.isNotEmpty()
        val hasTasks = file.tasks.isNotEmpty() || file.taskTypes.isNotEmpty() ||
            file.taskTags.isNotEmpty() || file.taskTagRefs.isNotEmpty()
        val hasBills = file.bills.isNotEmpty() || file.billCategories.isNotEmpty()
        val hasFocus = file.focusTags.isNotEmpty() || file.focusSessions.isNotEmpty()
        val hasSleep = file.sleepRecords.isNotEmpty()
        val hasNotes = file.noteFolders.isNotEmpty() || file.notes.isNotEmpty() ||
            file.noteTags.isNotEmpty() || file.noteTagRefs.isNotEmpty()
        // AI 段只有全量备份才会携带
        val hasAi = file.aiConversations.isNotEmpty() || file.aiMessages.isNotEmpty()

        db.withTransaction {
            if (mode == ImportMode.OVERWRITE) {
                // 逐个**子表**判定：只有备份里确实带了该表的数据才清空它。
                //
                // 旧实现按「段」判定（段内任一子表非空 → 清空整段所有表），于是
                // 「新机上只建了几个空文件夹 / 空标签、还没记内容」导出的一份备份，
                // 在主力机上做覆盖恢复时会把主力机上的全部笔记 / 专注会话 / 任务
                // 连带清空，而这些表在备份里是空的、无从恢复——UI 只会提示「恢复成功」。
                // 同理，清空类型表而备份里没有类型数据时，用户的自定义类型会永久消失。
                //
                // 父表先于子表清空，避免清空瞬间残留孤儿引用。
                if (file.reminders.isNotEmpty()) db.reminderDao().clearAll()
                if (file.assignments.isNotEmpty()) db.assignmentDao().clearAll()
                if (file.exams.isNotEmpty()) db.examDao().clearAll()
                if (file.sectionTimes.isNotEmpty()) db.sectionTimeDao().clearAll()
                if (file.courses.isNotEmpty()) db.courseDao().clearAll()
                if (file.terms.isNotEmpty()) db.termDao().getAll().forEach { db.termDao().delete(it) }

                if (file.taskTagRefs.isNotEmpty() || file.taskTags.isNotEmpty()) {
                    db.taskTagDao().clearRefs()
                    db.taskTagDao().clearAll()
                }
                if (file.tasks.isNotEmpty()) db.taskDao().clearAll()
                if (file.taskTypes.isNotEmpty()) db.taskTypeDao().clearAll()

                if (file.bills.isNotEmpty()) db.billDao().clearAll()
                if (file.billCategories.isNotEmpty()) db.billCategoryDao().clearAll()

                if (file.focusSessions.isNotEmpty()) db.focusSessionDao().clearAll()
                if (file.focusTags.isNotEmpty()) db.focusTagDao().clearAll()

                if (file.sleepRecords.isNotEmpty()) db.sleepRecordDao().clearAll()

                if (file.noteTagRefs.isNotEmpty() || file.noteTags.isNotEmpty()) {
                    db.noteTagDao().clearRefs()
                    db.noteTagDao().clearAll()
                }
                if (file.notes.isNotEmpty()) db.noteDao().clearAll()
                if (file.noteFolders.isNotEmpty()) db.noteFolderDao().clearAll()

                if (file.aiMessages.isNotEmpty()) db.aiChatDao().clearMessages()
                if (file.aiConversations.isNotEmpty()) db.aiChatDao().clearConversations()
            }

            // 分类 / 类型先落库
            file.taskTypes.forEach { db.taskTypeDao().upsert(it.toEntity()) }
            file.billCategories.forEach { db.billCategoryDao().upsert(it.toEntity()) }

            // ---- 课表段 ----
            if (hasTimetable) {
                // 学期：覆盖模式保留原 id；合并模式按名称匹配，已有则用现有 id
                val termIdMap = mutableMapOf<Long, Long>()
                val existingTerms = db.termDao().getAll()
                file.terms.forEach { t ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.termDao().insert(t.toEntity())
                        termIdMap[t.id] = t.id
                    } else {
                        val match = existingTerms.firstOrNull { it.name == t.name }
                        val newId = match?.id ?: db.termDao().insert(t.toEntity().copy(id = 0))
                        termIdMap[t.id] = newId
                    }
                }

                // 课程：合并模式按 (学期, 课名, 星期, 节次, 周次) 去重
                val existingCourses = db.courseDao().getAll()
                file.courses.forEach { c ->
                    val mappedTerm = termIdMap[c.termId]
                    if (mode == ImportMode.MERGE && mappedTerm != null) {
                        val dup = existingCourses.any {
                            it.termId == mappedTerm && it.name == c.name &&
                                it.dayOfWeek == c.dayOfWeek && it.startSection == c.startSection &&
                                it.endSection == c.endSection && it.weeks == c.weeks
                        }
                        if (dup) return@forEach
                        db.courseDao().insert(c.toEntity().copy(id = 0, termId = mappedTerm))
                    } else {
                        db.courseDao().insert(
                            c.toEntity().copy(termId = mappedTerm ?: c.termId)
                        )
                    }
                }

                // 节次时间：合并模式按 (学期, 小节) 去重
                // 兼容旧备份：v7 之前每条是「大节（含两小节）」。若最大序号 ≤6 且课程
                // 存在小节序号 >6，按 45+10 规则把每条拆成两小节后再导入。
                val restoredSectionTimes = expandBigSectionTimesIfNeeded(file.sectionTimes, file.courses)
                val existingSt = db.sectionTimeDao().getAll()
                restoredSectionTimes.forEach { s ->
                    val mappedTerm = termIdMap[s.termId] ?: s.termId
                    if (mode == ImportMode.MERGE &&
                        existingSt.any { it.termId == mappedTerm && it.sectionIndex == s.sectionIndex }
                    ) return@forEach
                    db.sectionTimeDao().insertAll(
                        listOf(s.toEntity().copy(id = 0, termId = mappedTerm))
                    )
                }

                // 考试 / 作业：合并模式按 (学期, 课名, 日期) 去重
                val existingExams = db.examDao().getAll()
                file.exams.forEach { e ->
                    val mappedTerm = termIdMap[e.termId] ?: e.termId
                    if (mode == ImportMode.MERGE &&
                        existingExams.any { it.termId == mappedTerm && it.courseName == e.courseName && it.date == e.date }
                    ) return@forEach
                    db.examDao().insert(e.toEntity().copy(id = 0, termId = mappedTerm))
                }
                val existingAssign = db.assignmentDao().getAll()
                file.assignments.forEach { a ->
                    val mappedTerm = termIdMap[a.termId] ?: a.termId
                    if (mode == ImportMode.MERGE &&
                        existingAssign.any { it.termId == mappedTerm && it.courseName == a.courseName && it.dueDate == a.dueDate }
                    ) return@forEach
                    db.assignmentDao().insert(a.toEntity().copy(id = 0, termId = mappedTerm))
                }

                // 提醒：引用课程 id，合并模式下无法稳定重映射 → 仅覆盖模式导入
                if (mode == ImportMode.OVERWRITE) {
                    file.reminders.forEach { r -> db.reminderDao().insert(r.toEntity()) }
                }
            }

            // ---- 任务段：标签按名称去重；任务合并模式按 (标题, 日期) 去重；关联重映射 ----
            if (hasTasks) {
                // 任务标签先入库并建立 oldId → newId 映射（参考专注标签的写法）
                val taskTagIdMap = mutableMapOf<Long, Long>()
                file.taskTags.forEach { t ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.taskTagDao().upsert(t.toEntity())
                        taskTagIdMap[t.id] = t.id
                    } else {
                        val match = db.taskTagDao().getAll().firstOrNull { it.name == t.name }
                        val newId = match?.id
                            ?: db.taskTagDao().upsert(t.toEntity().copy(id = 0))
                        taskTagIdMap[t.id] = newId
                    }
                }

                val existingTasks = db.taskDao().getAll()
                val taskIdMap = mutableMapOf<Long, Long>()
                file.tasks.forEach { t ->
                    val entity = t.toEntity()
                    if (mode == ImportMode.OVERWRITE) {
                        db.taskDao().upsert(entity)
                        taskIdMap[t.id] = t.id
                    } else {
                        val dup = existingTasks.firstOrNull {
                            it.title == entity.title && it.date == entity.date
                        }
                        // 重复任务映射到既有 id，使其标签关联仍能挂到正确任务上
                        taskIdMap[t.id] = dup?.id
                            ?: db.taskDao().upsert(entity.copy(id = 0))
                    }
                }

                // 关联经两端映射后插入；任一端映射不到（合并去重外的悬挂引用）则跳过
                val restoredTaskRefs = file.taskTagRefs.mapNotNull { r ->
                    val newTaskId = if (mode == ImportMode.OVERWRITE) r.taskId else taskIdMap[r.taskId]
                    val newTagId = if (mode == ImportMode.OVERWRITE) r.tagId else taskTagIdMap[r.tagId]
                    if (newTaskId == null || newTagId == null) return@mapNotNull null
                    TaskTagCrossRef(taskId = newTaskId, tagId = newTagId)
                }.distinct()
                if (restoredTaskRefs.isNotEmpty()) db.taskTagDao().insertRefs(restoredTaskRefs)
            }

            // ---- 账单段：合并模式按 (日期, 金额, 分类, 备注) 去重 ----
            if (hasBills) {
                val existingBills = db.billDao().getAll()
                file.bills.forEach { b ->
                    val entity = b.toEntity()
                    if (mode == ImportMode.MERGE &&
                        existingBills.any {
                            it.date == entity.date && it.amount == entity.amount &&
                                it.category == entity.category && it.note == entity.note
                        }
                    ) return@forEach
                    db.billDao().upsert(if (mode == ImportMode.OVERWRITE) entity else entity.copy(id = 0))
                }
            }

            // ---- 计时段：标签先入库并建立 id 映射，会话引用重映射 ----
            if (hasFocus) {
                val tagIdMap = mutableMapOf<Long, Long>()
                file.focusTags.forEach { t ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.focusTagDao().upsert(t.toEntity())
                        tagIdMap[t.id] = t.id
                    } else {
                        // 合并模式按 (名称, 颜色) 去重
                        val existing = db.focusTagDao().getAll()
                        val match = existing.firstOrNull { it.name == t.name && it.colorArgb == t.colorArgb }
                        val newId = match?.id
                            ?: db.focusTagDao().upsert(t.toEntity().copy(id = 0))
                        tagIdMap[t.id] = newId
                    }
                }
                // 合并模式按业务键（开始时刻 + 模式 + 计划/实际时长 + 状态 + 标签）去重，
                // 避免同一份备份反复合并导致统计翻倍
                val existingSessions =
                    if (mode == ImportMode.MERGE) db.focusSessionDao().getAll() else emptyList()
                file.focusSessions.forEach { s ->
                    val mappedTag = s.tagId?.let { tagIdMap[it] }
                    val entity = s.toEntity().copy(tagId = mappedTag)
                    if (mode == ImportMode.MERGE &&
                        existingSessions.any {
                            it.startedAtEpochMs == entity.startedAtEpochMs &&
                                it.mode == entity.mode &&
                                it.plannedSeconds == entity.plannedSeconds &&
                                it.focusedSeconds == entity.focusedSeconds &&
                                it.status == entity.status &&
                                it.tagId == entity.tagId
                        }
                    ) return@forEach
                    db.focusSessionDao().insert(
                        if (mode == ImportMode.OVERWRITE) entity else entity.copy(id = 0)
                    )
                }
            }

            // ---- 睡眠段：合并模式按 (类型, 入睡/醒来时刻, 时长) 去重后追加 ----
            if (hasSleep) {
                val existingSleep =
                    if (mode == ImportMode.MERGE) db.sleepRecordDao().getAll() else emptyList()
                file.sleepRecords.forEach { r ->
                    val entity = r.toEntity()
                    if (mode == ImportMode.MERGE &&
                        existingSleep.any {
                            it.kind == entity.kind &&
                                it.sleepAtEpochMs == entity.sleepAtEpochMs &&
                                it.wakeAtEpochMs == entity.wakeAtEpochMs &&
                                it.minutes == entity.minutes
                        }
                    ) return@forEach
                    db.sleepRecordDao().insert(
                        if (mode == ImportMode.OVERWRITE) entity else entity.copy(id = 0)
                    )
                }
            }

            // ---- 随身记段：文件夹 / 标签先入库建 id 映射，笔记与标签引用统一重映射 ----
            if (hasNotes) {
                val folderIdMap = mutableMapOf<Long, Long>()
                file.noteFolders.forEach { f ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.noteFolderDao().upsert(f.toEntity())
                        folderIdMap[f.id] = f.id
                    } else {
                        // 合并模式按名称去重
                        val match = db.noteFolderDao().getAll().firstOrNull { it.name == f.name }
                        val newId = match?.id
                            ?: db.noteFolderDao().upsert(f.toEntity().copy(id = 0))
                        folderIdMap[f.id] = newId
                    }
                }

                // 笔记标签按名称去重并建立映射
                val noteTagIdMap = mutableMapOf<Long, Long>()
                file.noteTags.forEach { t ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.noteTagDao().upsert(t.toEntity())
                        noteTagIdMap[t.id] = t.id
                    } else {
                        val match = db.noteTagDao().getAll().firstOrNull { it.name == t.name }
                        val newId = match?.id
                            ?: db.noteTagDao().upsert(t.toEntity().copy(id = 0))
                        noteTagIdMap[t.id] = newId
                    }
                }

                val existingNotes = if (mode == ImportMode.MERGE) db.noteDao().getAll() else emptyList()
                val noteIdMap = mutableMapOf<Long, Long>()
                file.notes.forEach { n ->
                    val mappedFolder = n.folderId?.let { folderIdMap[it] }
                    val entity = n.toEntity().copy(folderId = mappedFolder)
                    if (mode == ImportMode.OVERWRITE) {
                        db.noteDao().insert(entity)
                        noteIdMap[n.id] = n.id
                    } else {
                        val dup = existingNotes.firstOrNull {
                            it.folderId == entity.folderId && it.title == entity.title &&
                                it.content == entity.content && it.createdAt == entity.createdAt
                        }
                        // 重复笔记映射到既有 id，标签关联随之挂到既有笔记上
                        noteIdMap[n.id] = dup?.id
                            ?: db.noteDao().insert(entity.copy(id = 0))
                    }
                }

                // 标签关联经两端映射后插入；任一端映射不到则跳过
                val restoredNoteRefs = file.noteTagRefs.mapNotNull { r ->
                    val newNoteId = if (mode == ImportMode.OVERWRITE) r.noteId else noteIdMap[r.noteId]
                    val newTagId = if (mode == ImportMode.OVERWRITE) r.tagId else noteTagIdMap[r.tagId]
                    if (newNoteId == null || newTagId == null) return@mapNotNull null
                    NoteTagCrossRef(noteId = newNoteId, tagId = newTagId)
                }.distinct()
                if (restoredNoteRefs.isNotEmpty()) db.noteTagDao().insertRefs(restoredNoteRefs)
            }

            // ---- AI 段：会话按 (标题, 创建时间) 去重；消息整体重映射 conversationId，
            // 合并模式按目标会话现有顺序追加（orderIndex 重建） ----
            if (hasAi) {
                val existingConversations =
                    if (mode == ImportMode.MERGE) db.aiChatDao().getAllConversations() else emptyList()
                val conversationIdMap = mutableMapOf<Long, Long>()
                file.aiConversations.forEach { c ->
                    if (mode == ImportMode.OVERWRITE) {
                        db.aiChatDao().insertConversation(c.toEntity())
                        conversationIdMap[c.id] = c.id
                    } else {
                        val match = existingConversations.firstOrNull {
                            it.title == c.title && it.createdAt == c.createdAt
                        }
                        val newId = match?.id
                            ?: db.aiChatDao().insertConversation(c.toEntity().copy(id = 0))
                        conversationIdMap[c.id] = newId
                    }
                }
                file.aiMessages.forEach { m ->
                    val mappedConversation = conversationIdMap[m.conversationId] ?: return@forEach
                    if (mode == ImportMode.OVERWRITE) {
                        // 覆盖模式表已清空，保留原消息 id 与 orderIndex
                        db.aiChatDao().insertMessage(m.toEntity().copy(conversationId = mappedConversation))
                    } else {
                        val nextOrder = (db.aiChatDao().maxOrderIndex(mappedConversation) ?: -1) + 1
                        db.aiChatDao().insertMessage(
                            m.toEntity().copy(id = 0, conversationId = mappedConversation, orderIndex = nextOrder)
                        )
                    }
                }
            }
        }
    }

    /** 清空指定类别数据（设置页二次确认后调用）。 */
    suspend fun clearData(category: BackupCategory) {
        db.withTransaction {
            when (category) {
                BackupCategory.TIMETABLE -> clearTimetableTables()
                BackupCategory.TASKS -> {
                    clearTaskTables()
                    defaultTaskTypes().forEach { db.taskTypeDao().upsert(it) }
                }
                BackupCategory.BILLS -> {
                    clearBillTables()
                    defaultBillCategories().forEach { db.billCategoryDao().upsert(it) }
                }
                BackupCategory.FOCUS -> clearFocusTables()
                BackupCategory.SLEEP -> db.sleepRecordDao().clearAll()
                BackupCategory.NOTES -> clearNoteTables()
                BackupCategory.ALL -> {
                    clearTimetableTables()
                    clearTaskTables()
                    clearBillTables()
                    clearFocusTables()
                    db.sleepRecordDao().clearAll()
                    clearNoteTables()
                    clearAiTables()
                    defaultTaskTypes().forEach { db.taskTypeDao().upsert(it) }
                    defaultBillCategories().forEach { db.billCategoryDao().upsert(it) }
                }
            }
        }
    }

    /** 清空所有数据（保留兼容调用）。 */
    suspend fun clearAllData() = clearData(BackupCategory.ALL)

    private suspend fun clearTimetableTables() {
        db.reminderDao().clearAll()
        db.assignmentDao().clearAll()
        db.examDao().clearAll()
        db.sectionTimeDao().clearAll()
        db.courseDao().clearAll()
        db.termDao().getAll().forEach { db.termDao().delete(it) }
    }

    private suspend fun clearTaskTables() {
        // 先清标签关联，再清标签，最后清任务本体与类型
        db.taskTagDao().clearRefs()
        db.taskTagDao().clearAll()
        db.taskDao().clearAll()
        db.taskTypeDao().clearAll()
    }

    private suspend fun clearBillTables() {
        db.billDao().clearAll()
        db.billCategoryDao().clearAll()
    }

    private suspend fun clearFocusTables() {
        db.focusSessionDao().clearAll()
        db.focusTagDao().clearAll()
    }

    /** 随身记：先清标签关联，再清标签、笔记，最后删文件夹。 */
    private suspend fun clearNoteTables() {
        db.noteTagDao().clearRefs()
        db.noteTagDao().clearAll()
        db.noteDao().clearAll()
        db.noteFolderDao().clearAll()
    }

    /** AI：先清消息再清会话。 */
    private suspend fun clearAiTables() {
        db.aiChatDao().clearMessages()
        db.aiChatDao().clearConversations()
    }

    // ---------------- 映射 ----------------

    private fun Term.toDto() = BackupTerm(id, name, startDate.toString(), totalWeeks, isActive)
    private fun BackupTerm.toEntity() = Term(
        id = id, name = name, startDate = LocalDate.parse(startDate),
        totalWeeks = totalWeeks, isActive = isActive
    )

    private fun Course.toDto() = BackupCourse(
        id = id, termId = termId, name = name, teacher = teacher, location = location,
        campus = campus, classGroup = classGroup, classMembers = classMembers,
        assessment = assessment, remark = remark, hoursBreakdown = hoursBreakdown,
        weeklyHours = weeklyHours, totalHours = totalHours, credit = credit,
        category = category, colorHex = colorHex, weeks = weeks, dayOfWeek = dayOfWeek,
        startSection = startSection, endSection = endSection, note = note,
        kind = kind.name,
        teacherSegments = teacherSegments.map { BackupTeacherSegment(it.teacher, it.weeks, it.weeksRaw) },
        weeksRaw = weeksRaw, overrideDate = overrideDate?.toString(), overrideNote = overrideNote,
        createdAt = createdAt, updatedAt = updatedAt
    )

    private fun BackupCourse.toEntity() = Course(
        id = id, termId = termId, name = name, teacher = teacher, location = location,
        campus = campus, classGroup = classGroup, classMembers = classMembers,
        assessment = assessment, remark = remark, hoursBreakdown = hoursBreakdown,
        weeklyHours = weeklyHours, totalHours = totalHours, credit = credit,
        category = category, colorHex = colorHex, weeks = weeks, dayOfWeek = dayOfWeek,
        startSection = startSection, endSection = endSection, note = note,
        kind = runCatching { CourseKind.valueOf(kind) }.getOrDefault(CourseKind.GRID),
        teacherSegments = teacherSegments.map { TeacherSegment(it.teacher, it.weeks, it.weeksRaw) },
        weeksRaw = weeksRaw, overrideDate = overrideDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        overrideNote = overrideNote, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun SectionTime.toDto() = BackupSectionTime(id, termId, sectionIndex, startTime, endTime)
    private fun BackupSectionTime.toEntity() = SectionTime(id, termId, sectionIndex, startTime, endTime)

    /**
     * 旧备份兼容：v7 之前 section_times 每行是「大节」（含 2 小节）。
     * 检测：最大 sectionIndex ≤ 6 且存在课程小节序号 > 6。
     * 拆分规则与 MIGRATION_6_7 一致（≥90 分钟按 45 分钟两节，其余中点等分）。
     */
    private fun expandBigSectionTimesIfNeeded(
        rows: List<BackupSectionTime>,
        courses: List<BackupCourse>
    ): List<BackupSectionTime> {
        if (rows.isEmpty()) return rows
        val looksLikeBigBlocks = rows.maxOf { it.sectionIndex } <= 6
        val coursesUseSmallSections = courses.any { it.startSection > 6 || it.endSection > 6 }
        if (!looksLikeBigBlocks || !coursesUseSmallSections) return rows
        return rows.sortedWith(compareBy({ it.termId }, { it.sectionIndex })).flatMap { r ->
            val s = parseHmMinutes(r.startTime)
            val e = parseHmMinutes(r.endTime)
            if (s == null || e == null) {
                listOf(r)
            } else if (e - s >= 90) {
                listOf(
                    r.copy(id = 0, sectionIndex = r.sectionIndex * 2 - 1, startTime = fmtHm(s), endTime = fmtHm(s + 45)),
                    r.copy(id = 0, sectionIndex = r.sectionIndex * 2, startTime = fmtHm(e - 45), endTime = fmtHm(e))
                )
            } else {
                val mid = (s + e) / 2
                listOf(
                    r.copy(id = 0, sectionIndex = r.sectionIndex * 2 - 1, startTime = fmtHm(s), endTime = fmtHm(mid)),
                    r.copy(id = 0, sectionIndex = r.sectionIndex * 2, startTime = fmtHm(mid), endTime = fmtHm(e))
                )
            }
        }
    }

    private fun parseHmMinutes(hm: String?): Int? {
        if (hm == null) return null
        val parts = hm.trim().split(':', '：')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].take(2).toIntOrNull() ?: return null
        return h * 60 + m
    }

    private fun fmtHm(value: Int): String = "%02d:%02d".format(value / 60, value % 60)

    private fun Reminder.toDto() = BackupReminder(id, courseId, minutesBefore, enabled)
    private fun BackupReminder.toEntity() = Reminder(id, courseId, minutesBefore, enabled)

    private fun Exam.toDto() = BackupExam(id, termId, courseName, date, time, location, note)
    private fun BackupExam.toEntity() = Exam(id, termId, courseName, date, time, location, note)

    private fun Assignment.toDto() = BackupAssignment(id, termId, courseName, title, dueDate, note)
    private fun BackupAssignment.toEntity() = Assignment(id, termId, courseName, title, dueDate, note)

    private fun TaskType.toDto() = BackupTaskType(name, colorHex, sortOrder)
    private fun BackupTaskType.toEntity() = TaskType(name = name, colorHex = colorHex, sortOrder = sortOrder)

    private fun Task.toDto() = BackupTask(
        id = id, title = title, description = description, type = type,
        date = date, startTime = startTime, endTime = endTime,
        completed = completed, reminderMinutes = reminderMinutes,
        ruleJson = ruleJson, completedDates = completedDates, createdAt = createdAt
    )
    private fun BackupTask.toEntity() = Task(
        id = id, title = title, description = description, type = type,
        date = date, startTime = startTime, endTime = endTime,
        completed = completed, reminderMinutes = reminderMinutes,
        ruleJson = ruleJson, completedDates = completedDates, createdAt = createdAt
    )

    private fun BillCategory.toDto() = BackupBillCategory(name, type.name, colorHex, sortOrder)
    private fun BackupBillCategory.toEntity() = BillCategory(
        name = name,
        type = runCatching { BillType.valueOf(type) }.getOrDefault(BillType.EXPENSE),
        colorHex = colorHex,
        sortOrder = sortOrder
    )

    private fun Bill.toDto() = BackupBill(
        id = id, type = type.name, amount = amount, category = category,
        date = date, note = note, createdAt = createdAt
    )
    private fun BackupBill.toEntity() = Bill(
        id = id,
        type = runCatching { BillType.valueOf(type) }.getOrDefault(BillType.EXPENSE),
        amount = amount, category = category, date = date, note = note, createdAt = createdAt
    )

    private fun FocusTag.toDto() = BackupFocusTag(id, name, colorArgb, sortOrder)
    private fun BackupFocusTag.toEntity() = FocusTag(id, name, colorArgb, sortOrder)

    private fun FocusSession.toDto() = BackupFocusSession(
        id = id, tagId = tagId, mode = mode, plannedSeconds = plannedSeconds,
        startedAtEpochMs = startedAtEpochMs, endedAtEpochMs = endedAtEpochMs,
        focusedSeconds = focusedSeconds, status = status, createdAt = createdAt
    )
    private fun BackupFocusSession.toEntity() = FocusSession(
        id = id,
        tagId = tagId,
        mode = runCatching { FocusMode.valueOf(mode) }.getOrDefault(FocusMode.STOPWATCH).name,
        plannedSeconds = plannedSeconds,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        focusedSeconds = focusedSeconds,
        status = runCatching { FocusStatus.valueOf(status) }.getOrDefault(FocusStatus.COMPLETED).name,
        createdAt = createdAt
    )

    private fun SleepRecord.toDto() = BackupSleepRecord(
        id = id, kind = kind, sleepAtEpochMs = sleepAtEpochMs,
        wakeAtEpochMs = wakeAtEpochMs, minutes = minutes, note = note
    )
    private fun BackupSleepRecord.toEntity() = SleepRecord(
        id = id,
        kind = runCatching { SleepKind.valueOf(kind) }.getOrDefault(SleepKind.NIGHT).name,
        sleepAtEpochMs = sleepAtEpochMs,
        wakeAtEpochMs = wakeAtEpochMs,
        minutes = minutes,
        note = note
    )

    private fun NoteFolder.toDto() = BackupNoteFolder(id, name, sortOrder, createdAt)
    private fun BackupNoteFolder.toEntity() = NoteFolder(id, name, sortOrder, createdAt)

    private fun Note.toDto() = BackupNote(
        id = id, folderId = folderId, title = title, content = content,
        createdAt = createdAt, updatedAt = updatedAt, pinned = pinned, sortOrder = sortOrder,
        fontKey = fontKey, fontSizeSp = fontSizeSp
    )
    private fun BackupNote.toEntity() = Note(
        id = id, folderId = folderId, title = title, content = content,
        createdAt = createdAt, updatedAt = updatedAt, pinned = pinned, sortOrder = sortOrder,
        fontKey = fontKey, fontSizeSp = fontSizeSp
    )

    private fun NoteTag.toDto() = BackupNoteTag(id, name, colorArgb, sortOrder, createdAt)
    private fun BackupNoteTag.toEntity() = NoteTag(id, name, colorArgb, sortOrder, createdAt)

    private fun NoteTagCrossRef.toDto() = BackupNoteTagRef(noteId, tagId)
    private fun BackupNoteTagRef.toEntity() = NoteTagCrossRef(noteId, tagId)

    private fun TaskTag.toDto() = BackupTaskTag(id, name, colorHex, sortOrder, createdAt)
    private fun BackupTaskTag.toEntity() = TaskTag(id, name, colorHex, sortOrder, createdAt)

    private fun TaskTagCrossRef.toDto() = BackupTaskTagRef(taskId, tagId)
    private fun BackupTaskTagRef.toEntity() = TaskTagCrossRef(taskId, tagId)

    private fun AiConversation.toDto() = BackupAiConversation(id, title, createdAt, updatedAt)
    private fun BackupAiConversation.toEntity() = AiConversation(id, title, createdAt, updatedAt)

    private fun AiMessage.toDto() = BackupAiMessage(
        id = id, conversationId = conversationId, role = role, content = content,
        toolCallId = toolCallId, toolName = toolName, toolCallsJson = toolCallsJson,
        createdAt = createdAt, orderIndex = orderIndex
    )
    private fun BackupAiMessage.toEntity() = AiMessage(
        id = id, conversationId = conversationId, role = role, content = content,
        toolCallId = toolCallId, toolName = toolName, toolCallsJson = toolCallsJson,
        createdAt = createdAt, orderIndex = orderIndex
    )

    private fun defaultTaskTypes() = listOf(
        TaskType("考试", "#F44336", 0),
        TaskType("作业", "#FF9800", 1),
        TaskType("其他", "#2196F3", 2)
    )

    private fun defaultBillCategories() = buildList {
        listOf(
            Triple("餐饮", "#FF7043", 0),
            Triple("交通", "#42A5F5", 1),
            Triple("购物", "#EC407A", 2),
            Triple("学习", "#FFA726", 3),
            Triple("娱乐", "#AB47BC", 4),
            Triple("医疗", "#26C6DA", 5),
            Triple("日用品", "#9CCC65", 6),
            Triple("水电", "#5C6BC0", 7),
            Triple("其他支出", "#78909C", 8)
        ).forEach { (name, color, order) ->
            add(BillCategory(name, BillType.EXPENSE, color, order))
        }
        listOf(
            Triple("工资", "#66BB6A", 0),
            Triple("兼职", "#26A69A", 1),
            Triple("奖学金", "#5C6BC0", 2),
            Triple("其他收入", "#78909C", 3)
        ).forEach { (name, color, order) ->
            add(BillCategory(name, BillType.INCOME, color, order))
        }
    }
}
