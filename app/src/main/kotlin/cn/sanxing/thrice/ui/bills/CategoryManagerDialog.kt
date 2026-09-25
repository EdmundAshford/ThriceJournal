package cn.sanxing.thrice.ui.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.ui.common.ColorPickerSection
import androidx.compose.ui.res.stringResource

/** 新建分类时的备选颜色。 */
private val PALETTE = listOf(
    "#FF7043", "#EC407A", "#AB47BC", "#7E57C2", "#5C6BC0",
    "#42A5F5", "#29B6F6", "#26C6DA", "#66BB6A", "#9CCC65",
    "#FFA726", "#FFCA28", "#8D6E63", "#78909C", "#EF5350"
)

/**
 * 账单分类管理：按支出/收入分别列出，支持新增、改名、改颜色、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerDialog(
    categories: List<BillCategory>,
    onDismiss: () -> Unit,
    onAdd: (BillCategory) -> Unit,
    onSave: (existing: BillCategory, newName: String, newColor: String) -> Unit,
    onDelete: (BillCategory) -> Unit
) {
    var type by remember { mutableStateOf(BillType.EXPENSE) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BillCategory?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_manage_title)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BillType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, BillType.entries.size)
                        ) { Text(if (t == BillType.EXPENSE) stringResource(R.string.bill_expense) else stringResource(R.string.bill_income)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { adding = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.category_add), color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    categories.filter { it.type == type }.forEach { cat ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editing = cat }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(parseCategoryColor(cat.colorHex), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(cat.name, Modifier.weight(1f))
                            IconButton(onClick = { onDelete(cat) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.category_delete_cd, cat.name),
                                    tint = EXPENSE_RED
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    if (adding) {
        CategoryEditSheet(
            title = stringResource(R.string.category_add),
            initialName = "",
            initialColor = PALETTE.first(),
            existingNames = categories.filter { it.type == type }.map { it.name }.toSet(),
            onDismiss = { adding = false },
            onConfirm = { name, color ->
                val maxOrder = categories.filter { it.type == type }.maxOfOrNull { it.sortOrder } ?: 0
                onAdd(BillCategory(name = name, type = type, colorHex = color, sortOrder = maxOrder + 1))
                adding = false
            }
        )
    }
    editing?.let { cat ->
        CategoryEditSheet(
            title = stringResource(R.string.category_edit),
            initialName = cat.name,
            initialColor = cat.colorHex,
            existingNames = categories.filter { it.type == cat.type }.map { it.name }
                .filterNot { it == cat.name }.toSet(),
            onDismiss = { editing = null },
            onConfirm = { name, color ->
                onSave(cat, name, color)
                editing = null
            }
        )
    }
}

/** 新增 / 编辑分类的子弹窗：名称 + 调色板。 */
@Composable
private fun CategoryEditSheet(
    title: String,
    initialName: String,
    initialColor: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    val trimmed = name.trim()
    val colorValid = runCatching { android.graphics.Color.parseColor(color) }.isSuccess
    val valid = trimmed.isNotBlank() && trimmed !in existingNames && colorValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(trimmed, color) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    isError = trimmed in existingNames,
                    supportingText = if (trimmed in existingNames) {
                        { Text(stringResource(R.string.category_exists)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.field_color), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                // 预设色板 + RGB 滑块 + 自定义 Hex
                ColorPickerSection(
                    colorHex = color,
                    onColorChange = { color = it },
                    presetColors = PALETTE
                )
            }
        }
    )
}
