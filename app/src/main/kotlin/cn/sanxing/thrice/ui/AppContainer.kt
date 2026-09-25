package cn.sanxing.thrice.ui

import cn.sanxing.thrice.data.data.local.AssignmentDao
import cn.sanxing.thrice.data.data.local.BillCategoryDao
import cn.sanxing.thrice.data.data.local.BillDao
import cn.sanxing.thrice.data.data.local.ExamDao
import cn.sanxing.thrice.data.data.local.FocusSessionDao
import cn.sanxing.thrice.data.data.local.FocusTagDao
import cn.sanxing.thrice.data.data.local.ReminderDao
import cn.sanxing.thrice.data.data.local.SleepRecordDao
import cn.sanxing.thrice.data.data.local.SectionTimeDao
import cn.sanxing.thrice.data.data.local.TaskDao
import cn.sanxing.thrice.data.data.local.TaskTagDao
import cn.sanxing.thrice.data.data.local.TaskTypeDao
import cn.sanxing.thrice.data.data.backup.BackupManager
import cn.sanxing.thrice.data.ai.AiChatOrchestrator
import cn.sanxing.thrice.data.ai.AiChatRepository
import cn.sanxing.thrice.data.ai.AiClientFactory
import cn.sanxing.thrice.data.ai.AiPermissionRepository
import cn.sanxing.thrice.data.ai.AiPersonaRepository
import cn.sanxing.thrice.data.ai.AiSettingsRepository
import cn.sanxing.thrice.data.data.repository.CourseRepository
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.data.repository.NotesSettingsRepository
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.data.repository.TermRepository
import cn.sanxing.thrice.data.domain.usecase.DetectConflictsUseCase
import cn.sanxing.thrice.data.domain.usecase.GetCoursesForWeekUseCase
import cn.sanxing.thrice.data.domain.usecase.ImportScheduleUseCase
import cn.sanxing.thrice.data.domain.usecase.SeedSampleTermUseCase
import cn.sanxing.thrice.parser.ImportScheduleUseCase as ParserImportUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 依赖容器：MainActivity 注入一次，沿 Compose 树传给各页面。
 * （刻意不用 ViewModel 层：避免为本阶段引入 lifecycle-viewmodel 依赖，
 * 所有页面用 Flow.collectAsState + rememberCoroutineScope 管理状态。）
 */
@Singleton
class AppContainer @Inject constructor(
    val termRepository: TermRepository,
    val courseRepository: CourseRepository,
    val settingsRepository: SettingsRepository,
    val sectionTimeDao: SectionTimeDao,
    val examDao: ExamDao,
    val assignmentDao: AssignmentDao,
    val taskDao: TaskDao,
    val taskTypeDao: TaskTypeDao,
    val taskTagDao: TaskTagDao,
    val billDao: BillDao,
    val billCategoryDao: BillCategoryDao,
    val reminderDao: ReminderDao,
    val focusTagDao: FocusTagDao,
    val focusSessionDao: FocusSessionDao,
    val sleepRecordDao: SleepRecordDao,
    val notesRepository: NotesRepository,
    val notesSettingsRepository: NotesSettingsRepository,
    val aiSettingsRepository: AiSettingsRepository,
    val aiClientFactory: AiClientFactory,
    val aiPermissionRepository: AiPermissionRepository,
    val aiPersonaRepository: AiPersonaRepository,
    val aiChatOrchestrator: AiChatOrchestrator,
    val aiChatRepository: AiChatRepository,
    val backupManager: BackupManager,
    val getCoursesForWeek: GetCoursesForWeekUseCase,
    val detectConflicts: DetectConflictsUseCase,
    val importScheduleUseCase: ImportScheduleUseCase,
    val parserUseCase: ParserImportUseCase,
    val seedSampleTerm: SeedSampleTermUseCase
)
