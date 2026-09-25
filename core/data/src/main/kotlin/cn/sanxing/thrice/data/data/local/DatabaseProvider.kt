package cn.sanxing.thrice.data.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 进程级单例数据库提供者。
 *
 * Hilt（DataModule）与无法依赖注入的组件（Glance 小组件、开机广播）共用同一实例，
 * 避免多实例竞争。获取一律走 [get]。
 */
object DatabaseProvider {

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sanxing.db"
            )
                .addMigrations(
                    AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4,
                    AppDatabase.MIGRATION_4_5,
                    AppDatabase.MIGRATION_5_6,
                    AppDatabase.MIGRATION_6_7,
                    AppDatabase.MIGRATION_7_8,
                    AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11
                )
                // 全新安装不会执行任何 Migration，默认分类走 onCreate 回调播种
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        AppDatabase.seedDefaultBillCategories(db)
                    }
                })
                .build()
                .also { instance = it }
        }
}
