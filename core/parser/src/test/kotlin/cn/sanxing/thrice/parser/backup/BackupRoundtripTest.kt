package cn.sanxing.thrice.parser.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 备份 JSON 往返一致性测试（验收标准）：
 * Course.weeks 的 Set<Int>、Term.startDate 的 LocalDate、学分 Double 逐项相等。
 */
class BackupRoundtripTest {

    private fun sample(): BackupFile {
        val term = BackupTerm(
            id = 1, name = "2026-2027学年第1学期",
            startDate = "2026-08-31", totalWeeks = 20, isActive = true
        )
        val course = BackupCourse(
            id = 7, termId = 1, name = "线性代数A", teacher = "张平", location = "主教学楼306",
            credit = 3.0, colorHex = "#1E88E5",
            weeks = setOf(6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 19),  // 乱序 + 不连续
            dayOfWeek = 5, startSection = 1, endSection = 2,
            kind = "GRID",
            teacherSegments = listOf(BackupTeacherSegment("张平", setOf(6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 19))),
            overrideDate = "2026-10-20", overrideNote = "临时调课",
            createdAt = 1727000000000L, updatedAt = 1727100000000L
        )
        return BackupFile(
            schemaVersion = BackupSchema.CURRENT_VERSION,
            exportedAt = LocalDateTime.of(2026, 9, 18, 12, 30).toString(),
            terms = listOf(term),
            courses = listOf(course),
            sectionTimes = listOf(
                BackupSectionTime(id = 1, termId = 1, sectionIndex = 1, startTime = "08:00", endTime = "09:40")
            ),
            reminders = listOf(BackupReminder(id = 1, courseId = 7, minutesBefore = 15, enabled = true)),
            exams = listOf(BackupExam(id = 1, termId = 1, courseName = "线性代数A", date = "2027-01-10", time = "09:00")),
            assignments = listOf(BackupAssignment(id = 1, termId = 1, courseName = "线性代数A", title = "作业1", dueDate = "2026-10-01"))
        )
    }

    @Test
    fun `周次Set往返逐元素相等`() {
        val json = BackupCodec.encode(sample())
        val back = (BackupCodec.decode(json) as BackupParseResult.Ok).file
        assertEquals(sample().courses.single().weeks, back.courses.single().weeks)
        assertEquals(setOf(6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 19), back.courses.single().weeks)
    }

    @Test
    fun `LocalDate与Double逐项相等`() {
        val json = BackupCodec.encode(sample())
        val back = (BackupCodec.decode(json) as BackupParseResult.Ok).file
        assertEquals(LocalDate.of(2026, 8, 31), LocalDate.parse(back.terms.single().startDate))
        assertEquals(3.0, back.courses.single().credit)
        assertEquals("2026-10-20", back.courses.single().overrideDate)
    }

    @Test
    fun `所有表往返条数一致`() {
        val json = BackupCodec.encode(sample())
        val back = (BackupCodec.decode(json) as BackupParseResult.Ok).file
        assertEquals(1, back.terms.size)
        assertEquals(1, back.courses.size)
        assertEquals(1, back.sectionTimes.size)
        assertEquals(1, back.reminders.size)
        assertEquals(1, back.exams.size)
        assertEquals(1, back.assignments.size)
        assertEquals(BackupSchema.CURRENT_VERSION, back.schemaVersion)
        assertTrue(back.exportedAt.isNotBlank())
    }

    @Test
    fun `非法JSON返回错误`() {
        val r = BackupCodec.decode("{ not json")
        assertTrue(r is BackupParseResult.Error)
    }

    @Test
    fun `校验能抓住周次越界`() {
        val bad = sample().copy(
            courses = listOf(sample().courses.single().copy(weeks = setOf(0, 21)))
        )
        val errors = BackupCodec.validate(bad)
        assertTrue(errors.any { it.contains("超出") })
    }

    @Test
    fun `校验能抓住悬空外键`() {
        val bad = sample().copy(
            reminders = listOf(BackupReminder(courseId = 999, minutesBefore = 15, enabled = true))
        )
        assertTrue(BackupCodec.validate(bad).isNotEmpty())
    }

    @Test
    fun `合法样本校验通过`() {
        assertTrue(BackupCodec.validate(sample()).isEmpty())
    }

    @Test
    fun `v4随身记文件夹与笔记往返一致`() {
        val folder = BackupNoteFolder(id = 2, name = "读书", sortOrder = 0, createdAt = 1727000000000L)
        val noted = BackupNote(
            id = 10, folderId = 2, title = "人月神话", content = "# 心得\n没有银弹",
            createdAt = 1727000000000L, updatedAt = 1727100000000L,
            pinned = true, sortOrder = 0, fontKey = "07", fontSizeSp = 18
        )
        val loose = BackupNote(
            id = 11, folderId = null, title = "随记", content = "",
            createdAt = 1727200000000L, updatedAt = 1727200000000L
        )
        val data = sample().copy(
            noteFolders = listOf(folder),
            notes = listOf(noted, loose)
        )
        val back = (BackupCodec.decode(BackupCodec.encode(data)) as BackupParseResult.Ok).file

        assertEquals(1, back.noteFolders.size)
        assertEquals("读书", back.noteFolders.single().name)
        assertEquals(2, back.notes.size)
        val first = back.notes.first { it.id == 10L }
        assertEquals(2L, first.folderId)
        assertTrue(first.pinned)
        assertEquals("07", first.fontKey)
        assertEquals(18, first.fontSizeSp)
        assertNull(back.notes.first { it.id == 11L }.folderId)
        assertNull(back.notes.first { it.id == 11L }.fontKey)
        assertTrue(BackupCodec.validate(back).isEmpty())
    }

    @Test
    fun `v3旧备份缺省笔记字段可解析为空`() {
        val oldJson = """
            {
              "schemaVersion": 3,
              "app": "Sanxing",
              "exportedAt": "2026-09-18T12:30:00",
              "terms": [],
              "courses": [],
              "sectionTimes": [],
              "reminders": [],
              "exams": [],
              "assignments": [],
              "taskTypes": [],
              "tasks": [],
              "billCategories": [],
              "bills": [],
              "focusTags": [],
              "focusSessions": [],
              "sleepRecords": []
            }
        """.trimIndent()
        val result = BackupCodec.decode(oldJson)
        assertTrue(result is BackupParseResult.Ok)
        val file = (result as BackupParseResult.Ok).file
        assertEquals(3, file.schemaVersion)
        assertTrue(file.noteFolders.isEmpty())
        assertTrue(file.notes.isEmpty())
        assertTrue(BackupCodec.validate(file).isEmpty())
    }
}
