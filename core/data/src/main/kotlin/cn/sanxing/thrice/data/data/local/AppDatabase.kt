package cn.sanxing.thrice.data.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.sanxing.thrice.data.domain.model.Assignment
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.Exam
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusTag
import cn.sanxing.thrice.data.domain.model.AiConversation
import cn.sanxing.thrice.data.domain.model.AiMessage
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.data.domain.model.NoteTagCrossRef
import cn.sanxing.thrice.data.domain.model.Reminder
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.model.TaskTag
import cn.sanxing.thrice.data.domain.model.TaskTagCrossRef
import cn.sanxing.thrice.data.domain.model.TaskType
import cn.sanxing.thrice.data.domain.model.Term

@Database(
    entities = [Term::class, Course::class, SectionTime::class, Reminder::class, Exam::class, Assignment::class, Task::class, TaskType::class, Bill::class, BillCategory::class, FocusTag::class, FocusSession::class, SleepRecord::class, NoteFolder::class, Note::class, NoteTag::class, NoteTagCrossRef::class, TaskTag::class, TaskTagCrossRef::class, AiConversation::class, AiMessage::class],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun termDao(): TermDao
    abstract fun sectionTimeDao(): SectionTimeDao
    abstract fun reminderDao(): ReminderDao
    abstract fun examDao(): ExamDao
    abstract fun assignmentDao(): AssignmentDao
    abstract fun taskDao(): TaskDao
    abstract fun taskTypeDao(): TaskTypeDao
    abstract fun billDao(): BillDao
    abstract fun billCategoryDao(): BillCategoryDao
    abstract fun focusTagDao(): FocusTagDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun sleepRecordDao(): SleepRecordDao
    abstract fun noteFolderDao(): NoteFolderDao
    abstract fun noteDao(): NoteDao
    abstract fun noteTagDao(): NoteTagDao
    abstract fun taskTagDao(): TaskTagDao
    abstract fun aiChatDao(): AiChatDao

    companion object {
        /** v1 → v2：courses 增加 overrideDate / overrideNote（调课/停课/补课/临时地点）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN overrideDate TEXT")
                db.execSQL("ALTER TABLE courses ADD COLUMN overrideNote TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增 tasks 表。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        type TEXT NOT NULL DEFAULT 'OTHER',
                        date TEXT,
                        startTime TEXT,
                        endTime TEXT,
                        completed INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** v3 → v4：新增 task_types 表。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_types (
                        name TEXT PRIMARY KEY NOT NULL,
                        colorHex TEXT NOT NULL DEFAULT '#2196F3',
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 插入默认任务类型
                db.execSQL("INSERT INTO task_types (name, colorHex, sortOrder) VALUES ('考试', '#F44336', 0)")
                db.execSQL("INSERT INTO task_types (name, colorHex, sortOrder) VALUES ('作业', '#FF9800', 1)")
                db.execSQL("INSERT INTO task_types (name, colorHex, sortOrder) VALUES ('其他', '#2196F3', 2)")
            }
        }

        /** v4 → v5：新增账单 bills / bill_categories 表，并写入默认分类。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bills (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'EXPENSE',
                        amount REAL NOT NULL,
                        category TEXT NOT NULL,
                        date TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bill_categories (
                        name TEXT PRIMARY KEY NOT NULL,
                        type TEXT NOT NULL DEFAULT 'EXPENSE',
                        colorHex TEXT NOT NULL DEFAULT '#FF7043',
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 默认支出 / 收入分类
                seedDefaultBillCategories(db)
            }
        }

        /** v5 → v6：tasks 增加 reminderMinutes（任务闹钟提前分钟数，NULL = 不提醒）。 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderMinutes INTEGER")
            }
        }

        /**
         * v6 → v7：节次配置从「大节（含两小节，一条时间）」改为「小节（每节独立时间）」。
         * 表结构不变，仅转换数据：旧大节 S..E 拆成两条小节 2i-1 / 2i；
         * 时长 ≥90 分钟按「45 分钟一节」拆（标准 100 分钟块自然得到 10 分钟课间），
         * 否则中点等分。课程的 startSection/endSection 原本就是小节序号，无需改动。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                data class OldRow(val termId: Long, val idx: Int, val startMin: Int, val endMin: Int)

                val oldRows = ArrayList<OldRow>()
                db.query("SELECT termId, sectionIndex, startTime, endTime FROM section_times").use { c ->
                    val termCol = c.getColumnIndexOrThrow("termId")
                    val idxCol = c.getColumnIndexOrThrow("sectionIndex")
                    val sCol = c.getColumnIndexOrThrow("startTime")
                    val eCol = c.getColumnIndexOrThrow("endTime")
                    while (c.moveToNext()) {
                        val s = parseMinutes(c.getString(sCol)) ?: continue
                        val e = parseMinutes(c.getString(eCol)) ?: continue
                        oldRows.add(OldRow(c.getLong(termCol), c.getInt(idxCol), s, e))
                    }
                }

                db.execSQL("DELETE FROM section_times")
                // 旧库可能存在重复的 (termId, sectionIndex) 行，展开后会撞唯一索引 / 产生重复小节；
                // 迁移前按键去重（同键保留最后一行）。
                val dedupedRows = oldRows.associateBy { it.termId to it.idx }.values
                dedupedRows.sortedWith(compareBy({ it.termId }, { it.idx })).forEach { r ->
                    val (p1s, p1e, p2s, p2e) = if (r.endMin - r.startMin >= 90) {
                        intArrayOf(r.startMin, r.startMin + 45, r.endMin - 45, r.endMin)
                    } else {
                        val mid = (r.startMin + r.endMin) / 2
                        intArrayOf(r.startMin, mid, mid, r.endMin)
                    }
                    db.execSQL(
                        "INSERT INTO section_times (id, termId, sectionIndex, startTime, endTime) " +
                            "VALUES (NULL, ?, ?, ?, ?)",
                        arrayOf<Any>(r.termId, r.idx * 2 - 1, formatMinutes(p1s), formatMinutes(p1e))
                    )
                    db.execSQL(
                        "INSERT INTO section_times (id, termId, sectionIndex, startTime, endTime) " +
                            "VALUES (NULL, ?, ?, ?, ?)",
                        arrayOf<Any>(r.termId, r.idx * 2, formatMinutes(p2s), formatMinutes(p2e))
                    )
                }
            }

            private fun parseMinutes(hm: String?): Int? {
                if (hm == null) return null
                val parts = hm.trim().split(':', '：')
                if (parts.size < 2) return null
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].take(2).toIntOrNull() ?: return null
                return h * 60 + m
            }

            private fun formatMinutes(value: Int): String =
                "%02d:%02d".format(value / 60, value % 60)
        }

        /**
         * v7 → v8：tasks 增加重复规则与分次完成记录。
         * ruleJson 为 NULL 表示不重复（行为与旧版完全一致）；
         * completedDates 为逗号分隔的 yyyy-MM-dd，默认空串。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN ruleJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN completedDates TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v8 → v9：新增计时（专注标签 / 会话）与睡眠记录三张表。 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS focus_tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS focus_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tagId INTEGER,
                        mode TEXT NOT NULL,
                        plannedSeconds INTEGER NOT NULL,
                        startedAtEpochMs INTEGER NOT NULL,
                        endedAtEpochMs INTEGER,
                        focusedSeconds INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sleep_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        sleepAtEpochMs INTEGER NOT NULL,
                        wakeAtEpochMs INTEGER,
                        minutes INTEGER,
                        note TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v9 → v10：新增随身记（笔记文件夹 / 笔记）两张表。 */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        folderId INTEGER,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        pinned INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        fontKey TEXT,
                        fontSizeSp INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_folderId` ON notes (`folderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_updatedAt` ON notes (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_pinned` ON notes (`pinned`)")
            }
        }

        /**
         * v10 → v11：新增笔记多标签、任务多标签（多对多关联）与 AI 对话 / 消息持久化。
         * 所有新表均无外键；实体没有声明列默认值，故建表 SQL 也不写 DEFAULT。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 笔记标签 ----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_tag_cross_ref (
                        noteId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(noteId, tagId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_tags_name` ON note_tags (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tag_cross_ref_tagId` ON note_tag_cross_ref (`tagId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tag_cross_ref_noteId` ON note_tag_cross_ref (`noteId`)")

                // ---- 任务标签 ----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorHex TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task_tag_cross_ref (
                        taskId INTEGER NOT NULL,
                        tagId INTEGER NOT NULL,
                        PRIMARY KEY(taskId, tagId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_task_tags_name` ON task_tags (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_tag_cross_ref_tagId` ON task_tag_cross_ref (`tagId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_tag_cross_ref_taskId` ON task_tag_cross_ref (`taskId`)")

                // ---- AI 对话 / 消息 ----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        toolCallId TEXT,
                        toolName TEXT,
                        toolCallsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_conversationId` ON ai_messages (`conversationId`)")
            }
        }

        /** 默认账单分类（迁移与全新安装的 onCreate 回调共用）。 */
        fun seedDefaultBillCategories(db: SupportSQLiteDatabase) {
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
                db.execSQL(
                    "INSERT OR IGNORE INTO bill_categories (name, type, colorHex, sortOrder) VALUES (?, 'EXPENSE', ?, ?)",
                    arrayOf<Any>(name, color, order)
                )
            }
            listOf(
                Triple("工资", "#66BB6A", 0),
                Triple("兼职", "#26A69A", 1),
                Triple("奖学金", "#5C6BC0", 2),
                Triple("其他收入", "#78909C", 3)
            ).forEach { (name, color, order) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO bill_categories (name, type, colorHex, sortOrder) VALUES (?, 'INCOME', ?, ?)",
                    arrayOf<Any>(name, color, order)
                )
            }
        }
    }
}
