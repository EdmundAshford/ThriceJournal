package cn.sanxing.thrice.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R

/**
 * 「预连接获取可用模型」结果选择弹窗：
 * 高度受限的可滚动列表，点选后回填模型字段；同 id 只展示一次并按名称排序。
 */
@Composable
fun ModelPickerDialog(
    models: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_pick_model)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(models, key = { it }) { model ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(model) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
