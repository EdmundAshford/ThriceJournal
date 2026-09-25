package cn.sanxing.thrice.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.ai.AiScreen
import cn.sanxing.thrice.ui.bills.BillsScreen
import cn.sanxing.thrice.ui.focus.FocusScreen
import cn.sanxing.thrice.ui.notes.NotesScreen
import cn.sanxing.thrice.ui.schedule.ScheduleScreen
import cn.sanxing.thrice.ui.sleep.SleepScreen
import cn.sanxing.thrice.ui.settings.SettingsScreen
import cn.sanxing.thrice.ui.tasks.TasksScreen
import cn.sanxing.thrice.ui.timetable.TimetableScreen
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主页容器：tab 由 HorizontalPager 承载，屏幕左右滑动即可切换，
 * 与底部导航栏双向联动（点击底栏翻页 / 滑动翻页更新选中态）。
 *
 * R10：除设置外的 tab 均可在设置页中显隐与排序（见 SettingsRepository.navTabOrder /
 * hiddenNavTabs）；底栏改为横向可滑动，便于后续承载更多界面；设置页固定末尾。
 *
 * 导入 / 课程列表 / 编辑页仍是 NavHost 上的全屏路由，返回后可通过
 * savedStateHandle 的 [KEY_TARGET_TAB]（tab 的 name 字符串）指定落地 tab。
 */
@Composable
fun HomeScaffold(
    container: AppContainer,
    navController: NavHostController
) {
    val defaultConfigurable = BottomNavItem.entries.filter { it.configurable }

    val order by container.settingsRepository.navTabOrder
        .collectAsState(defaultConfigurable.map { it.name })
    val hidden by container.settingsRepository.hiddenNavTabs.collectAsState(emptySet())

    // 当前实际可见的 tab（顺序即页序，设置页永远在最后）
    val visibleItems = remember(order, hidden) {
        val byName = BottomNavItem.entries.associateBy { it.name }
        order.mapNotNull { name -> if (name in hidden) null else byName[name] } +
            BottomNavItem.SETTINGS
    }

    // 记住选中项的 name（而非页码）：显隐 / 排序后仍能停留在原界面。
    // 必须用 rememberSaveable：进入导入 / AI 配置等全屏路由时 HomeScaffold 会被
    // NavHost 移出组合，普通 remember 随组合丢弃，返回时选中项被重置为第一个 tab；
    // rememberSaveable 的状态由 NavHost 按回退栈条目保存恢复（PagerState 页码同理）。
    var selectedName by rememberSaveable { mutableStateOf(visibleItems.first().name) }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { visibleItems.size }
    )
    val scope = rememberCoroutineScope()

    // 可见项变化（显隐 / 排序）后，锚定变化发生时的 selectedName（局部捕获，
    // 避免拖拽同步协程的中间态把选中项改掉），把页码纠正到它；被隐藏时落到第一项。
    LaunchedEffect(visibleItems) {
        val anchored = selectedName
        val target = visibleItems.indexOfFirst { it.name == anchored }
            .let { if (it < 0) 0 else it }
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        selectedName = visibleItems[target].name
    }

    // 仅当用户亲手拖拽翻页（DragInteraction）并吸附停止后，才把页码同步为选中项；
    // 程序触发的 scrollToPage/animateScrollToPage（含显隐导致的页码纠正）不经过
    // interactionSource，不会反过来篡改 selectedName —— 修复设置里开关底栏项跳出设置。
    LaunchedEffect(visibleItems) {
        var activeDrags = 0
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> activeDrags++
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    activeDrags = (activeDrags - 1).coerceAtLeast(0)
                    if (activeDrags == 0) {
                        snapshotFlow { !pagerState.isScrollInProgress }.first { it }
                        visibleItems.getOrNull(pagerState.currentPage)?.let {
                            selectedName = it.name
                        }
                    }
                }
            }
        }
    }

    // 全屏路由返回时消费「目标 tab」信号（按 name 定位；目标已被隐藏则忽略）
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry) {
        val target = backStackEntry
            ?.savedStateHandle
            ?.remove<String>(KEY_TARGET_TAB)
            ?: return@LaunchedEffect
        val index = visibleItems.indexOfFirst { it.name == target }
        if (index >= 0) {
            selectedName = target
            pagerState.scrollToPage(index)
        }
    }

    // 底栏横向滚动状态：页面切换时自动把当前项滚动到视口中央
    val barScroll = rememberScrollState()
    var barCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    // 当前页变化（点击 / 横滑 / 显隐排序归位）后，自动滚动底栏使选中项完整可见并尽量居中。
    // 仅在当前页变化时触发：用户单纯手动横滑底栏不会被拉回。
    LaunchedEffect(visibleItems) {
        snapshotFlow { pagerState.currentPage }
            .collectLatest { page ->
                val viewport = barCoordinates?.size?.width ?: return@collectLatest
                val (left, right) = itemBounds[page] ?: return@collectLatest
                val width = right - left
                // 项在滚动内容中的位置 = 相对底栏位置 + 当前滚动偏移
                val target = (left + barScroll.value + width / 2f - viewport / 2f)
                    .toInt()
                    .coerceIn(0, barScroll.maxValue.takeIf { it > 0 } ?: 0)
                if (kotlin.math.abs(target - barScroll.value) > 2) {
                    barScroll.animateScrollTo(
                        target,
                        androidx.compose.animation.core.tween(280)
                    )
                }
            }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            // 自绘横向可滑动底栏：NavigationBar 子项带权重无法滚动，故用 Row 自绘，
            // 视觉对齐 Material3 导航栏（图标药丸 + 单行 11sp 标签）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .navigationBarsPadding()
                    .onGloballyPositioned { barCoordinates = it }
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(barScroll)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    visibleItems.forEachIndexed { index, item ->
                        NavTabButton(
                            item = item,
                            selected = pagerState.currentPage == index,
                            onBoundsChanged = { coords ->
                                val bar = barCoordinates
                                if (bar != null) {
                                    val l = coords.positionInRoot().x - bar.positionInRoot().x
                                    itemBounds[index] = l to (l + coords.size.width)
                                }
                            },
                            onClick = {
                                selectedName = item.name
                                if (pagerState.currentPage != index) {
                                    scope.launch {
                                        // 相邻页用平滑动画；跨越多页时直接落到目标页，
                                        // 避免动画途中逐帧组合中间页造成的卡顿。
                                        val distance = kotlin.math.abs(index - pagerState.currentPage)
                                        if (distance <= 1) {
                                            pagerState.animateScrollToPage(
                                                index,
                                                animationSpec = androidx.compose.animation.core.tween(260)
                                            )
                                        } else {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            // 预组合左右相邻页，避免滑动开始的几帧临时组合邻页造成顿挫
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                // 状态栏 + 底部导航栏占位（与原 NavHost 外层 Scaffold 行为一致；inset 消费后
                // 各页内嵌 Scaffold 不会重复留白）
                .padding(padding)
        ) { page ->
            when (visibleItems[page]) {
                BottomNavItem.SCHEDULE -> ScheduleScreen(
                    container = container,
                    onOpenEdit = { id -> navController.navigate(AppRoutes.edit(id)) }
                )

                BottomNavItem.BILLS -> BillsScreen(
                    container = container,
                    onOpenStats = { navController.navigate(AppRoutes.BILLS_STATS) }
                )

                BottomNavItem.TASKS -> TasksScreen(
                    container = container,
                    onOpenEdit = { id -> navController.navigate(AppRoutes.edit(id)) }
                )

                BottomNavItem.TIMETABLE -> TimetableScreen(
                    container = container,
                    onOpenImport = { navController.navigate(AppRoutes.IMPORT) },
                    onOpenEdit = { id -> navController.navigate(AppRoutes.edit(id)) },
                    onOpenAllCourses = { navController.navigate(AppRoutes.COURSES) }
                )

                BottomNavItem.FOCUS -> FocusScreen(container = container)

                BottomNavItem.SLEEP -> SleepScreen(container = container)

                BottomNavItem.NOTES -> NotesScreen(container = container)

                BottomNavItem.AI -> AiScreen(
                    container = container,
                    isCurrentPage = pagerState.currentPage == page,
                    onOpenProviderConfig = { navController.navigate(AppRoutes.AI_PROVIDER) }
                )

                BottomNavItem.SETTINGS -> SettingsScreen(
                    container = container,
                    onOpenReward = { navController.navigate(AppRoutes.REWARD) }
                )
            }
        }
    }
}

/** 底栏单个可滑动条目：图标选中药丸 + 单行 11sp 标签。 */
@Composable
private fun NavTabButton(
    item: BottomNavItem,
    selected: Boolean,
    onBoundsChanged: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onClick: () -> Unit
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .onGloballyPositioned(onBoundsChanged)
            .widthIn(min = 64.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .widthIn(min = 48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                item.icon,
                contentDescription = stringResource(item.labelRes),
                modifier = Modifier.padding(horizontal = 12.dp),
                tint = contentColor
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(item.labelRes),
            color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
            maxLines = 1,
            softWrap = false,
            fontSize = 11.sp
        )
    }
}

/** 全屏路由返回主页时指定落地 tab：值为 [BottomNavItem.name]。 */
const val KEY_TARGET_TAB = "targetTab"
