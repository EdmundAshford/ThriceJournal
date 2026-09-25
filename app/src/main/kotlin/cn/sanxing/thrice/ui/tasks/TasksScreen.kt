package cn.sanxing.thrice.ui.tasks

import android.database.sqlite.SQLiteConstraintException
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.model.TaskTag
import cn.sanxing.thrice.data.domain.model.TaskType as TaskTypeEntity
import cn.sanxing.thrice.data.domain.recurrence.TaskRecurrence
import cn.sanxing.thrice.data.domain.recurrence.firstOccurrenceOnOrAfter
import cn.sanxing.thrice.data.domain.recurrence.isCompletedOn
import cn.sanxing.thrice.data.domain.recurrence.isRecurring
import cn.sanxing.thrice.data.domain.recurrence.occurrencesInRange
import cn.sanxing.thrice.data.domain.recurrence.parsedRecurrence
import cn.sanxing.thrice.data.domain.recurrence.toggleCompletionOn
import cn.sanxing.thrice.notification.ReminderScheduler
import cn.sanxing.thrice.R
import cn.sanxing.thrice.ui.AppContainer
import cn.sanxing.thrice.ui.common.ColorPickerSection
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import cn.sanxing.thrice.parser.reminder.ReminderCalculator

private val DATE_FMT = DateTimeFormatter.ofPattern("M.d")

/**
 * 任务标签默认调色板（hex）：固定颜色、按标签总数取模，禁止未种子化的随机色。
 */
private val TASK_TAG_PRESET_COLORS = listOf(
    "#7E57C2", "#2E6DA4", "#E07A5F", "#81B29A", "#F2CC8F",
    "#57A773", "#D08C4A", "#4D7EA8", "#B56576", "#E89B9B"
)

/** 解析任务标签 hex 色；失败回落到标签紫。 */
private fun parseTagColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrElse { Color(0xFF7E57C2) }

/** 长日期格式（跟随系统语言，中文资源为 M月d日，英文资源为 MMM d）。 */
@Composable
private fun rememberLongDateFormatter(): DateTimeFormatter {
    val pattern = stringResource(R.string.task_date_fmt_long)
    return remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    container: AppContainer,
    onOpenEdit: (Long) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val allTasks by container.taskDao.observeAll().collectAsState(initial = emptyList())
    val taskTypes by container.taskTypeDao.observeAll().collectAsState(initial = emptyList())
    val taskTags by container.taskTagDao.observeAll().collectAsState(initial = emptyList())
    var showCalendar by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTypeManager by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var editingTask by remember { mutableStateOf<Task?>(null) }

    // 标签筛选：单选，null = 全部；与日期 / 类型筛选并存（列表数据先按标签过滤）
    var tagFilter by remember { mutableStateOf<Long?>(null) }
    var creatingTag by remember { mutableStateOf(false) }
    var renamingTag by remember { mutableStateOf<TaskTag?>(null) }
    var deletingTag by remember { mutableStateOf<TaskTag?>(null) }

    // 屏幕级订阅：每个标签一个「命中任务 id」Flow（数量随标签、不随任务），
    // 合并后在内存里翻转成 taskId -> tagIds，避免逐任务 N 次查询
    val tagIdsByTask: Map<Long, List<Long>> by produceState<Map<Long, List<Long>>>(
        emptyMap(), taskTags
    ) {
        if (taskTags.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        combine(taskTags.map { container.taskTagDao.observeTaskIdsForTag(it.id) }) { perTag ->
            val grouped = HashMap<Long, MutableList<Long>>()
            taskTags.indices.forEach { index ->
                val tagId = taskTags[index].id
                perTag[index].forEach { taskId ->
                    grouped.getOrPut(taskId) { mutableListOf() }.add(tagId)
                }
            }
            grouped
        }.collect { value = it }
    }
    val tagsByTask: Map<Long, List<TaskTag>> = remember(taskTags, tagIdsByTask) {
        tagIdsByTask.mapValues { (_, ids) ->
            ids.mapNotNull { id -> taskTags.find { it.id == id } }
        }
    }
    // 标签被删后筛选条件回到「全部」（删除时也会显式复位，这里做兜底）
    LaunchedEffect(taskTags) {
        if (tagFilter != null && taskTags.none { it.id == tagFilter }) tagFilter = null
    }

    val visibleTasks = tagFilter?.let { filterId ->
        val hitIds = tagIdsByTask.filterValues { filterId in it }.keys
        allTasks.filter { it.id in hitIds }
    } ?: allTasks

    /** 新建任务标签；重名时数据库唯一索引抛约束异常，返回 null 由调用方提示。 */
    val createTaskTag: suspend (String) -> Long? = { name ->
        try {
            container.taskTagDao.insert(
                TaskTag(
                    name = name,
                    colorHex = TASK_TAG_PRESET_COLORS[
                        taskTags.size % TASK_TAG_PRESET_COLORS.size
                    ],
                    sortOrder = (taskTags.maxOfOrNull { it.sortOrder } ?: 0) + 1
                )
            )
        } catch (e: SQLiteConstraintException) {
            null
        }
    }

    // 勾选 / 取消勾选某次发生：重复任务按日期记录，无日期 / 无规则任务用整体完成态
    val toggleComplete: (Task, LocalDate?) -> Unit = { task, date ->
        scope.launch {
            val updated = if (date != null) task.toggleCompletionOn(date)
            else task.copy(completed = !task.completed)
            container.taskDao.upsert(updated)
            ReminderScheduler.rescheduleTasks(context)
        }
    }
    val deleteTask: (Task) -> Unit = { task ->
        scope.launch {
            // 关联表无外键级联，删除任务前先清它的标签关联
            container.taskTagDao.deleteRefsForTask(task.id)
            container.taskDao.delete(task)
            ReminderScheduler.cancelTask(context, task.id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    TextButton(onClick = { showCalendar = !showCalendar }) {
                        Text(if (showCalendar) stringResource(R.string.tasks_list) else stringResource(R.string.tasks_calendar))
                    }
                    IconButton(onClick = { showTypeManager = true }) {
                        Icon(Icons.Filled.Category, contentDescription = stringResource(R.string.tasks_manage_types_cd))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.tasks_add_cd))
            }
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            TaskTagFilterBar(
                tags = taskTags,
                selectedTagId = tagFilter,
                onSelect = { tagFilter = it },
                onCreate = { creatingTag = true },
                onRename = { renamingTag = it },
                onDelete = { deletingTag = it }
            )
            if (showCalendar) {
                CalendarView(
                    tasks = visibleTasks,
                    taskTypes = taskTypes,
                    taskTagsByTask = tagsByTask,
                    selectedDate = selectedDate,
                    onDateSelected = { selectedDate = it },
                    onToggleComplete = toggleComplete,
                    onDelete = deleteTask,
                    onEdit = { editingTask = it },
                    modifier = Modifier.weight(1f)
                )
            } else {
                ListView(
                    tasks = visibleTasks,
                    taskTypes = taskTypes,
                    taskTagsByTask = tagsByTask,
                    onToggleComplete = toggleComplete,
                    onDelete = deleteTask,
                    onEdit = { task -> editingTask = task },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            taskTypes = taskTypes,
            taskTags = taskTags,
            initialTagIds = emptySet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { task, tagIds ->
                scope.launch {
                    // 先 upsert 拿到新任务 id，再整体替换标签关联
                    val id = container.taskDao.upsert(task)
                    container.taskTagDao.replaceTagsForTask(id, tagIds)
                    ReminderScheduler.rescheduleTasks(context)
                    showAddDialog = false
                }
            },
            onCreateTag = createTaskTag
        )
    }

    if (showTypeManager) {
        TaskTypeManagerDialog(
            taskTypes = taskTypes,
            onDismiss = { showTypeManager = false },
            onAdd = { name, color ->
                scope.launch {
                    val maxOrder = taskTypes.maxOfOrNull { it.sortOrder } ?: 0
                    container.taskTypeDao.upsert(TaskTypeEntity(name = name, colorHex = color, sortOrder = maxOrder + 1))
                }
            },
            onDelete = { name ->
                scope.launch { container.taskTypeDao.deleteByName(name) }
            }
        )
    }

    editingTask?.let { task ->
        AddTaskDialog(
            taskTypes = taskTypes,
            taskTags = taskTags,
            initialTagIds = tagIdsByTask[task.id]?.toSet() ?: emptySet(),
            onDismiss = { editingTask = null },
            onConfirm = { updatedTask, tagIds ->
                scope.launch {
                    container.taskDao.upsert(updatedTask.copy(id = task.id))
                    container.taskTagDao.replaceTagsForTask(task.id, tagIds)
                    ReminderScheduler.rescheduleTasks(context)
                    editingTask = null
                }
            },
            onCreateTag = createTaskTag,
            initialTask = task
        )
    }

    // 标签管理：新建（重名 Toast 提示）
    if (creatingTag) {
        TaskTagNameDialog(
            title = stringResource(R.string.task_tag_create_title),
            initial = "",
            existingNames = taskTags.map { it.name },
            onDismiss = { creatingTag = false },
            onConfirm = { name ->
                scope.launch {
                    val newId = createTaskTag(name)
                    if (newId == null) {
                        Toast.makeText(context, R.string.task_tag_exists, Toast.LENGTH_SHORT).show()
                    }
                }
                creatingTag = false
            }
        )
    }

    // 重命名
    renamingTag?.let { tag ->
        TaskTagNameDialog(
            title = stringResource(R.string.task_tag_rename_title),
            initial = tag.name,
            existingNames = taskTags.filter { it.id != tag.id }.map { it.name },
            onDismiss = { renamingTag = null },
            onConfirm = { name ->
                scope.launch {
                    try {
                        container.taskTagDao.update(tag.copy(name = name))
                    } catch (e: SQLiteConstraintException) {
                        Toast.makeText(context, R.string.task_tag_exists, Toast.LENGTH_SHORT).show()
                    }
                }
                renamingTag = null
            }
        )
    }

    // 删除：确认后事务内清关联并删除标签；若正按它筛选则回到「全部」
    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = { Text(stringResource(R.string.task_tag_delete_confirm, tag.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.taskTagDao.deleteTagAndRefs(tag.id) }
                    if (tagFilter == tag.id) tagFilter = null
                    deletingTag = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 任务标签筛选行：「全部」+ 各标签 chip（色点取自 colorHex，单选筛选，
 * 长按呼出重命名 / 删除菜单）+ 行尾「+」新建标签。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskTagFilterBar(
    tags: List<TaskTag>,
    selectedTagId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: () -> Unit,
    onRename: (TaskTag) -> Unit,
    onDelete: (TaskTag) -> Unit
) {
    var menuTag by remember { mutableStateOf<TaskTag?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskTagChip(
            text = stringResource(R.string.task_tag_filter_all),
            selected = selectedTagId == null,
            onClick = { onSelect(null) }
        )
        tags.forEach { tag ->
            Box {
                TaskTagChip(
                    text = tag.name,
                    selected = selectedTagId == tag.id,
                    dotColor = parseTagColor(tag.colorHex),
                    onClick = { onSelect(tag.id) },
                    onLongClick = { menuTag = tag }
                )
                DropdownMenu(
                    expanded = menuTag?.id == tag.id,
                    onDismissRequest = { menuTag = null }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_tag_rename)) },
                        onClick = {
                            menuTag = null
                            onRename(tag)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuTag = null
                            onDelete(tag)
                        }
                    )
                }
            }
        }
        TaskTagChip(
            text = "",
            selected = false,
            onClick = onCreate,
            leadingIcon = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.task_tag_new_cd),
                    modifier = Modifier.size(17.dp)
                )
            }
        )
    }
}

/** 任务标签胶囊 chip：点按 + 长按；选中 secondaryContainer，未选 surfaceVariant。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskTagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    dotColor: Color? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leadingIcon?.invoke()
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(dotColor, CircleShape)
                )
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 任务标签新建 / 重命名命名弹窗：空名与重名常识拦截，数据库唯一索引兜底。 */
@Composable
private fun TaskTagNameDialog(
    title: String,
    initial: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.trim() == trimmed }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.task_tag_name_hint)) }
                )
                if (duplicate) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.task_tag_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !duplicate,
                onClick = { onConfirm(trimmed) }
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ListView(
    tasks: List<Task>,
    taskTypes: List<TaskTypeEntity>,
    taskTagsByTask: Map<Long, List<TaskTag>>,
    onToggleComplete: (Task, LocalDate?) -> Unit,
    onDelete: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val longFmt = rememberLongDateFormatter()
    // 「近七天」窗口：含今天在内的最近 7 天
    val recentStart = today.minusDays(6)
    // 重复任务的近期展开窗口：今天起 60 天；窗口外再补「下一次发生」
    val futureWindowEnd = today.plusDays(60)

    // 分区：
    // - overdue：过期且未完成（重复任务仅取近 7 天窗口，避免长期积压刷屏）
    // - recentCompleted：近 7 天窗口内已完成的发生（更早的完成记录界面隐藏、仍在数据库）
    // - 日期分组：今天及以后的发生（已完成但日期在未来的留在原地）
    // - 无日期：始终单列一组
    val overdue = mutableListOf<TaskOccurrence>()
    val recentCompleted = mutableListOf<TaskOccurrence>()
    val dateGroups = sortedMapOf<String, MutableList<TaskOccurrence>>()
    val withoutDate = mutableListOf<TaskOccurrence>()

    tasks.forEach { task ->
        val anchor = task.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (!task.isRecurring) {
            when {
                anchor == null -> withoutDate.add(TaskOccurrence(task, null))
                task.completed && anchor in recentStart..today ->
                    recentCompleted.add(TaskOccurrence(task, anchor))
                task.completed && anchor < recentStart -> Unit
                !task.completed && anchor < today ->
                    overdue.add(TaskOccurrence(task, anchor))
                else -> dateGroups.getOrPut(anchor.toString()) { mutableListOf() }
                    .add(TaskOccurrence(task, anchor))
            }
        } else {
            // 近 7 天窗口：完成 → 近期完成；未完成且在今天之前 → 过期；今天 → 当日组
            task.occurrencesInRange(recentStart, today).forEach { day ->
                when {
                    task.isCompletedOn(day) -> recentCompleted.add(TaskOccurrence(task, day))
                    day < today -> overdue.add(TaskOccurrence(task, day))
                    else -> dateGroups.getOrPut(day.toString()) { mutableListOf() }
                        .add(TaskOccurrence(task, day))
                }
            }
            // 未来窗口（不含今天，已在上面处理）
            task.occurrencesInRange(today.plusDays(1), futureWindowEnd).forEach { day ->
                dateGroups.getOrPut(day.toString()) { mutableListOf() }
                    .add(TaskOccurrence(task, day))
            }
            // 窗口之后的下一次未完成发生（每月 / 每年等远期任务保持可见）
            val farNext = task.firstOccurrenceOnOrAfter(
                futureWindowEnd.plusDays(1), skipCompleted = true
            )
            if (farNext != null) {
                dateGroups.getOrPut(farNext.toString()) { mutableListOf() }
                    .add(TaskOccurrence(task, farNext))
            }
        }
    }
    overdue.sortWith(compareBy({ it.dateStr }, { it.task.startTime }, { it.task.title }))
    recentCompleted.sortWith(
        compareByDescending<TaskOccurrence> { it.dateStr }.thenBy { it.task.startTime }
    )
    dateGroups.values.forEach { group ->
        group.sortWith(compareBy({ it.task.startTime }, { it.task.title }))
    }

    // 折叠状态：key 存在且为 true 表示折叠；默认全部展开
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val sectionKeys = buildList {
        if (overdue.isNotEmpty()) add(KEY_OVERDUE)
        if (withoutDate.isNotEmpty()) add(KEY_NO_DATE)
        addAll(dateGroups.keys)
        if (recentCompleted.isNotEmpty()) add(KEY_RECENT)
    }
    val anyExpanded = sectionKeys.any { collapsed[it] != true }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 顶部：一键折叠 / 展开
        item(key = "global-toggle") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    if (anyExpanded) sectionKeys.forEach { collapsed[it] = true }
                    else collapsed.clear()
                }) {
                    Text(if (anyExpanded) stringResource(R.string.task_collapse_all) else stringResource(R.string.task_expand_all))
                }
            }
        }

        if (overdue.isNotEmpty()) {
            item(key = "header-$KEY_OVERDUE") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.task_overdue),
                    count = overdue.size,
                    collapsed = collapsed[KEY_OVERDUE] == true,
                    onToggle = { collapsed[KEY_OVERDUE] = collapsed[KEY_OVERDUE] != true }
                )
            }
            if (collapsed[KEY_OVERDUE] != true) {
                items(overdue, key = { "overdue-${it.dateStr}-${it.task.id}" }) { occ ->
                    TaskItem(
                        task = occ.task,
                        colorHex = taskTypes.find { it.name == occ.task.type }?.colorHex ?: "#2196F3",
                        tags = taskTagsByTask[occ.task.id].orEmpty(),
                        occurrenceDate = occ.date,
                        onToggleComplete = { onToggleComplete(occ.task, occ.date) },
                        onDelete = { onDelete(occ.task) },
                        onEdit = { onEdit(occ.task) }
                    )
                }
            }
        }

        if (withoutDate.isNotEmpty()) {
            item(key = "header-$KEY_NO_DATE") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.task_no_date_section),
                    count = withoutDate.size,
                    collapsed = collapsed[KEY_NO_DATE] == true,
                    onToggle = { collapsed[KEY_NO_DATE] = collapsed[KEY_NO_DATE] != true }
                )
            }
            if (collapsed[KEY_NO_DATE] != true) {
                items(withoutDate, key = { "nodate-${it.task.id}" }) { occ ->
                    TaskItem(
                        task = occ.task,
                        colorHex = taskTypes.find { it.name == occ.task.type }?.colorHex ?: "#2196F3",
                        tags = taskTagsByTask[occ.task.id].orEmpty(),
                        occurrenceDate = null,
                        onToggleComplete = { onToggleComplete(occ.task, null) },
                        onDelete = { onDelete(occ.task) },
                        onEdit = { onEdit(occ.task) }
                    )
                }
            }
        }

        dateGroups.forEach { (dateStr, dayOccurrences) ->
            item(key = "header-$dateStr") {
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull()
                CollapsibleSectionHeader(
                    title = date?.let { dateSectionLabel(it, today, longFmt) } ?: dateStr,
                    count = dayOccurrences.size,
                    collapsed = collapsed[dateStr] == true,
                    onToggle = { collapsed[dateStr] = collapsed[dateStr] != true }
                )
            }
            if (collapsed[dateStr] != true) {
                items(dayOccurrences, key = { "d-$dateStr-${it.task.id}" }) { occ ->
                    TaskItem(
                        task = occ.task,
                        colorHex = taskTypes.find { it.name == occ.task.type }?.colorHex ?: "#2196F3",
                        tags = taskTagsByTask[occ.task.id].orEmpty(),
                        occurrenceDate = occ.date,
                        onToggleComplete = { onToggleComplete(occ.task, occ.date) },
                        onDelete = { onDelete(occ.task) },
                        onEdit = { onEdit(occ.task) }
                    )
                }
            }
        }

        if (recentCompleted.isNotEmpty()) {
            item(key = "header-$KEY_RECENT") {
                CollapsibleSectionHeader(
                    title = stringResource(R.string.task_recent_done),
                    count = recentCompleted.size,
                    collapsed = collapsed[KEY_RECENT] == true,
                    onToggle = { collapsed[KEY_RECENT] = collapsed[KEY_RECENT] != true }
                )
            }
            if (collapsed[KEY_RECENT] != true) {
                items(recentCompleted, key = { "recent-${it.dateStr}-${it.task.id}" }) { occ ->
                    TaskItem(
                        task = occ.task,
                        colorHex = taskTypes.find { it.name == occ.task.type }?.colorHex ?: "#2196F3",
                        tags = taskTagsByTask[occ.task.id].orEmpty(),
                        occurrenceDate = occ.date,
                        onToggleComplete = { onToggleComplete(occ.task, occ.date) },
                        onDelete = { onDelete(occ.task) },
                        onEdit = { onEdit(occ.task) }
                    )
                }
            }
        }

        if (sectionKeys.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.tasks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

private const val KEY_OVERDUE = "__overdue__"
private const val KEY_NO_DATE = "__no_date__"
private const val KEY_RECENT = "__recent_done__"

/** 日期分组标题：今天/明天加前缀，其余显示长日期 + 星期。 */
@Composable
private fun dateSectionLabel(date: LocalDate, today: LocalDate, longFmt: DateTimeFormatter): String {
    val base = date.format(longFmt)
    val weekday = stringResource(when (date.dayOfWeek.value) {
        1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed
        4 -> R.string.day_thu; 5 -> R.string.day_fri; 6 -> R.string.day_sat
        else -> R.string.day_sun
    })
    return when (date) {
        today -> stringResource(R.string.task_section_today, base)
        today.plusDays(1) -> stringResource(R.string.task_section_tomorrow, base, weekday)
        else -> stringResource(R.string.task_section_normal, base, weekday)
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = if (collapsed) stringResource(R.string.cd_expand) else stringResource(R.string.cd_collapse),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskItem(
    task: Task,
    colorHex: String,
    occurrenceDate: LocalDate?,
    onToggleComplete: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (Task) -> Unit,
    tags: List<TaskTag> = emptyList()
) {
    val isDone = if (occurrenceDate != null) task.isCompletedOn(occurrenceDate) else task.completed
    val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrElse { Color(0xFF2196F3) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onToggleComplete() },
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isDone, onCheckedChange = { onToggleComplete() })
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null
                )
                if (task.description.isNotBlank()) {
                    Text(text = task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 用 FlowRow 承载类型 / 标签 / 时间，标签多时可自动换行
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Text(text = "[${task.type}]", style = MaterialTheme.typography.labelSmall, color = color)
                    tags.forEach { tag ->
                        val tagColor = parseTagColor(tag.colorHex)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(tagColor)
                            )
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = tagColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (task.startTime != null) {
                        Text(text = "${task.startTime}${if (task.endTime != null) " - ${task.endTime}" else ""}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (task.isRecurring) {
                        Icon(
                            Icons.Filled.Repeat,
                            contentDescription = stringResource(R.string.cd_repeat_icon),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = { onEdit(task) }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun CalendarView(
    tasks: List<Task>,
    taskTypes: List<TaskTypeEntity>,
    taskTagsByTask: Map<Long, List<TaskTag>>,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onToggleComplete: (Task, LocalDate) -> Unit,
    onDelete: (Task) -> Unit,
    onEdit: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    // 重复任务按整月展开；无日期任务不出现在日历中
    val monthOccurrences = remember(tasks, currentMonth) {
        tasks.expandOccurrences(currentMonth.atDay(1), currentMonth.atEndOfMonth())
    }
    val tasksByDate = monthOccurrences.groupBy { it.dateStr!! }
    val longFmt = rememberLongDateFormatter()
    val monthFmtPattern = stringResource(R.string.task_month_year_fmt)
    val monthFmt = remember(monthFmtPattern) { DateTimeFormatter.ofPattern(monthFmtPattern) }
    val weekHeaders = listOf(
        R.string.day_sun, R.string.day_mon, R.string.day_tue, R.string.day_wed,
        R.string.day_thu, R.string.day_fri, R.string.day_sat
    ).map { stringResource(it) }
    val emptyTasksText = stringResource(R.string.tasks_empty)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.task_month_prev_cd))
            }
            Text(text = currentMonth.format(monthFmt), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.task_month_next_cd))
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            weekHeaders.forEach {
                Text(text = it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }

        val firstDay = currentMonth.atDay(1)
        val daysInMonth = currentMonth.lengthOfMonth()
        val startOffset = (firstDay.dayOfWeek.value % 7)

        var day = 1
        for (week in 0..5) {
            if (day > daysInMonth) break
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dow in 0..6) {
                    val cellIndex = week * 7 + dow
                    if (cellIndex < startOffset || day > daysInMonth) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = currentMonth.atDay(day)
                        val dateStr = date.toString()
                        val dayOccurrences = tasksByDate[dateStr]
                        val hasTasks = !dayOccurrences.isNullOrEmpty()
                        val isSelected = date == selectedDate

                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onDateSelected(date) }.padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    hasTasks -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                    Text(
                                        text = day.toString(),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (hasTasks) {
                                        Text(
                                            text = "${dayOccurrences!!.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                        day++
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = selectedDate.format(longFmt),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val dayOccurrences = tasks.expandOccurrences(selectedDate, selectedDate)
        if (dayOccurrences.isEmpty()) {
            Text(text = emptyTasksText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(dayOccurrences, key = { "cal-${it.dateStr}-${it.task.id}" }) { occ ->
                    TaskItem(
                        task = occ.task,
                        colorHex = taskTypes.find { it.name == occ.task.type }?.colorHex ?: "#2196F3",
                        tags = taskTagsByTask[occ.task.id].orEmpty(),
                        occurrenceDate = occ.date,
                        onToggleComplete = { onToggleComplete(occ.task, occ.date!!) },
                        onDelete = { onDelete(occ.task) },
                        onEdit = onEdit
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 新增 / 编辑任务（含重复规则）
// ----------------------------------------------------------------------

/** 重复结束方式（编辑态）。 */
private enum class RepeatEndMode { NEVER, ON_DATE, COUNT }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddTaskDialog(
    taskTypes: List<TaskTypeEntity>,
    taskTags: List<TaskTag>,
    initialTagIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Task, List<Long>) -> Unit,
    onCreateTag: suspend (String) -> Long?,
    initialTask: Task? = null
) {
    val defaultType = stringResource(R.string.task_default_type)
    val longFmt = rememberLongDateFormatter()
    val tagScope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initialTask?.title ?: "") }
    var description by remember { mutableStateOf(initialTask?.description ?: "") }
    var selectedType by remember { mutableStateOf(initialTask?.type ?: taskTypes.firstOrNull()?.name ?: defaultType) }

    // 多标签选择（与 type 单分类互不影响）；新建标签后自动勾上
    var selectedTagIds by remember { mutableStateOf(initialTagIds) }
    // 屏幕级 refs 首帧可能比弹窗晚：用户尚未手动操作过时，等待初始集合到达后同步一次
    var tagsTouched by remember { mutableStateOf(false) }
    LaunchedEffect(initialTagIds) {
        if (!tagsTouched && initialTagIds.isNotEmpty()) selectedTagIds = initialTagIds
    }
    var creatingTag by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    var tagDuplicate by remember { mutableStateOf(false) }

    val initialRule = initialTask?.parsedRecurrence()
    var selectedDate by remember {
        mutableStateOf(initialTask?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }
    var repeatKind by remember { mutableStateOf(initialRule?.kind ?: TaskRecurrence.Kind.NONE) }
    var weekdays by remember {
        mutableStateOf(
            initialRule?.weekdays?.takeIf { it.isNotEmpty() }
                ?: selectedDate?.dayOfWeek?.value?.let { setOf(it) }
                ?: setOf(1)
        )
    }
    var intervalDays by remember { mutableIntStateOf(initialRule?.interval ?: 2) }
    var customDates by remember { mutableStateOf(initialRule?.dates ?: emptySet()) }
    var endMode by remember {
        mutableStateOf(
            when {
                initialRule?.maxCount != null -> RepeatEndMode.COUNT
                initialRule?.endDate != null -> RepeatEndMode.ON_DATE
                else -> RepeatEndMode.NEVER
            }
        )
    }
    var ruleEndDate by remember { mutableStateOf(initialRule?.endDate) }
    var maxCountValue by remember { mutableIntStateOf(initialRule?.maxCount ?: 10) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showOccurrencePicker by remember { mutableStateOf(false) }

    // 用容错解析而非 substring：startTime 可能不足 5 个字符（"8:0"、空串等），
    // 裸 substring 会抛 StringIndexOutOfBoundsException
    val initialStart = ReminderCalculator.parseTime(initialTask?.startTime)
    val initialEnd = ReminderCalculator.parseTime(initialTask?.endTime)
    var startHour by remember { mutableIntStateOf(initialStart?.hour ?: 8) }
    var startMinute by remember { mutableIntStateOf(initialStart?.minute ?: 0) }
    var endHour by remember { mutableIntStateOf(initialEnd?.hour ?: 9) }
    var endMinute by remember { mutableIntStateOf(initialEnd?.minute ?: 40) }
    var hasTime by remember { mutableStateOf(initialTask?.startTime != null) }
    var enableReminder by remember { mutableStateOf(initialTask?.reminderMinutes != null) }
    var reminderMinutes by remember { mutableIntStateOf(initialTask?.reminderMinutes ?: 15) }

    // 注意：DatePicker 的 selectedDateMillis 以 UTC 零点归一（回读端按 UTC 算 epochDay），
    // 初值也必须用 UTC，东八区下用系统时区会让选中日整体错位一天。
    val startDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            ?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: System.currentTimeMillis()
    )
    val endDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = ruleEndDate
            ?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: System.currentTimeMillis()
    )
    val occurrenceDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    // DATES 模式以日期集合为准；其它重复模式必须有锚点日期
    val canHaveDate = repeatKind == TaskRecurrence.Kind.DATES || selectedDate != null
    val datesValid = repeatKind != TaskRecurrence.Kind.DATES || customDates.isNotEmpty()
    // 锚点型重复（DAILY/WEEKLY/…）缺锚点时规则永不发生，禁止入库形成「死任务」；
    // NONE 允许无日期的随手任务，DATES 由 datesValid 保证至少一个日期。
    val anchorValid = repeatKind == TaskRecurrence.Kind.NONE ||
        repeatKind == TaskRecurrence.Kind.DATES || selectedDate != null
    val canConfirm = title.isNotBlank() && datesValid && anchorValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTask == null) stringResource(R.string.task_add_title) else stringResource(R.string.task_edit_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.task_title_required)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.task_description)) }, modifier = Modifier.fillMaxWidth())

                Text(stringResource(R.string.task_type), style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    taskTypes.forEach { tt ->
                        FilterChip(
                            selected = selectedType == tt.name,
                            onClick = { selectedType = tt.name },
                            label = { Text(tt.name) }
                        )
                    }
                }

                // 标签：多选 chips（色点取自 colorHex）+ 行内新建；重复任务的标签
                // 绑定在任务实体上，对所有发生日共享
                Text(stringResource(R.string.task_tag_section_title), style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    taskTags.forEach { t ->
                        FilterChip(
                            selected = t.id in selectedTagIds,
                            onClick = {
                                tagsTouched = true
                                selectedTagIds = if (t.id in selectedTagIds) {
                                    selectedTagIds - t.id
                                } else {
                                    selectedTagIds + t.id
                                }
                            },
                            label = { Text(t.name) },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(parseTagColor(t.colorHex))
                                )
                            }
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = {
                            creatingTag = !creatingTag
                            tagDuplicate = false
                        },
                        label = { Text(stringResource(R.string.task_tag_add)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.task_tag_new_cd),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                if (creatingTag) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTagName,
                            onValueChange = {
                                newTagName = it
                                tagDuplicate = false
                            },
                            singleLine = true,
                            isError = tagDuplicate,
                            label = { Text(stringResource(R.string.task_tag_name_hint)) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            enabled = newTagName.isNotBlank(),
                            onClick = {
                                val name = newTagName.trim()
                                if (name.isEmpty()) return@IconButton
                                tagScope.launch {
                                    val newId = onCreateTag(name)
                                    if (newId != null) {
                                        tagsTouched = true
                                        selectedTagIds = selectedTagIds + newId
                                        newTagName = ""
                                        tagDuplicate = false
                                        creatingTag = false
                                    } else {
                                        tagDuplicate = true
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_confirm))
                        }
                    }
                    if (tagDuplicate) {
                        Text(
                            stringResource(R.string.task_tag_exists),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // 重复方式
                Text(stringResource(R.string.task_repeat), style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    data class RepeatOption(val kind: TaskRecurrence.Kind, val label: String)
                    listOf(
                        RepeatOption(TaskRecurrence.Kind.NONE, stringResource(R.string.repeat_none)),
                        RepeatOption(TaskRecurrence.Kind.DAILY, stringResource(R.string.repeat_daily)),
                        RepeatOption(TaskRecurrence.Kind.WEEKLY, stringResource(R.string.repeat_weekly)),
                        RepeatOption(TaskRecurrence.Kind.MONTHLY, stringResource(R.string.repeat_monthly)),
                        RepeatOption(TaskRecurrence.Kind.YEARLY, stringResource(R.string.repeat_yearly)),
                        RepeatOption(TaskRecurrence.Kind.INTERVAL, stringResource(R.string.repeat_interval)),
                        RepeatOption(TaskRecurrence.Kind.LEGAL_WORKDAY, stringResource(R.string.repeat_legal_workday)),
                        RepeatOption(TaskRecurrence.Kind.EBBINGHAUS, stringResource(R.string.repeat_ebbinghaus)),
                        RepeatOption(TaskRecurrence.Kind.DATES, stringResource(R.string.repeat_dates))
                    ).forEach { option ->
                        FilterChip(
                            selected = repeatKind == option.kind,
                            onClick = {
                                repeatKind = option.kind
                                // 选择锚点型重复时若未选日期，自动补今天
                                if (option.kind != TaskRecurrence.Kind.NONE &&
                                    option.kind != TaskRecurrence.Kind.DATES &&
                                    selectedDate == null
                                ) {
                                    selectedDate = LocalDate.now()
                                }
                            },
                            label = { Text(option.label) }
                        )
                    }
                }

                if (repeatKind == TaskRecurrence.Kind.DATES) {
                    // 指定日期：已选日期 chips（点击移除）+ 添加按钮
                    Text(stringResource(R.string.repeat_dates_label), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        customDates.sorted().forEach { d ->
                            FilterChip(
                                selected = true,
                                onClick = { customDates = customDates - d },
                                label = { Text(d.format(longFmt)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.task_remove_occurrence_date_cd),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { showOccurrencePicker = true },
                            label = { Text(stringResource(R.string.task_add_occurrence_date)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                    if (customDates.isEmpty()) {
                        Text(
                            stringResource(R.string.repeat_dates_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    // 开始（锚点）日期：不重复时可不选；选了重复方式后必填
                    OutlinedTextField(
                        value = selectedDate?.format(longFmt) ?: stringResource(R.string.task_no_date),
                        onValueChange = { },
                        readOnly = true,
                        label = {
                            Text(
                                if (repeatKind == TaskRecurrence.Kind.NONE) stringResource(R.string.task_date)
                                else stringResource(R.string.task_anchor_date)
                            )
                        },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showStartDatePicker = true }) {
                                    Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.task_pick_date_cd))
                                }
                                if (selectedDate != null && repeatKind == TaskRecurrence.Kind.NONE) {
                                    IconButton(onClick = { selectedDate = null }) {
                                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.task_clear_date_cd))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 自选星期
                if (repeatKind == TaskRecurrence.Kind.WEEKLY) {
                    Text(stringResource(R.string.repeat_weekdays_label), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            1 to R.string.day_mon, 2 to R.string.day_tue, 3 to R.string.day_wed,
                            4 to R.string.day_thu, 5 to R.string.day_fri, 6 to R.string.day_sat,
                            7 to R.string.day_sun
                        ).forEach { (dow, labelRes) ->
                            FilterChip(
                                selected = dow in weekdays,
                                onClick = {
                                    weekdays = if (dow in weekdays) weekdays - dow else weekdays + dow
                                },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }
                }

                // 间隔 N 天
                if (repeatKind == TaskRecurrence.Kind.INTERVAL) {
                    NumberStepper(
                        text = stringResource(R.string.repeat_every_n_days, intervalDays),
                        onDecrease = { intervalDays = (intervalDays - 1).coerceAtLeast(1) },
                        onIncrease = { intervalDays = (intervalDays + 1).coerceAtMost(365) },
                        decreaseEnabled = intervalDays > 1
                    )
                }

                // 结束条件（DATES 本身有限，不显示）
                if (repeatKind != TaskRecurrence.Kind.NONE &&
                    repeatKind != TaskRecurrence.Kind.DATES &&
                    repeatKind != TaskRecurrence.Kind.EBBINGHAUS
                ) {
                    Text(stringResource(R.string.repeat_end_label), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = endMode == RepeatEndMode.NEVER,
                            onClick = { endMode = RepeatEndMode.NEVER },
                            label = { Text(stringResource(R.string.repeat_end_never)) }
                        )
                        FilterChip(
                            selected = endMode == RepeatEndMode.ON_DATE,
                            onClick = {
                                endMode = RepeatEndMode.ON_DATE
                                if (ruleEndDate == null) ruleEndDate = selectedDate?.plusDays(30) ?: LocalDate.now().plusDays(30)
                            },
                            label = {
                                Text(
                                    ruleEndDate?.let { stringResource(R.string.repeat_end_on) + " " + it.format(DATE_FMT) }
                                        ?: stringResource(R.string.repeat_end_on)
                                )
                            }
                        )
                        FilterChip(
                            selected = endMode == RepeatEndMode.COUNT,
                            onClick = { endMode = RepeatEndMode.COUNT },
                            label = { Text(stringResource(R.string.repeat_end_count, maxCountValue)) }
                        )
                    }
                    if (endMode == RepeatEndMode.ON_DATE) {
                        OutlinedButton(onClick = { showEndDatePicker = true }) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(ruleEndDate?.format(longFmt) ?: stringResource(R.string.repeat_end_on))
                        }
                    }
                    if (endMode == RepeatEndMode.COUNT) {
                        NumberStepper(
                            text = stringResource(R.string.repeat_end_count, maxCountValue),
                            onDecrease = { maxCountValue = (maxCountValue - 1).coerceAtLeast(1) },
                            onIncrease = { maxCountValue = (maxCountValue + 1).coerceAtMost(365) },
                            decreaseEnabled = maxCountValue > 1
                        )
                    }
                }

                // 时间滑动选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.task_set_time), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Switch(checked = hasTime, onCheckedChange = { hasTime = it })
                }
                if (hasTime) {
                    Text(stringResource(R.string.task_start_time, String.format("%02d:%02d", startHour, startMinute)), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = startHour.toFloat(),
                        onValueChange = { startHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 23
                    )
                    Slider(
                        value = startMinute.toFloat(),
                        onValueChange = { startMinute = (it / 5).toInt() * 5 },
                        valueRange = 0f..55f,
                        steps = 10
                    )
                    Text(stringResource(R.string.task_end_time, String.format("%02d:%02d", endHour, endMinute)), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = endHour.toFloat(),
                        onValueChange = { endHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 23
                    )
                    Slider(
                        value = endMinute.toFloat(),
                        onValueChange = { endMinute = (it / 5).toInt() * 5 },
                        valueRange = 0f..55f,
                        steps = 10
                    )
                }

                // 提醒开关（必须先有发生日期；未设置具体时刻时按当天 09:00 提前提醒）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.task_reminder_switch), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = enableReminder,
                        enabled = canHaveDate,
                        onCheckedChange = { enableReminder = it }
                    )
                }
                if (!canHaveDate) {
                    Text(
                        stringResource(R.string.task_reminder_need_date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (enableReminder) {
                    Text(stringResource(R.string.task_reminder_before, reminderMinutes), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = reminderMinutes.toFloat(),
                        onValueChange = { reminderMinutes = it.toInt() },
                        valueRange = 5f..60f,
                        steps = 10
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!canConfirm) return@TextButton
                    val startTime = if (hasTime) String.format("%02d:%02d", startHour, startMinute) else null
                    val endTime = if (hasTime) String.format("%02d:%02d", endHour, endMinute) else null
                    val endDate = if (endMode == RepeatEndMode.ON_DATE) ruleEndDate else null
                    val maxCount = if (endMode == RepeatEndMode.COUNT) maxCountValue else null
                    val rule: TaskRecurrence? = when (repeatKind) {
                        TaskRecurrence.Kind.NONE -> null
                        TaskRecurrence.Kind.DATES -> TaskRecurrence(
                            kind = TaskRecurrence.Kind.DATES,
                            dates = customDates
                        )
                        TaskRecurrence.Kind.WEEKLY -> TaskRecurrence(
                            kind = TaskRecurrence.Kind.WEEKLY,
                            // 未选星期时兜底为开始日当天的星期，避免规则永不发生
                            weekdays = weekdays.ifEmpty {
                                selectedDate?.dayOfWeek?.value?.let { setOf(it) } ?: setOf(1)
                            },
                            endDate = endDate,
                            maxCount = maxCount
                        )
                        TaskRecurrence.Kind.INTERVAL -> TaskRecurrence(
                            kind = TaskRecurrence.Kind.INTERVAL,
                            interval = intervalDays,
                            endDate = endDate,
                            maxCount = maxCount
                        )
                        TaskRecurrence.Kind.EBBINGHAUS ->
                            // 艾宾浩斯为固定复习节奏，忽略自定义结束条件
                            TaskRecurrence(kind = TaskRecurrence.Kind.EBBINGHAUS)
                        else -> TaskRecurrence(
                            kind = repeatKind,
                            endDate = endDate,
                            maxCount = maxCount
                        )
                    }
                    val dateToSave = if (repeatKind == TaskRecurrence.Kind.DATES) {
                        customDates.minOrNull()?.toString()
                    } else {
                        selectedDate?.toString()
                    }
                    onConfirm(
                        (initialTask ?: Task(title = title)).copy(
                            title = title,
                            description = description,
                            type = selectedType,
                            date = dateToSave,
                            startTime = startTime,
                            endTime = endTime,
                            reminderMinutes = if (enableReminder && canHaveDate) reminderMinutes else null,
                            ruleJson = rule?.toJson()
                        ),
                        selectedTagIds.toList()
                    )
                },
                enabled = canConfirm
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )

    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startDatePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = LocalDate.ofEpochDay(millis / (24 * 60 * 60 * 1000))
                    }
                    showStartDatePicker = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = startDatePickerState)
        }
    }

    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endDatePickerState.selectedDateMillis?.let { millis ->
                        ruleEndDate = LocalDate.ofEpochDay(millis / (24 * 60 * 60 * 1000))
                    }
                    showEndDatePicker = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = endDatePickerState)
        }
    }

    if (showOccurrencePicker) {
        DatePickerDialog(
            onDismissRequest = { showOccurrencePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    occurrenceDatePickerState.selectedDateMillis?.let { millis ->
                        val picked = LocalDate.ofEpochDay(millis / (24 * 60 * 60 * 1000))
                        customDates = customDates + picked
                    }
                    showOccurrencePicker = false
                }) {
                    Text(stringResource(R.string.task_add_occurrence_date))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOccurrencePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            DatePicker(state = occurrenceDatePickerState)
        }
    }
}

/** 数字步进行：文本 + 「−」「＋」。 */
@Composable
private fun NumberStepper(
    text: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrease, enabled = decreaseEnabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = null)
        }
        IconButton(onClick = onIncrease, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskTypeManagerDialog(
    taskTypes: List<TaskTypeEntity>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf("#2196F3") }
    val presetColors = listOf("#F44336", "#E91E63", "#9C27B0", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#795548", "#607D8B")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_type_manage_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                taskTypes.forEach { tt ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(tt.colorHex)))
                        )
                        Text(text = tt.name, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { onDelete(tt.name) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.task_type_add_new), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.task_type_name)) }, modifier = Modifier.fillMaxWidth())
                // 预设色板 + RGB 滑块 + 自定义 Hex
                ColorPickerSection(
                    colorHex = newColor,
                    onColorChange = { newColor = it },
                    presetColors = presetColors
                )
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onAdd(newName, newColor)
                            newName = ""
                        }
                    },
                    enabled = newName.isNotBlank() &&
                        runCatching { android.graphics.Color.parseColor(newColor) }.isSuccess,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}
