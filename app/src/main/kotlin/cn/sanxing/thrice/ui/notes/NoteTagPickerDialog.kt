package cn.sanxing.thrice.ui.notes

import android.database.sqlite.SQLiteConstraintException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.domain.model.NoteTag
import kotlinx.coroutines.launch

/**
 * 笔记标签默认调色板：固定颜色、按标签总数取模，禁止未种子化的随机色。
 * NotesScreen 新建标签与本弹窗内新建共用。
 */
internal val NOTE_TAG_PRESET_COLORS = listOf(
    0xFF7E57C2.toInt(),
    0xFF2E6DA4.toInt(),
    0xFFE07A5F.toInt(),
    0xFF81B29A.toInt(),
    0xFFF2CC8F.toInt(),
    0xFF57A773.toInt(),
    0xFFD08C4A.toInt(),
    0xFF4D7EA8.toInt(),
    0xFFB56576.toInt(),
    0xFFE89B9B.toInt()
)

/** 标签色点（ARGB）。 */
@Composable
internal fun NoteTagDot(colorArgb: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(Color(colorArgb), CircleShape)
    )
}

/**
 * 笔记标签选择弹窗（编辑器内使用）：
 * 上半部分多选已有标签（复选框 + 色点 + 名称），底部可直接新建标签，
 * 新建成功后自动勾选。重名由数据库唯一索引兜底，错误就地提示。
 */
@Composable
fun NoteTagPickerDialog(
    repository: NotesRepository,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val tags by repository.observeTags().collectAsState(initial = emptyList())

    var newName by remember { mutableStateOf("") }
    var duplicate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        // 不显式设置容器色：Material3 AlertDialog 默认即 colorScheme.surface，
        // 保证弹窗为主题色不透明底色，不会透出底层内容
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_tag_picker_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(tags, key = { it.id }) { tag ->
                        TagPickRow(
                            tag = tag,
                            selected = tag.id in selectedTagIds,
                            onToggle = { onToggleTag(tag.id) }
                        )
                    }
                }

                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        duplicate = false
                    },
                    singleLine = true,
                    enabled = !busy,
                    isError = duplicate,
                    label = { Text(stringResource(R.string.note_tag_name_hint)) },
                    trailingIcon = {
                        IconButton(
                            enabled = !busy && newName.isNotBlank(),
                            onClick = {
                                val name = newName.trim()
                                if (name.isEmpty()) return@IconButton
                                busy = true
                                scope.launch {
                                    try {
                                        val color = NOTE_TAG_PRESET_COLORS[
                                            tags.size % NOTE_TAG_PRESET_COLORS.size
                                        ]
                                        val newId = repository.createTag(name = name, colorArgb = color)
                                        // 新建即勾选
                                        if (newId !in selectedTagIds) onToggleTag(newId)
                                        newName = ""
                                        duplicate = false
                                    } catch (e: SQLiteConstraintException) {
                                        // 名称唯一索引冲突：就地提示重名
                                        duplicate = true
                                    }
                                    busy = false
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.note_tag_new_cd)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (duplicate) {
                    Text(
                        stringResource(R.string.note_tag_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
private fun TagPickRow(
    tag: NoteTag,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        NoteTagDot(tag.colorArgb)
        Spacer(Modifier.size(10.dp))
        Text(
            tag.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
