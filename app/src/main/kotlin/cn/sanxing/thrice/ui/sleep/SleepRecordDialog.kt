package cn.sanxing.thrice.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.ui.common.WheelNumberPicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 睡眠记录补录 / 编辑：类型 + 入睡（日期+滚轮时刻）+ 苏醒（日期+滚轮时刻）+ 备注。
 * 苏醒必须晚于入睡且时长 > 0；编辑中可删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepRecordDialog(
    initial: SleepRecord?,
    isZh: Boolean,
    onDismiss: () -> Unit,
    onSave: (SleepRecord) -> Unit,
    onDelete: (SleepRecord) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }
    val baseSleep = remember { initial?.sleepAtEpochMs ?: (now - 8L * 3600_000L) }
    val baseWake = remember { initial?.wakeAtEpochMs ?: now }

    var kind by remember {
        mutableStateOf(runCatching { SleepKind.valueOf(initial?.kind ?: "NIGHT") }
            .getOrDefault(SleepKind.NIGHT))
    }
    var sleepDate by remember { mutableStateOf(Instant.ofEpochMilli(baseSleep).atZone(zone).toLocalDate()) }
    var sleepHour by remember { mutableIntStateOf(Instant.ofEpochMilli(baseSleep).atZone(zone).hour) }
    var sleepMinute by remember { mutableIntStateOf(Instant.ofEpochMilli(baseSleep).atZone(zone).minute) }
    var wakeDate by remember { mutableStateOf(Instant.ofEpochMilli(baseWake).atZone(zone).toLocalDate()) }
    var wakeHour by remember { mutableIntStateOf(Instant.ofEpochMilli(baseWake).atZone(zone).hour) }
    var wakeMinute by remember { mutableIntStateOf(Instant.ofEpochMilli(baseWake).atZone(zone).minute) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var showSleepDatePicker by remember { mutableStateOf(false) }
    var showWakeDatePicker by remember { mutableStateOf(false) }

    fun combine(date: LocalDate, h: Int, m: Int) =
        date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    val sleepMs = combine(sleepDate, sleepHour, sleepMinute)
    val wakeMs = combine(wakeDate, wakeHour, wakeMinute)
    val minutes = ((wakeMs - sleepMs) / 60_000L).toInt()
    val valid = wakeMs > sleepMs && minutes > 0

    val dateFmt = remember(isZh) {
        DateTimeFormatter.ofPattern(if (isZh) "yyyy-MM-dd EEE" else "MMM d, yyyy EEE",
            if (isZh) Locale.CHINESE else Locale.ENGLISH)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initial == null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = kind == SleepKind.NIGHT,
                            onClick = { kind = SleepKind.NIGHT },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text(stringResource(R.string.sleep_kind_night)) }
                        SegmentedButton(
                            selected = kind == SleepKind.NAP,
                            onClick = { kind = SleepKind.NAP },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text(stringResource(R.string.sleep_kind_nap)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(stringResource(R.string.sleep_field_sleep_at),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showSleepDatePicker = true }) {
                    Text(dateFmt.format(sleepDate))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    WheelNumberPicker(
                        value = sleepHour, onValueChange = { sleepHour = it },
                        range = 0..23,
                        format = { "%02d".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(":")
                    WheelNumberPicker(
                        value = sleepMinute, onValueChange = { sleepMinute = it },
                        range = 0..59,
                        format = { "%02d".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sleep_field_wake_at),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showWakeDatePicker = true }) {
                    Text(dateFmt.format(wakeDate))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    WheelNumberPicker(
                        value = wakeHour, onValueChange = { wakeHour = it },
                        range = 0..23,
                        format = { "%02d".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(":")
                    WheelNumberPicker(
                        value = wakeMinute, onValueChange = { wakeMinute = it },
                        range = 0..59,
                        format = { "%02d".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sleep_field_minutes) + "：$minutes",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = if (valid)
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    else androidx.compose.material3.MaterialTheme.colorScheme.error
                )
                if (!valid) {
                    Text(
                        if (minutes <= 0) stringResource(R.string.sleep_wake_before_sleep)
                        else stringResource(R.string.sleep_zero_invalid),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(50) },
                    label = { Text(stringResource(R.string.sleep_note_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        (initial ?: SleepRecord(sleepAtEpochMs = sleepMs)).copy(
                            kind = kind.name,
                            sleepAtEpochMs = sleepMs,
                            wakeAtEpochMs = wakeMs,
                            minutes = minutes,
                            note = note.trim()
                        )
                    )
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    )

    if (showSleepDatePicker) {
        // DatePicker 以 UTC 零点归一 millis，日期初值/回读走 UTC（时刻换算才用 zone）
        val state = rememberDatePickerState(
            initialSelectedDateMillis = sleepDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showSleepDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        sleepDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showSleepDatePicker = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSleepDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) { DatePicker(state = state) }
    }
    if (showWakeDatePicker) {
        // 同睡眠日期：DatePicker 入参/回读统一 UTC
        val state = rememberDatePickerState(
            initialSelectedDateMillis = wakeDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showWakeDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        wakeDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showWakeDatePicker = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWakeDatePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) { DatePicker(state = state) }
    }
}
