package cn.sanxing.thrice.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.DialogProperties
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.FocusTag
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.ColorPickerSection
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val TAG_PRESET_COLORS = listOf(
    "#2E6DA4", "#E07A5F", "#81B29A", "#F2CC8F", "#9D8DF1",
    "#E89B9B", "#57A773", "#D08C4A", "#4D7EA8", "#B56576"
)

/** Int ARGB ↔ "#RRGGBB"（不透明度恒为不透明）。 */
internal fun Int.toHexColor(): String = "#%06X".format(0xFFFFFF and this)
internal fun String.toColorIntOrNull(): Int? =
    runCatching { android.graphics.Color.parseColor(this) }.getOrNull()

/**
 * 专注标签管理对话框（独立于任务标签）：
 * 列表编辑 / 删除（删除后历史会话在统计中归入「未分类」，不做级联），
 * 底部新建；颜色复用通用颜色选择器。
 */
@Composable
fun FocusTagDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val tags by container.focusTagDao.observeAll()
        .map { list -> list.sortedWith(compareBy({ it.sortOrder }, { it.id })) }
        .collectAsState(initial = emptyList())

    // editing == null 表示列表态；非 null 表示编辑/新建态
    var editing by remember { mutableStateOf<FocusTag?>(null) }
    var isNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.focus_tag_dialog_title)) },
        text = {
            if (editing == null) {
                Column(Modifier.fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(tags, key = { it.id }) { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(Color(tag.colorArgb), CircleShape)
                                )
                                Text(
                                    tag.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp)
                                )
                                IconButton(onClick = { editing = tag; isNew = false }) {
                                    Icon(Icons.Filled.Edit, contentDescription = null)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        container.focusTagDao.deleteTagAndDetach(tag.id)
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        editing = FocusTag(
                            id = 0L,
                            name = "",
                            colorArgb = TAG_PRESET_COLORS[tags.size % TAG_PRESET_COLORS.size]
                                .toColorIntOrNull() ?: 0xFF2E6DA4.toInt(),
                            sortOrder = (tags.maxOfOrNull { it.sortOrder } ?: 0) + 1
                        )
                        isNew = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.common_new))
                    }
                }
            } else {
                TagEditor(
                    initial = editing!!,
                    onCancel = { editing = null },
                    onSave = { name, color ->
                        scope.launch {
                            val tag = editing!!.copy(name = name, colorArgb = color)
                            container.focusTagDao.upsert(tag)
                            editing = null
                        }
                    }
                )
            }
        },
    )
}

@Composable
private fun TagEditor(
    initial: FocusTag,
    onCancel: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var colorHex by remember { mutableStateOf(initial.colorArgb.toHexColor()) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(20) },
            label = { Text(stringResource(R.string.focus_tag_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        ColorPickerSection(
            colorHex = colorHex,
            onColorChange = { colorHex = it },
            presetColors = TAG_PRESET_COLORS
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            TextButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.common_cancel))
            }
            TextButton(
                enabled = name.isNotBlank() && colorHex.toColorIntOrNull() != null,
                onClick = {
                    onSave(name.trim(), colorHex.toColorIntOrNull() ?: 0xFF2E6DA4.toInt())
                }
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.common_save))
            }
        }
    }
}
