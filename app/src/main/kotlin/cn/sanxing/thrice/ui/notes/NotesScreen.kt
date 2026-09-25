@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cn.sanxing.thrice.ui.notes

import android.database.sqlite.SQLiteConstraintException
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.NotesSettingsRepository
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.components.FullScreenOverlay
import cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 随身记（笔记）主界面：
 * 顶栏（居中标题 + 文件夹 + 设置）、置顶优先的笔记卡片列表（可单 / 双列、
 * 三种排序字段 × 升降序）、文件夹筛选、右下琥珀色新建 FAB。
 * 编辑器与文件夹管理均为内部 Box 覆盖层，不经过底栏 NavHost。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(container: AppContainer) {
    val repository = container.notesRepository
    val settings = container.notesSettingsRepository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isZh = remember {
        context.resources.configuration.locales[0].language == Locale.CHINESE.language
    }

    val folders by repository.observeFolders().collectAsState(initial = emptyList())
    val noteTags by repository.observeTags().collectAsState(initial = emptyList())
    val fontSize by settings.fontSize.collectAsState(NotesSettingsRepository.DEFAULT_FONT_SIZE)
    val fontKey by settings.fontKey.collectAsState(null)
    val twoColumn by settings.twoColumn.collectAsState(false)
    val sortField by settings.sortField.collectAsState(NotesSettingsRepository.SORT_FIELD_UPDATED)
    val sortAsc by settings.sortAscending.collectAsState(false)

    var filter by remember { mutableStateOf<NoteFilter>(NoteFilter.All) }
    var showFolders by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var editorNote by remember { mutableStateOf<Note?>(null) }
    var pendingDelete by remember { mutableStateOf<Note?>(null) }
    var moveTarget by remember { mutableStateOf<Note?>(null) }

    // 标签筛选：多选，AND 语义（命中全部所选标签才显示）；空集合 = 不按标签筛选
    var selectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var creatingTag by remember { mutableStateOf(false) }
    var renamingTag by remember { mutableStateOf<NoteTag?>(null) }
    var deletingTag by remember { mutableStateOf<NoteTag?>(null) }

    // 标签被删除后把失效 id 移出筛选集合，避免 AND 交集恒空
    LaunchedEffect(noteTags) {
        val alive = noteTags.mapTo(HashSet()) { it.id }
        selectedTagIds = selectedTagIds.filterTo(HashSet()) { it in alive }
    }

    // 屏幕级订阅所选标签各自命中的笔记 id（仅为当前选中的标签创建 Flow），
    // 在内存中取交集后过滤列表，避免逐笔记查询
    val matchingNoteIds: Set<Long>? by produceState<Set<Long>?>(null, selectedTagIds) {
        if (selectedTagIds.isEmpty()) {
            value = null
            return@produceState
        }
        combine(selectedTagIds.map { repository.observeNoteIdsForTag(it) }) { perTag ->
            perTag.map { it.toSet() }.reduce { acc, set -> acc intersect set }
        }.collect { value = it }
    }

    val notesFlow = remember(filter) {
        // filter 是委托属性，when 分支内无法 smart cast，用局部量 f
        when (val f = filter) {
            NoteFilter.All -> repository.observeAllNotes()
            NoteFilter.Uncategorized -> repository.observeUncategorizedNotes()
            is NoteFilter.Folder -> repository.observeNotesInFolder(f.id)
        }
    }
    val notes by notesFlow.collectAsState(initial = emptyList())

    // 置顶永远最前；其下按设置的字段 / 方向排序
    val sortedNotes = remember(notes, sortField, sortAsc) {
        val fieldComparator: Comparator<Note> = when (sortField) {
            NotesSettingsRepository.SORT_FIELD_CREATED -> compareBy { it.createdAt }
            NotesSettingsRepository.SORT_FIELD_TITLE -> compareBy { it.title }
            else -> compareBy { it.updatedAt }
        }
        val oriented = if (sortAsc) fieldComparator else fieldComparator.reversed()
        notes.sortedWith(compareByDescending<Note> { it.pinned }.then(oriented))
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.notes_title)) },
                    actions = {
                        IconButton(onClick = { showFolders = true }) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = stringResource(R.string.notes_folders_cd)
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.notes_settings_cd)
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    // 新建归属当前筛选文件夹；「全部」/「未分类」视图下为未分类（null）
                    onClick = {
                        editorNote = Note(folderId = (filter as? NoteFilter.Folder)?.id)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.notes_add_cd))
                }
            }
        ) { padding ->
            // 标签筛选与文件夹筛选并存：文件夹 Flow 已取数，标签在内存中再过滤
            val displayedNotes = matchingNoteIds?.let { hitIds ->
                sortedNotes.filter { it.id in hitIds }
            } ?: sortedNotes

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NoteTagFilterBar(
                    tags = noteTags,
                    selectedTagIds = selectedTagIds,
                    onToggleTag = { tagId ->
                        selectedTagIds = if (tagId in selectedTagIds) {
                            selectedTagIds - tagId
                        } else {
                            selectedTagIds + tagId
                        }
                    },
                    onClear = { selectedTagIds = emptySet() },
                    onCreate = { creatingTag = true },
                    onRename = { renamingTag = it },
                    onDelete = { deletingTag = it }
                )

                if (displayedNotes.isEmpty()) {
                    // 空态：灰色文档图标 + 提示
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.notes_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (twoColumn) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                    ) {
                        // 单列 items 与网格 items 不同接收者，导入时用别名 gridItems
                        gridItems(
                            displayedNotes,
                            key = { it.id }
                        ) { note ->
                            NoteCard(
                                note = note,
                                isZh = isZh,
                                onOpen = { editorNote = note },
                                onTogglePin = {
                                    scope.launch { repository.setPinned(note.id, !note.pinned) }
                                },
                                onMove = { moveTarget = note },
                                onDelete = { pendingDelete = note }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
                    ) {
                        items(displayedNotes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                isZh = isZh,
                                onOpen = { editorNote = note },
                                onTogglePin = {
                                    scope.launch { repository.setPinned(note.id, !note.pinned) }
                                },
                                onMove = { moveTarget = note },
                                onDelete = { pendingDelete = note }
                            )
                        }
                    }
                }
            }
        }

        // 全屏覆盖层（在 NotesScreen 的 Box 内叠加）
        if (showFolders) {
            NoteFoldersOverlay(
                repository = repository,
                folders = folders,
                current = filter,
                onSelectFilter = {
                    filter = it
                    showFolders = false
                },
                onClose = { showFolders = false }
            )
        }

        if (showSettingsDialog) {
            NotesSettingsDialog(
                repository = settings,
                onDismiss = { showSettingsDialog = false }
            )
        }

        editorNote?.let { note ->
            // 编辑器整页叠在列表之上：必须走 FullScreenOverlay 先铺一层不透明背景，
            // 否则壁纸模式下半透明纸面会让底层笔记列表透出来形成重影。
            FullScreenOverlay {
                NoteEditor(
                    repository = repository,
                    note = note,
                    folders = folders,
                    globalFontKey = fontKey,
                    globalFontSize = fontSize,
                    onClose = { editorNote = null }
                )
            }
        }
    }

    // 删除确认
    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.notes_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteNote(note.id) }
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 移动到文件夹
    moveTarget?.let { note ->
        NoteFolderPickerDialog(
            folders = folders,
            currentFolderId = note.folderId,
            onDismiss = { moveTarget = null },
            onConfirm = { targetFolderId ->
                scope.launch { repository.moveNote(note.id, targetFolderId) }
                moveTarget = null
            }
        )
    }

    // 新建标签
    if (creatingTag) {
        NoteTagNameDialog(
            title = stringResource(R.string.note_tag_create_title),
            initial = "",
            existingNames = noteTags.map { it.name },
            onDismiss = { creatingTag = false },
            onConfirm = { name ->
                scope.launch {
                    try {
                        repository.createTag(
                            name = name,
                            colorArgb = NOTE_TAG_PRESET_COLORS[
                                noteTags.size % NOTE_TAG_PRESET_COLORS.size
                            ]
                        )
                    } catch (e: SQLiteConstraintException) {
                        Toast.makeText(context, R.string.note_tag_exists, Toast.LENGTH_SHORT).show()
                    }
                }
                creatingTag = false
            }
        )
    }

    // 重命名标签
    renamingTag?.let { tag ->
        NoteTagNameDialog(
            title = stringResource(R.string.note_tag_rename_title),
            initial = tag.name,
            existingNames = noteTags.filter { it.id != tag.id }.map { it.name },
            onDismiss = { renamingTag = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        repository.renameTag(tag.id, name)
                    } catch (e: SQLiteConstraintException) {
                        Toast.makeText(context, R.string.note_tag_exists, Toast.LENGTH_SHORT).show()
                    }
                }
                renamingTag = null
            }
        )
    }

    // 删除标签（关联由数据层在同一事务内清理）
    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.note_tag_delete_confirm, tag.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteTag(tag.id) }
                    deletingTag = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 标签筛选行：最左「全部标签」（清除标签筛选），其后为各标签 chip（色点取自
 * colorArgb，点按切换筛选、长按呼出重命名 / 删除菜单），行尾「+」新建标签。
 * 与文件夹筛选并存（AND 语义）。未选中 chip 保持壁纸半透明视觉。
 */
@Composable
private fun NoteTagFilterBar(
    tags: List<NoteTag>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onClear: () -> Unit,
    onCreate: () -> Unit,
    onRename: (NoteTag) -> Unit,
    onDelete: (NoteTag) -> Unit
) {
    var menuTag by remember { mutableStateOf<NoteTag?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NoteFilterChip(
            text = stringResource(R.string.note_tag_filter_all),
            selected = selectedTagIds.isEmpty(),
            onClick = onClear
        )
        tags.forEach { tag ->
            Box {
                NoteFilterChip(
                    text = tag.name,
                    selected = tag.id in selectedTagIds,
                    dotColorArgb = tag.colorArgb,
                    onClick = { onToggleTag(tag.id) },
                    onLongClick = { menuTag = tag }
                )
                DropdownMenu(
                    expanded = menuTag?.id == tag.id,
                    onDismissRequest = { menuTag = null }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.note_tag_rename)) },
                        onClick = {
                            menuTag = null
                            onRename(tag)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuTag = null
                            onDelete(tag)
                        }
                    )
                }
            }
        }
        NoteFilterChip(
            text = "",
            selected = false,
            onClick = onCreate,
            leadingIcon = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.note_tag_new_cd),
                    modifier = Modifier.size(17.dp)
                )
            }
        )
    }
}

/**
 * 筛选行用的胶囊 chip：点按 + 长按；未选中使用与笔记卡片一致的半透明 surface，
 * 选中使用 secondaryContainer（主题色，非白底）。
 */
@Composable
private fun NoteFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    dotColorArgb: Int? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current),
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leadingIcon?.invoke()
            if (dotColorArgb != null) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(Color(dotColorArgb), CircleShape)
                )
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 标签新建 / 重命名命名弹窗：空名与重名做常识拦截，数据库唯一索引兜底。 */
@Composable
private fun NoteTagNameDialog(
    title: String,
    initial: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.trim() == trimmed }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.note_tag_name_hint)) }
                )
                if (duplicate) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.note_tag_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onConfirm(trimmed) }
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 笔记卡片：标题（无标题取正文首行）、纯文本摘要（1-2 行）、更新时间、
 * 置顶图钉与卡片菜单（置顶 / 移动 / 删除）。长按也可呼出菜单。
 */
@Composable
private fun NoteCard(
    note: Note,
    isZh: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val title = noteDisplayTitle(note) ?: stringResource(R.string.notes_untitled)
    val summary = noteSummary(note.content)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalUiMaskAlpha.current)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.notes_pinned_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.notes_card_menu_cd),
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (note.pinned) R.string.notes_unpin else R.string.notes_pin
                                )
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_move_to)) },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                formatNoteTime(note.updatedAt, isZh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** 更新时间展示：今天显示时刻、今年显示月日、跨年显示完整日期。 */
private fun formatNoteTime(epochMillis: Long, isZh: Boolean): String {
    val zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val date = zoned.toLocalDate()
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(zoned)
    return when {
        date == today -> time
        date.year == today.year -> if (isZh) {
            "${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.monthValue}/${date.dayOfMonth}"
        }

        else -> if (isZh) {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        }
    }
}
