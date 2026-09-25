package cn.sanxing.thrice.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.ui.components.FullScreenOverlay
import kotlinx.coroutines.launch

/** 笔记列表的文件夹筛选状态。 */
sealed interface NoteFilter {
    /** 全部笔记。 */
    data object All : NoteFilter

    /** 未分类（folderId = null）。 */
    data object Uncategorized : NoteFilter

    /** 指定文件夹。 */
    data class Folder(val id: Long) : NoteFilter
}

/**
 * 文件夹管理 / 筛选全屏覆盖层（在 NotesScreen 的 Box 内覆盖，
 * 不走 NavHost，避免底栏页面销毁）。
 *
 * 上半部分是筛选入口（全部 / 未分类 / 各文件夹），下半部分管理
 * （新建 / 重命名 / 删除）。删除文件夹时明确提示：笔记只会移入未分类。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteFoldersOverlay(
    repository: NotesRepository,
    folders: List<NoteFolder>,
    current: NoteFilter,
    onSelectFilter: (NoteFilter) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // 弹窗状态：null 不弹；true=新建，NoteFolder=重命名 / 删除
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteFolder?>(null) }
    var deleting by remember { mutableStateOf<NoteFolder?>(null) }

    // 本页是叠在笔记列表之上的全屏覆盖页：必须走 FullScreenOverlay，
    // 否则壁纸模式下半透明纸面会让底层笔记列表透出来形成重影。
    FullScreenOverlay {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.notes_folders_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    SectionLabel(stringResource(R.string.notes_filter_section))
                }
                item {
                    FilterRow(
                        icon = Icons.Filled.Description,
                        label = stringResource(R.string.notes_filter_all),
                        selected = current is NoteFilter.All,
                        onClick = { onSelectFilter(NoteFilter.All) }
                    )
                }
                item {
                    FilterRow(
                        icon = Icons.Filled.FolderOff,
                        label = stringResource(R.string.notes_uncategorized),
                        selected = current is NoteFilter.Uncategorized,
                        onClick = { onSelectFilter(NoteFilter.Uncategorized) }
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    SectionLabel(stringResource(R.string.notes_my_folders))
                }

                // 指定文件夹：整行点击 = 按该文件夹筛选；右侧按钮为重命名 / 删除
                items(folders, key = { it.id }) { folder ->
                    FolderRow(
                        folder = folder,
                        selected = (current as? NoteFilter.Folder)?.id == folder.id,
                        onClick = { onSelectFilter(NoteFilter.Folder(folder.id)) },
                        onRename = { renaming = folder },
                        onDelete = { deleting = folder }
                    )
                }

                item {
                    TextButton(
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ ${stringResource(R.string.notes_folder_new)}")
                    }
                }
            }
        }
    }

    if (creating) {
        FolderNameDialog(
            initial = "",
            existingNames = folders.map { it.name },
            onDismiss = { creating = false },
            onConfirm = { name ->
                scope.launch { repository.createFolder(name) }
                creating = false
            }
        )
    }
    renaming?.let { folder ->
        FolderNameDialog(
            initial = folder.name,
            existingNames = folders.filter { it.id != folder.id }.map { it.name },
            onDismiss = { renaming = null },
            onConfirm = { name ->
                scope.launch { repository.renameFolder(folder.id, name) }
                renaming = null
            }
        )
    }
    deleting?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.notes_folder_delete_cd)) },
            text = {
                Text(stringResource(R.string.notes_folder_delete_confirm, folder.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteFolder(folder.id) }
                    // 若正筛选在被删文件夹上，退回「全部」
                    if ((current as? NoteFilter.Folder)?.id == folder.id) {
                        onSelectFilter(NoteFilter.All)
                    }
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun FilterRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FolderRow(
    folder: NoteFolder,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(12.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(4.dp))
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.notes_folder_rename_cd))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.notes_folder_delete_cd),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 新建 / 重命名文件夹的命名弹窗：空名与重名做常识拦截。 */
@Composable
private fun FolderNameDialog(
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
        title = {
            Text(
                if (initial.isEmpty()) stringResource(R.string.notes_folder_new)
                else stringResource(R.string.notes_folder_rename)
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_folder_name_hint)) }
                )
                if (duplicate) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.notes_folder_exists),
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
 * 「移动到文件夹」单选弹窗（卡片菜单与编辑器菜单共用）：
 * 第一项固定「未分类」。
 */
@Composable
fun NoteFolderPickerDialog(
    folders: List<NoteFolder>,
    currentFolderId: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit
) {
    var selected by remember { mutableStateOf(currentFolderId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_move_dialog_title)) },
        text = {
            Column {
                RadioRow(
                    label = stringResource(R.string.notes_uncategorized),
                    selected = selected == null,
                    onSelect = { selected = null }
                )
                folders.forEach { folder ->
                    RadioRow(
                        label = folder.name,
                        selected = selected == folder.id,
                        onSelect = { selected = folder.id }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selected)
                onDismiss()
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
