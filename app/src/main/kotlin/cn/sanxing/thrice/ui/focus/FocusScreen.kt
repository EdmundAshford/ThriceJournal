package cn.sanxing.thrice.ui.focus

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.data.domain.model.FocusStatus
import cn.sanxing.thrice.notification.FocusCompletionWorker
import cn.sanxing.thrice.notification.FocusNotifier
import cn.sanxing.thrice.notification.FocusScheduler
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.WheelDurationPicker
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import android.os.SystemClock

/** 正计时水位时间常数：τ≈2600s 时 60 分钟约到 0.75，渐近 1。 */
private const val STOPWATCH_TAU_SECONDS = 2600f

/** ON_STOP 与页面 dispose 同一次切换的去重时间窗。 */
private const val LEAVE_DEDUP_WINDOW_MS = 2000L

/** 离开策略运行时载体：观察者 / dispose 回调读取最新值，无需随设置重建监听。 */
private class LeaveRuntime {
    var behavior: String = SettingsRepository.FOCUS_LEAVE_PAUSE
    var notify: Boolean = true
    var lastElapsed: Long = 0L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository

    val fs by FocusController.state.collectAsState()
    val tags by container.focusTagDao.observeAll().collectAsState(initial = emptyList())

    val keepScreenOn by settings.focusKeepScreenOn.collectAsState(initial = true)
    val leaveBehavior by settings.focusLeaveBehavior.collectAsState(initial = SettingsRepository.FOCUS_LEAVE_PAUSE)
    val notifyOnFinish by settings.focusNotifyOnFinish.collectAsState(initial = true)
    val savedMinutes by settings.focusCountdownMinutes.collectAsState(initial = 25)
    val lastTagId by settings.focusLastTagId.collectAsState(initial = null)

    var showStats by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showTagManager by remember { mutableStateOf(false) }
    var showAbandonConfirm by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }

    var mode by remember { mutableStateOf(FocusMode.COUNTDOWN) }
    // 倒计时时长直接以设置仓库为准（设置弹窗 / 滚轮选择器即时写入），避免本地副本陈旧
    val plannedMinutes = savedMinutes
    var selectedTagId by remember { mutableStateOf<Long?>(null) }
    var lastTagRestored by remember { mutableStateOf(false) }
    if (!lastTagRestored) {
        selectedTagId = lastTagId
        lastTagRestored = true
    }

    // 放弃 / 离开判定失败都会播放破碎动画。终态由 FocusController.lastStatus 持久持有，
    // 离开后再回来仍会播放一次（或动画已结束则直接呈现空球），不会误显满水。
    var shattering by remember { mutableStateOf(false) }
    var shatterDone by remember { mutableStateOf(false) }
    var terminalShattered by remember { mutableStateOf(false) }
    LaunchedEffect(fs.lastStatus) {
        if (fs.lastStatus == FocusStatus.ABANDONED || fs.lastStatus == FocusStatus.FAILED) {
            if (!terminalShattered) {
                terminalShattered = true
                shattering = true
                shatterDone = false
            }
        } else {
            terminalShattered = false
        }
    }

    // 200ms 心跳：更新时间显示 + 到点兜底。
    // notifyOnFinish 纳入 key：运行中改开关后协程立即以最新值重启。
    var tickElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(fs.phase, notifyOnFinish) {
        while (isActive) {
            if (fs.phase == FocusController.Phase.RUNNING) {
                tickElapsed = SystemClock.elapsedRealtime()
                if (FocusController.completeIfDue()) {
                    if (notifyOnFinish) {
                        val secs = FocusController.state.value.focusedSeconds
                        FocusNotifier.showFinished(
                            context, FocusCompletionWorker.formatDuration(secs)
                        )
                    }
                    FocusScheduler.cancelCompletion(context)
                }
            }
            delay(200)
        }
    }

    // 屏幕常亮（专注进行中且开关开启）
    val view = LocalView.current
    DisposableEffect(keepScreenOn, fs.phase) {
        val window = (view.context as? Activity)?.window
        val active = fs.phase == FocusController.Phase.RUNNING || fs.phase == FocusController.Phase.PAUSED
        if (keepScreenOn && active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 离开「专注模块」策略：
    //  - 底栏切到其它页：HorizontalPager dispose 本页组合 → onDispose；
    //  - App 切后台：ON_STOP；
    //  - 模块内导航（统计覆盖层 / 设置 / 标签弹窗）不销毁组合，不算离开。
    // 两个触发源同一次切换用时间窗去重，只处理一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    val leave = remember { LeaveRuntime() }
    leave.behavior = leaveBehavior
    leave.notify = notifyOnFinish
    DisposableEffect(Unit) {
        fun handleLeave() {
            // 仅运行中按策略处理；暂停中 / 终态 / 空闲离开均无动作。
            if (!FocusController.isRunning) return
            val now = SystemClock.elapsedRealtime()
            if (now - leave.lastElapsed < LEAVE_DEDUP_WINDOW_MS) return
            leave.lastElapsed = now
            when (leave.behavior) {
                SettingsRepository.FOCUS_LEAVE_KEEP -> {
                    // 继续计时：不做任何动作，后台到期仍由 FocusCompletionWorker 结算。
                }
                SettingsRepository.FOCUS_LEAVE_FAIL -> {
                    FocusController.failByLeave()
                    FocusScheduler.cancelCompletion(context)
                    if (FocusController.state.value.phase == FocusController.Phase.FAILED &&
                        leave.notify
                    ) {
                        FocusNotifier.showFailed(context)
                    }
                }
                else -> {
                    // 暂停态保留前台服务：通知切暂停文案、闹钟撤掉，恢复后继续
                    FocusController.pause()
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) handleLeave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            handleLeave()
        }
    }

    val phase = fs.phase
    val activeSeconds = FocusController.activeMs(tickElapsed) / 1000L
    val displaySeconds: Long = when (phase) {
        FocusController.Phase.IDLE -> if (mode == FocusMode.COUNTDOWN) plannedMinutes * 60L else 0L
        FocusController.Phase.FINISHED, FocusController.Phase.FAILED -> fs.focusedSeconds
        else ->
            // 倒计时展示剩余时间（支持任意小时数）；正计时展示已专注时长。
            if (fs.mode == FocusMode.COUNTDOWN && fs.plannedSeconds > 0)
                (fs.plannedSeconds - activeSeconds).coerceAtLeast(0L)
            else activeSeconds
    }
    val abandoned = fs.lastStatus == FocusStatus.ABANDONED
    val failed = phase == FocusController.Phase.FAILED
    // 排水动画进行中保持终态瞬间水位（供动画从该水位匀速排空），动画结束（或切走再回）一律 0。
    val terminalWater: Float? = when {
        abandoned -> when {
            shattering ->
                if (fs.mode == FocusMode.COUNTDOWN && fs.plannedSeconds > 0)
                    (fs.focusedSeconds.toFloat() / fs.plannedSeconds).coerceIn(0f, 1f)
                else stopwatchFill(fs.focusedSeconds)
            else -> 0f
        }
        failed -> if (shattering) {
            if (fs.mode == FocusMode.COUNTDOWN && fs.plannedSeconds > 0)
                (fs.focusedSeconds.toFloat() / fs.plannedSeconds).coerceIn(0f, 1f)
            else stopwatchFill(fs.focusedSeconds)
        } else 0f
        else -> null
    }
    val rawFill: Float = when (phase) {
        FocusController.Phase.IDLE -> 0f
        FocusController.Phase.FINISHED -> terminalWater ?: 1f
        FocusController.Phase.FAILED -> terminalWater ?: 0f
        FocusController.Phase.RUNNING, FocusController.Phase.PAUSED ->
            if (fs.mode == FocusMode.COUNTDOWN && fs.plannedSeconds > 0)
                (activeSeconds.toFloat() / fs.plannedSeconds).coerceIn(0f, 1f)
            else stopwatchFill(activeSeconds)
    }
    val fill by animateFloatAsState(rawFill, tween(700), label = "fill")
    val idle = phase == FocusController.Phase.IDLE
    val paperAlpha = LocalUiMaskAlpha.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = paperAlpha))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.focus_title)) },
                    actions = {
                        IconButton(onClick = { showStats = true }) {
                            Icon(Icons.Filled.BarChart, contentDescription = stringResource(R.string.focus_stats_entry))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.focus_settings_entry))
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    // 内容（水球画布 + 标签 + 操作按钮）在矮屏 / 大字设置下可能超出可视高度，
                    // 允许纵向滚动并在底部留白，避免「开始」等按钮被底栏 / 手势条裁掉不可点。
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 模式切换（仅空闲可改）
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == FocusMode.STOPWATCH,
                        onClick = { if (idle) mode = FocusMode.STOPWATCH },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text(stringResource(R.string.focus_mode_stopwatch)) }
                    SegmentedButton(
                        selected = mode == FocusMode.COUNTDOWN,
                        onClick = { if (idle) mode = FocusMode.COUNTDOWN },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text(stringResource(R.string.focus_mode_countdown)) }
                }

                if (mode == FocusMode.COUNTDOWN && idle) {
                    val durationText = if (plannedMinutes >= 60)
                        stringResource(
                            R.string.focus_hours_minutes,
                            plannedMinutes / 60, plannedMinutes % 60
                        )
                    else
                        stringResource(R.string.focus_minutes_only, plannedMinutes)
                    TextButton(onClick = {
                        showDurationPicker = true
                    }) {
                        Text(
                            stringResource(R.string.focus_duration_title) + "：$durationText",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Box(contentAlignment = Alignment.Center) {
                    WaterSphere(
                        fill = fill,
                        drip = phase == FocusController.Phase.RUNNING,
                        waterColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        waterDeepColor = MaterialTheme.colorScheme.primary,
                        glassTint = MaterialTheme.colorScheme.surfaceVariant,
                        glassBorder = MaterialTheme.colorScheme.outline,
                        shatter = shattering,
                        onShatterEnd = { shatterDone = true },
                        diameter = 272.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            formatHms(displaySeconds),
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Light,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        when (phase) {
                            FocusController.Phase.FINISHED ->
                                Text(
                                    if (abandoned) stringResource(R.string.focus_status_abandoned)
                                    else stringResource(R.string.focus_finished_badge),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (abandoned) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            FocusController.Phase.FAILED ->
                                Text(stringResource(R.string.focus_failed_badge),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error)
                            FocusController.Phase.PAUSED ->
                                Text(stringResource(R.string.focus_pause),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FocusController.Phase.IDLE ->
                                if (mode == FocusMode.STOPWATCH)
                                    Text(stringResource(R.string.focus_stopwatch_hint),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Spacer(Modifier.height(0.dp))
                            FocusController.Phase.RUNNING -> Spacer(Modifier.height(0.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 标签行：未分类 + 各标签 + 管理；专注中只显示当前标签
                val currentTagId = if (idle) selectedTagId else fs.tagId
                if (idle) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = currentTagId == null,
                                onClick = { selectedTagId = null },
                                label = { Text(stringResource(R.string.focus_tag_none)) }
                            )
                        }
                        items(tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = currentTagId == tag.id,
                                onClick = { selectedTagId = tag.id },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(tag.colorArgb))
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(tag.name)
                                    }
                                }
                            )
                        }
                        item {
                            TextButton(onClick = { showTagManager = true }) {
                                Text(stringResource(R.string.focus_tag_manage))
                            }
                        }
                    }
                } else {
                    val tag = tags.firstOrNull { it.id == currentTagId }
                    if (tag != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(tag.colorArgb))
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(tag.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(stringResource(R.string.focus_tag_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 操作按钮
                when (phase) {
                    FocusController.Phase.IDLE -> {
                        val canStart = mode == FocusMode.STOPWATCH || plannedMinutes >= 1
                        Button(
                            onClick = {
                                if (!canStart) return@Button
                                val planned = if (mode == FocusMode.COUNTDOWN) plannedMinutes * 60L else 0L
                                FocusController.start(mode, planned, selectedTagId)
                                if (mode == FocusMode.COUNTDOWN) {
                                    FocusScheduler.scheduleCompletion(context, planned * 1000L)
                                }
                                scope.launch {
                                    settings.setFocusCountdownMinutes(plannedMinutes)
                                    settings.setFocusLastTag(selectedTagId)
                                }
                            },
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(stringResource(R.string.focus_start), style = MaterialTheme.typography.titleMedium) }
                        if (mode == FocusMode.COUNTDOWN && plannedMinutes < 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.focus_duration_zero_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    FocusController.Phase.RUNNING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    // 前台服务保留：自动切为暂停态通知并撤掉完成闹钟
                                    FocusController.pause()
                                },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text(stringResource(R.string.focus_pause)) }
                            OutlinedButton(
                                onClick = { showAbandonConfirm = true },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text(stringResource(R.string.focus_abandon)) }
                        }
                        // 倒计时未到点前只有「暂停 / 放弃」，不提供提前结束；
                        // 正计时才允许手动结束。
                        if (fs.mode != FocusMode.COUNTDOWN) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    FocusController.completeManually()
                                    FocusScheduler.cancelCompletion(context)
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text(stringResource(R.string.focus_finish)) }
                        }
                    }
                    FocusController.Phase.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { showAbandonConfirm = true },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text(stringResource(R.string.focus_abandon)) }
                            Button(
                                onClick = {
                                    // 前台服务一直在：恢复后自动重排完成闹钟
                                    FocusController.resume()
                                },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text(stringResource(R.string.focus_resume)) }
                        }
                        if (fs.mode != FocusMode.COUNTDOWN) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    FocusController.completeManually()
                                    FocusScheduler.cancelCompletion(context)
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) { Text(stringResource(R.string.focus_finish)) }
                        }
                    }
                    FocusController.Phase.FINISHED, FocusController.Phase.FAILED -> {
                        // 排水动画期间锁定操作，动画结束后才允许 reset。
                        Button(
                            onClick = {
                                FocusController.reset()
                                shattering = false
                                shatterDone = false
                                terminalShattered = false
                            },
                            enabled = !shattering || shatterDone,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(stringResource(R.string.focus_done)) }
                    }
                }

                // 滚动内容底部留白，保证最后一个按钮与屏幕底边之间有间距
                Spacer(Modifier.height(20.dp))
            }
        }

        if (showAbandonConfirm) {
            AlertDialog(
                onDismissRequest = { showAbandonConfirm = false },
                title = { Text(stringResource(R.string.focus_abandon)) },
                text = { Text(stringResource(R.string.focus_abandon_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showAbandonConfirm = false
                        // 先落库（ABANDONED 语义不变），再播排水动画；reset 按钮锁到动画结束。
                        shattering = true
                        shatterDone = false
                        FocusController.abandon()
                        FocusScheduler.cancelCompletion(context)
                    }) { Text(stringResource(R.string.common_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showAbandonConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        if (showDurationPicker) {
            var draft by remember { mutableStateOf(plannedMinutes) }
            AlertDialog(
                onDismissRequest = { showDurationPicker = false },
                title = { Text(stringResource(R.string.focus_duration_title)) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        WheelDurationPicker(
                            totalMinutes = draft,
                            onTotalChange = { draft = it.coerceIn(0, SettingsRepository.FOCUS_MAX_COUNTDOWN_MIN) },
                            hourLabel = stringResource(R.string.focus_wheel_hour),
                            minuteLabel = stringResource(R.string.focus_wheel_minute)
                        )
                        if (draft < 1) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.focus_duration_zero_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = draft >= 1,
                        onClick = {
                            scope.launch { settings.setFocusCountdownMinutes(draft) }
                            showDurationPicker = false
                        }
                    ) { Text(stringResource(R.string.common_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDurationPicker = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        if (showSettings) {
            FocusSettingsDialog(
                container = container,
                onDismiss = { showSettings = false }
            )
        }

        if (showTagManager) {
            FocusTagDialog(container = container, onDismiss = { showTagManager = false })
        }

        // 统计页作为同模块全屏覆盖层：主屏组合保持存活，不触发离开策略。
        AnimatedVisibility(
            visible = showStats,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 统计为全屏覆盖页，使用不透明白底，防止底层计时界面透出重影
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                FocusStatsScreen(container, onBack = { showStats = false })
            }
        }
    }
}

/** f = 1 - e^(-t/τ)：1 小时约 0.75，永不满且越往后越慢。 */
private fun stopwatchFill(seconds: Long): Float =
    (1f - exp(-seconds.toFloat() / STOPWATCH_TAU_SECONDS)).coerceIn(0f, 1f)

/** 秒 → H:MM:SS（小时为 0 时 MM:SS），支持任意小时数。 */
private fun formatHms(total: Long): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/** 专注设置：屏幕常亮 / 结束通知 / 离开策略 / 默认倒计时时长。 */
@Composable
private fun FocusSettingsDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository
    val keepOn by settings.focusKeepScreenOn.collectAsState(initial = true)
    val notifyOn by settings.focusNotifyOnFinish.collectAsState(initial = true)
    val behavior by settings.focusLeaveBehavior.collectAsState(initial = SettingsRepository.FOCUS_LEAVE_PAUSE)
    val minutes by settings.focusCountdownMinutes.collectAsState(initial = 25)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focus_settings_entry)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.focus_keep_screen_on),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = keepOn,
                        onCheckedChange = { v -> scope.launch { settings.setFocusKeepScreenOn(v) } }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.focus_finish_notify),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = notifyOn,
                        onCheckedChange = { v -> scope.launch { settings.setFocusNotifyOnFinish(v) } }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.focus_leave_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = behavior == SettingsRepository.FOCUS_LEAVE_PAUSE,
                        onClick = { scope.launch { settings.setFocusLeaveBehavior(SettingsRepository.FOCUS_LEAVE_PAUSE) } }
                    )
                    Text(stringResource(R.string.focus_leave_pause))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = behavior == SettingsRepository.FOCUS_LEAVE_FAIL,
                        onClick = { scope.launch { settings.setFocusLeaveBehavior(SettingsRepository.FOCUS_LEAVE_FAIL) } }
                    )
                    Text(stringResource(R.string.focus_leave_fail))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = behavior == SettingsRepository.FOCUS_LEAVE_KEEP,
                        onClick = { scope.launch { settings.setFocusLeaveBehavior(SettingsRepository.FOCUS_LEAVE_KEEP) } }
                    )
                    Text(stringResource(R.string.focus_leave_keep))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.focus_duration_title),
                    style = MaterialTheme.typography.titleSmall
                )
                WheelDurationPicker(
                    totalMinutes = minutes,
                    onTotalChange = { v ->
                        scope.launch {
                            settings.setFocusCountdownMinutes(
                                v.coerceIn(1, SettingsRepository.FOCUS_MAX_COUNTDOWN_MIN)
                            )
                        }
                    },
                    hourLabel = stringResource(R.string.focus_wheel_hour),
                    minuteLabel = stringResource(R.string.focus_wheel_minute)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}
