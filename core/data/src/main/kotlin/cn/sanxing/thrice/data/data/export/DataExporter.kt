package cn.sanxing.thrice.data.data.export

import android.content.Context
import cn.sanxing.thrice.data.data.local.AppDatabase
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.data.domain.model.NoteTagCrossRef
import cn.sanxing.thrice.data.domain.model.TaskTag
import cn.sanxing.thrice.data.domain.model.TaskTagCrossRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * 全部用户数据的可读格式导出：
 * - [exportTablesZip]：CSV 表格（任务 / 账单 / 专注 / 睡眠），UTF-8 BOM，Excel 可直接打开；
 * - [exportNotesZip]：随身记 Markdown（每篇一个 .md + notes_index.json）；
 * - [exportJsonZip]：可读 JSON（schedule.json 课表数据 + settings.json 用户设置）；
 * - [exportAllZip]：以上全部 + README.txt。
 *
 * 产物位于 cacheDir/export/<时间戳>/，通过应用 FileProvider（authority =
 * "<包名>.fileprovider"，cache-path "export/"）分享。
 *
 * 安全：settings.json 只枚举 [SettingsRepository] 公开的用户设置键，DataStore 中
 * 所有 ai_ 前缀键（含 ai_api_key）不会被读取或写入。
 */
class DataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository,
    // 笔记文件夹走 Repository 读取（与 UI 语义一致）；其余实体直接用 DAO 快照
    private val notesRepository: NotesRepository
) {

    /** 导出四张 CSV 表格 → tables.zip。 */
    suspend fun exportTablesZip(): File = withContext(Dispatchers.IO) {
        val work = newWorkDir()
        writeCsvFiles(File(work, "csv"))
        File(work, "tables.zip").also { zipDirectory(work, it) }
    }

    /** 导出随身记 Markdown → notes_md.zip。 */
    suspend fun exportNotesZip(): File = withContext(Dispatchers.IO) {
        val work = newWorkDir()
        writeNotesFiles(work)
        File(work, "notes_md.zip").also { zipDirectory(work, it) }
    }

    /** 导出可读 JSON（课表 + 设置）→ json_data.zip。 */
    suspend fun exportJsonZip(): File = withContext(Dispatchers.IO) {
        val work = newWorkDir()
        writeJsonFiles(work)
        File(work, "json_data.zip").also { zipDirectory(work, it) }
    }

    /** 导出全部可读数据 → thrice_export_yyyyMMdd_HHmm.zip。 */
    suspend fun exportAllZip(): File = withContext(Dispatchers.IO) {
        val work = newWorkDir()
        // 笔记 / 任务标签四张 CSV 仅在全量导出中携带，单表 tables.zip 不含
        writeCsvFiles(File(work, "csv"), includeTagTables = true)
        writeNotesFiles(work)
        writeJsonFiles(work)
        File(work, "README.txt").writeText(buildReadme(), Charsets.UTF_8)
        File(work, "thrice_export_${work.name}.zip").also { zipDirectory(work, it) }
    }

    // ------------------------------------------------------------ 内部实现

    private fun newWorkDir(): File {
        val root = File(context.cacheDir, "export").apply { mkdirs() }
        // 秒级时间戳目录；同一秒内的重复导出用序号避让
        val base = ExportFormats.timestampDirName()
        var dir = File(root, base)
        var n = 1
        while (dir.exists()) dir = File(root, "${base}_$n")
        dir.mkdirs()
        return dir
    }

    private suspend fun writeCsvFiles(csvDir: File, includeTagTables: Boolean = false) {
        ExportFormats.writeTasksCsv(File(csvDir, "tasks.csv"), db.taskDao().getAll())
        ExportFormats.writeBillsCsv(File(csvDir, "bills.csv"), db.billDao().getAll())
        ExportFormats.writeFocusSessionsCsv(
            File(csvDir, "focus_sessions.csv"),
            db.focusSessionDao().getAll(),
            db.focusTagDao().getAll()
        )
        ExportFormats.writeSleepRecordsCsv(
            File(csvDir, "sleep_records.csv"),
            db.sleepRecordDao().getAll(),
            settingsRepository.sleepNightGoalMinutes.first(),
            settingsRepository.sleepNapGoalMinutes.first()
        )
        if (includeTagTables) writeTagCsvFiles(csvDir)
    }

    /**
     * 笔记 / 任务标签与关联四张 CSV（列与实体字段一一对应）。
     * 仅全量导出 zip 携带；关联表只放双方 id，名称可经标签表按 id 查得。
     */
    private suspend fun writeTagCsvFiles(csvDir: File) {
        writeNoteTagsCsv(File(csvDir, "note_tags.csv"), db.noteTagDao().getAll())
        writeNoteTagRefsCsv(File(csvDir, "note_tag_refs.csv"), db.noteTagDao().getAllRefs())
        writeTaskTagsCsv(File(csvDir, "task_tags.csv"), db.taskTagDao().getAll())
        writeTaskTagRefsCsv(File(csvDir, "task_tag_refs.csv"), db.taskTagDao().getAllRefs())
    }

    private fun writeNoteTagsCsv(file: File, tags: List<NoteTag>) = ExportFormats.run {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("ID", "名称", "颜色ARGB", "排序", "创建时间"))
            tags.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { t ->
                w.writeCsvRow(
                    listOf(t.id, t.name, t.colorArgb, t.sortOrder, formatMillis(t.createdAt))
                )
            }
        }
    }

    private fun writeNoteTagRefsCsv(file: File, refs: List<NoteTagCrossRef>) = ExportFormats.run {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("笔记ID", "标签ID"))
            refs.sortedWith(compareBy({ it.noteId }, { it.tagId })).forEach { r ->
                w.writeCsvRow(listOf(r.noteId, r.tagId))
            }
        }
    }

    private fun writeTaskTagsCsv(file: File, tags: List<TaskTag>) = ExportFormats.run {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("ID", "名称", "颜色", "排序", "创建时间"))
            tags.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { t ->
                w.writeCsvRow(
                    listOf(t.id, t.name, t.colorHex, t.sortOrder, formatMillis(t.createdAt))
                )
            }
        }
    }

    private fun writeTaskTagRefsCsv(file: File, refs: List<TaskTagCrossRef>) = ExportFormats.run {
        csvWriter(file).use { w ->
            w.writeCsvRow(listOf("任务ID", "标签ID"))
            refs.sortedWith(compareBy({ it.taskId }, { it.tagId })).forEach { r ->
                w.writeCsvRow(listOf(r.taskId, r.tagId))
            }
        }
    }

    /** 在 [work] 下生成 notes 目录内的 .md 文件与 notes_index.json；空文件夹也会进入索引。 */
    private suspend fun writeNotesFiles(work: File) {
        val folders: List<NoteFolder> = notesRepository.getFolders()
        val notes: List<Note> = db.noteDao().getAll()
        val folderNameById = folders.associate { it.id to it.name }

        val notesDir = File(work, "notes").apply { mkdirs() }
        val fileNameById = HashMap<Long, String>(notes.size)
        notes.forEach { note ->
            val name = ExportFormats.noteFileName(note)
            fileNameById[note.id] = name
            val folderName = note.folderId?.let { folderNameById[it] }
            File(notesDir, name).writeText(
                ExportFormats.buildNoteMarkdown(note, folderName),
                Charsets.UTF_8
            )
        }

        val exportedAt = ExportFormats.formatMillis(System.currentTimeMillis())
        val index = ExportFormats.buildNotesIndex(
            notes = notes,
            folders = folders,
            folderNameById = folderNameById,
            fileNameById = fileNameById,
            exportedAt = exportedAt
        )
        File(work, "notes_index.json").writeText(PRETTY_JSON.encodeToString(index), Charsets.UTF_8)
    }

    private suspend fun writeJsonFiles(work: File) {
        val exportedAt = ExportFormats.formatMillis(System.currentTimeMillis())
        val schedule = ExportFormats.buildScheduleJson(
            terms = db.termDao().getAll(),
            courses = db.courseDao().getAll(),
            sectionTimes = db.sectionTimeDao().getAll(),
            assignments = db.assignmentDao().getAll(),
            exams = db.examDao().getAll(),
            reminders = db.reminderDao().getAll(),
            exportedAt = exportedAt
        )
        File(work, "schedule.json").writeText(
            PRETTY_JSON.encodeToString(schedule),
            Charsets.UTF_8
        )

        val settings = ExportFormats.buildSettingsJson(settingsRepository, exportedAt)
        File(work, "settings.json").writeText(
            PRETTY_JSON.encodeToString(settings),
            Charsets.UTF_8
        )
    }

    private fun buildReadme(): String = """
        叁省手账 · 数据导出说明
        ========================
        导出时间：${ExportFormats.formatMillis(System.currentTimeMillis())}
        所有文本文件均为 UTF-8 编码（CSV 带 BOM，可用 Excel / WPS 直接打开）；
        时间格式为 yyyy-MM-dd HH:mm（手机本地时区），枚举值已翻译为中文。

        文件清单：
        - csv/tasks.csv          任务（标题、备注、类型、截止时间、完成、创建时间）
        - csv/bills.csv          账单（时间、金额、类别、收支、备注、地点）
        - csv/focus_sessions.csv 专注记录（开始、结束、时长、计划时长、状态、标签、计时方式）
        - csv/sleep_records.csv  睡眠记录（日期、夜睡/午休、时长、目标时长、是否达成、开始时刻）
        - csv/note_tags.csv      笔记标签（ID、名称、颜色ARGB、排序、创建时间，仅全量导出）
        - csv/note_tag_refs.csv  笔记-标签关联（笔记ID、标签ID，仅全量导出）
        - csv/task_tags.csv      任务标签（ID、名称、颜色、排序、创建时间，仅全量导出）
        - csv/task_tag_refs.csv  任务-标签关联（任务ID、标签ID，仅全量导出）
        - notes/                 随身记正文，每篇一个 Markdown（.md）文件
        - notes_index.json       随身记索引（文件夹、标题、置顶、对应 .md 文件等）
        - schedule.json          课表数据（学期、课程、节次时间、作业、考试、提醒）
        - settings.json          应用设置（外观、提醒、底部导航、专注、睡眠偏好）

        安全说明：本导出不包含任何 AI 配置（apiKey 等 ai_ 前缀设置始终留在本机）。

        ---------------- ENGLISH ----------------
        Thrice data export. All text files are UTF-8 (CSV files include a BOM and
        open directly in Excel/WPS). Times use yyyy-MM-dd HH:mm in the device time
        zone, and enum values are translated into Chinese. AI settings (including
        the API key) are never exported.
    """.trimIndent() + "\n"

    /**
     * 将 [rootDir] 内全部文件（相对路径保留目录结构）打包到 [outZip]。
     * 使用 JDK 自带 [ZipOutputStream]，不引入额外依赖。
     */
    private fun zipDirectory(rootDir: File, outZip: File) {
        outZip.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outZip)), Charsets.UTF_8).use { zip ->
            rootDir.walkTopDown()
                .filter { it.isFile }
                .filter { it.absolutePath != outZip.absolutePath }
                .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
                .forEach { file ->
                    val entryName = file.relativeTo(rootDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private companion object {
        val PRETTY_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
