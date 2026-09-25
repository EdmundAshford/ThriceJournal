package cn.sanxing.thrice.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import cn.sanxing.thrice.data.data.local.AppDatabase
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.data.local.AssignmentDao
import cn.sanxing.thrice.data.data.local.BillCategoryDao
import cn.sanxing.thrice.data.data.local.BillDao
import cn.sanxing.thrice.data.data.local.CourseDao
import cn.sanxing.thrice.data.data.local.ExamDao
import cn.sanxing.thrice.data.data.local.FocusSessionDao
import cn.sanxing.thrice.data.data.local.FocusTagDao
import cn.sanxing.thrice.data.data.local.NoteDao
import cn.sanxing.thrice.data.data.local.NoteFolderDao
import cn.sanxing.thrice.data.data.local.NoteTagDao
import cn.sanxing.thrice.data.data.local.AiChatDao
import cn.sanxing.thrice.data.data.local.ReminderDao
import cn.sanxing.thrice.data.data.local.SleepRecordDao
import cn.sanxing.thrice.data.data.local.SectionTimeDao
import cn.sanxing.thrice.data.data.local.TaskDao
import cn.sanxing.thrice.data.data.local.TaskTagDao
import cn.sanxing.thrice.data.data.local.TaskTypeDao
import cn.sanxing.thrice.data.data.local.TermDao
import cn.sanxing.thrice.data.pdf.PdfboxTextExtractor
import cn.sanxing.thrice.parser.ImportScheduleUseCase as ParserImportUseCase
import cn.sanxing.thrice.parser.text.PdfTextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        // 与小组件 / 开机广播共用进程级单例（见 DatabaseProvider）
        DatabaseProvider.get(context)

    @Provides
    fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()

    @Provides
    fun provideTermDao(db: AppDatabase): TermDao = db.termDao()

    @Provides
    fun provideSectionTimeDao(db: AppDatabase): SectionTimeDao = db.sectionTimeDao()

    @Provides
    fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideExamDao(db: AppDatabase): ExamDao = db.examDao()

    @Provides
    fun provideAssignmentDao(db: AppDatabase): AssignmentDao = db.assignmentDao()

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideTaskTypeDao(db: AppDatabase): TaskTypeDao = db.taskTypeDao()

    @Provides
    fun provideBillDao(db: AppDatabase): BillDao = db.billDao()

    @Provides
    fun provideBillCategoryDao(db: AppDatabase): BillCategoryDao = db.billCategoryDao()

    @Provides
    fun provideFocusTagDao(db: AppDatabase): FocusTagDao = db.focusTagDao()

    @Provides
    fun provideFocusSessionDao(db: AppDatabase): FocusSessionDao = db.focusSessionDao()

    @Provides
    fun provideSleepRecordDao(db: AppDatabase): SleepRecordDao = db.sleepRecordDao()

    @Provides
    fun provideNoteFolderDao(db: AppDatabase): NoteFolderDao = db.noteFolderDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideNoteTagDao(db: AppDatabase): NoteTagDao = db.noteTagDao()

    @Provides
    fun provideTaskTagDao(db: AppDatabase): TaskTagDao = db.taskTagDao()

    @Provides
    fun provideAiChatDao(db: AppDatabase): AiChatDao = db.aiChatDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("settings") }
        )

    /**
     * AI 配置（含 API Key）独立文件；已在 backup_rules / data_extraction_rules
     * 的云备份段中排除 datastore/ai_secrets.preferences_pb。
     */
    @Provides
    @Singleton
    @javax.inject.Named("ai_secrets")
    fun provideAiSecretsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("ai_secrets") }
        )

    @Provides
    fun providePdfTextExtractor(@ApplicationContext context: Context): PdfTextExtractor {
        // pdfbox-android 要求在首次使用前初始化资源加载器
        PDFBoxResourceLoader.init(context)
        return PdfboxTextExtractor()
    }

    @Provides
    fun provideParserImportUseCase(extractor: PdfTextExtractor): ParserImportUseCase =
        ParserImportUseCase(extractor)
}
