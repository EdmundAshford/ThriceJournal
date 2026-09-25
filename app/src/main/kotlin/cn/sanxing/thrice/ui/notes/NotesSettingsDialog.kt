package cn.sanxing.thrice.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.repository.NotesSettingsRepository
import cn.sanxing.thrice.ui.common.WheelNumberPicker
import cn.sanxing.thrice.ui.theme.AppFontOption
import kotlinx.coroutines.launch

/**
 * 笔记设置弹窗：正文字号（12-24）、默认字体（第一项跟随全局）、
 * 单 / 双列布局、排序字段、升降序。全部实时写入 DataStore，界面即时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSettingsDialog(
    repository: NotesSettingsRepository,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val fontSize by repository.fontSize.collectAsState(NotesSettingsRepository.DEFAULT_FONT_SIZE)
    val fontKey by repository.fontKey.collectAsState(null)
    val twoColumn by repository.twoColumn.collectAsState(false)
    val sortField by repository.sortField.collectAsState(NotesSettingsRepository.SORT_FIELD_UPDATED)
    val sortAsc by repository.sortAscending.collectAsState(false)

    // 字体滚轮弹窗（草稿在确认时才落库，与设置页字体弹窗一致）
    var showFontPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_settings_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_confirm)) }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 正文字号：- 15 sp +
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.notes_settings_font_size),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = fontSize > NotesSettingsRepository.MIN_FONT_SIZE,
                            onClick = { scope.launch { repository.setFontSize(fontSize - 1) } }
                        ) { Icon(Icons.Filled.Remove, contentDescription = null) }
                        Text(
                            stringResource(R.string.notes_size_fmt, fontSize),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        IconButton(
                            enabled = fontSize < NotesSettingsRepository.MAX_FONT_SIZE,
                            onClick = { scope.launch { repository.setFontSize(fontSize + 1) } }
                        ) { Icon(Icons.Filled.Add, contentDescription = null) }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // 默认字体：点击弹滚轮
                val fontNumber = fontKey?.toIntOrNull() ?: 0
                val fontLabel = if (fontNumber == 0) {
                    stringResource(R.string.notes_follow_global)
                } else {
                    stringResource(R.string.notes_font_numbered_fmt, fontNumber)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.notes_settings_default_font),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        fontLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(
                    onClick = { showFontPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.notes_editor_font_title)) }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                SettingsChipRow(label = stringResource(R.string.notes_settings_layout)) {
                    FilterChip(
                        selected = !twoColumn,
                        onClick = { scope.launch { repository.setTwoColumn(false) } },
                        label = { Text(stringResource(R.string.notes_layout_one)) }
                    )
                    FilterChip(
                        selected = twoColumn,
                        onClick = { scope.launch { repository.setTwoColumn(true) } },
                        label = { Text(stringResource(R.string.notes_layout_two)) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                SettingsChipRow(label = stringResource(R.string.notes_settings_sort_field)) {
                    FilterChip(
                        selected = sortField == NotesSettingsRepository.SORT_FIELD_UPDATED,
                        onClick = {
                            scope.launch {
                                repository.setSortField(NotesSettingsRepository.SORT_FIELD_UPDATED)
                            }
                        },
                        label = { Text(stringResource(R.string.notes_sort_updated)) }
                    )
                    FilterChip(
                        selected = sortField == NotesSettingsRepository.SORT_FIELD_CREATED,
                        onClick = {
                            scope.launch {
                                repository.setSortField(NotesSettingsRepository.SORT_FIELD_CREATED)
                            }
                        },
                        label = { Text(stringResource(R.string.notes_sort_created)) }
                    )
                    FilterChip(
                        selected = sortField == NotesSettingsRepository.SORT_FIELD_TITLE,
                        onClick = {
                            scope.launch {
                                repository.setSortField(NotesSettingsRepository.SORT_FIELD_TITLE)
                            }
                        },
                        label = { Text(stringResource(R.string.notes_sort_title)) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                SettingsChipRow(label = stringResource(R.string.notes_settings_sort_order)) {
                    FilterChip(
                        selected = !sortAsc,
                        onClick = { scope.launch { repository.setSortAscending(false) } },
                        label = { Text(stringResource(R.string.notes_sort_desc)) }
                    )
                    FilterChip(
                        selected = sortAsc,
                        onClick = { scope.launch { repository.setSortAscending(true) } },
                        label = { Text(stringResource(R.string.notes_sort_asc)) }
                    )
                }
            }
        }
    )

    if (showFontPicker) {
        // 只列出包内实际存在的字体编号（字体文件不随仓库分发）
        val maxFont = AppFontOption.maxAvailableNumber(LocalContext.current)
        val fontNumber = (fontKey?.toIntOrNull() ?: 0).coerceAtMost(maxFont)
        var fontDraft by remember(fontNumber, maxFont) { mutableIntStateOf(fontNumber) }
        val fontLabels = (0..maxFont).map { n ->
            if (n == 0) stringResource(R.string.notes_follow_global)
            else stringResource(R.string.notes_font_numbered_fmt, n)
        }
        AlertDialog(
            onDismissRequest = { showFontPicker = false },
            title = { Text(stringResource(R.string.notes_settings_default_font)) },
            text = {
                WheelNumberPicker(
                    value = fontDraft,
                    onValueChange = { fontDraft = it },
                    range = 0..maxFont,
                    // 高度由组件内部按行高决定，外部不要另行限定（会与指示线错位）
                    modifier = Modifier.fillMaxWidth(),
                    format = { n -> fontLabels.getOrElse(n) { "" } },
                    // 每个编号行用其自身对应的字体渲染示例（缺失时内部回落系统字体）
                    itemFontFamily = { n -> AppFontOption.entries.getOrNull(n)?.fontFamily() }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 00 = 跟随全局，存 null
                        repository.setFontKey(if (fontDraft == 0) null else "%02d".format(fontDraft))
                    }
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
}

/** 小标题 + 自动换行的 Chip 行。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsChipRow(
    label: String,
    content: @Composable () -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}
