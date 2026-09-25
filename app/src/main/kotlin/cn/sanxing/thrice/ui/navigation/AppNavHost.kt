package cn.sanxing.thrice.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cn.sanxing.thrice.rescheduleAndRefreshWidgets
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.ai.AiProviderRoute
import cn.sanxing.thrice.ui.bills.BillsStatsScreen
import cn.sanxing.thrice.ui.edit.CourseEditScreen
import cn.sanxing.thrice.ui.imports.ImportScreen
import cn.sanxing.thrice.ui.settings.RewardScreen
import cn.sanxing.thrice.ui.timetable.CourseListScreen

/** 全部路由。主页（含五个 tab）为 [HOME]；导入页 / 编辑页等是全屏路由。 */
object AppRoutes {
    const val HOME = "home"
    const val BILLS_STATS = "bills_stats"
    const val IMPORT = "import"
    const val COURSES = "courses"
    const val REWARD = "reward"
    const val AI_PROVIDER = "ai_provider"
    const val ARG_COURSE_ID = "courseId"

    /** courseId = -1 表示新增。 */
    const val EDIT = "edit/{$ARG_COURSE_ID}"

    fun edit(courseId: Long = -1L): String = "edit/$courseId"
}

/**
 * 导航图。tab 页统一由 [HomeScaffold] 的 HorizontalPager 承载（支持左右滑动）；
 * 页面本身不持有 NavController：只接收所需的跳转回调，便于单页预览与测试。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 数据变更（导入 / 编辑 / 删除）后：重排提醒 + 刷新小组件
    val onDataChanged: () -> Unit = {
        rescheduleAndRefreshWidgets(container, context)
    }

    NavHost(navController = navController, startDestination = AppRoutes.HOME, modifier = modifier) {

        composable(AppRoutes.HOME) {
            HomeScaffold(container = container, navController = navController)
        }

        composable(AppRoutes.BILLS_STATS) {
            BillsStatsScreen(
                container = container,
                initialMonth = java.time.YearMonth.now(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(AppRoutes.COURSES) {
            CourseListScreen(
                container = container,
                onClose = { navController.popBackStack() },
                onOpenEdit = { id -> navController.navigate(AppRoutes.edit(id)) }
            )
        }

        composable(AppRoutes.REWARD) {
            RewardScreen(onBack = { navController.popBackStack() })
        }

        composable(AppRoutes.AI_PROVIDER) {
            AiProviderRoute(
                container = container,
                onBack = { navController.popBackStack() },
                onSaved = { }
            )
        }

        composable(AppRoutes.IMPORT) {
            ImportScreen(
                container = container,
                onFinished = {
                    // 导入完成后回到主页并落在课表 tab（清掉导入页，避免返回键回到向导）
                    onDataChanged()
                    navController.getBackStackEntry(AppRoutes.HOME)
                        .savedStateHandle[KEY_TARGET_TAB] = BottomNavItem.TIMETABLE.name
                    navController.popBackStack(AppRoutes.HOME, inclusive = false)
                },
                onClose = { navController.popBackStack() }
            )
        }

        composable(
            route = AppRoutes.EDIT,
            arguments = listOf(navArgument(AppRoutes.ARG_COURSE_ID) { type = NavType.LongType })
        ) { entry ->
            val courseId = entry.arguments?.getLong(AppRoutes.ARG_COURSE_ID) ?: -1L
            CourseEditScreen(
                container = container,
                courseId = courseId,
                onFinished = {
                    onDataChanged()
                    navController.popBackStack()
                }
            )
        }
    }
}
