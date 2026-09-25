@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.sanxing.thrice.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.ai.AiPersona
import cn.sanxing.thrice.data.ai.DEFAULT_AI_PERSONA_ZH
import cn.sanxing.thrice.ui.AppContainer
import kotlinx.coroutines.launch

/**
 * AI 人设（系统提示词）管理弹窗：
 * - 首位为内置默认人设（随应用语言取中 / 英文版），只能选用，不可编辑 / 删除；
 * - 用户可新建、编辑、删除自建人设；整行 / 单选钮点击即切换为当前启用；
 * - 当前人设名同时显示在 AI 界面输入框上方的 chip 上。
 */
@Composable
fun AiPersonaDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val personas by container.aiPersonaRepository.personas
        .collectAsState(initial = listOf(DEFAULT_AI_PERSONA_ZH))
    val activeId by container.aiPersonaRepository.activePersonaId
        .collectAsState(initial = AiPersona.DEFAULT_PERSONA_ID)

    // null = 未打开编辑；非 null = 正在编辑的人设（新建时为空白草稿）
    var editing by remember { mutableStateOf<AiPersona?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AiPersona?>(null) }
    // 默认人设只读查看（不可编辑，用只读弹窗展示完整内容）
    var viewing by remember { mutableStateOf<AiPersona?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_persona_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { isCreating = true }) {
                Text(stringResource(R.string.ai_persona_new))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_persona_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                personas.forEach { persona ->
                    PersonaRow(
                        persona = persona,
                        selected = persona.id == activeId,
                        onSelect = {
                            scope.launch { container.aiPersonaRepository.setActive(persona.id) }
                        },
                        onEdit = { editing = persona },
                        onView = { viewing = persona },
                        onDelete = { deleting = persona }
                    )
                }
            }
        }
    )

    if (isCreating || editing != null) {
        PersonaEditDialog(
            target = editing,
            onDismiss = {
                editing = null
                isCreating = false
            },
            onSave = { name, content ->
                scope.launch {
                    val savedId = container.aiPersonaRepository.savePersona(
                        id = editing?.id,
                        name = name,
                        content = content
                    )
                    // 新建后自动切换到这套人设（编辑当前人设则保持选中不变）
                    if (isCreating) container.aiPersonaRepository.setActive(savedId)
                }
                editing = null
                isCreating = false
            }
        )
    }

    deleting?.let { persona ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ai_persona_delete_title)) },
            text = { Text(stringResource(R.string.ai_persona_delete_confirm, persona.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.aiPersonaRepository.deletePersona(persona.id) }
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

    // 默认人设只读查看：标题为人设名，正文可滚动、不可编辑
    viewing?.let { persona ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = {
                Text(
                    stringResource(R.string.ai_persona_view_title, persona.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = { viewing = null }) {
                    Text(stringResource(R.string.action_done))
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = persona.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }
}

/** 单条人设行：单选钮 + 名称（默认带角标）+ 内容预览；默认项可查看，自建项可编辑 / 删除。 */
@Composable
private fun PersonaRow(
    persona: AiPersona,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onView: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onSelect
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (persona.builtIn) {
                        Spacer(Modifier.size(6.dp))
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    text = stringResource(R.string.ai_persona_builtin_badge),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
                Text(
                    text = persona.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (persona.builtIn) {
                androidx.compose.material3.IconButton(onClick = onView) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.ai_persona_view_cd)
                    )
                }
            } else {
                androidx.compose.material3.IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.ai_persona_edit_cd)
                    )
                }
                androidx.compose.material3.IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.ai_persona_delete_cd)
                    )
                }
            }
        }
    }
}

/** 新建 / 编辑人设：名称 + 多行正文；正文留空或名称留空时禁止保存。 */
@Composable
private fun PersonaEditDialog(
    target: AiPersona?,
    onDismiss: () -> Unit,
    onSave: (name: String, content: String) -> Unit
) {
    var name by remember(target?.id) { mutableStateOf(target?.name.orEmpty()) }
    var content by remember(target?.id) { mutableStateOf(target?.content.orEmpty()) }
    val nameBlank = name.isBlank()
    val contentBlank = content.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (target == null) R.string.ai_persona_new
                    else R.string.ai_persona_edit
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = !nameBlank && !contentBlank,
                onClick = { onSave(name.trim(), content.trim()) }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 20) name = it },
                    label = { Text(stringResource(R.string.ai_persona_name_label)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.ai_persona_name_hint))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { if (it.length <= 2000) content = it },
                    label = { Text(stringResource(R.string.ai_persona_content_label)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.ai_persona_content_hint),
                            fontWeight = FontWeight.Normal
                        )
                    },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                )
            }
        }
    )
}
