package cn.sanxing.thrice.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.ai.AiPermDomain
import cn.sanxing.thrice.ui.AppContainer
import kotlinx.coroutines.launch

/** Switch(59dp) + 水平 padding(4dp) 的占位宽度，用于「读取 / 修改」列标题对齐。 */
private val SwitchColumnWidth = 63.dp

/**
 * AI 数据权限管理弹窗：7 个数据域，每域「读取 / 修改」两个开关，
 * 切换即时持久化（setPermission）；底部一键全部开启 / 关闭。
 * 默认全部关闭（DataStore 缺省 false，AiPermissions.NONE）。
 *
 * AiPermDomain 实际枚举项（读自 AiPermissionRepository.kt）：
 * COURSES(courses,「课程表」)、TASKS(tasks,「任务」)、BILLS(bills,「账单」)、
 * FOCUS(focus,「专注」)、SLEEP(sleep,「睡眠」)、NOTES(notes,「随身记」)、
 * SETTINGS(settings,「设置」)。
 */
@Composable
fun AiPermissionDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val repository = container.aiPermissionRepository
    val perms by repository.permsFlow().collectAsState(initial = null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_dialog_perm_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.ai_perm_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                // 列标题：与下方两个 Switch 列对齐
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.ai_perm_read),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SwitchColumnWidth)
                    )
                    Text(
                        stringResource(R.string.ai_perm_write),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SwitchColumnWidth)
                    )
                }

                AiPermDomain.entries.forEach { domain ->
                    val canRead = perms?.isAllowed(domain, write = false) ?: false
                    val canWrite = perms?.isAllowed(domain, write = true) ?: false
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(domain.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = canRead,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    repository.setPermission(domain, write = false, enabled = enabled)
                                }
                            }
                        )
                        Switch(
                            checked = canWrite,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    repository.setPermission(domain, write = true, enabled = enabled)
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { scope.launch { repository.setAll(false) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_perm_all_off))
                    }
                    Button(
                        onClick = { scope.launch { repository.setAll(true) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_perm_all_on))
                    }
                }
            }
        }
    )
}

/** 数据域名走资源（不直接用枚举内置中文 label，保证英文环境可翻译）。 */
private fun AiPermDomain.labelRes(): Int = when (this) {
    AiPermDomain.COURSES -> R.string.ai_domain_courses
    AiPermDomain.TASKS -> R.string.ai_domain_tasks
    AiPermDomain.BILLS -> R.string.ai_domain_bills
    AiPermDomain.FOCUS -> R.string.ai_domain_focus
    AiPermDomain.SLEEP -> R.string.ai_domain_sleep
    AiPermDomain.NOTES -> R.string.ai_domain_notes
    AiPermDomain.SETTINGS -> R.string.ai_domain_settings
}
