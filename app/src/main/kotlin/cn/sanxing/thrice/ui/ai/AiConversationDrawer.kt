@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.sanxing.thrice.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.AiConversation
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * AI 会话管理抽屉：顶部「新建对话」，下方会话列表按最近活跃倒序，
 * 显示标题（空标题回落「新对话」）与相对时间；每项可重命名 / 删除（删除有确认）。
 * 背景跟随全局界面透明度设置（LocalUiMaskAlpha）。
 *
 * R17：由 ModalDrawerSheet 改为普通 Surface——ModalNavigationDrawer 的抽屉与
 * scrim 运行在窗口级 Popup 中，不随 HorizontalPager 翻页被裁剪，切到隔壁 tab
 * 后仍浮在其上方。现在作为 AiScreen 页内 Box 的子节点组合，翻页即随页面移除。
 * [modifier] 由调用方给定宽度 / 高度 / 系统栏内边距。
 */
@Composable
fun AiConversationDrawer(
    modifier: Modifier = Modifier,
    conversations: List<AiConversation>,
    currentId: Long?,
    enabled: Boolean,
    onNewConversation: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onRenameConversation: (AiConversation, String) -> Unit,
    onDeleteConversation: (AiConversation) -> Unit
) {
    val sheetColor = MaterialTheme.colorScheme.surface
        .copy(alpha = cn.sanxing.thrice.ui.theme.LocalUiMaskAlpha.current)
    var renaming by remember { mutableStateOf<AiConversation?>(null) }
    var deleting by remember { mutableStateOf<AiConversation?>(null) }

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RectangleShape,
        color = sheetColor,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ai_conversations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNewConversation, enabled = enabled) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.ai_new_chat)
                )
            }
        }

        if (conversations.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_no_conversations_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == currentId,
                        enabled = enabled,
                        onSelect = { onSelectConversation(conversation.id) },
                        onRename = { renaming = conversation },
                        onDelete = { deleting = conversation }
                    )
                }
            }
        }
        }
    }

    renaming?.let { conversation ->
        var draft by remember(conversation.id) {
            mutableStateOf(conversation.title)
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.ai_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ai_default_conversation_title)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = draft.trim()
                    if (title.isNotEmpty()) {
                        onRenameConversation(conversation, title)
                    }
                    renaming = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ai_delete_conversation)) },
            text = { Text(stringResource(R.string.ai_delete_conversation_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(conversation)
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

/** 单个会话条目：整行点击切换；右侧溢出菜单（重命名 / 删除）；当前会话高亮。 */
@Composable
private fun ConversationRow(
    conversation: AiConversation,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    val defaultTitle = stringResource(R.string.ai_default_conversation_title)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        onClick = onSelect,
        enabled = enabled
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifBlank { defaultTitle },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = relativeTimeText(conversation.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.ai_conversation_actions_cd)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_rename)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_delete_conversation)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }
                    )
                }
            }
        }
    }
}

/**
 * 相对时间：今天显示「刚刚 / x 分钟前 / x 小时前」，昨天显示「昨天」，
 * 本年显示月日，跨年显示年月日；格式串随系统语言走资源文件。
 */
@Composable
private fun relativeTimeText(timeMs: Long): String {
    val date = Instant.ofEpochMilli(timeMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> {
            val diffMs = System.currentTimeMillis() - timeMs
            val minutes = diffMs / 60_000L
            when {
                minutes < 1 -> stringResource(R.string.ai_time_just_now)
                minutes < 60 -> stringResource(R.string.ai_time_minutes_ago, minutes.toInt())
                else -> stringResource(R.string.ai_time_hours_ago, (diffMs / 3_600_000L).toInt())
            }
        }
        date == today.minusDays(1) -> stringResource(R.string.ai_time_yesterday)
        date.year == today.year -> SimpleDateFormat(
            stringResource(R.string.ai_time_date_pattern),
            Locale.getDefault()
        ).format(Date(timeMs))
        else -> SimpleDateFormat(
            stringResource(R.string.ai_time_year_pattern),
            Locale.getDefault()
        ).format(Date(timeMs))
    }
}
