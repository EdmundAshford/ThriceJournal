package cn.sanxing.thrice.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.ui.common.WheelNumberPicker
import cn.sanxing.thrice.ui.theme.AppFontOption
import cn.sanxing.thrice.ui.theme.LocalAppFontFamily
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 输入停止后的自动保存防抖时长。 */
private const val AUTOSAVE_DEBOUNCE_MS = 500L

/** 一次落库内容快照，用于防抖比对，避免仅打开笔记就刷新 updatedAt。 */
private data class NoteSnapshot(
    val title: String,
    val content: String,
    val folderId: Long?,
    val fontKey: String?,
    val fontSizeSp: Int?
)

/**
 * 随身记编辑器（NotesScreen 内部 Box 全屏覆盖，不走 NavHost，
 * 不会触发底栏 HorizontalPager 页面销毁）。
 *
 * @param note id = 0 表示新建未落库笔记（首次输入才插入，空笔记不落库）
 * @param globalFontKey 笔记全局字体（null = 跟随应用整体字体）
 * @param globalFontSize 笔记全局字号
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(
    repository: NotesRepository,
    note: Note,
    folders: List<NoteFolder>,
    globalFontKey: String?,
    globalFontSize: Int,
    onClose: () -> Unit
) {
    // 独立于 Composition 的协程作用域：onDispose 时仍可把最后一次写入送进 Room
    val editorScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // 串行化防抖保存与 onDispose 保存，避免并发时新建笔记被插入两次
    val saveMutex = remember { Mutex() }

    var title by remember { mutableStateOf(note.title) }
    var bodyValue by remember { mutableStateOf(TextFieldValue(note.content, TextRange(0))) }
    var persistedId by remember { mutableStateOf(note.id) }
    var folderId by remember { mutableStateOf(note.folderId) }
    var fontKey by remember { mutableStateOf(note.fontKey) }
    var fontSizeSp by remember { mutableStateOf(note.fontSizeSp) }
    var lastSaved by remember {
        mutableStateOf(
            NoteSnapshot(note.title, note.content, note.folderId, note.fontKey, note.fontSizeSp)
        )
    }

    // 标签：全部标签用于选择弹窗；selectedTagIds 为本篇选中态；
    // lastSyncedTagIds 记录最近一次已落库集合，避免无变化的重复写入
    val allTags by repository.observeTags().collectAsState(initial = emptyList())
    var selectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var lastSyncedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showTagPicker by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    var showSizePicker by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 删除 / 返回完成后阻止 onDispose 再次落库（删除后重插）
    var finished by remember { mutableStateOf(false) }

    /**
     * 立即落库：内容无变化不重复写；新建空笔记不入库；Room 写入不可取消。
     * 标签同步独立于内容快照——即使内容没动，标签选择变化也要写入。
     */
    suspend fun persist() = saveMutex.withLock {
        if (finished) return@withLock
        val snap = NoteSnapshot(title, bodyValue.text, folderId, fontKey, fontSizeSp)
        var persisted = persistedId
        if (snap != lastSaved) {
            if (persisted == 0L && snap.title.isBlank() && snap.content.isBlank()) {
                // 仍未落库的空白笔记：只更新快照，不插入
                lastSaved = snap
            } else {
                persisted = withContext(NonCancellable) {
                    repository.saveNote(
                        note.copy(
                            id = persisted,
                            title = snap.title,
                            content = snap.content,
                            folderId = snap.folderId,
                            fontKey = snap.fontKey,
                            fontSizeSp = snap.fontSizeSp
                        )
                    )
                }
                persistedId = persisted
                lastSaved = snap
            }
        }
        if (persisted != 0L && selectedTagIds != lastSyncedTagIds) {
            withContext(NonCancellable) {
                repository.setNoteTags(persisted, selectedTagIds.toList())
            }
            lastSyncedTagIds = selectedTagIds
        }
    }

    // 打开已落库笔记时订阅它当前挂载的标签；id = 0 的新笔记从空选择开始
    LaunchedEffect(persistedId) {
        if (persistedId == 0L) {
            selectedTagIds = emptySet()
            lastSyncedTagIds = emptySet()
        } else {
            repository.observeTagIdsForNote(persistedId).collect { ids ->
                val set = ids.toSet()
                selectedTagIds = set
                lastSyncedTagIds = set
            }
        }
    }

    /** 切换某标签后立即触发一次同步落库（笔记尚为空时先记在内存，首次保存时写入）。 */
    fun toggleNoteTag(tagId: Long) {
        selectedTagIds = if (tagId in selectedTagIds) {
            selectedTagIds - tagId
        } else {
            selectedTagIds + tagId
        }
        editorScope.launch { persist() }
    }

    // 500ms 防抖自动保存：collectLatest 在新输入到来时取消上一段等待
    LaunchedEffect(Unit) {
        snapshotFlow { NoteSnapshot(title, bodyValue.text, folderId, fontKey, fontSizeSp) }
            .collectLatest {
                delay(AUTOSAVE_DEBOUNCE_MS)
                persist()
            }
    }

    // 退出编辑器时兜底保存一次
    DisposableEffect(Unit) {
        onDispose {
            editorScope.launch { withContext(NonCancellable) { persist() } }
        }
    }

    /** 返回：先保存完成再退出。 */
    fun requestClose() {
        editorScope.launch {
            persist()
            finished = true
            onClose()
        }
    }

    // 生效字体：单篇覆盖 → 笔记默认字体 → 应用整体字体
    val appFontFamily = LocalAppFontFamily.current
    val effectiveFamily = when {
        fontKey != null -> AppFontOption.fromKey(fontKey).fontFamily()
        globalFontKey != null -> AppFontOption.fromKey(globalFontKey).fontFamily()
        else -> appFontFamily
    }
    val effectiveSize = (fontSizeSp ?: globalFontSize)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = effectiveFamily,
        fontSize = effectiveSize.sp,
        lineHeight = (effectiveSize * 1.55f).sp
    )
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = effectiveFamily)

    val folderName = folders.firstOrNull { it.id == folderId }?.name
        ?: stringResource(R.string.notes_uncategorized)

    Scaffold(
        // 编辑器是全屏覆盖页：不透明，避免背后的笔记列表透出重影
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        folderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.notes_editor_back_cd)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (preview) R.string.notes_edit_cd else R.string.notes_preview_cd
                            )
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.notes_editor_menu_cd)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_menu_font)) },
                            onClick = {
                                menuExpanded = false
                                showFontPicker = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_menu_font_size)) },
                            onClick = {
                                menuExpanded = false
                                showSizePicker = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_menu_move)) },
                            onClick = {
                                menuExpanded = false
                                showMoveDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.notes_menu_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showDeleteConfirm = true
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            TextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = titleStyle,
                placeholder = {
                    Text(stringResource(R.string.notes_title_hint), style = titleStyle)
                },
                colors = editorFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            NoteEditorTagRow(
                tags = allTags.filter { it.id in selectedTagIds },
                onRemove = { tagId -> toggleNoteTag(tagId) },
                onAdd = { showTagPicker = true }
            )
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (preview) {
                    // 预览模式：轻量解析器渲染标记子集
                    val annotated = parseNoteContent(
                        markdown = bodyValue.text,
                        baseStyle = bodyStyle,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(annotated, style = bodyStyle)
                    }
                } else {
                    TextField(
                        value = bodyValue,
                        onValueChange = { bodyValue = it },
                        textStyle = bodyStyle,
                        placeholder = {
                            Text(stringResource(R.string.notes_content_hint), style = bodyStyle)
                        },
                        colors = editorFieldColors(),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (!preview) {
                NoteFormatToolbar(value = bodyValue, onChange = { bodyValue = it })
            }
        }
    }

    if (showMoveDialog) {
        NoteFolderPickerDialog(
            folders = folders,
            currentFolderId = folderId,
            onDismiss = { showMoveDialog = false },
            onConfirm = { target -> folderId = target }
        )
    }

    if (showTagPicker) {
        NoteTagPickerDialog(
            repository = repository,
            selectedTagIds = selectedTagIds,
            onToggleTag = { tagId -> toggleNoteTag(tagId) },
            onDismiss = { showTagPicker = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            text = { Text(stringResource(R.string.notes_editor_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    editorScope.launch {
                        withContext(NonCancellable) {
                            if (persistedId != 0L) repository.deleteNote(persistedId)
                        }
                        finished = true
                        showDeleteConfirm = false
                        onClose()
                    }
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showFontPicker) {
        // 只列出包内实际存在的字体编号（字体文件不随仓库分发）
        val maxFont = AppFontOption.maxAvailableNumber(LocalContext.current)
        val currentNumber = (fontKey?.toIntOrNull() ?: 0).coerceAtMost(maxFont)
        var draft by remember(currentNumber, maxFont) { mutableIntStateOf(currentNumber) }
        val labels = (0..maxFont).map { n ->
            if (n == 0) stringResource(R.string.notes_follow_global)
            else stringResource(R.string.notes_font_numbered_fmt, n)
        }
        AlertDialog(
            onDismissRequest = { showFontPicker = false },
            title = { Text(stringResource(R.string.notes_editor_font_title)) },
            text = {
                WheelNumberPicker(
                    value = draft,
                    onValueChange = { draft = it },
                    range = 0..maxFont,
                    // 高度由组件内部按行高决定，外部不要另行限定（会与指示线错位）
                    modifier = Modifier.fillMaxWidth(),
                    format = { n -> labels.getOrElse(n) { "" } },
                    itemFontFamily = { n -> AppFontOption.entries.getOrNull(n)?.fontFamily() }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // 第一项「跟随全局」：fontKey 存 null
                    fontKey = if (draft == 0) null else "%02d".format(draft)
                    showFontPicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showFontPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showSizePicker) {
        // 滚轮只允许落在合法档位上：第 0 项「跟随全局」，其后 12..28sp
        val sizeOptions = remember { listOf<Int?>(null) + (12..28).toList() }
        val sizeLabels = sizeOptions.map { sp ->
            if (sp == null) stringResource(R.string.notes_follow_global)
            else stringResource(R.string.notes_size_fmt, sp)
        }
        var draft by remember {
            mutableIntStateOf(sizeOptions.indexOf(fontSizeSp).coerceAtLeast(0))
        }
        AlertDialog(
            onDismissRequest = { showSizePicker = false },
            title = { Text(stringResource(R.string.notes_editor_size_title)) },
            text = {
                WheelNumberPicker(
                    value = draft,
                    onValueChange = { draft = it },
                    range = sizeOptions.indices,
                    // 高度由组件内部按行高决定，外部不要另行限定（会与指示线错位）
                    modifier = Modifier.fillMaxWidth(),
                    format = { index -> sizeLabels.getOrElse(index) { "" } }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fontSizeSp = sizeOptions[draft]
                    showSizePicker = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSizePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 编辑器无边框输入框配色：容器透明、无下划线。 */
@Composable
private fun editorFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)

/**
 * 编辑器标题下方的标签选择区：已选标签 chip（色点 + 名称 + 「x」移除），
 * 末尾「+ 添加标签」打开多选弹窗。横向滚动，始终展示，便于从零创建标签。
 */
@Composable
private fun NoteEditorTagRow(
    tags: List<NoteTag>,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { tag ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NoteTagDot(tag.colorArgb)
                    Text(
                        tag.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.note_tag_remove_cd),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onRemove(tag.id) }
                    )
                }
            }
        }
        // 末尾「+ 添加标签」：描边半透明样式，与编辑器（surface 不透明底）协调
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.clickable(onClick = onAdd)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.note_tag_add),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 格式工具条：粗体 / 斜体 / 下划线（选区包裹），
// 无序 / 有序列表 / 引用（行首插入或切换）
// ------------------------------------------------------------------

@Composable
private fun NoteFormatToolbar(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolbarToggle(
            icon = Icons.Filled.FormatBold,
            description = stringResource(R.string.notes_format_bold_cd),
            active = isWrapped(value, "**"),
            onClick = { onChange(wrapWithMarker(value, "**")) }
        )
        ToolbarToggle(
            icon = Icons.Filled.FormatItalic,
            description = stringResource(R.string.notes_format_italic_cd),
            active = isWrapped(value, "*"),
            onClick = { onChange(wrapWithMarker(value, "*")) }
        )
        ToolbarToggle(
            icon = Icons.Filled.FormatUnderlined,
            description = stringResource(R.string.notes_format_underline_cd),
            active = isWrapped(value, "++"),
            onClick = { onChange(wrapWithMarker(value, "++")) }
        )
        ToolbarToggle(
            icon = Icons.Filled.FormatListBulleted,
            description = stringResource(R.string.notes_format_bullet_cd),
            active = selectedLines(value).all { LEADING_BULLET.containsMatchIn(it) },
            onClick = { onChange(toggleBullet(value)) }
        )
        ToolbarToggle(
            icon = Icons.Filled.FormatListNumbered,
            description = stringResource(R.string.notes_format_ordered_cd),
            active = selectedLines(value).all { LEADING_ORDERED.containsMatchIn(it) },
            onClick = { onChange(toggleOrdered(value)) }
        )
        ToolbarToggle(
            icon = Icons.Filled.FormatQuote,
            description = stringResource(R.string.notes_format_quote_cd),
            active = selectedLines(value).all { LEADING_QUOTE.matches(it) },
            onClick = { onChange(toggleQuote(value)) }
        )
    }
}

@Composable
private fun ToolbarToggle(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val LEADING_BULLET = Regex("""^- """)
private val LEADING_ORDERED = Regex("""^(\d+)\. """)
private val LEADING_QUOTE = Regex("""^> ?""")

/** 选区两侧是否正好贴着成对标记（用于粗 / 斜 / 下划线的选中态与再次点击解包）。 */
private fun isWrapped(v: TextFieldValue, marker: String): Boolean {
    val text = v.text
    val s = v.selection.start
    val e = v.selection.end
    if (s < marker.length || e + marker.length > text.length) return false
    return text.substring(s - marker.length, s) == marker &&
        text.substring(e, e + marker.length) == marker
}

/** 用成对标记包裹选区；无选区时在光标处插入一对标记并把光标放到中间；已包裹则解包。 */
private fun wrapWithMarker(v: TextFieldValue, marker: String): TextFieldValue {
    val text = v.text
    val s = v.selection.start
    val e = v.selection.end
    val before = text.substring(0, s)
    val selection = text.substring(s, e)
    val after = text.substring(e)

    // 已被同标记完整包裹 → 解包
    if (before.endsWith(marker) && after.startsWith(marker)) {
        val newText = before.removeSuffix(marker) + selection + after.removePrefix(marker)
        return v.copy(
            text = newText,
            selection = TextRange(s - marker.length, e - marker.length)
        )
    }

    val newText = before + marker + selection + marker + after
    return if (s == e) {
        // 无选区：光标停在两个标记之间
        v.copy(text = newText, selection = TextRange(s + marker.length))
    } else {
        // 有选区：保持内部文字被选中，便于继续输入替换
        v.copy(
            text = newText,
            selection = TextRange(s + marker.length, e + marker.length)
        )
    }
}

/** 取选区（或光标）覆盖到的行。 */
private fun selectedLines(v: TextFieldValue): List<String> {
    val text = v.text
    if (text.isEmpty()) return listOf("")
    val s = v.selection.start.coerceIn(0, text.length)
    val e = v.selection.end.coerceIn(0, text.length)
    val lineStart = if (s == 0) 0 else text.lastIndexOf('\n', s - 1) + 1
    var lineEnd = text.indexOf('\n', e)
    if (lineEnd < 0) lineEnd = text.length
    return text.substring(lineStart, lineEnd).split('\n')
}

/**
 * 对选区覆盖的每一行做前缀变换，并重映射光标 / 选区位置。
 */
private fun transformSelectedLines(
    v: TextFieldValue,
    transform: (line: String, indexInSelection: Int) -> String
): TextFieldValue {
    val text = v.text
    val s = v.selection.start.coerceIn(0, text.length)
    val e = v.selection.end.coerceIn(0, text.length)
    val firstLineStart = if (s == 0) 0 else text.lastIndexOf('\n', s - 1) + 1
    var lastLineEnd = text.indexOf('\n', e)
    if (lastLineEnd < 0) lastLineEnd = text.length

    val middle = text.substring(firstLineStart, lastLineEnd)
    val lines = middle.split('\n')
    val mapped = lines.mapIndexed { index, line -> transform(line, index) }
    val newMiddle = mapped.joinToString("\n")
    val newText = text.replaceRange(firstLineStart, lastLineEnd, newMiddle)

    fun mapIndex(idx: Int): Int {
        if (idx <= firstLineStart) return idx.coerceAtMost(firstLineStart)
        if (idx >= lastLineEnd) return idx + (newMiddle.length - middle.length)
        var origPos = firstLineStart
        var newPos = firstLineStart
        for (i in lines.indices) {
            val oLen = lines[i].length
            val nLen = mapped[i].length
            if (idx <= origPos + oLen) {
                return newPos + (idx - origPos).coerceIn(0, nLen)
            }
            origPos += oLen + 1
            newPos += nLen + 1
        }
        return newPos
    }

    return v.copy(text = newText, selection = TextRange(mapIndex(s), mapIndex(e)))
}

/** 去掉行首已有的任意列表 / 引用前缀。 */
private fun stripLeading(line: String): String =
    line.replaceFirst(LEADING_QUOTE, "")
        .replaceFirst(LEADING_ORDERED, "")
        .replaceFirst(LEADING_BULLET, "")

/** 无序列表：所有选中行已是 "- " 则取消，否则（替换其他前缀后）加上。 */
private fun toggleBullet(v: TextFieldValue): TextFieldValue {
    val lines = selectedLines(v)
    val allBulleted = lines.all { LEADING_BULLET.containsMatchIn(it) }
    return transformSelectedLines(v) { line, _ ->
        if (allBulleted) line.replaceFirst(LEADING_BULLET, "")
        else "- " + stripLeading(line)
    }
}

/** 引用：所有选中行已是 "> " 则取消，否则在行首加上（与列表前缀可叠加）。 */
private fun toggleQuote(v: TextFieldValue): TextFieldValue {
    val lines = selectedLines(v)
    val allQuoted = lines.all { LEADING_QUOTE.matches(it) }
    return transformSelectedLines(v) { line, _ ->
        if (allQuoted) line.replaceFirst(LEADING_QUOTE, "")
        else "> " + line
    }
}

/**
 * 有序列表：所有选中行已是 "N. " 则取消；否则按上文最近一个有序序号自增，
 * 选中多行时顺序编号。
 */
private fun toggleOrdered(v: TextFieldValue): TextFieldValue {
    val lines = selectedLines(v)
    val allOrdered = lines.all { LEADING_ORDERED.containsMatchIn(it) }
    if (allOrdered) {
        return transformSelectedLines(v) { line, _ -> line.replaceFirst(LEADING_ORDERED, "") }
    }

    // 找选区上方最近的有序行，序号 + 1
    val text = v.text
    val s = v.selection.start.coerceIn(0, text.length)
    val firstLineStart = if (s == 0) 0 else text.lastIndexOf('\n', s - 1) + 1
    var startNumber = 1
    if (firstLineStart > 0) {
        val above = text.substring(0, firstLineStart).split('\n')
        for (line in above.asReversed()) {
            val match = LEADING_ORDERED.find(line)
            if (match != null) {
                startNumber = (match.groupValues[1].toIntOrNull() ?: 0) + 1
                break
            }
            if (line.isNotBlank()) break // 中间隔着空行 / 普通段落则重新从 1 开始
        }
    }

    return transformSelectedLines(v) { line, index ->
        "${startNumber + index}. " + stripLeading(line)
    }
}
