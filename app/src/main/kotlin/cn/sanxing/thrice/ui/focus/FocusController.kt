package cn.sanxing.thrice.ui.focus

import android.content.Context
import android.os.SystemClock
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.FocusMode
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 专注计时状态机（进程内单例 + 磁盘快照）。
 *
 * 走时锚定 [SystemClock.elapsedRealtime]（不受系统时间被改 / 休眠墙钟跳变影响）：
 * - activeMs = now - startElapsed - 累计暂停时长；
 * - 屏幕旋转 / 重组不丢状态（状态不在 Composition 中）。
 *
 * R15 起每次状态迁移都把运行快照写入 SharedPreferences("focus_state")：
 * 应用被划掉 / 手动清理后台 / 进程崩溃后再次进入，[attach] 会恢复进行中会话。
 * 若检测到设备重启（elapsedRealtime 倒流，锚点失效），改用开始墙钟时间戳
 * （startedWallMs，暂停扣除量本身是时长，两种时钟下通用）重算并把锚点重新基线化
 * 到本次开机后的 elapsedRealtime，后续走时逻辑不变：
 * - 倒计时已过点：按计划秒数补结算落库（结束时刻取应到点墙钟，不把死后时长算进去）；
 * - 倒计时未到点：由调用方（SanxingApplication）重排精确闹钟并重启前台服务；
 * - 正计时：仅恢复状态并重启前台服务；暂停态：保持暂停。
 *
 * 结束（完成 / 失败 / 放弃）落库 focus_sessions；终态快照带 recorded 标记，
 * 异常退出未落库成功时恢复补写，并按 startedAtEpochMs 去重。
 */
object FocusController {

    enum class Phase { IDLE, RUNNING, PAUSED, FINISHED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val mode: FocusMode = FocusMode.STOPWATCH,
        /** 倒计时目标秒；正计时为 0。 */
        val plannedSeconds: Long = 0L,
        val tagId: Long? = null,
        val startedWallMs: Long = 0L,
        val endedWallMs: Long = 0L,
        /** 结束后定格的专注秒；进行中为 0（实时值取 [activeSeconds]）。 */
        val focusedSeconds: Long = 0L,
        /**
         * 终态落库的结果状态（COMPLETED / ABANDONED / FAILED）；空闲与进行中为 null。
         * 由持久单例持有：放弃动画期间切走再回来，UI 仍能正确呈现「已放弃、水流空」，
         * 不会误显满水与「专注完成」。
         */
        val lastStatus: FocusStatus? = null
    )

    /**
     * 冷启动恢复结果：仅 [attach] 首次执行且磁盘上存在进行中 / 终态会话时非空。
     * 应用层据此重排闹钟、重启前台服务或补发展示 / 通知。
     */
    data class Recovery(
        val phase: Phase,
        val mode: FocusMode,
        val plannedSeconds: Long,
        /** 倒计时恢复时尚余毫秒（已过点为 0）；其它情况为 0。 */
        val remainingMs: Long = 0L,
        /** 恢复时发现倒计时已过点，本次已补结算完成；应用层应补发展束通知。 */
        val causedCompletion: Boolean = false,
        /** [causedCompletion] 时定格的专注秒，供通知文案使用。 */
        val focusedSeconds: Long = 0L
    )

    private const val PREFS_NAME = "focus_state"
    private const val KEY_PHASE = "phase"
    private const val KEY_MODE = "mode"
    private const val KEY_PLANNED = "planned_seconds"
    private const val KEY_TAG = "tag_id"
    private const val KEY_STARTED_WALL = "started_wall_ms"
    private const val KEY_ENDED_WALL = "ended_wall_ms"
    private const val KEY_FOCUSED_SEC = "focused_seconds"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_ACC_PAUSE = "accumulated_pause_ms"
    private const val KEY_PAUSE_ELAPSED = "pause_start_elapsed"
    private const val KEY_PAUSE_WALL = "pause_start_wall_ms"
    private const val KEY_RECORDED = "recorded"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var startElapsed: Long = 0L
    private var accumulatedPauseMs: Long = 0L
    private var pauseStartElapsed: Long = 0L
    private var pauseStartWallMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionDao: cn.sanxing.thrice.data.data.local.FocusSessionDao? = null
    private var appContext: Context? = null

    @Volatile
    private var restored = false

    /**
     * Application.onCreate 调用：绑定落库 DAO，并从磁盘快照恢复被杀前的会话（仅一次）。
     * 返回 [Recovery] 描述恢复结果（无快照 / 空闲时为 null）；闹钟与前台服务由应用层处理。
     * 后续在接收器 / Worker / Service 中重复调用只绑定 DAO，不重复恢复。
     */
    @Synchronized
    fun attach(context: Context): Recovery? {
        val app = context.applicationContext
        if (sessionDao == null) sessionDao = DatabaseProvider.get(app).focusSessionDao()
        appContext = app
        if (restored) return null
        restored = true
        return runCatching { restore(app) }.getOrNull()
    }

    /** 当前实际专注毫秒（扣除暂停）。 */
    fun activeMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long = when (_state.value.phase) {
        Phase.RUNNING -> nowElapsed - startElapsed - accumulatedPauseMs
        Phase.PAUSED -> pauseStartElapsed - startElapsed - accumulatedPauseMs
        Phase.FINISHED, Phase.FAILED -> _state.value.focusedSeconds * 1000L
        Phase.IDLE -> 0L
    }.coerceAtLeast(0L)

    fun activeSeconds(): Long = activeMs() / 1000L

    val isRunning: Boolean get() = _state.value.phase == Phase.RUNNING
    val isPaused: Boolean get() = _state.value.phase == Phase.PAUSED

    @Synchronized
    fun start(mode: FocusMode, plannedSeconds: Long, tagId: Long?) {
        val nowElapsed = SystemClock.elapsedRealtime()
        startElapsed = nowElapsed
        accumulatedPauseMs = 0L
        pauseStartElapsed = 0L
        pauseStartWallMs = 0L
        _state.value = State(
            phase = Phase.RUNNING,
            mode = mode,
            plannedSeconds = plannedSeconds,
            tagId = tagId,
            startedWallMs = System.currentTimeMillis()
        )
        persist(recorded = true)
    }

    @Synchronized
    fun pause() {
        if (_state.value.phase != Phase.RUNNING) return
        pauseStartElapsed = SystemClock.elapsedRealtime()
        pauseStartWallMs = System.currentTimeMillis()
        _state.value = _state.value.copy(phase = Phase.PAUSED)
        persist(recorded = true)
    }

    @Synchronized
    fun resume() {
        if (_state.value.phase != Phase.PAUSED) return
        accumulatedPauseMs += SystemClock.elapsedRealtime() - pauseStartElapsed
        pauseStartElapsed = 0L
        pauseStartWallMs = 0L
        _state.value = _state.value.copy(phase = Phase.RUNNING)
        persist(recorded = true)
    }

    /** 正计时手动结束（记为完成）；倒计时未到点手动结束同样算完成。 */
    @Synchronized
    fun completeManually() {
        if (_state.value.phase == Phase.IDLE) return
        finish(FocusStatus.COMPLETED)
    }

    /** 用户主动放弃。 */
    @Synchronized
    fun abandon() {
        if (_state.value.phase == Phase.IDLE) return
        finish(FocusStatus.ABANDONED)
    }

    /** 离开专注模块策略 = 判定失败时调用。暂停中离开不算失败。 */
    @Synchronized
    fun failByLeave() {
        if (_state.value.phase != Phase.RUNNING) return
        finish(FocusStatus.FAILED)
    }

    /**
     * 由界面定时 / WorkManager / 精确闹钟到点回调：倒计时到点则置完成。幂等。
     * 返回 true 表示本次调用真正触发了完成。
     *
     * 加锁：主线程 200ms tick 与 WorkManager 线程可能同时到点，
     * check-then-set 必须串行，否则会落两条 COMPLETED 记录、发两次通知。
     */
    @Synchronized
    fun completeIfDue(): Boolean {
        val s = _state.value
        if (s.phase != Phase.RUNNING || s.mode != FocusMode.COUNTDOWN || s.plannedSeconds <= 0) {
            return false
        }
        if (activeSeconds() < s.plannedSeconds) return false
        finish(FocusStatus.COMPLETED)
        return true
    }

    /** 成品页「完成」展示看完后回到空闲态。 */
    @Synchronized
    fun reset() {
        _state.value = State()
        startElapsed = 0L
        accumulatedPauseMs = 0L
        pauseStartElapsed = 0L
        pauseStartWallMs = 0L
        runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        }
    }

    @Synchronized
    private fun finish(status: FocusStatus) {
        val seconds = activeMs() / 1000L
        settle(status, secondsOverride = seconds, endWallOverride = System.currentTimeMillis())
    }

    /**
     * 终态结算并落库。
     * @param secondsOverride 实际定格专注秒（正常结束取实时 activeMs；杀进程恢复补结算
     *        取计划秒数，避免把死后经过的时间算入专注）。
     * @param endWallOverride 结束墙钟（恢复补结算时为应到点时刻而非当前时刻）。
     * 先把 recorded=false 快照落盘再异步插库：即便插库瞬间进程再被杀，下次启动仍会补写。
     */
    @Synchronized
    private fun settle(
        status: FocusStatus,
        secondsOverride: Long,
        endWallOverride: Long
    ) {
        val s = _state.value
        // 已在终态（FINISHED/FAILED）时拒绝重复落库：worker 到点与手动/离开结束
        // 即使在极小窗口交错，也只有第一次 finish 生效。
        if (s.phase == Phase.IDLE || s.phase == Phase.FINISHED || s.phase == Phase.FAILED) {
            return
        }
        val seconds = secondsOverride.coerceAtLeast(0L)
        _state.value = s.copy(
            phase = if (status == FocusStatus.FAILED) Phase.FAILED else Phase.FINISHED,
            endedWallMs = endWallOverride,
            focusedSeconds = seconds,
            lastStatus = status
        )
        pauseStartElapsed = 0L
        pauseStartWallMs = 0L
        persist(recorded = false)
        scope.launch {
            runCatching { insertSessionIfAbsent(s, endWallOverride, seconds, status) }
            // 已存在同 startedAt 记录（崩溃前插库成功但标记未写）也视为已落库
            persist(recorded = true)
        }
    }

    private suspend fun insertSessionIfAbsent(
        s: State, endWall: Long, seconds: Long, status: FocusStatus
    ) {
        val dao = sessionDao ?: return
        if (dao.countByStartedAt(s.startedWallMs) > 0) return
        dao.insert(
            FocusSession(
                tagId = s.tagId,
                mode = s.mode.name,
                plannedSeconds = s.plannedSeconds,
                startedAtEpochMs = s.startedWallMs,
                endedAtEpochMs = endWall,
                focusedSeconds = seconds,
                status = status.name
            )
        )
    }

    // ---- 磁盘快照 ----------------------------------------------------------------

    private fun persist(recorded: Boolean) {
        val ctx = appContext ?: return
        val s = _state.value
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString(KEY_PHASE, s.phase.name)
                putString(KEY_MODE, s.mode.name)
                putLong(KEY_PLANNED, s.plannedSeconds)
                if (s.tagId == null) remove(KEY_TAG) else putLong(KEY_TAG, s.tagId)
                putLong(KEY_STARTED_WALL, s.startedWallMs)
                putLong(KEY_ENDED_WALL, s.endedWallMs)
                putLong(KEY_FOCUSED_SEC, s.focusedSeconds)
                if (s.lastStatus == null) remove(KEY_LAST_STATUS)
                else putString(KEY_LAST_STATUS, s.lastStatus.name)
                putLong(KEY_START_ELAPSED, startElapsed)
                putLong(KEY_ACC_PAUSE, accumulatedPauseMs)
                putLong(KEY_PAUSE_ELAPSED, pauseStartElapsed)
                putLong(KEY_PAUSE_WALL, pauseStartWallMs)
                putBoolean(KEY_RECORDED, recorded)
            }.apply()
        }
    }

    /** 读盘恢复；返回 null 表示无有效快照。 */
    private fun restore(app: Context): Recovery? {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val phaseName = prefs.getString(KEY_PHASE, null) ?: return null
        val phase = runCatching { Phase.valueOf(phaseName) }.getOrNull() ?: return null
        if (phase == Phase.IDLE) return null

        val mode = runCatching {
            FocusMode.valueOf(prefs.getString(KEY_MODE, FocusMode.STOPWATCH.name)!!)
        }.getOrDefault(FocusMode.STOPWATCH)
        val planned = prefs.getLong(KEY_PLANNED, 0L)
        val tagId = if (prefs.contains(KEY_TAG)) prefs.getLong(KEY_TAG, 0L) else null
        val startedWall = prefs.getLong(KEY_STARTED_WALL, 0L)
        val endedWall = prefs.getLong(KEY_ENDED_WALL, 0L)
        val focusedSec = prefs.getLong(KEY_FOCUSED_SEC, 0L)
        val lastStatus = prefs.getString(KEY_LAST_STATUS, null)
            ?.let { runCatching { FocusStatus.valueOf(it) }.getOrNull() }
        startElapsed = prefs.getLong(KEY_START_ELAPSED, 0L)
        accumulatedPauseMs = prefs.getLong(KEY_ACC_PAUSE, 0L)
        pauseStartElapsed = prefs.getLong(KEY_PAUSE_ELAPSED, 0L)
        pauseStartWallMs = prefs.getLong(KEY_PAUSE_WALL, 0L)

        val base = State(
            mode = mode,
            plannedSeconds = planned,
            tagId = tagId,
            startedWallMs = startedWall,
            endedWallMs = endedWall,
            focusedSeconds = focusedSec,
            lastStatus = lastStatus
        )

        val nowElapsed = SystemClock.elapsedRealtime()
        // elapsedRealtime 在重启后从小值重新计数：当前值小于历史锚点 => 设备重启过，
        // elapsed 锚点已失效，改用墙钟时间戳计算（暂停累计量是纯时长，两种时钟通用）。
        val rebooted = nowElapsed < startElapsed
        val nowWall = System.currentTimeMillis()

        when (phase) {
            Phase.RUNNING -> {
                val activeMs = if (rebooted) {
                    (nowWall - startedWall - accumulatedPauseMs).coerceAtLeast(0L)
                } else {
                    (nowElapsed - startElapsed - accumulatedPauseMs).coerceAtLeast(0L)
                }
                // 重新基线化到本次开机后的 elapsed 时钟，之后所有走时计算保持原公式
                startElapsed = nowElapsed - activeMs
                accumulatedPauseMs = 0L
                pauseStartElapsed = 0L
                pauseStartWallMs = 0L
                _state.value = base.copy(phase = Phase.RUNNING)

                return if (mode == FocusMode.COUNTDOWN && planned > 0 &&
                    activeMs >= planned * 1000L
                ) {
                    // 已过点：按计划秒数定格，结束时刻取应到点墙钟（含暂停占用的墙钟时长）
                    val dueWall = startedWall + planned * 1000L +
                        prefs.getLong(KEY_ACC_PAUSE, 0L)
                    settle(
                        FocusStatus.COMPLETED,
                        secondsOverride = planned,
                        endWallOverride = dueWall.coerceAtMost(nowWall)
                    )
                    Recovery(
                        phase = Phase.FINISHED,
                        mode = mode,
                        plannedSeconds = planned,
                        causedCompletion = true,
                        focusedSeconds = planned
                    )
                } else {
                    Recovery(
                        phase = Phase.RUNNING,
                        mode = mode,
                        plannedSeconds = planned,
                        remainingMs = if (mode == FocusMode.COUNTDOWN)
                            (planned * 1000L - activeMs).coerceAtLeast(0L) else 0L
                    )
                }
            }

            Phase.PAUSED -> {
                val frozenMs = if (rebooted) {
                    (pauseStartWallMs - startedWall - accumulatedPauseMs).coerceAtLeast(0L)
                } else {
                    (pauseStartElapsed - startElapsed - accumulatedPauseMs).coerceAtLeast(0L)
                }
                // 基线化：暂停起点钉在当前 elapsed，start 提前 frozenMs
                pauseStartElapsed = nowElapsed
                startElapsed = nowElapsed - frozenMs
                accumulatedPauseMs = 0L
                _state.value = base.copy(phase = Phase.PAUSED)
                return Recovery(phase = Phase.PAUSED, mode = mode, plannedSeconds = planned)
            }

            Phase.FINISHED, Phase.FAILED -> {
                _state.value = base.copy(
                    phase = if (phase == Phase.FAILED) Phase.FAILED else Phase.FINISHED
                )
                // 终态快照：终态时 elapsed 锚点已清零，无需重基。
                if (!prefs.getBoolean(KEY_RECORDED, true)) {
                    scope.launch {
                        runCatching {
                            insertSessionIfAbsent(
                                base.copy(phase = phase), endedWall, focusedSec,
                                lastStatus ?: FocusStatus.COMPLETED
                            )
                        }
                        persist(recorded = true)
                    }
                }
                return Recovery(
                    phase = phase, mode = mode, plannedSeconds = planned,
                    focusedSeconds = focusedSec
                )
            }

            Phase.IDLE -> return null
        }
    }
}
