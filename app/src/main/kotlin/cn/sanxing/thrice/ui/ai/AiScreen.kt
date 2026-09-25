@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.sanxing.thrice.ui.ai

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.ai.AiChatMessage
import cn.sanxing.thrice.data.ai.AiChatRole
import cn.sanxing.thrice.data.ai.AiProfile
import cn.sanxing.thrice.data.ai.AiProvider
import cn.sanxing.thrice.data.ai.DEFAULT_AI_PERSONA_ZH
import cn.sanxing.thrice.data.data.local.DatabaseProvider
import cn.sanxing.thrice.data.domain.model.AiMessage
import cn.sanxing.thrice.data.ai.toChatHistory
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 气泡宽度上限（占屏比例之外的硬上限，避免平板上气泡过宽）。 */
private val BubbleMaxWidth = 320.dp

/**
 * 错误回执固定前缀：发送异常时以 assistant 消息落库，UI 据此染错色，
 * 且下一轮发送构造历史时会剔除（不回灌给模型）。
 */
private const val ERROR_PREFIX = "⚠️ "

/** 首条用户消息自动生成会话标题的最大长度。 */
private const val AUTO_TITLE_MAX = 20

/**
 * 「AI问」对话主界面：
 * 会话 / 消息全部经 Room 持久化；模态抽屉管理会话；消息气泡长按可复制 / 修改 / 删除。
 * 修改用户消息会裁掉该消息及其后的全部消息并自动重发；服务商未配置时点发送直接打开配置弹窗。
 */
@Composable
fun AiScreen(
    container: AppContainer,
    // 是否为 HorizontalPager 当前停留页。预组合的相邻页为 false。
    isCurrentPage: Boolean = true,
    // 打开服务商配置全屏页（R17：由独立窗口 Dialog 改为导航路由，
    // 彻底消除 Dialog 窗口内导航栏 inset 取不到导致底部按钮被裁切的问题）。
    onOpenProviderConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = container.aiChatRepository

    // 与 Hilt 共用同一 Room 实例：仓库的 observeMessages 映射后丢失行 id，
    // 单条消息改删需要 id，因此消息列表直接订阅 DAO；写操作仍统一走仓库。
    val aiDao = remember { DatabaseProvider.get(context).aiChatDao() }

    // 是否已完成服务商配置：null = 尚未探测。从配置页返回（ON_RESUME）后重新探测。
    var configured by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        configured = container.aiSettingsRepository.isConfigured()
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    configured = container.aiSettingsRepository.isConfigured()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    var showPermissionDialog by remember { mutableStateOf(false) }

    // AI 人设（系统提示词）选择弹窗；当前人设名展示在输入框上方的 chip
    var showPersonaDialog by remember { mutableStateOf(false) }
    val activePersona by container.aiPersonaRepository.activePersona
        .collectAsState(initial = DEFAULT_AI_PERSONA_ZH)

    // 正在编辑（修改后重发）的消息；null 表示无编辑弹窗
    var editingMessage by remember { mutableStateOf<AiMessage?>(null) }

    // 会话抽屉开关：页内覆盖层实现（不再使用 ModalNavigationDrawer——它的抽屉与
    // scrim 运行在窗口级 Popup 中，不随 HorizontalPager 翻页被裁剪，切到隔壁 tab
    // 后仍浮在其上方造成重影；R15/R16 的 close()/snapTo/内容门控均无法移除 Popup
    // 容器本身）。页内抽屉只是本页 Box 的子节点，Pager 翻页时随页面一起被裁剪。
    var drawerOpen by remember { mutableStateOf(false) }

    // 切到其他 tab / 横滑离开本页时立即关闭（普通布尔状态，瞬时生效，无动画竞态）。
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) drawerOpen = false
    }

    // null = 会话列表尚未从库中读出；空列表 = 确实没有任何会话
    val conversations by repository.observeConversations()
        .collectAsState(initial = null)
    var currentId by remember { mutableStateOf<Long?>(null) }

    // 已保存的多套服务商配置与当前活动配置（顶栏切换菜单使用）
    val profiles by container.aiSettingsRepository.profiles
        .collectAsState(initial = null)
    val activeProfileId by container.aiSettingsRepository.activeProfileId
        .collectAsState(initial = null)
    var profileMenuOpen by remember { mutableStateOf(false) }

    // 列表首次加载、删除当前会话后自动选中最近一条；没有会话时回到空态
    LaunchedEffect(conversations) {
        val list = conversations
        if (list.isNullOrEmpty()) {
            if (list != null) currentId = null
            return@LaunchedEffect
        }
        if (currentId == null || list.none { it.id == currentId }) {
            currentId = list.first().id
        }
    }

    // 消息随当前会话切换订阅；切换瞬间先清空，避免闪现上一个会话的内容
    val messages by produceState(initialValue = emptyList<AiMessage>(), currentId) {
        val id = currentId
        value = emptyList()
        if (id != null) {
            aiDao.observeMessages(id).collect { value = it }
        }
    }

    val listState = rememberLazyListState()
    // 新消息或加载态变化时自动滚到底（加载气泡占最后一个位置）
    LaunchedEffect(messages.size, sending) {
        val total = messages.size + if (sending) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    /** 一次完整发送：追加用户消息 →（首条时）自动命名 → 请求模型 → 追加回复 / 错误回执。 */
    suspend fun runSend(convId: Long, text: String) {
        sending = true
        try {
            // 历史取裁剪前的库内快照（此时本次用户消息尚未 append）；错误回执不回灌。
            // 直接读 DAO 拿到 AiMessage（角色为字符串），与消息列表展示走同一数据源。
            val history = aiDao.getMessages(convId)
                .filter { it.role == AiChatRole.USER.name || it.role == AiChatRole.ASSISTANT.name }
                .filterNot {
                    it.role == AiChatRole.ASSISTANT.name && it.content.startsWith(ERROR_PREFIX)
                }
                .toChatHistory()

            repository.appendMessage(convId, AiChatMessage.user(text))
            repository.getConversation(convId)?.let { conversation ->
                if (conversation.title.isBlank()) {
                    repository.renameConversation(convId, text.take(AUTO_TITLE_MAX))
                }
            }

            val reply = container.aiChatOrchestrator.send(text, history)
            repository.appendMessage(
                convId,
                AiChatMessage.assistant(reply.ifBlank { context.getString(R.string.ai_reply_empty) })
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IllegalStateException（未配置）/ AiException（网络类）消息均已是中文用户提示
            val errorText = ERROR_PREFIX +
                (e.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.ai_send_failed_generic))
            repository.appendMessage(convId, AiChatMessage.assistant(errorText))
        } finally {
            sending = false
        }
    }

    /** 发送入口：未配置直接打开配置弹窗；当前无会话时先建会话。 */
    fun requestSend(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || sending) return
        scope.launch {
            if (!container.aiSettingsRepository.isConfigured()) {
                configured = false
                onOpenProviderConfig()
                return@launch
            }
            configured = true
            // 极端时序下（初始加载 / 刚删除当前会话）currentId 可能短暂为 null：
            // 能复用最近会话就复用，避免凭空多建一个空会话
            val convId = currentId
                ?: conversations?.firstOrNull()?.id?.also { currentId = it }
                ?: repository.createConversation().also { currentId = it }
            input = ""
            runSend(convId, text)
        }
    }

    // 系统语音识别：识别结果填入输入框并直接发送给 AI（无需额外权限；
    // 依赖设备上的语音识别服务，如 Gboard / 系统语音引擎）
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (spoken.isNotEmpty()) {
                input = spoken
                requestSend(spoken)
            }
        }
    }

    fun startVoiceInput() {
        if (sending) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.ai_voice_hint))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            voiceLauncher.launch(intent)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.ai_voice_no_recognizer),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 修改用户消息：就地更新 → 删除该消息及其后所有消息 → 以新内容走一次正常发送。 */
    fun editAndResend(target: AiMessage, newContent: String) {
        val text = newContent.trim()
        if (text.isEmpty() || sending) return
        val convId = currentId ?: return
        // 以点击修改时的列表顺序为裁剪基准，避免等待 Flow 刷新带来的时序差
        val snapshot = messages
        scope.launch {
            if (!container.aiSettingsRepository.isConfigured()) {
                configured = false
                onOpenProviderConfig()
                return@launch
            }
            configured = true
            sending = true
            try {
                val index = snapshot.indexOfFirst { it.id == target.id }
                if (index >= 0) {
                    repository.updateMessageContent(target.id, text)
                    snapshot.drop(index).forEach { repository.deleteMessage(it.id) }
                }
                sending = false
                runSend(convId, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sending = false
                Toast.makeText(
                    context,
                    e.message ?: context.getString(R.string.ai_send_failed_generic),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun deleteMessage(message: AiMessage) {
        if (sending) return
        scope.launch { repository.deleteMessage(message.id) }
    }

    fun newConversation() {
        if (sending) return
        scope.launch {
            currentId = repository.createConversation()
            drawerOpen = false
        }
    }

    fun selectConversation(id: Long) {
        if (sending) return
        scope.launch {
            currentId = id
            drawerOpen = false
        }
    }

    // 页内抽屉：Scaffold 与抽屉覆盖层同处一个 Box，抽屉只存在于本页组合树中，
    // HorizontalPager 翻页时随本页一起被裁剪移除（ModalNavigationDrawer 的窗口级
    // Popup 无法被 Pager 裁剪，是此前抽屉浮在隔壁 tab 之上的根因）。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface
                .copy(alpha = LocalUiMaskAlpha.current),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ai_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                            .copy(alpha = LocalUiMaskAlpha.current)
                    ),
                    navigationIcon = {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.ai_conversations_cd)
                            )
                        }
                    },
                    actions = {
                        // 快速切换已保存的服务商配置；菜单末尾进入完整配置管理
                        Box {
                            IconButton(onClick = { profileMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.SwapHoriz,
                                    contentDescription = stringResource(R.string.ai_model_switch_cd)
                                )
                            }
                            DropdownMenu(
                                expanded = profileMenuOpen,
                                onDismissRequest = { profileMenuOpen = false }
                            ) {
                                val savedProfiles = profiles.orEmpty()
                                if (savedProfiles.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.ai_no_active_profile_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                savedProfiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(profileLabel(profile))
                                                Text(
                                                    text = profile.model.ifBlank {
                                                        stringResource(R.string.ai_profile_model_unset)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            scope.launch {
                                                container.aiSettingsRepository
                                                    .setActiveProfile(profile.id)
                                            }
                                            profileMenuOpen = false
                                        },
                                        trailingIcon = if (profile.id == activeProfileId) {
                                            {
                                                Icon(
                                                    Icons.Filled.VerifiedUser,
                                                    contentDescription = null
                                                )
                                            }
                                        } else null
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.ai_dialog_provider_title))
                                    },
                                    onClick = {
                                        profileMenuOpen = false
                                        onOpenProviderConfig()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Settings, contentDescription = null)
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { showPermissionDialog = true }) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = stringResource(R.string.ai_perms_cd)
                            )
                        }
                        IconButton(onClick = onOpenProviderConfig) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.ai_provider_cd)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                AiInputBar(
                    value = input,
                    onValueChange = { input = it },
                    sending = sending,
                    onSend = { requestSend(input) },
                    onVoice = { startVoiceInput() },
                    personaName = activePersona.name,
                    onSelectPersona = { showPersonaDialog = true },
                    modifier = Modifier.imePadding()
                )
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val list = conversations
                when {
                    // 会话列表加载中
                    list == null -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(34.dp)
                            .align(Alignment.Center),
                        strokeWidth = 3.dp
                    )

                    // 一个会话都没有：空态引导，示例点击会先建会话再发送
                    list.isEmpty() -> AiEmptyState(
                        showNewChat = true,
                        onNewChat = { newConversation() },
                        onExampleClick = { requestSend(it) }
                    )

                    // 当前会话无消息：保留示例引导
                    messages.isEmpty() && !sending -> AiEmptyState(
                        showNewChat = false,
                        onNewChat = { newConversation() },
                        onExampleClick = { requestSend(it) }
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 10.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            AiMessageBubble(
                                message = message,
                                enabled = !sending,
                                onEdit = { editingMessage = it },
                                onDelete = { deleteMessage(it) }
                            )
                        }
                        if (sending) {
                            item { AiTypingBubble() }
                        }
                    }
                }
            }
        }

        // 半透明遮罩：点击关闭。与面板同为页内节点（非窗口级 Popup），
        // Pager 翻页时随本页一起消失，不会再浮在隔壁 tab 之上。
        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) {
                        detectTapGestures { drawerOpen = false }
                    }
            )
        }

        // 左侧滑出面板：宽 320dp（同 ModalDrawer 默认最大宽度），
        // 顶部避开状态栏、底部避开手势导航条。
        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            AiConversationDrawer(
                modifier = Modifier
                    .width(320.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                conversations = conversations.orEmpty(),
                currentId = currentId,
                enabled = !sending,
                onNewConversation = { newConversation() },
                onSelectConversation = { selectConversation(it) },
                onRenameConversation = { conversation, title ->
                    scope.launch { repository.renameConversation(conversation.id, title) }
                },
                onDeleteConversation = { conversation ->
                    scope.launch { repository.deleteConversation(conversation.id) }
                }
            )
        }
    }

    // 单条用户消息修改弹窗（确认后裁剪历史并自动重发）
    editingMessage?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text(stringResource(R.string.ai_message_edit_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingMessage = null
                    editAndResend(target, draft)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showPermissionDialog) {
        AiPermissionDialog(
            container = container,
            onDismiss = { showPermissionDialog = false }
        )
    }

    if (showPersonaDialog) {
        AiPersonaDialog(
            container = container,
            onDismiss = { showPersonaDialog = false }
        )
    }
}

/** 单条消息气泡：用户靠右 primary 容器色；AI 靠左 surface 卡片；错误回执用 error 容器色。 */
@Composable
private fun AiMessageBubble(
    message: AiMessage,
    enabled: Boolean,
    onEdit: (AiMessage) -> Unit,
    onDelete: (AiMessage) -> Unit
) {
    val isUser = message.role == AiChatRole.USER.name
    val isError = !isUser && message.content.startsWith(ERROR_PREFIX)
    var menuOpen by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedHint = stringResource(R.string.ai_message_copied)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Box {
                val containerColor = when {
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    isError -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current)
                }
                val contentColor = when {
                    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                // 用户气泡右下角、AI 气泡左下角做小尖角感
                val shape = RoundedCornerShape(16.dp).let {
                    if (isUser) it.copy(bottomEnd = CornerSize(4.dp))
                    else it.copy(bottomStart = CornerSize(4.dp))
                }
                Surface(
                    modifier = Modifier
                        .widthIn(max = BubbleMaxWidth)
                        .pointerInput(enabled) {
                            detectTapGestures(
                                onLongPress = { if (enabled) menuOpen = true }
                            )
                        },
                    shape = shape,
                    color = containerColor,
                    contentColor = contentColor,
                    tonalElevation = if (isUser || isError) 0.dp else 1.dp
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                    )
                }
                MessageDropdownMenu(
                    expanded = menuOpen,
                    isUser = isUser,
                    onDismiss = { menuOpen = false },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        Toast.makeText(context, copiedHint, Toast.LENGTH_SHORT).show()
                    },
                    onEdit = { onEdit(message) },
                    onDelete = { onDelete(message) }
                )
            }
        }
    }
}

/** 消息长按菜单：用户消息 复制/修改/删除；assistant 消息 复制/删除。 */
@Composable
private fun MessageDropdownMenu(
    expanded: Boolean,
    isUser: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ai_message_copy)) },
            onClick = {
                onDismiss()
                onCopy()
            },
            leadingIcon = {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
            }
        )
        if (isUser) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_message_edit)) },
                onClick = {
                    onDismiss()
                    onEdit()
                },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ai_message_delete)) },
            onClick = {
                onDismiss()
                onDelete()
            },
            leadingIcon = {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        )
    }
}

/** AI 回复加载中：靠左的三点动画气泡。 */
@Composable
private fun AiTypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp).copy(bottomStart = CornerSize(4.dp)),
            color = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current),
            tonalElevation = 1.dp
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val transition = rememberInfiniteTransition(label = "aiTyping")
                repeat(3) { index ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(520),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(index * 170)
                        ),
                        label = "dot$index"
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

/** 空会话引导：问候语 + 可点示例 prompt；无会话时额外展示「开始新对话」按钮。 */
@Composable
private fun AiEmptyState(
    showNewChat: Boolean,
    onNewChat: () -> Unit,
    onExampleClick: (String) -> Unit
) {
    val examples = listOf(
        stringResource(R.string.ai_example_1),
        stringResource(R.string.ai_example_2),
        stringResource(R.string.ai_example_3),
        stringResource(R.string.ai_example_4)
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ai_empty_greeting),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ai_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showNewChat) {
            item {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Button(onClick = onNewChat) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ai_start_new_chat))
                }
            }
        }
        items(examples) { prompt ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onExampleClick(prompt) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current),
                tonalElevation = 1.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** 底部输入栏：高度自适应（最多 4 行后内部滚动）的 TextField + 发送按钮，发送中禁用。 */
@Composable
private fun AiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    personaName: String,
    onSelectPersona: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current),
        tonalElevation = 2.dp
    ) {
        Column {
            // 当前人设（系统提示词）入口：点击切换默认 / 自建提示词
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelectPersona)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.ai_persona_chip_prefix, personaName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.ai_persona_cd),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.size(8.dp))
            // 语音输入：系统语音识别转文字后直接发送
            IconButton(
                onClick = onVoice,
                enabled = !sending,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.ai_voice_input_cd)
                )
            }
            Spacer(Modifier.size(4.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = !sending && value.isNotBlank(),
                modifier = Modifier.size(46.dp)
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.ai_send_cd)
                    )
                }
            }
            }
        }
    }
}

/** 配置在切换菜单中的显示名：自定义名优先，其次服务商预设名，最后回落「自定义」。 */
@Composable
private fun profileLabel(profile: AiProfile): String {
    val customName = profile.displayName.trim()
    if (customName.isNotBlank()) return customName
    val preset = AiProvider.fromId(profile.providerId)
    return if (preset != null && !preset.custom) preset.displayName
    else stringResource(R.string.ai_provider_custom)
}
