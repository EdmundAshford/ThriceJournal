@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.sanxing.thrice.ui.ai

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.ai.AiProfile
import cn.sanxing.thrice.data.ai.AiProtocol
import cn.sanxing.thrice.data.ai.AiProvider
import cn.sanxing.thrice.data.ai.AiSettings
import cn.sanxing.thrice.ui.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 测试连接 / 拉取模型的行内状态（[ok] 决定成功 / 失败配色）。 */
private data class InlineStatus(
    val ok: Boolean,
    val message: String
)

/**
 * AI 服务商配置（全屏路由页，注册于 AppNavHost 的 ai_provider 路由）。
 *
 * 两个界面：
 * - 配置列表：展示全部已保存配置（可来自不同服务商），可切换当前使用、新建、编辑、删除；
 * - 配置编辑：选择服务商预设、地址 / 协议 / 模型（可在线拉取 /models）、API Key、
 *   测试连接与保存。
 *
 * R17：此前是独立窗口全屏 Dialog，Dialog 窗口内 WindowInsets 取不到真实导航栏
 * 高度（decorView / WindowMetrics 两版方案在部分全面屏机型上仍为 0），底部
 * 「保存 / 新建配置」按钮被手势条裁切。改为 Activity 窗口内的普通全屏路由后，
 * edge-to-edge（MainActivity.enableEdgeToEdge）下 navigationBarsPadding
 * 由系统正常分发，按钮始终完整可见。
 *
 * @param onSaved 保存 / 切换成功后的回调（调用方据此刷新配置态）
 */
@Composable
fun AiProviderRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onSaved: () -> Unit = {}
) {
    val repository = container.aiSettingsRepository
    val scope = rememberCoroutineScope()
    val profiles by repository.profiles.collectAsState(initial = null)
    val activeId by repository.activeProfileId.collectAsState(initial = null)

    // null = 列表页；非空 pair：(编辑目标 profile，null 表示新建)
    var editing by remember { mutableStateOf<AiProfile?>(null) }
    var creating by remember { mutableStateOf(false) }

    if (creating || editing != null) {
        ProfileEditScreen(
            container = container,
            target = editing,
            onBack = {
                editing = null
                creating = false
            },
            onSaved = {
                editing = null
                creating = false
                onSaved()
            }
        )
    } else {
        ProfileListScreen(
            profiles = profiles,
            activeId = activeId,
            onBack = onBack,
            onNew = { creating = true },
            onEdit = { editing = it },
            onActivate = { id ->
                scope.launch {
                    repository.setActiveProfile(id)
                    onSaved()
                }
            },
            onDelete = { profile ->
                scope.launch {
                    repository.deleteProfile(profile.id)
                    onSaved()
                }
            }
        )
    }
}

/** 配置列表页。 */
@Composable
private fun ProfileListScreen(
    profiles: List<AiProfile>?,
    activeId: String?,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (AiProfile) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (AiProfile) -> Unit
) {
    // 壁纸模式下主题 surface 带透明度，配置页强制不透明，避免透出后层重影
    val opaqueSurface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    var deleting by remember { mutableStateOf<AiProfile?>(null) }

    Scaffold(
        containerColor = opaqueSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_profiles_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ai_close_cd)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.ai_profile_new)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = opaqueSurface)
            )
        },
        bottomBar = {
            // 全屏路由运行在 Activity 窗口内，navigationBars inset 正常分发，
            // 手势导航条不再遮挡「新增配置」按钮。
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ai_profile_new))
                }
            }
        }
    ) { padding ->
        val list = profiles
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                list == null -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(34.dp)
                        .align(Alignment.Center),
                    strokeWidth = 3.dp
                )

                list.isEmpty() -> Text(
                    text = stringResource(R.string.ai_profile_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    list.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            active = profile.id == activeId,
                            onActivate = { onActivate(profile.id) },
                            onEdit = { onEdit(profile) },
                            onDelete = { deleting = profile }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ai_profile_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ai_profile_delete_confirm,
                        profileLabel(profile)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(profile)
                    deleting = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 单个配置条目：整行点击切换为当前使用；右侧编辑 / 删除按钮；使用中标 RadioButton。 */
@Composable
private fun ProfileRow(
    profile: AiProfile,
    active: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onActivate
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = active, onClick = onActivate)
            Column(Modifier.weight(1f)) {
                Text(
                    text = profileLabel(profile),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.model.ifBlank {
                        stringResource(R.string.ai_profile_model_unset)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.ai_profile_edit_cd)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.ai_profile_delete_cd)
                )
            }
        }
    }
}

/** 列表显示名：自定义名为空时回落到服务商预设名。 */
@Composable
private fun profileLabel(profile: AiProfile): String {
    val customName = profile.displayName.trim()
    if (customName.isNotBlank()) return customName
    val preset = AiProvider.fromId(profile.providerId)
    return if (preset != null && !preset.custom) preset.displayName
    else stringResource(R.string.ai_provider_custom)
}

/**
 * 配置编辑页（新建或编辑已有 [target]，null 表示新建）。
 * 表单逻辑沿用 R13：服务商单选自动回填；custom 可改名称 / 协议；
 * 任意协议均可用当前草稿的地址 + Key 在线拉取模型列表；测试连接不要求先保存。
 */
@Composable
private fun ProfileEditScreen(
    container: AppContainer,
    target: AiProfile?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = container.aiSettingsRepository
    val opaqueSurface = MaterialTheme.colorScheme.surface.copy(alpha = 1f)

    var providerId by remember {
        mutableStateOf(target?.providerId ?: AiProvider.ID_DEEPSEEK)
    }
    var displayName by remember { mutableStateOf(target?.displayName.orEmpty()) }
    var baseUrl by remember {
        mutableStateOf(target?.baseUrl ?: AiProvider.DEEPSEEK.baseUrl)
    }
    var model by remember {
        mutableStateOf(target?.model ?: AiProvider.DEEPSEEK.defaultModel)
    }
    var protocol by remember {
        mutableStateOf(target?.protocol ?: AiProvider.DEEPSEEK.protocol)
    }
    var apiKeyInput by remember { mutableStateOf("") }
    var clearKeyPending by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<InlineStatus?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchStatus by remember { mutableStateOf<InlineStatus?>(null) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }

    val savedKey = target?.apiKey.orEmpty()
    val selectedProvider = AiProvider.fromId(providerId) ?: AiProvider.CUSTOM
    val isCustom = selectedProvider.custom
    val isDoubao = providerId == AiProvider.ID_DOUBAO
    val busy = testing || fetching

    fun effectiveApiKey(): String =
        apiKeyInput.trim().ifEmpty { if (clearKeyPending) "" else savedKey }

    fun buildDraftSettings(): AiSettings = AiSettings(
        providerId = providerId,
        baseUrl = baseUrl.trim(),
        apiKey = effectiveApiKey(),
        model = model.trim(),
        protocol = protocol,
        displayName = displayName.trim().ifEmpty { null }
    )

    fun validate(): String? = when {
        isCustom && displayName.isBlank() ->
            context.getString(R.string.ai_validate_display_name)
        baseUrl.isBlank() -> context.getString(R.string.ai_validate_base_url)
        model.isBlank() -> context.getString(R.string.ai_validate_model)
        effectiveApiKey().isBlank() -> context.getString(R.string.ai_validate_api_key)
        else -> null
    }

    fun applyProvider(provider: AiProvider) {
        if (busy) return
        providerId = provider.id
        baseUrl = provider.baseUrl
        model = provider.defaultModel
        protocol = provider.protocol
        testStatus = null
        fetchStatus = null
    }

    /** 保存当前草稿（新建或更新）；成功后退回列表。 */
    fun doSave() {
        val error = validate()
        if (error != null) {
            testStatus = InlineStatus(false, error)
            return
        }
        scope.launch {
            val apiKeyArg = when {
                target == null -> apiKeyInput.trim().ifBlank { "" }
                clearKeyPending -> ""
                apiKeyInput.isNotBlank() -> apiKeyInput.trim()
                else -> null
            }
            try {
                repository.saveProfile(
                    id = target?.id,
                    providerId = providerId,
                    displayName = displayName.trim(),
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKeyArg,
                    model = model.trim(),
                    protocol = protocol
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.ai_saved_toast),
                    Toast.LENGTH_SHORT
                ).show()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                testStatus = InlineStatus(
                    false,
                    e.message ?: context.getString(R.string.ai_send_failed_generic)
                )
            }
        }
    }

    Scaffold(
        containerColor = opaqueSurface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (target == null) R.string.ai_profile_new
                            else R.string.ai_profile_edit
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ai_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = opaqueSurface)
            )
        },
        bottomBar = {
            // 全屏路由：navigationBarsPadding 避开手势条；imePadding 键盘弹起时跟随上移
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                testStatus?.let { status ->
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val error = validate()
                            if (error != null) {
                                testStatus = InlineStatus(false, error)
                                return@OutlinedButton
                            }
                            scope.launch {
                                testing = true
                                testStatus = InlineStatus(
                                    true,
                                    context.getString(R.string.ai_test_running)
                                )
                                try {
                                    container.aiClientFactory.create(buildDraftSettings())
                                        .testConnection()
                                    testStatus = InlineStatus(
                                        true,
                                        context.getString(R.string.ai_test_ok)
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    testStatus = InlineStatus(
                                        false,
                                        context.getString(
                                            R.string.ai_test_failed,
                                            e.message ?: context.getString(
                                                R.string.ai_send_failed_generic
                                            )
                                        )
                                    )
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        enabled = !busy
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.ai_test_running))
                        } else {
                            Text(stringResource(R.string.ai_test_connection))
                        }
                    }
                    Button(
                        onClick = { doSave() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_field_provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AiProvider.PRESETS.forEach { provider ->
                ProviderRadioRow(
                    provider = provider,
                    selected = provider.id == providerId,
                    enabled = !busy,
                    onSelect = { applyProvider(provider) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            if (isCustom) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.ai_field_display_name)) },
                    supportingText = {
                        Text(stringResource(R.string.ai_provider_custom_hint))
                    },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.ai_field_base_url)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )

            if (isCustom) {
                Text(
                    stringResource(R.string.ai_field_protocol),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = protocol == AiProtocol.OPENAI_COMPAT,
                        onClick = {
                            protocol = AiProtocol.OPENAI_COMPAT
                            fetchStatus = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text(stringResource(R.string.ai_protocol_openai)) }
                    SegmentedButton(
                        selected = protocol == AiProtocol.CLAUDE,
                        onClick = {
                            protocol = AiProtocol.CLAUDE
                            fetchStatus = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text(stringResource(R.string.ai_protocol_claude)) }
                }
            }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.ai_field_model)) },
                singleLine = true,
                enabled = !busy,
                supportingText = if (isDoubao) {
                    { Text(stringResource(R.string.ai_model_hint_doubao)) }
                } else null,
                isError = (isDoubao || isCustom) && model.isBlank(),
                trailingIcon = {
                    IconButton(
                        enabled = !fetching,
                        onClick = {
                            if (baseUrl.isBlank()) {
                                fetchStatus = InlineStatus(
                                    false,
                                    context.getString(R.string.ai_validate_base_url)
                                )
                                return@IconButton
                            }
                            if (effectiveApiKey().isBlank()) {
                                fetchStatus = InlineStatus(
                                    false,
                                    context.getString(R.string.ai_validate_api_key)
                                )
                                return@IconButton
                            }
                            scope.launch {
                                fetching = true
                                fetchStatus = InlineStatus(
                                    true,
                                    context.getString(R.string.ai_fetch_models_running)
                                )
                                try {
                                    val result = container.aiClientFactory.fetchAvailableModels(
                                        baseUrl = baseUrl.trim(),
                                        apiKey = effectiveApiKey(),
                                        protocol = protocol
                                    )
                                    if (result.isEmpty()) {
                                        fetchStatus = InlineStatus(
                                            false,
                                            context.getString(R.string.ai_fetch_models_empty)
                                        )
                                    } else {
                                        fetchedModels = result
                                        fetchStatus = InlineStatus(
                                            true,
                                            context.getString(
                                                R.string.ai_fetch_models_success,
                                                result.size
                                            )
                                        )
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    fetchStatus = InlineStatus(
                                        false,
                                        context.getString(
                                            R.string.ai_fetch_models_failed,
                                            e.message ?: context.getString(
                                                R.string.ai_send_failed_generic
                                            )
                                        )
                                    )
                                } finally {
                                    fetching = false
                                }
                            }
                        }
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.ai_fetch_models)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            fetchStatus?.let { status ->
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { input: String ->
                    apiKeyInput = input
                    clearKeyPending = false
                },
                label = { Text(stringResource(R.string.ai_field_api_key)) },
                placeholder = {
                    if (clearKeyPending) {
                        Text(stringResource(R.string.ai_api_key_will_clear))
                    } else if (savedKey.isNotBlank()) {
                        Text(stringResource(R.string.ai_api_key_saved_hint))
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (showApiKey) R.string.ai_hide_key
                                else R.string.ai_show_key
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (savedKey.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = {
                            apiKeyInput = ""
                            clearKeyPending = true
                        },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.ai_clear_key))
                    }
                }
            }

            // 底部额外留白，滚动到最末时内容不贴边
            Spacer(Modifier.height(12.dp))
        }
    }

    fetchedModels?.let { models ->
        ModelPickerDialog(
            models = models,
            onPick = { picked ->
                model = picked
                fetchedModels = null
            },
            onDismiss = { fetchedModels = null }
        )
    }
}

/** 服务商单选项：RadioButton + 名称（custom 显示本地化「自定义」），整行可点。 */
@Composable
private fun ProviderRadioRow(
    provider: AiProvider,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Text(
            text = if (provider.custom) stringResource(R.string.ai_provider_custom)
            else provider.displayName,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
