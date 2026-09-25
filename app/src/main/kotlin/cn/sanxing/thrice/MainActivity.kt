package cn.sanxing.thrice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.notification.ReminderScheduler
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.components.CustomBackgroundImage
import cn.sanxing.thrice.ui.components.GeometricBackground
import cn.sanxing.thrice.ui.navigation.AppNavHost
import cn.sanxing.thrice.ui.navigation.AppRoutes
import cn.sanxing.thrice.ui.theme.LocalAppBackground
import cn.sanxing.thrice.ui.theme.SanxingTheme
import cn.sanxing.thrice.util.runCatchingOrCancel
import cn.sanxing.thrice.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var container: AppContainer

    /** 小组件 / 通知点击带进来的课程 id，由导航层消费（定位到该课）。 */
    private val pendingCourseId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeCourseIntent(intent)
        enableEdgeToEdge()
        setContent { AppRoot(container, pendingCourseId) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeCourseIntent(intent)
    }

    private fun consumeCourseIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_COURSE_ID, -1L) ?: -1L
        if (id > 0) pendingCourseId.value = id
    }

    companion object {
        const val EXTRA_COURSE_ID = "courseId"
    }
}

/**
 * 应用根：设置（DataStore）驱动主题 → 背景层（几何 / 自定义壁纸）→ NavHost。
 * 底部五个 tab（含左右滑动）收敛在 HomeScaffold 内；导入 / 编辑页是全屏路由。
 */
@Composable
fun AppRoot(container: AppContainer, pendingCourseId: MutableStateFlow<Long?>? = null) {
    val context = LocalContext.current
    val themeColor by container.settingsRepository.themeColor
        .collectAsState(SettingsRepository.THEME_COLOR_BLUE)
    val customColorHex by container.settingsRepository.customColor
        .collectAsState(SettingsRepository.DEFAULT_CUSTOM_COLOR)
    val themeMode by container.settingsRepository.themeMode
        .collectAsState(SettingsRepository.THEME_MODE_SYSTEM)
    val dynamicColor by container.settingsRepository.dynamicColor.collectAsState(false)
    val backgroundAnimation by container.settingsRepository.backgroundAnimation.collectAsState(true)
    val customBgPath by container.settingsRepository.customBgPath.collectAsState(null)
    val customBgAlpha by container.settingsRepository.customBgAlpha.collectAsState(80)
    val uiMaskAlpha by container.settingsRepository.uiMaskAlpha.collectAsState(62)
    val appFont by container.settingsRepository.appFont.collectAsState(SettingsRepository.APP_FONT_SYSTEM)
    val bgOffsetX by container.settingsRepository.bgOffsetX.collectAsState(0f)
    val bgOffsetY by container.settingsRepository.bgOffsetY.collectAsState(0f)
    val bgScale by container.settingsRepository.bgScale.collectAsState(1f)

    val wallpaperFile = customBgPath?.let { java.io.File(it) }?.takeIf { it.exists() }
    val wallpaperActive = wallpaperFile != null

    // Android 13+ 首次进入申请通知权限（拒绝后仍可在设置页再次申请）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不阻塞：设置页有状态展示与再次入口 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 每次回到前台自愈重排：WorkManager 任务被系统清理 / 厂商省电导致丢失时，打开 app 即补齐
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatchingOrCancel {
                        val minutes = container.settingsRepository.reminderMinutes.first()
                        val enabled = container.settingsRepository.reminderEnabled.first()
                        ReminderScheduler.rescheduleAll(
                            context.applicationContext, minutes, enabled
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SanxingTheme(
        themeColorKey = themeColor,
        darkMode = themeMode,
        dynamicColorEnabled = dynamicColor,
        customColorHex = customColorHex,
        wallpaperActive = wallpaperActive,
        surfaceAlpha = uiMaskAlpha / 100f,
        appFontKey = appFont
    ) {
        val navController = rememberNavController()

        // 供全屏覆盖页（笔记编辑 / 文件夹等）自绘背景用，避免半透明纸面透出底层页面形成重影
        val appBackground: @Composable (Modifier) -> Unit = { m ->
            AppBackgroundLayer(
                wallpaperActive = wallpaperActive,
                wallpaperFile = wallpaperFile,
                customBgAlpha = customBgAlpha,
                bgOffsetX = bgOffsetX,
                bgOffsetY = bgOffsetY,
                bgScale = bgScale,
                backgroundAnimation = backgroundAnimation,
                modifier = m
            )
        }

        // 小组件 / 通知点击 → 定位到课程
        pendingCourseId?.let { flow ->
            LaunchedEffect(flow) {
                flow.collect { id ->
                    if (id != null && id > 0) {
                        flow.value = null
                        if (navController.currentDestination?.route != AppRoutes.EDIT) {
                            navController.navigate(AppRoutes.edit(id))
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // 不透明底色：壁纸透明区域 / 深色模式下兜住底层
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 背景层：有自定义壁纸显示壁纸，否则显示几何线条
            AppBackgroundLayer(
                wallpaperActive = wallpaperActive,
                wallpaperFile = wallpaperFile,
                customBgAlpha = customBgAlpha,
                bgOffsetX = bgOffsetX,
                bgOffsetY = bgOffsetY,
                bgScale = bgScale,
                backgroundAnimation = backgroundAnimation,
                modifier = Modifier.fillMaxSize()
            )
            CompositionLocalProvider(LocalAppBackground provides appBackground) {
                AppNavHost(
                    navController = navController,
                    container = container,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 应用背景层：有自定义壁纸则画壁纸，否则画几何线条。
 *
 * 被两处使用：[AppRoot] 的最底层，以及全屏覆盖页（见 [LocalAppBackground]）——
 * 后者借此在自身层内重绘一遍背景，使「半透明纸面」只透出背景而不透出被覆盖的页面。
 */
@Composable
private fun AppBackgroundLayer(
    wallpaperActive: Boolean,
    wallpaperFile: java.io.File?,
    customBgAlpha: Int,
    bgOffsetX: Float,
    bgOffsetY: Float,
    bgScale: Float,
    backgroundAnimation: Boolean,
    modifier: Modifier = Modifier
) {
    if (wallpaperActive && wallpaperFile != null) {
        CustomBackgroundImage(
            file = wallpaperFile,
            alphaPercent = customBgAlpha,
            panX = bgOffsetX,
            panY = bgOffsetY,
            scale = bgScale,
            modifier = modifier
        )
    } else {
        GeometricBackground(modifier = modifier, animated = backgroundAnimation)
    }
}

/** 数据变更后的统一收尾：重排提醒 + 刷新小组件（后台执行，不阻塞返回）。 */
fun rescheduleAndRefreshWidgets(container: AppContainer, context: android.content.Context) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        runCatchingOrCancel {
            val minutes = container.settingsRepository.reminderMinutes.first()
            val enabled = container.settingsRepository.reminderEnabled.first()
            ReminderScheduler.rescheduleAll(context.applicationContext, minutes, enabled)
            WidgetUpdater.refreshAll(context.applicationContext)
        }
    }
}
