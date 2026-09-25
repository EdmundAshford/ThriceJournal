package cn.sanxing.thrice.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.sanxing.thrice.ui.theme.LocalAppFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.data.local.SeedData
import cn.sanxing.thrice.data.domain.model.Term
import cn.sanxing.thrice.parser.conflict.ConflictDetector
import cn.sanxing.thrice.parser.format.FormatDetector
import cn.sanxing.thrice.parser.merge.CourseMerger
import cn.sanxing.thrice.parser.model.ParseResult
import cn.sanxing.thrice.parser.model.ParsedCourse
import cn.sanxing.thrice.parser.model.ScheduleFormat
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.UiUtils
import cn.sanxing.thrice.ui.components.PostcardFrame
import cn.sanxing.thrice.ui.components.StepperRow
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入向导（四步）：选择数据 → 预览映射 → 冲突检查 → 完成。
 *
 * - Step1：PDF（OpenDocument + pdfbox 抽文本）/ Excel(.xlsx，内置零依赖解析) /
 *   CSV / 粘贴文本 / 2 个 PDF 样例（点开有格式参考与 AI 转换指引）；
 * - Step2：格式判定（格式/置信度/依据）+ 映射报告表格，点行可手动修正（applyManualFix）；
 * - Step3：冲突列表（同天同节次且周次有交集）+ 告警，可"忽略并继续"；
 * - Step4：写入统计 + 选择/新建学期 → ImportScheduleUseCase.importParsed 落库。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(
    container: AppContainer,
    onFinished: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var rawText by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var successCount by remember { mutableIntStateOf(-1) }

    val terms by container.termRepository.observeAll().collectAsState(initial = emptyList())
    var targetTermId by remember { mutableStateOf<Long?>(null) }

    val steps = listOf(
        stringResource(R.string.import_step1),
        stringResource(R.string.import_step2),
        stringResource(R.string.import_step3),
        stringResource(R.string.import_step4)
    )

    /** 解析完成后的统一收尾：进入 Step2。 */
    fun onParsed(r: ParseResult, sourceText: String) {
        result = r
        rawText = sourceText
        errorRes = null
        step = 1
        busy = false
    }

    fun fail(res: Int) {
        errorRes = res
        busy = false
    }

    /** 手动修正某条原始课程：applyManualFix + 重跑合并与冲突检测。 */
    fun applyFix(index: Int, patch: Map<String, String>) {
        val r = result ?: return
        val fixed = r.rawCourses.toMutableList()
        if (index !in fixed.indices) return
        fixed[index] = container.parserUseCase.applyManualFix(fixed[index], patch)
        val merged = CourseMerger.merge(fixed)
        result = r.copy(
            rawCourses = fixed,
            courses = merged.courses.filter { it.kind == cn.sanxing.thrice.parser.model.CourseKind.GRID },
            conflicts = ConflictDetector.detect(merged.courses)
        )
    }

    /** 手动补录映射报告里"未识别出课程名"而被跳过的行：修正后作为新原始课程追加。 */
    fun applyAdd(seed: ParsedCourse, patch: Map<String, String>) {
        val r = result ?: return
        val created = container.parserUseCase.applyManualFix(seed, patch)
        if (created.name.isBlank()) return
        val fixed = r.rawCourses + created
        val merged = CourseMerger.merge(fixed)
        result = r.copy(
            rawCourses = fixed,
            courses = merged.courses.filter { it.kind == cn.sanxing.thrice.parser.model.CourseKind.GRID },
            conflicts = ConflictDetector.detect(merged.courses)
        )
    }

    fun reparse(format: ScheduleFormat) {
        val text = rawText ?: return
        result = container.parserUseCase.reparseWithOverride(text, format)
    }

    // ---------------- Step1 启动器 ----------------
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readPdf(context.contentResolver.openInputStream(it), container, scope, ::onParsed) { fail(R.string.import_pdf_failed) } }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                busy = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                    }
                    val text = bytes?.toString(Charsets.UTF_8) ?: ""
                    onParsed(withContext(Dispatchers.Default) { container.importScheduleUseCase.previewCsv(text) }, text)
                } catch (_: Exception) {
                    fail(R.string.import_read_failed)
                }
            }
        }
    }
    // Excel(.xlsx)：MIME 在不同文件管理器上可能被标成 octet-stream，
    // 这里一并放行，文件本身的合法性由 XlsxReader（zip 头）校验。
    val excelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                busy = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                    } ?: run {
                        fail(R.string.import_read_failed)
                        return@launch
                    }
                    val r = withContext(Dispatchers.Default) {
                        container.importScheduleUseCase.previewExcel(bytes)
                    }
                    onParsed(r, r.rawText)
                } catch (_: Exception) {
                    fail(R.string.import_excel_failed)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        PostcardFrame(
            title = stringResource(R.string.import_title),
            fillHeight = false,
            modifier = Modifier.fillMaxWidth(),
            header = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } }
        ) {
            StepperRow(steps = steps, currentStep = step)
            Spacer(Modifier.height(12.dp))
            errorRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }

            when (step) {
                0 -> Step1Content(
                    onPickPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                    onPickCsv = { csvLauncher.launch(arrayOf("*/*")) },
                    onPickExcel = {
                        excelLauncher.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onUseText = { text ->
                        busy = true
                        scope.launch(Dispatchers.Default) {
                            try {
                                onParsed(container.importScheduleUseCase.previewText(text), text)
                            } catch (_: Exception) {
                                fail(R.string.import_read_failed)
                            }
                        }
                    },
                    onLoadSample = { path, isPdf ->
                        busy = true
                        scope.launch {
                            try {
                                if (isPdf) {
                                    readPdf(
                                        withContext(Dispatchers.IO) { context.assets.open(path) },
                                        container, scope, ::onParsed
                                    ) { fail(R.string.import_pdf_failed) }
                                } else {
                                    val text = withContext(Dispatchers.IO) {
                                        context.assets.open(path).bufferedReader().use { it.readText() }
                                    }
                                    onParsed(
                                        withContext(Dispatchers.Default) { container.importScheduleUseCase.previewText(text) },
                                        text
                                    )
                                }
                            } catch (_: Exception) {
                                fail(R.string.import_read_failed)
                            }
                        }
                    }
                )

                1 -> result?.let { r ->
                    Step2Content(
                        result = r,
                        onFix = ::applyFix,
                        onAdd = ::applyAdd,
                        onReparse = ::reparse
                    )
                }

                2 -> result?.let { r ->
                    Step3Content(result = r)
                }

                3 -> result?.let { r ->
                    Step4Content(
                        result = r,
                        terms = terms,
                        targetTermId = targetTermId ?: terms.firstOrNull { it.isActive }?.id ?: terms.firstOrNull()?.id,
                        onSelectTerm = { targetTermId = it },
                        onCreateTerm = { name ->
                            scope.launch {
                                val id = container.termRepository.createBlankScheme(
                                    name = name,
                                    startDate = SeedData.DEFAULT_START_DATE,
                                    totalWeeks = SeedData.DEFAULT_TOTAL_WEEKS
                                )
                                targetTermId = id
                            }
                        },
                        alreadyImported = successCount >= 0,
                        onConfirm = {
                            val termId = targetTermId ?: terms.firstOrNull { it.isActive }?.id
                                ?: terms.firstOrNull()?.id
                            if (termId != null) {
                                scope.launch {
                                    busy = true
                                    try {
                                        val importResult = container.importScheduleUseCase.importParsed(r, termId)
                                        successCount = importResult.courses.size
                                        // 确保导入后目标学期成为活跃学期
                                        container.termRepository.setActive(termId)
                                    } catch (_: Exception) {
                                        errorRes = R.string.import_failed
                                    }
                                    busy = false
                                }
                            }
                        }
                    )
                }
            }

            // ---------------- 底部导航按钮 ----------------
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step == 1 || step == 2 || step == 3) {
                    OutlinedButton(onClick = { step-- }) {
                        Text(stringResource(R.string.action_prev))
                    }
                }
                when (step) {
                    0 -> {}
                    1 -> if (result != null) Button(onClick = { step = 2 }) { Text(stringResource(R.string.action_next)) }
                    2 -> Button(onClick = { step = 3 }) { Text(stringResource(R.string.action_next)) }
                    3 -> {
                        if (successCount >= 0) {
                            Button(onClick = onFinished) { Text(stringResource(R.string.import_go_timetable)) }
                        }
                    }
                }
            }
            if (step == 3 && successCount >= 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_success, successCount),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 读取 PDF 字节流 → 抽文本 → 解析预览。流可为 contentResolver 或 assets 打开。 */
private fun readPdf(
    stream: java.io.InputStream?,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onParsed: (ParseResult, String) -> Unit,
    onFail: () -> Unit
) {
    scope.launch {
        try {
            val bytes = withContext(Dispatchers.IO) { stream?.use { it.readBytes() } }
            if (bytes == null) {
                onFail()
                return@launch
            }
            val r = withContext(Dispatchers.Default) { container.importScheduleUseCase.previewPdf(bytes) }
            onParsed(r, r.rawText)
        } catch (_: Exception) {
            onFail()
        }
    }
}

// ============================================================
// Step 1：选择数据
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step1Content(
    onPickPdf: () -> Unit,
    onPickCsv: () -> Unit,
    onPickExcel: () -> Unit,
    onUseText: (String) -> Unit,
    onLoadSample: (String, Boolean) -> Unit
) {
    var pasted by remember { mutableStateOf("") }
    // 弹出格式 A / B 的细致参考说明（null = 不显示）
    var referenceFormat by remember { mutableStateOf<ScheduleFormat?>(null) }

    Text(
        text = stringResource(R.string.import_step1),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    // 文件入口：PDF / Excel / CSV（FlowRow 自动换行，窄屏不挤）
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        OutlinedButton(onClick = onPickPdf) { Text(stringResource(R.string.import_pick_pdf)) }
        OutlinedButton(onClick = onPickExcel) { Text(stringResource(R.string.import_pick_excel)) }
        OutlinedButton(onClick = onPickCsv) { Text(stringResource(R.string.import_pick_csv)) }
    }
    Text(
        stringResource(R.string.import_excel_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = pasted,
        onValueChange = { pasted = it },
        label = { Text(stringResource(R.string.import_paste_text)) },
        placeholder = { Text(stringResource(R.string.import_paste_hint)) },
        minLines = 4,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = { onUseText(pasted) },
        enabled = pasted.isNotBlank()
    ) { Text(stringResource(R.string.import_use_text)) }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.import_smart_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.import_load_sample),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalAppFontFamily.current,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        text = stringResource(R.string.import_sample_ref_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(top = 2.dp)
    )
    Spacer(Modifier.height(4.dp))
    SampleButton(R.string.import_sample_pdf_a) { referenceFormat = ScheduleFormat.FORMAT_A }
    SampleButton(R.string.import_sample_pdf_b) { referenceFormat = ScheduleFormat.FORMAT_B }

    referenceFormat?.let { format ->
        FormatReferenceDialog(
            format = format,
            onLoadSample = {
                val path = if (format == ScheduleFormat.FORMAT_A) {
                    "samples/schedule_formatA.pdf"
                } else {
                    "samples/schedule_formatB.pdf"
                }
                referenceFormat = null
                onLoadSample(path, true)
            },
            onDismiss = { referenceFormat = null }
        )
    }
}

@Composable
private fun SampleButton(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(labelRes)) }
}

/**
 * 两种 PDF 格式的细致参考对话框：
 * ①格式外观特征 → ②字段映射规则 → ③让 AI 转换的操作指引（含「截图课表+映射规则发给 AI」），
 * 并保留一键载入对应 PDF 样例的入口。
 */
@Composable
private fun FormatReferenceDialog(
    format: ScheduleFormat,
    onLoadSample: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatTag = if (format == ScheduleFormat.FORMAT_A) "A" else "B"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_ref_title_fmt, formatTag)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.import_ref_section_look),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(
                        if (format == ScheduleFormat.FORMAT_A) R.string.import_pdf_a_format_desc
                        else R.string.import_pdf_b_format_desc
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.import_ref_section_mapping),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.import_ref_mapping_body),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LocalAppFontFamily.current,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.import_ref_section_ai),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.import_ref_ai_body),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onLoadSample) {
                Text(stringResource(R.string.import_ref_load_sample))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_ref_close)) }
        }
    )
}

/** 原始文本预览：等宽、可滚动；超长文本截断（防 OOM），防大位图进内存。 */
@Composable
fun RawPreview(text: String, maxLines: Int = 300) {
    val lines = text.lines()
    val shown = if (lines.size > maxLines) lines.take(maxLines) else lines
    Text(
        text = stringResource(R.string.import_raw_preview),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalAppFontFamily.current,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = shown.joinToString("\n") +
                if (lines.size > maxLines) "\n" + stringResource(R.string.import_truncated, lines.size) else "",
            fontSize = 10.sp,
            fontFamily = LocalAppFontFamily.current,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// Step 2：预览映射
// ============================================================

@Composable
private fun Step2Content(
    result: ParseResult,
    onFix: (Int, Map<String, String>) -> Unit,
    onAdd: (ParsedCourse, Map<String, String>) -> Unit,
    onReparse: (ScheduleFormat) -> Unit
) {
    // 正在修正的已识别课程下标（对应 rawCourses）；-1 = 未打开
    var fixCourseIndex by remember { mutableIntStateOf(-1) }
    // 正在补录的"被跳过行"种子课程；null = 未打开
    var fixSeed by remember { mutableStateOf<ParsedCourse?>(null) }
    // 映射报告折叠状态：默认展开，点击按钮条收起 / 展开。
    // R17：此前按钮条只是装饰条（无 onClick），且解析器漏传 mappingReport 时
    // 下方一条内容都没有，用户点击毫无反馈。
    var mappingExpanded by remember(result.rawText) { mutableStateOf(true) }
    val detection = remember(result.rawText) {
        FormatDetector.detect(cn.sanxing.thrice.parser.text.TextNormalizer.normalize(result.rawText))
    }

    Text(
        text = stringResource(R.string.import_format_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.import_format_label) + ": " + stringResource(formatLabelRes(result.format)) +
            "  " + stringResource(
                R.string.import_confidence,
                String.format(Locale.CHINA, "%.2f", detection.confidence)
            ),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = LocalAppFontFamily.current
    )
    Text(
        text = stringResource(R.string.import_reason, detection.reason),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.import_reparse), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onReparse(ScheduleFormat.FORMAT_A) }) { Text("A") }
        TextButton(onClick = { onReparse(ScheduleFormat.FORMAT_B) }) { Text("B") }
        TextButton(onClick = { onReparse(ScheduleFormat.CSV) }) { Text("CSV") }
    }
    Spacer(Modifier.height(8.dp))
    // 映射报告入口条：真正的折叠开关，点击展开 / 收起报告条目。
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { mappingExpanded = !mappingExpanded }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.import_mapping_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (mappingExpanded) 90f else 0f)
            )
        }
    }
    Spacer(Modifier.height(6.dp))

    if (mappingExpanded) {
        // 映射报告：每条原始行 → 抽到的字段；未识别行高亮；点行可修正 / 补录。
        // 注意：必须用 entry.courseIndex 定位 rawCourses——被跳过的行（无课程名）
        // 不产生 rawCourse，直接用行号会错位，导致点行无反应或修正到别的课程。
        result.mappingReport.forEach { entry ->
        val recognized = entry.courseIndex in result.rawCourses.indices
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (recognized) {
                        fixCourseIndex = entry.courseIndex
                    } else {
                        fixSeed = seedCourseFromEntry(entry)
                    }
                }
                .background(
                    if (recognized) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    RoundedCornerShape(4.dp)
                )
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.rawLine.take(120) + if (entry.rawLine.length > 120) "…" else "",
                    fontSize = 10.sp,
                    fontFamily = LocalAppFontFamily.current,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.fields.isEmpty()) {
                    Text(
                        text = stringResource(R.string.import_unrecognized),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = entry.fields.entries.joinToString("　") { (k, v) -> "$k=$v" },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Text(
                    text = stringResource(
                        if (recognized) R.string.import_tap_to_fix
                        else R.string.import_tap_to_add
                    ),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
    }

        // 空态：一条映射都没有时（如 PDF 抽取后未识别出任何标准课程行），
        // 明确提示并引导用上方 A/B/CSV 强制重解析——此前此处一片空白，
        // 用户点击报告按钮条像"没反应"。
        if (result.mappingReport.isEmpty()) {
            Text(
                text = stringResource(R.string.import_mapping_empty),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }

    // 其他课程（原始行不在映射报告里，单独列出）
    if (result.otherCourses.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.other_courses_title, result.otherCourses.size),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
        result.otherCourses.forEach { c ->
            Text(
                text = "• ${c.name}　${c.teacher}　${c.weeksRaw.ifBlank { UiUtils.formatWeeks(c.weeks) }}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }

    if (fixCourseIndex in result.rawCourses.indices) {
        ManualFixDialog(
            course = result.rawCourses[fixCourseIndex],
            onApply = { patch -> onFix(fixCourseIndex, patch); fixCourseIndex = -1 },
            onDismiss = { fixCourseIndex = -1 }
        )
    }
    fixSeed?.let { seed ->
        ManualFixDialog(
            course = seed,
            onApply = { patch -> onAdd(seed, patch); fixSeed = null },
            onDismiss = { fixSeed = null }
        )
    }
}

/**
 * 为映射报告中"未产生课程"的跳过行构造手动补录种子：
 * 用已抽到的字段预填（课名 / 教师 / 地点 / 星期 / 节次等），用户补全后走 applyManualFix。
 */
private fun seedCourseFromEntry(entry: cn.sanxing.thrice.parser.model.MappingEntry): ParsedCourse {
    val f = entry.fields
    var c = ParsedCourse(
        name = f["name"].orEmpty(),
        rawLine = entry.rawLine,
        colorHex = f["name"]?.takeIf { it.isNotBlank() }
            ?.let { cn.sanxing.thrice.parser.parse.CommonParser.defaultColor(it) }
            ?: ""
    )
    c = cn.sanxing.thrice.parser.parse.CommonParser.applyFieldMap(c, f)
    f["dayOfWeek"]?.let { raw ->
        cn.sanxing.thrice.parser.parse.CommonParser.parseDayOfWeek(raw)
            ?.let { d -> c = c.copy(dayOfWeek = d) }
    }
    f["sections"]?.let { raw ->
        cn.sanxing.thrice.parser.parse.CommonParser.parseSection(raw)
            ?.let { (s, e) -> c = c.copy(startSection = s, endSection = e) }
    }
    return c
}

/** 手动修正对话框：编辑字段后 applyManualFix 重解析。 */
@Composable
private fun ManualFixDialog(
    course: ParsedCourse,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(course.name) }
    var teacher by remember { mutableStateOf(course.teacher) }
    var location by remember { mutableStateOf(course.location) }
    var day by remember { mutableStateOf(if (course.dayOfWeek > 0) course.dayOfWeek.toString() else "") }
    var sections by remember {
        mutableStateOf(if (course.startSection > 0) "${course.startSection}-${course.endSection}" else "")
    }
    var weeks by remember { mutableStateOf(course.weeksRaw.ifBlank { UiUtils.formatWeeks(course.weeks) }) }
    var credit by remember { mutableStateOf(course.credit?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_manual_fix)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FixField(stringResource(R.string.import_col_name), name) { name = it }
                FixField(stringResource(R.string.import_col_teacher), teacher) { teacher = it }
                FixField(stringResource(R.string.import_col_location), location) { location = it }
                FixField(stringResource(R.string.fix_day_hint, stringResource(R.string.import_col_day)), day) { day = it.filter(Char::isDigit).take(1) }
                FixField(stringResource(R.string.fix_section_hint, stringResource(R.string.import_col_section)), sections) { sections = it }
                FixField(stringResource(R.string.fix_weeks_hint, stringResource(R.string.import_col_weeks)), weeks) { weeks = it }
                FixField(stringResource(R.string.import_col_credit), credit) { credit = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val patch = mutableMapOf<String, String>()
                if (name != course.name) patch["name"] = name
                if (teacher != course.teacher) patch["teacher"] = teacher
                if (location != course.location) patch["location"] = location
                if (day.isNotBlank()) patch["dayOfWeek"] = day
                if (sections.isNotBlank()) patch["sections"] = sections
                if (weeks.isNotBlank()) patch["weeks"] = weeks
                if (credit.isNotBlank()) patch["credit"] = credit
                onApply(patch)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun FixField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

// ============================================================
// Step 3：冲突检查
// ============================================================

@Composable
private fun Step3Content(result: ParseResult) {
    Text(
        text = stringResource(R.string.import_conflict_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
    if (result.conflicts.isEmpty()) {
        Text(
            text = stringResource(R.string.import_no_conflicts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Text(
            text = stringResource(R.string.import_conflict_count, result.conflicts.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        result.conflicts.forEach { c ->
            Text(
                text = stringResource(
                    R.string.import_conflict_item,
                    c.courseA, c.courseB, c.dayOfWeek.toString(),
                    c.startSection, c.endSection, UiUtils.formatWeeks(c.overlappingWeeks)
                ),
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
    if (result.warnings.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.import_warnings_title),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = LocalAppFontFamily.current,
            color = MaterialTheme.colorScheme.primary
        )
        result.warnings.forEach { w ->
            Text(
                text = "• $w",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.import_ignore_continue),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
}

// ============================================================
// Step 4：完成
// ============================================================

@Composable
private fun Step4Content(
    result: ParseResult,
    terms: List<Term>,
    targetTermId: Long?,
    onSelectTerm: (Long) -> Unit,
    onCreateTerm: (String) -> Unit,
    alreadyImported: Boolean,
    onConfirm: () -> Unit
) {
    var showCreate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val springName = stringResource(R.string.term_name_spring)
    val autumnName = stringResource(R.string.term_name_autumn)
    val defaultTermName = java.time.LocalDate.now().let { d ->
        stringResource(
            R.string.term_name_default_fmt,
            d.year,
            if (d.monthValue <= 6) springName else autumnName
        )
    }
    var newTermName by androidx.compose.runtime.remember(defaultTermName) {
        androidx.compose.runtime.mutableStateOf(defaultTermName)
    }
    Text(
        text = stringResource(R.string.import_summary, result.courses.size, result.otherCourses.size),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.import_select_term),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalAppFontFamily.current,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    terms.forEach { t ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectTerm(t.id) }
                .padding(vertical = 3.dp)
        ) {
            androidx.compose.material3.RadioButton(
                selected = targetTermId == t.id,
                onClick = { onSelectTerm(t.id) }
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = t.name + if (t.isActive) stringResource(R.string.term_active_suffix) else "",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    if (!showCreate) {
        TextButton(onClick = { showCreate = true }) {
            Text("+ " + stringResource(R.string.import_create_term))
        }
    } else {
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = newTermName,
            onValueChange = { newTermName = it },
            singleLine = true,
            label = { Text(stringResource(R.string.import_term_name_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(
                enabled = newTermName.isNotBlank(),
                onClick = {
                    onCreateTerm(newTermName.trim())
                    showCreate = false
                }
            ) { Text(stringResource(R.string.import_term_create_confirm)) }
            androidx.compose.material3.TextButton(onClick = { showCreate = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (!alreadyImported) {
        Button(
            onClick = onConfirm,
            enabled = targetTermId != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.import_confirm)) }
    }
}

private fun formatLabelRes(format: ScheduleFormat): Int = when (format) {
    ScheduleFormat.FORMAT_A -> R.string.import_format_a
    ScheduleFormat.FORMAT_B -> R.string.import_format_b
    ScheduleFormat.CSV -> R.string.import_format_csv
    ScheduleFormat.UNKNOWN -> R.string.import_format_unknown
}
