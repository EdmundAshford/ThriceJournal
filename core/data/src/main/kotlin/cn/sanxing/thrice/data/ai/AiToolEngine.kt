package cn.sanxing.thrice.data.ai

import cn.sanxing.thrice.data.data.local.BillCategoryDao
import cn.sanxing.thrice.data.data.local.BillDao
import cn.sanxing.thrice.data.data.local.FocusSessionDao
import cn.sanxing.thrice.data.data.local.FocusTagDao
import cn.sanxing.thrice.data.data.local.NoteDao
import cn.sanxing.thrice.data.data.local.NoteTagDao
import cn.sanxing.thrice.data.data.local.SectionTimeDao
import cn.sanxing.thrice.data.data.local.SleepRecordDao
import cn.sanxing.thrice.data.data.local.TaskDao
import cn.sanxing.thrice.data.data.local.TaskTagDao
import cn.sanxing.thrice.data.data.repository.CourseRepository
import cn.sanxing.thrice.data.data.repository.NotesRepository
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import cn.sanxing.thrice.data.data.repository.TermRepository
import cn.sanxing.thrice.data.domain.model.Bill
import cn.sanxing.thrice.data.domain.model.BillCategory
import cn.sanxing.thrice.data.domain.model.BillType
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.FocusSession
import cn.sanxing.thrice.data.domain.model.FocusStatus
import cn.sanxing.thrice.data.domain.model.NoteTag
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.SleepKind
import cn.sanxing.thrice.data.domain.model.SleepRecord
import cn.sanxing.thrice.data.domain.model.Task
import cn.sanxing.thrice.data.domain.model.TaskTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * AI 本地数据工具引擎：按 [AiPermissions] 授权矩阵暴露 7 个数据域的读 / 写工具，
 * 负责参数解析、执行前权限复检、数据库操作与紧凑 JSON 结果（写操作附中文回执）。
 *
 * 所有依赖均为构造注入：DAO / Repository 已由 DataModule 提供，无需改动 DI 模块。
 */
@Singleton
class AiToolEngine @Inject constructor(
    private val courseRepository: CourseRepository,
    private val termRepository: TermRepository,
    private val sectionTimeDao: SectionTimeDao,
    private val taskDao: TaskDao,
    private val billDao: BillDao,
    private val billCategoryDao: BillCategoryDao,
    private val focusSessionDao: FocusSessionDao,
    private val focusTagDao: FocusTagDao,
    private val sleepRecordDao: SleepRecordDao,
    private val noteDao: NoteDao,
    private val noteTagDao: NoteTagDao,
    private val taskTagDao: TaskTagDao,
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
    private val permissionRepository: AiPermissionRepository
) {

    /** 参数校验类错误：message 为可直接回灌给模型的中文原因。 */
    private class ToolArgException(message: String) : Exception(message)

    /** 一个工具的完整注册项：元信息 + 所属域 + 读写属性 + 执行体。 */
    private class ToolDef(
        val name: String,
        val domain: AiPermDomain,
        val write: Boolean,
        val descriptor: AiToolDescriptor,
        val handler: suspend (JsonObject) -> String
    )

    // ================= 公共 API =================

    /** 按授权矩阵返回当前可调用的工具描述（未授权的工具不暴露给模型）。 */
    fun availableTools(perms: AiPermissions): List<AiToolDescriptor> =
        registry.filter { perms.isAllowed(it.domain, it.write) }.map { it.descriptor }

    /**
     * 执行一个工具调用。
     * @param argumentsJson 模型给出的参数 JSON 字符串（通常为 object）
     * @return 紧凑 JSON；失败统一为 {"ok":false,"error":"中文原因"}
     */
    suspend fun execute(toolName: String, argumentsJson: String): String {
        val def = registry.firstOrNull { it.name == toolName }
            ?: return errorJson("未知工具：$toolName")

        val args = try {
            parseArgs(argumentsJson)
        } catch (e: ToolArgException) {
            return errorJson(e.message ?: "参数错误")
        }

        // 执行前以最新授权复检（写操作同样在此拦截，不只依赖会话开始时的快照）
        val allowed = try {
            permissionRepository.isAllowed(def.domain, def.write)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return errorJson("权限校验失败，请稍后再试")
        }
        if (!allowed) {
            val action = if (def.write) "修改" else "读取"
            return errorJson("尚未获得${def.domain.label}数据的${action}授权，该操作未执行")
        }

        return try {
            def.handler(args)
        } catch (e: CancellationException) {
            // 必须原样抛出：`runCatching` 会把取消信号当成普通失败吞掉，
            // 于是用户取消 / 离开页面后工具循环仍继续执行，并把伪造的失败回执喂回模型。
            throw e
        } catch (e: ToolArgException) {
            errorJson(e.message ?: "参数错误")
        } catch (_: Exception) {
            // 不要把 e.message 回传给模型：它可能包含表名、列名、SQL 片段或文件路径，
            // 而这些内容会随请求体发往第三方服务商并落进 ai_messages。
            errorJson("操作本地数据失败，请稍后再试")
        }
    }

    // ================= 工具注册 =================

    private val registry: List<ToolDef> = listOf(
        // ---- 课程表（读） ----
        ToolDef("query_courses", AiPermDomain.COURSES, false,
            AiToolDescriptor(
                name = "query_courses",
                description = "查询当前课表方案中的课程。不传 date 时默认查询本周课程（this_week=true）；" +
                    "传 date（yyyy-MM-dd）查询指定当天的课程（按学期周次与星期匹配，含调课补课）；" +
                    "this_week=false 且不传 date 时返回学期全部课程概要。返回课程名、地点、节次时间、教师、星期。",
                parameters = schema {
                    pStr("date", "日期，格式 yyyy-MM-dd；不传则按本周 / 全学期查询")
                    pBool("this_week", "不传 date 时是否查本周，默认 true；false 返回学期全部课程概要")
                }
            )
        ) { queryCourses(it) },

        // ---- 课程表（写） ----
        ToolDef("create_course", AiPermDomain.COURSES, true,
            AiToolDescriptor(
                name = "create_course",
                description = "在当前课表方案中创建一门固定时间的课程。day_of_week 为 1=周一…7=周日；" +
                    "start_section/end_section 为小节序号；weeks 为上课周次（不传时默认本周）。",
                parameters = schema(required = listOf("name", "day_of_week", "start_section", "end_section")) {
                    pStr("name", "课程名称，如「高等数学」")
                    pStr("teacher", "授课教师")
                    pStr("location", "上课地点 / 教室")
                    pInt("day_of_week", "星期几上课：1=周一，7=周日")
                    pInt("start_section", "开始小节序号，从 1 开始")
                    pInt("end_section", "结束小节序号（须不小于开始节次）")
                    pIntArray("weeks", "上课周次序号列表，如 [1,2,3,4]；不传默认本周")
                }
            )
        ) { createCourse(it) },
        ToolDef("update_course", AiPermDomain.COURSES, true,
            AiToolDescriptor(
                name = "update_course",
                description = "按 id 局部更新一门课程的字段，只修改传入的字段（常用于改上课地点或教师）。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "课程 id")
                    pStr("name", "新课程名称")
                    pStr("teacher", "新教师")
                    pStr("location", "新上课地点")
                    pInt("day_of_week", "新星期：1=周一，7=周日")
                    pInt("start_section", "新开始小节序号")
                    pInt("end_section", "新结束小节序号")
                    pIntArray("weeks", "新上课周次列表")
                }
            )
        ) { updateCourse(it) },
        ToolDef("delete_course", AiPermDomain.COURSES, true,
            AiToolDescriptor(
                name = "delete_course",
                description = "按 id 删除一门课程，不可恢复，谨慎使用。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的课程 id")
                }
            )
        ) { deleteCourse(it) },
        ToolDef("query_section_times", AiPermDomain.COURSES, false,
            AiToolDescriptor(
                name = "query_section_times",
                description = "查询当前课表方案的学期信息与每小节上下课时间（节次作息表），" +
                    "课程的 start_section/end_section 引用这里的小节序号。",
                parameters = schema()
            )
        ) { querySectionTimes(it) },
        ToolDef("update_section_times", AiPermDomain.COURSES, true,
            AiToolDescriptor(
                name = "update_section_times",
                description = "整体替换当前课表方案的小节作息时间：按传入顺序作为第 1..N 小节，" +
                    "原有小节表全部覆盖（小节数量可增可减，1..20 节）。时间为 24 小时制 HH:mm，" +
                    "每节下课时间必须晚于上课时间。",
                parameters = schema(required = listOf("sections")) {
                    putJsonObject("sections") {
                        put("description", "小节数组，按顺序即第 1、2、3… 小节")
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                        }
                    }
                    pStr("sections[].start_time", "上课时间 HH:mm，如 08:00")
                    pStr("sections[].end_time", "下课时间 HH:mm，如 08:45")
                }
            )
        ) { updateSectionTimes(it) },
        ToolDef("update_active_term", AiPermDomain.COURSES, true,
            AiToolDescriptor(
                name = "update_active_term",
                description = "更新当前课表方案（学期）的名称、开学日期或总周数，只修改传入的字段。",
                parameters = schema {
                    pStr("name", "方案 / 学期名称")
                    pStr("start_date", "开学日期（周一），格式 yyyy-MM-dd")
                    pInt("total_weeks", "总教学周数，1..40")
                }
            )
        ) { updateActiveTerm(it) },

        // ---- 任务（读） ----
        ToolDef("list_tasks", AiPermDomain.TASKS, false,
            AiToolDescriptor(
                name = "list_tasks",
                description = "查询任务清单。可按日期过滤（date），可选择是否包含已完成任务" +
                    "（include_completed，默认 true）。返回标题、备注、日期与时间、完成状态等。",
                parameters = schema {
                    pStr("date", "仅查询该日期的任务，格式 yyyy-MM-dd；不传则返回全部日期与无日期任务")
                    pBool("include_completed", "是否包含已完成任务，默认 true；false 只看待办")
                }
            )
        ) { listTasks(it) },
        ToolDef("list_task_tags", AiPermDomain.TASKS, false,
            AiToolDescriptor(
                name = "list_task_tags",
                description = "查询全部任务标签（任务支持多标签），返回标签 id 与名称，" +
                    "创建 / 更新任务时用 tag_ids 挂载。",
                parameters = schema()
            )
        ) { listTaskTags(it) },

        // ---- 任务（写） ----
        ToolDef("create_task", AiPermDomain.TASKS, true,
            AiToolDescriptor(
                name = "create_task",
                description = "新建一条任务（待办）。可设置日期、起止时间、提前提醒分钟数与标签。",
                parameters = schema(required = listOf("title")) {
                    pStr("title", "任务标题")
                    pStr("description", "备注 / 详情")
                    pStr("type", "任务分类名，默认「其他」")
                    pStr("date", "任务日期，格式 yyyy-MM-dd；不传表示无指定日期")
                    pStr("start_time", "开始时间 HH:mm")
                    pStr("end_time", "截止 / 结束时间 HH:mm")
                    pInt("reminder_minutes", "提前提醒分钟数；不传表示不提醒")
                    pIntArray("tag_ids", "任务标签 id 列表（多标签），id 见 list_task_tags")
                }
            )
        ) { createTask(it) },
        ToolDef("update_task", AiPermDomain.TASKS, true,
            AiToolDescriptor(
                name = "update_task",
                description = "按 id 局部更新任务：标题、备注、日期、起止时间、完成状态、提醒分钟、标签。" +
                    "date / start_time / end_time 传空字符串可清空；tag_ids 为整体替换。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "任务 id")
                    pStr("title", "新标题")
                    pStr("description", "新备注")
                    pStr("type", "新分类名")
                    pStr("date", "新日期 yyyy-MM-dd；空字符串清空日期")
                    pStr("start_time", "新开始时间 HH:mm；空字符串清空")
                    pStr("end_time", "新结束时间 HH:mm；空字符串清空")
                    pBool("completed", "完成状态：true 已完成，false 未完成")
                    pInt("reminder_minutes", "提前提醒分钟数；空值清空提醒")
                    pIntArray("tag_ids", "新的标签 id 列表（整体替换原标签）；传 [] 清空全部标签")
                }
            )
        ) { updateTask(it) },
        ToolDef("create_task_tag", AiPermDomain.TASKS, true,
            AiToolDescriptor(
                name = "create_task_tag",
                description = "新建一个任务标签（重名会失败），返回标签 id，可用于 create_task / update_task。",
                parameters = schema(required = listOf("name")) {
                    pStr("name", "标签名称")
                    pStr("color_hex", "标签颜色 #RRGGBB，不传使用默认色")
                }
            )
        ) { createTaskTag(it) },
        ToolDef("delete_task", AiPermDomain.TASKS, true,
            AiToolDescriptor(
                name = "delete_task",
                description = "按 id 删除一条任务，不可恢复。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的任务 id")
                }
            )
        ) { deleteTask(it) },

        // ---- 账单（读） ----
        ToolDef("list_bills", AiPermDomain.BILLS, false,
            AiToolDescriptor(
                name = "list_bills",
                description = "查询账单流水，可按日期闭区间（from/to，yyyy-MM-dd）与收支类型过滤，" +
                    "默认最近 100 条；同时返回区间内支出 / 收入合计。",
                parameters = schema {
                    pStr("from", "起始日期 yyyy-MM-dd（含）")
                    pStr("to", "截止日期 yyyy-MM-dd（含）")
                    pStr("type", "收支类型：expense=支出，income=收入；不传返回全部", listOf("expense", "income"))
                    pInt("limit", "最多返回条数，默认 100")
                }
            )
        ) { listBills(it) },
        ToolDef("list_bill_categories", AiPermDomain.BILLS, false,
            AiToolDescriptor(
                name = "list_bill_categories",
                description = "列出账单的支出 / 收入分类（含用户自定义分类）。",
                parameters = schema()
            )
        ) { listBillCategories(it) },

        // ---- 账单（写） ----
        ToolDef("create_bill_category", AiPermDomain.BILLS, true,
            AiToolDescriptor(
                name = "create_bill_category",
                description = "新建一个账单分类（支出或收入），同名分类会失败；创建后即可在 create_bill 中使用。",
                parameters = schema(required = listOf("name")) {
                    pStr("name", "分类名称，如「宠物」")
                    pStr("type", "expense=支出（默认），income=收入", listOf("expense", "income"))
                    pStr("color_hex", "分类颜色 #RRGGBB，不传使用默认色")
                }
            )
        ) { createBillCategory(it) },
        ToolDef("create_bill", AiPermDomain.BILLS, true,
            AiToolDescriptor(
                name = "create_bill",
                description = "记一笔账单：金额恒为正数，收支方向用 type（默认 expense 支出），" +
                    "category 必须是已有分类，date 不传默认今天。账单没有独立地点字段，location 会并入备注。",
                parameters = schema(required = listOf("amount", "category")) {
                    pNumber("amount", "金额，正数，如 35.5")
                    pStr("category", "分类名（须先存在，可用 list_bill_categories 查询）")
                    pStr("type", "expense=支出（默认），income=收入", listOf("expense", "income"))
                    pStr("date", "账单日期 yyyy-MM-dd，默认今天")
                    pStr("note", "备注")
                    pStr("location", "消费地点（实体无独立字段，将追加进备注）")
                }
            )
        ) { createBill(it) },
        ToolDef("delete_bill", AiPermDomain.BILLS, true,
            AiToolDescriptor(
                name = "delete_bill",
                description = "按 id 删除一笔账单，不可恢复。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的账单 id")
                }
            )
        ) { deleteBill(it) },

        // ---- 专注（读） ----
        ToolDef("list_focus_sessions", AiPermDomain.FOCUS, false,
            AiToolDescriptor(
                name = "list_focus_sessions",
                description = "查询专注会话记录，可指定日期闭区间（from/to，默认最近 30 天）。" +
                    "返回每次会话的标签名、时长、开始结束时间、状态，并汇总总时长 / 按标签与状态聚合。",
                parameters = schema {
                    pStr("from", "起始日期 yyyy-MM-dd（含），默认 30 天前")
                    pStr("to", "截止日期 yyyy-MM-dd（含），默认今天")
                }
            )
        ) { listFocusSessions(it) },
        ToolDef("list_focus_tags", AiPermDomain.FOCUS, false,
            AiToolDescriptor(
                name = "list_focus_tags",
                description = "列出专注标签（计时用的独立标签体系）。",
                parameters = schema()
            )
        ) { listFocusTags(it) },

        // ---- 专注（写） ----
        ToolDef("create_focus_tag", AiPermDomain.FOCUS, true,
            AiToolDescriptor(
                name = "create_focus_tag",
                description = "新建一个专注标签（重名会失败），计时开始时可选择该标签。",
                parameters = schema(required = listOf("name")) {
                    pStr("name", "标签名称")
                    pStr("color_hex", "标签颜色 #RRGGBB，不传使用默认色")
                }
            )
        ) { createFocusTag(it) },
        ToolDef("delete_focus_session", AiPermDomain.FOCUS, true,
            AiToolDescriptor(
                name = "delete_focus_session",
                description = "按 id 删除一条专注会话记录，不可恢复。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的专注会话 id")
                }
            )
        ) { deleteFocusSession(it) },

        // ---- 睡眠（读） ----
        ToolDef("list_sleep_records", AiPermDomain.SLEEP, false,
            AiToolDescriptor(
                name = "list_sleep_records",
                description = "查询睡眠记录，可指定日期闭区间（from/to，默认最近 30 天）。" +
                    "返回夜睡 / 午休的入睡、醒来时刻、时长，并按类型汇总次数与平均时长。",
                parameters = schema {
                    pStr("from", "起始日期 yyyy-MM-dd（含），默认 30 天前")
                    pStr("to", "截止日期 yyyy-MM-dd（含），默认今天")
                }
            )
        ) { listSleepRecords(it) },

        // ---- 睡眠（写） ----
        ToolDef("create_sleep_record", AiPermDomain.SLEEP, true,
            AiToolDescriptor(
                name = "create_sleep_record",
                description = "补记一条已完成的睡眠记录：type=night 夜睡 / nap 午休，minutes 为睡眠分钟数，" +
                    "date 默认今天，sleep_time 为入睡时刻（夜睡默认 23:30、午休默认 13:00）。",
                parameters = schema(required = listOf("minutes")) {
                    pStr("type", "night=夜睡（默认），nap=午休", listOf("night", "nap"))
                    pInt("minutes", "睡眠时长（分钟），正整数")
                    pStr("date", "入睡归属日期 yyyy-MM-dd，默认今天")
                    pStr("sleep_time", "入睡时刻 HH:mm；不传夜睡默认 23:30、午休默认 13:00")
                    pStr("note", "备注")
                }
            )
        ) { createSleepRecord(it) },
        ToolDef("delete_sleep_record", AiPermDomain.SLEEP, true,
            AiToolDescriptor(
                name = "delete_sleep_record",
                description = "按 id 删除一条睡眠记录，不可恢复。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的睡眠记录 id")
                }
            )
        ) { deleteSleepRecord(it) },

        // ---- 随身记（读） ----
        ToolDef("list_notes", AiPermDomain.NOTES, false,
            AiToolDescriptor(
                name = "list_notes",
                description = "列出笔记摘要（只返回标题与正文前 60 字摘录，不含全文；需要全文用 get_note）。" +
                    "可按 folder_id 过滤；返回置顶标记、所属文件夹与更新时间。",
                parameters = schema {
                    pLong("folder_id", "文件夹 id；不传返回全部笔记")
                }
            )
        ) { listNotes(it) },
        ToolDef("get_note", AiPermDomain.NOTES, false,
            AiToolDescriptor(
                name = "get_note",
                description = "按 id 读取一篇笔记的完整正文。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "笔记 id")
                }
            )
        ) { getNote(it) },
        ToolDef("list_folders", AiPermDomain.NOTES, false,
            AiToolDescriptor(
                name = "list_folders",
                description = "列出随身记文件夹及各文件夹下笔记数量（含「未分类」）。",
                parameters = schema()
            )
        ) { listFolders(it) },
        ToolDef("list_note_tags", AiPermDomain.NOTES, false,
            AiToolDescriptor(
                name = "list_note_tags",
                description = "列出全部笔记标签（笔记支持多标签，与文件夹体系并存），" +
                    "创建 / 更新笔记时用 tag_ids 挂载。",
                parameters = schema()
            )
        ) { listNoteTags(it) },

        // ---- 随身记（写） ----
        ToolDef("create_note", AiPermDomain.NOTES, true,
            AiToolDescriptor(
                name = "create_note",
                description = "新建一篇笔记，可指定标题、正文、所属文件夹、置顶与标签。",
                parameters = schema {
                    pStr("title", "标题")
                    pStr("content", "正文")
                    pLong("folder_id", "文件夹 id；不传为「未分类」")
                    pBool("pinned", "是否置顶，默认 false")
                    pIntArray("tag_ids", "笔记标签 id 列表（多标签），id 见 list_note_tags")
                }
            )
        ) { createNote(it) },
        ToolDef("update_note", AiPermDomain.NOTES, true,
            AiToolDescriptor(
                name = "update_note",
                description = "按 id 局部更新笔记的标题、正文、置顶或标签（移动文件夹用 move_note）；" +
                    "tag_ids 为整体替换。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "笔记 id")
                    pStr("title", "新标题")
                    pStr("content", "新正文（整段替换）")
                    pBool("pinned", "是否置顶")
                    pIntArray("tag_ids", "新的标签 id 列表（整体替换原标签）；传 [] 清空全部标签")
                }
            )
        ) { updateNote(it) },
        ToolDef("create_note_tag", AiPermDomain.NOTES, true,
            AiToolDescriptor(
                name = "create_note_tag",
                description = "新建一个笔记标签（重名会失败），返回标签 id，可用于 create_note / update_note。",
                parameters = schema(required = listOf("name")) {
                    pStr("name", "标签名称")
                    pStr("color_hex", "标签颜色 #RRGGBB，不传使用默认色")
                }
            )
        ) { createNoteTag(it) },
        ToolDef("move_note", AiPermDomain.NOTES, true,
            AiToolDescriptor(
                name = "move_note",
                description = "把笔记移动到指定文件夹；folder_id 传 null 表示移入「未分类」。",
                parameters = schema(required = listOf("id", "folder_id")) {
                    pLong("id", "笔记 id")
                    pLong("folder_id", "目标文件夹 id；null=未分类")
                }
            )
        ) { moveNote(it) },
        ToolDef("delete_note", AiPermDomain.NOTES, true,
            AiToolDescriptor(
                name = "delete_note",
                description = "按 id 删除一篇笔记，不可恢复。",
                parameters = schema(required = listOf("id")) {
                    pLong("id", "要删除的笔记 id")
                }
            )
        ) { deleteNote(it) },

        // ---- 设置（读） ----
        ToolDef("get_settings", AiPermDomain.SETTINGS, false,
            AiToolDescriptor(
                name = "get_settings",
                description = "读取用户偏好设置：界面语言、主题模式 / 主题色 / 自定义颜色、字体、" +
                    "动态取色、壁纸不透明度与主体浓度、壁纸平移缩放、背景动画、" +
                    "提醒开关与提前量、通知铃声振动、专注与睡眠（含午休时刻）相关设置。" +
                    "不包含任何 API Key、服务商配置与数据授权等敏感项。时刻类设置值为 0..1439 的分钟数。",
                parameters = schema()
            )
        ) { getSettings(it) },

        // ---- 设置（写） ----
        ToolDef("update_setting", AiPermDomain.SETTINGS, true,
            AiToolDescriptor(
                name = "update_setting",
                description = "更新一项用户偏好，key 必须取自白名单（见 get_settings 返回的 writable_keys），" +
                    "value 类型须与设置匹配（布尔 / 整数 / 字符串，时刻为分钟数）。" +
                    "授权设置域即可调整设置页中全部用户可调项（语言、主题色、壁纸浓度等），" +
                    "但 AI API Key、服务商配置、数据授权、系统权限开关与底部导航布局不可修改。" +
                    "自定义壁纸无法由 AI 指定图片：wallpaper_reset=true 仅可清除自定义壁纸恢复默认。",
                parameters = schema(required = listOf("key", "value")) {
                    pStr(
                        "key", "设置项键名，如 app_language / theme_mode / theme_color / custom_color / " +
                            "dynamic_color / app_font / custom_bg_alpha / ui_mask_alpha / wallpaper_reset / " +
                            "bg_offset_x / bg_offset_y / bg_scale / background_animation / " +
                            "reminder_enabled / reminder_minutes / notification_sound / notification_vibrate / " +
                            "focus_keep_screen_on / focus_leave_behavior / focus_countdown_min / " +
                            "focus_notify_on_finish / sleep_nap_enabled / sleep_goal_mode / " +
                            "sleep_night_goal_min / sleep_bed_time_minute / sleep_wake_time_minute / " +
                            "sleep_bed_reminder_enabled / sleep_bed_reminder_minute / " +
                            "sleep_nap_goal_mode / sleep_nap_goal_min / sleep_nap_start_minute / " +
                            "sleep_nap_end_minute / sleep_nap_reminder_enabled / sleep_nap_reminder_minute"
                    )
                    putJsonObject("value") {
                        put("description", "新值：布尔 / 整数 / 字符串，按设置项类型传")
                    }
                }
            )
        ) { updateSetting(it) }
    )

    // ================= 课程表 =================

    private suspend fun queryCourses(args: JsonObject): String {
        val term = termRepository.getActive()
            ?: return successJson {
                put("ok", true)
                put("message", "当前没有课表方案")
                putJsonArray("courses") {}
            }
        val sections = sectionTimeDao.getByTerm(term.id).associateBy { it.sectionIndex }
        val all = courseRepository.getByTerm(term.id)
        val today = LocalDate.now()

        return successJson {
            putJsonObject("term") {
                put("id", term.id)
                put("name", term.name)
                put("start_date", term.startDate.toString())
                put("total_weeks", term.totalWeeks)
                put("current_week", weekOf(term.startDate, today))
            }
            val dateStr = args.str("date")
            when {
                dateStr != null -> {
                    val date = parseDate(dateStr, "date")
                    val week = weekOf(term.startDate, date)
                    val dow = date.dayOfWeek.value
                    val items = all
                        .filter { courseOnDate(it, date, week, dow, term.totalWeeks) }
                        .sortedBy { it.startSection }
                    putJsonObject("query") {
                        put("mode", "date")
                        put("date", date.toString())
                        put("week", week)
                        put("day_of_week", dow)
                        put("weekday", weekday(dow))
                    }
                    putJsonArray("courses") {
                        items.forEach { add(courseElement(it, sections, includeWeeks = false)) }
                    }
                }
                args.bool("this_week") ?: true -> {
                    val week = weekOf(term.startDate, today).coerceIn(1, term.totalWeeks)
                    val monday = term.startDate.plusWeeks((week - 1).toLong())
                    putJsonObject("query") {
                        put("mode", "week")
                        put("week", week)
                        put("week_start", monday.toString())
                    }
                    putJsonArray("days") {
                        for (dow in 1..7) {
                            val date = monday.plusDays((dow - 1).toLong())
                            val items = all
                                .filter {
                                    it.kind == CourseKind.GRID &&
                                        it.overrideDate == null &&
                                        week in it.weeks &&
                                        it.dayOfWeek == dow
                                }
                                .sortedBy { it.startSection }
                            add(buildJsonObject {
                                put("date", date.toString())
                                put("weekday", weekday(dow))
                                putJsonArray("courses") {
                                    items.forEach { add(courseElement(it, sections, includeWeeks = false)) }
                                }
                            })
                        }
                    }
                }
                else -> {
                    putJsonObject("query") { put("mode", "term") }
                    val grid = all.filter { it.kind == CourseKind.GRID }
                        .sortedWith(compareBy<Course> { it.dayOfWeek }.thenBy { it.startSection })
                    val others = all.filter { it.kind != CourseKind.GRID }.sortedBy { it.name }
                    putJsonArray("courses") {
                        grid.forEach { add(courseElement(it, sections, includeWeeks = true)) }
                    }
                    putJsonArray("other_courses") {
                        others.forEach {
                            add(buildJsonObject {
                                put("id", it.id)
                                put("name", it.name)
                                put("teacher", it.teacher)
                                put("location", it.location)
                                if (it.note.isNotBlank()) put("note", it.note)
                            })
                        }
                    }
                }
            }
        }
    }

    private suspend fun createCourse(args: JsonObject): String {
        val term = termRepository.getActive()
            ?: throw ToolArgException("还没有课表方案，无法创建课程")
        val name = args.str("name") ?: throw ToolArgException("缺少课程名称 name")
        val dow = args.int("day_of_week")
            ?: throw ToolArgException("缺少上课星期 day_of_week（1=周一 … 7=周日）")
        if (dow !in 1..7) throw ToolArgException("day_of_week 必须在 1..7 之间")
        val start = args.int("start_section")
            ?: throw ToolArgException("缺少开始节次 start_section")
        val end = args.int("end_section")
            ?: throw ToolArgException("缺少结束节次 end_section")
        if (start < 1 || end < start) {
            throw ToolArgException("节次不合法：开始节次须 ≥1，结束节次不能早于开始节次")
        }
        val weeks = args.intList("weeks")?.distinct()?.filter { it in 1..term.totalWeeks }
            ?: listOf(
                weekOf(term.startDate, LocalDate.now()).takeIf { it in 1..term.totalWeeks } ?: 1
            )
        if (weeks.isEmpty()) {
            throw ToolArgException("weeks 周次必须落在 1..${term.totalWeeks} 之间")
        }

        val now = System.currentTimeMillis()
        val course = Course(
            termId = term.id,
            name = name,
            teacher = args.str("teacher").orEmpty(),
            location = args.str("location").orEmpty(),
            dayOfWeek = dow,
            startSection = start,
            endSection = end,
            weeks = weeks.sorted().toSet(),
            kind = CourseKind.GRID,
            createdAt = now,
            updatedAt = now
        )
        val id = courseRepository.insert(course)
        return successJson {
            put("ok", true)
            put("id", id)
            put(
                "message",
                "已创建课程：《$name》，${weekday(dow)}第$start-${end}节" +
                    "（第${formatWeekRanges(weeks)}周）" +
                    course.location.takeIf { it.isNotBlank() }?.let { "，地点 $it" }.orEmpty()
            )
        }
    }

    private suspend fun updateCourse(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少课程 id")
        val existing = courseRepository.get(id)
            ?: throw ToolArgException("课程不存在：id=$id")
        var course = existing
        // 取课程所属的学期（而非活动学期）：被更新的课可能属于非活动方案，
        // 用活动学期的 totalWeeks 去夹它的周次会误判。
        val term = termRepository.get(existing.termId)
            ?: throw ToolArgException("课程所属课表方案不存在：termId=${existing.termId}")

        args.str("name")?.let { course = course.copy(name = it) }
        args.str("teacher")?.let { course = course.copy(teacher = it) }
        args.str("location")?.let { course = course.copy(location = it) }

        val dow = args.int("day_of_week") ?: course.dayOfWeek
        if (dow !in 0..7) throw ToolArgException("day_of_week 必须在 1..7 之间（0 仅限无固定时间课程）")
        val start = args.int("start_section") ?: course.startSection
        val end = args.int("end_section") ?: course.endSection
        // 维持星期与节次的一致性：dow=0（无固定时间）必须节次为 0；
        // dow 1..7 必须给出 ≥1 的有效节次，防止混合出半残课程。
        if (dow == 0) {
            if (start != 0 || end != 0) {
                throw ToolArgException("无固定星期（day_of_week=0）的课程不能设置节次")
            }
        } else if (start < 1 || end < start) {
            throw ToolArgException("节次不合法：开始节次须 ≥1，结束节次不能早于开始节次")
        }
        course = course.copy(
            dayOfWeek = dow,
            startSection = start,
            endSection = end,
            updatedAt = System.currentTimeMillis()
        )

        args.intList("weeks")?.let { raw ->
            // 必须与 createCourse 一致地夹到 1..term.totalWeeks：
            // 只过滤 `>= 1` 的话，AI 可以写入 2147483647 这样的周次，
            // 课表的周次判定与 ICS 导出都会跟着错乱。
            val valid = raw.distinct().filter { it in 1..term.totalWeeks }.sorted()
            if (valid.isEmpty()) {
                throw ToolArgException("weeks 周次必须落在 1..${term.totalWeeks} 之间")
            }
            course = course.copy(weeks = valid.toSet())
        }

        courseRepository.update(course)
        val changed = buildList {
            if (args.str("name") != null) add("名称")
            if (args.str("teacher") != null) add("教师")
            if (args.str("location") != null) add("地点")
            if (args.containsKey("day_of_week") || args.containsKey("start_section") ||
                args.containsKey("end_section")
            ) add("时间")
            if (args.containsKey("weeks")) add("周次")
        }.joinToString("、").ifEmpty { "资料" }
        // 只回显本次**由 AI 提供**的字段值：回显 `course.name` 会把它（可能是既有、
        // 未被授权读取的）原样交给模型，等同于绕过读权限。
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已更新课程${args.str("name")?.let { "《$it》" } ?: " id=$id"}的$changed")
        }
    }

    private suspend fun deleteCourse(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少课程 id")
        val existing = courseRepository.get(id)
            ?: throw ToolArgException("课程不存在：id=$id")
        courseRepository.delete(existing)
        // 回执只回 id，不回显名称：用户可能只授予了「写」而没授予「读」，
        // 回显既有内容等于让 AI 靠 id 枚举出未授权读取的数据（且以销毁数据为代价）。
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已删除课程：id=$id")
        }
    }

    private suspend fun querySectionTimes(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val term = termRepository.getActive()
            ?: return successJson {
                put("ok", true)
                put("message", "当前没有课表方案")
                putJsonArray("sections") {}
            }
        val sections = sectionTimeDao.getByTerm(term.id)
        val today = LocalDate.now()
        return successJson {
            putJsonObject("term") {
                put("id", term.id)
                put("name", term.name)
                put("start_date", term.startDate.toString())
                put("total_weeks", term.totalWeeks)
                put("current_week", weekOf(term.startDate, today))
            }
            put("section_count", sections.size)
            putJsonArray("sections") {
                sections.forEach { s ->
                    add(buildJsonObject {
                        put("index", s.sectionIndex)
                        put("start_time", s.startTime)
                        put("end_time", s.endTime)
                    })
                }
            }
        }
    }

    private suspend fun updateSectionTimes(args: JsonObject): String {
        val term = termRepository.getActive()
            ?: throw ToolArgException("还没有课表方案，无法修改节次时间")
        val rawSections = args["sections"] as? JsonArray
            ?: throw ToolArgException("缺少 sections 小节数组")
        if (rawSections.isEmpty() || rawSections.size > 20) {
            throw ToolArgException("小节数量必须在 1..20 之间")
        }
        val parsed = rawSections.mapIndexed { i, element ->
            val obj = element as? JsonObject
                ?: throw ToolArgException("sections[$i] 必须是对象")
            val start = obj.str("start_time")
                ?.let { normalizeHhmm(it, "第${i + 1}节上课时间") }
                ?: throw ToolArgException("第${i + 1}节缺少 start_time（HH:mm）")
            val end = obj.str("end_time")
                ?.let { normalizeHhmm(it, "第${i + 1}节下课时间") }
                ?: throw ToolArgException("第${i + 1}节缺少 end_time（HH:mm）")
            if (hmMinutes(end) <= hmMinutes(start)) {
                throw ToolArgException("第${i + 1}节下课时间必须晚于上课时间")
            }
            SectionTime(termId = term.id, sectionIndex = i + 1, startTime = start, endTime = end)
        }

        // 单事务替换：分两步提交会在 insert 失败时把该学期节次清空且无法回滚
        sectionTimeDao.replaceForTerm(term.id, parsed)
        return successJson {
            put("ok", true)
            put("message", "已更新「${term.name}」的节次时间，共 ${parsed.size} 小节" +
                "（首节 ${parsed.first().startTime} 上课，末节 ${parsed.last().endTime} 下课）")
        }
    }

    private suspend fun updateActiveTerm(args: JsonObject): String {
        val term = termRepository.getActive()
            ?: throw ToolArgException("还没有课表方案，无法修改学期信息")
        var updated = term
        val changed = mutableListOf<String>()

        args.str("name")?.let {
            updated = updated.copy(name = it)
            changed += "名称"
        }
        args.str("start_date")?.let {
            updated = updated.copy(startDate = parseDate(it, "start_date"))
            changed += "开学日期"
        }
        args.int("total_weeks")?.let { weeks ->
            if (weeks !in 1..40) throw ToolArgException("total_weeks 必须在 1..40 之间")
            updated = updated.copy(totalWeeks = weeks)
            changed += "总周数"
        }
        if (changed.isEmpty()) {
            throw ToolArgException("至少传入一个要修改的字段：name / start_date / total_weeks")
        }
        termRepository.update(updated)
        return successJson {
            put("ok", true)
            put("message", "已更新当前课表方案的${changed.joinToString("、")}：${updated.name}" +
                "（${updated.startDate} 开学，共 ${updated.totalWeeks} 周）")
        }
    }

    // ================= 任务 =================

    private suspend fun listTasks(args: JsonObject): String {
        var tasks = taskDao.getAll()
        args.str("date")?.let { raw ->
            val date = parseDate(raw, "date").toString()
            tasks = tasks.filter { it.date == date }
        }
        if (args.bool("include_completed") == false) {
            tasks = tasks.filter { !it.completed }
        }
        tasks = tasks.sortedWith(
            compareBy<Task, String?>(nullsLast()) { it.date }
                .thenBy(nullsLast()) { it.startTime }
                .thenBy { it.id }
        )
        val tagsById = taskTagDao.getAll().associateBy { it.id }
        val tagNamesByTask = taskTagDao.getAllRefs()
            .groupBy({ it.taskId }, { tagsById[it.tagId]?.name })
        val shown = tasks.take(MAX_LIST_ROWS)
        return successJson {
            put("ok", true)
            put("count", tasks.size)
            put("returned_count", shown.size)
            put("truncated", tasks.size > shown.size)
            putJsonArray("tasks") {
                shown.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("title", t.title)
                        put("description", t.description)
                        put("type", t.type)
                        put("date", t.date)
                        put("start_time", t.startTime)
                        put("end_time", t.endTime)
                        put("completed", t.completed)
                        put("recurring", !t.ruleJson.isNullOrBlank())
                        put("reminder_minutes", t.reminderMinutes)
                        put("created_at", formatMillis(t.createdAt))
                        putJsonArray("tags") {
                            tagNamesByTask[t.id].orEmpty().filterNotNull().sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        }
                    })
                }
            }
        }
    }

    /** 校验并解析 tag_ids 参数：数组元素必须是存在的标签 id，返回去重后的标签实体。 */
    private suspend fun resolveTaskTagIds(args: JsonObject): List<TaskTag>? {
        if (!args.containsKey("tag_ids")) return null
        return resolveTagIds(
            raw = args["tag_ids"],
            listAll = { taskTagDao.getAll() },
            idOf = { it.id },
            label = "任务标签"
        )
    }

    private suspend fun createTask(args: JsonObject): String {
        val title = args.str("title") ?: throw ToolArgException("缺少任务标题 title")
        val date = args.str("date")?.let { parseDate(it, "date").toString() }
        val startTime = args.str("start_time")?.let { normalizeHhmm(it, "start_time") }
        val endTime = args.str("end_time")?.let { normalizeHhmm(it, "end_time") }
        if (startTime != null && endTime != null && endTime < startTime) {
            throw ToolArgException("结束时间不能早于开始时间")
        }
        val reminder = args.int("reminder_minutes")?.also {
            if (it < 0) throw ToolArgException("reminder_minutes 不能为负数")
        }
        val tags = resolveTaskTagIds(args)
        val task = Task(
            title = title,
            description = args.str("description").orEmpty(),
            type = args.str("type") ?: "其他",
            date = date,
            startTime = startTime,
            endTime = endTime,
            reminderMinutes = reminder
        )
        val id = taskDao.upsert(task)
        if (tags != null) taskTagDao.replaceTagsForTask(id, tags.map { it.id })
        val whenText = when {
            date != null && endTime != null -> "，截止 $date $endTime"
            date != null && startTime != null -> "，$date $startTime"
            date != null -> "，日期 $date"
            else -> "（无指定日期）"
        }
        val tagText = tags?.takeIf { it.isNotEmpty() }
            ?.let { "，标签：${it.joinToString("、") { t -> t.name }}" }
            .orEmpty()
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已创建任务：$title$whenText$tagText")
        }
    }

    private suspend fun updateTask(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少任务 id")
        val existing = taskDao.getById(id)
            ?: throw ToolArgException("任务不存在：id=$id")
        var task = existing

        args.str("title")?.let { task = task.copy(title = it) }
        args.str("description")?.let { task = task.copy(description = it) }
        args.str("type")?.let { task = task.copy(type = it) }
        args.bool("completed")?.let { task = task.copy(completed = it) }

        if (args.containsKey("date")) {
            task = task.copy(date = presentString(args, "date")?.let { parseDate(it, "date").toString() })
        }
        if (args.containsKey("start_time")) {
            task = task.copy(startTime = presentString(args, "start_time")?.let { normalizeHhmm(it, "start_time") })
        }
        if (args.containsKey("end_time")) {
            task = task.copy(endTime = presentString(args, "end_time")?.let { normalizeHhmm(it, "end_time") })
        }
        if (args.containsKey("reminder_minutes")) {
            // 键存在：null / 空值表示清空提醒；缺省（键不存在）则不修改
            val p = args["reminder_minutes"]
            val v = if (p == null || p is JsonNull) {
                null
            } else {
                (p as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw ToolArgException("reminder_minutes 必须是整数或 null")
            }
            if (v != null && v < 0) throw ToolArgException("reminder_minutes 不能为负数")
            task = task.copy(reminderMinutes = v)
        }
        val tags = resolveTaskTagIds(args)

        taskDao.upsert(task)
        if (tags != null) taskTagDao.replaceTagsForTask(id, tags.map { it.id })
        val tagText = tags?.let {
            if (it.isEmpty()) "，已清空标签"
            else "，标签：${it.joinToString("、") { t -> t.name }}"
        }.orEmpty()
        val doneText = if (args.bool("completed") == true) "，已标记完成" else ""
        return successJson {
            put("ok", true)
            put("id", id)
            // 见 updateCourse：只回显 AI 自己传入的标题，不回显库里既有的
            put("message", "已更新任务：${args.str("title") ?: "id=$id"}$doneText$tagText")
        }
    }

    private suspend fun listTaskTags(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val tags = taskTagDao.getAll()
        return successJson {
            put("ok", true)
            put("count", tags.size)
            putJsonArray("tags") {
                tags.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        put("color_hex", t.colorHex)
                        put("sort_order", t.sortOrder)
                    })
                }
            }
        }
    }

    private suspend fun createTaskTag(args: JsonObject): String {
        val name = args.str("name") ?: throw ToolArgException("缺少标签名称 name")
        if (taskTagDao.getAll().any { it.name == name }) {
            throw ToolArgException("任务标签已存在：$name")
        }
        val colorHex = args.str("color_hex")?.let { parseHexColor(it, "color_hex") } ?: "#7E57C2"
        val sortOrder = taskTagDao.getAll().size
        val id = taskTagDao.insert(TaskTag(name = name, colorHex = colorHex, sortOrder = sortOrder))
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已创建任务标签：$name")
        }
    }

    private suspend fun deleteTask(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少任务 id")
        taskDao.getById(id) ?: throw ToolArgException("任务不存在：id=$id")
        taskDao.deleteById(id)
        // 见 deleteCourse：回执不回显既有内容，避免只授「写」时绕过读权限
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已删除任务：id=$id")
        }
    }

    // ================= 账单 =================

    private suspend fun listBills(args: JsonObject): String {
        var bills = billDao.getAll()
        args.str("from")?.let { bills = bills.filter { b -> b.date >= parseDate(it, "from").toString() } }
        args.str("to")?.let { bills = bills.filter { b -> b.date <= parseDate(it, "to").toString() } }
        args.str("type")?.let { raw ->
            val type = parseBillType(raw)
                ?: throw ToolArgException("type 只能是 expense（支出）或 income（收入）")
            bills = bills.filter { it.type == type }
        }
        bills = bills.sortedWith(compareByDescending<Bill> { it.date }.thenByDescending { it.createdAt })

        // limit 必须夹到 1..MAX_LIST_ROWS：模型可以任意改写它
        val limit = args.int("limit")?.takeIf { it > 0 }?.coerceAtMost(MAX_LIST_ROWS) ?: 100
        val expenseTotal = bills.filter { it.type == BillType.EXPENSE }.sumOf { it.amount }
        val incomeTotal = bills.filter { it.type == BillType.INCOME }.sumOf { it.amount }

        return successJson {
            put("ok", true)
            put("total_count", bills.size)
            put("returned_count", minOf(limit, bills.size))
            put("truncated", bills.size > limit)
            put("expense_total", round2(expenseTotal))
            put("income_total", round2(incomeTotal))
            putJsonArray("bills") {
                bills.take(limit).forEach { b -> add(billElement(b)) }
            }
        }
    }

    private suspend fun listBillCategories(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val categories = billCategoryDao.getAll()
        return successJson {
            put("ok", true)
            putJsonArray("categories") {
                categories.sortedWith(
                    compareBy<BillCategory> { it.type.ordinal }.thenBy { it.sortOrder }.thenBy { it.name }
                ).forEach { c ->
                    add(buildJsonObject {
                        put("name", c.name)
                        put("type", if (c.type == BillType.EXPENSE) "expense" else "income")
                        put("color_hex", c.colorHex)
                        put("sort_order", c.sortOrder)
                    })
                }
            }
        }
    }

    private suspend fun createBillCategory(args: JsonObject): String {
        val name = args.str("name") ?: throw ToolArgException("缺少分类名称 name")
        val type = args.str("type")?.let {
            parseBillType(it) ?: throw ToolArgException("type 只能是 expense（支出）或 income（收入）")
        } ?: BillType.EXPENSE
        val existing = billCategoryDao.getAll()
        if (existing.any { it.name == name && it.type == type }) {
            val typeLabel = if (type == BillType.EXPENSE) "支出" else "收入"
            throw ToolArgException("$typeLabel 分类已存在：$name")
        }
        val colorHex = args.str("color_hex")?.let { parseHexColor(it, "color_hex") }
            ?: if (type == BillType.EXPENSE) "#FF7043" else "#43A047"
        val sortOrder = existing.count { it.type == type }
        billCategoryDao.upsert(
            BillCategory(name = name, type = type, colorHex = colorHex, sortOrder = sortOrder)
        )
        val typeLabel = if (type == BillType.EXPENSE) "支出" else "收入"
        return successJson {
            put("ok", true)
            put("message", "已创建$typeLabel 分类：$name")
        }
    }

    private suspend fun createBill(args: JsonObject): String {
        val amount = args.double("amount")
            ?: throw ToolArgException("缺少金额 amount")
        if (amount <= 0.0 || !amount.isFinite()) {
            throw ToolArgException("金额 amount 必须是大于 0 的数字")
        }
        val category = args.str("category") ?: throw ToolArgException("缺少分类 category")
        val type = args.str("type")?.let {
            parseBillType(it) ?: throw ToolArgException("type 只能是 expense（支出）或 income（收入）")
        } ?: BillType.EXPENSE

        val validNames = billCategoryDao.getAll().filter { it.type == type }.map { it.name }
        if (category !in validNames) {
            val hint = validNames.joinToString("、").ifBlank { "（暂无分类）" }
            val typeLabel = if (type == BillType.EXPENSE) "支出" else "收入"
            throw ToolArgException("${typeLabel}分类不存在：$category。可选分类：$hint")
        }

        val date = args.str("date")?.let { parseDate(it, "date") } ?: LocalDate.now()
        val noteParts = mutableListOf<String>()
        args.str("note")?.let { noteParts += it }
        // Bill 实体没有独立地点字段，地点并入备注，避免臆造字段
        args.str("location")?.let { noteParts += "地点：$it" }

        val bill = Bill(
            type = type,
            amount = round2(amount),
            category = category,
            date = date.toString(),
            note = noteParts.joinToString("；")
        )
        val id = billDao.upsert(bill)
        val typeLabel = if (type == BillType.EXPENSE) "支出" else "收入"
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已记一笔$typeLabel：$category ${trimAmount(round2(amount))} 元（${date}）")
        }
    }

    private suspend fun deleteBill(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少账单 id")
        billDao.getById(id) ?: throw ToolArgException("账单不存在：id=$id")
        billDao.deleteById(id)
        // 见 deleteCourse：回执不回显既有内容，避免只授「写」时绕过读权限
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已删除 1 条账单：id=$id")
        }
    }

    // ================= 专注 =================

    private suspend fun listFocusSessions(args: JsonObject): String {
        val today = LocalDate.now()
        val from = args.str("from")?.let { parseDate(it, "from") } ?: today.minusDays(29)
        val to = args.str("to")?.let { parseDate(it, "to") } ?: today
        if (to.isBefore(from)) throw ToolArgException("to 不能早于 from")

        val allSessions = focusSessionDao.getRange(dayStartMillis(from), dayEndMillis(to))
        // 统计用全量，**返回明细**才截断——模型可把范围改成十年，全量明细会撑爆请求体
        val sessions = allSessions.take(MAX_LIST_ROWS)
        val tags = focusTagDao.getAll().associateBy { it.id }
        val totalFocusedSeconds = allSessions.sumOf { it.focusedSeconds }

        return successJson {
            putJsonObject("range") {
                put("from", from.toString())
                put("to", to.toString())
            }
            put("session_count", allSessions.size)
            put("returned_count", sessions.size)
            put("truncated", allSessions.size > sessions.size)
            put("total_focused_minutes", (totalFocusedSeconds / 60.0).let { (it * 10).roundToLong() / 10.0 })
            putJsonObject("by_status") {
                FocusStatus.entries.forEach { status ->
                    val list = allSessions.filter { it.status == status.name }
                    putJsonObject(status.name.lowercase()) {
                        put("count", list.size)
                        put("focused_minutes", list.sumOf { it.focusedSeconds } / 60)
                    }
                }
            }
            putJsonArray("by_tag") {
                allSessions.groupBy { it.tagId }.forEach { (tagId, list) ->
                    add(buildJsonObject {
                        put("tag_id", tagId)
                        put("tag_name", tagId?.let { tags[it]?.name } ?: "未分类")
                        put("count", list.size)
                        put("focused_minutes", list.sumOf { it.focusedSeconds } / 60)
                    })
                }
            }
            putJsonArray("sessions") {
                sessions.forEach { s -> add(focusSessionElement(s, tags[s.tagId]?.name)) }
            }
        }
    }

    private suspend fun listFocusTags(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val tags = focusTagDao.getAll()
        return successJson {
            put("ok", true)
            put("count", tags.size)
            putJsonArray("tags") {
                tags.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        put("color_argb", t.colorArgb)
                        put("color_hex", argbToHex(t.colorArgb))
                        put("sort_order", t.sortOrder)
                    })
                }
            }
        }
    }

    private suspend fun createFocusTag(args: JsonObject): String {
        val name = args.str("name") ?: throw ToolArgException("缺少标签名称 name")
        if (focusTagDao.getAll().any { it.name == name }) {
            throw ToolArgException("专注标签已存在：$name")
        }
        val colorArgb = args.str("color_hex")?.let { parseColorArgb(it, "color_hex") }
            ?: 0xFF2E6DA4.toInt()
        val id = focusTagDao.upsert(
            cn.sanxing.thrice.data.domain.model.FocusTag(
                name = name,
                colorArgb = colorArgb,
                sortOrder = focusTagDao.maxSortOrder() + 1
            )
        )
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已创建专注标签：$name")
        }
    }

    private suspend fun deleteFocusSession(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少专注会话 id")
        val existing = focusSessionDao.getById(id)
            ?: throw ToolArgException("专注记录不存在：id=$id")
        focusSessionDao.deleteById(id)
        return successJson {
            put("ok", true)
            put(
                // 见 deleteCourse：回执不回显既有内容，避免只授「写」时绕过读权限
                "message",
                "已删除 1 条专注记录：id=$id"
            )
        }
    }

    // ================= 睡眠 =================

    private suspend fun listSleepRecords(args: JsonObject): String {
        val today = LocalDate.now()
        val from = args.str("from")?.let { parseDate(it, "from") } ?: today.minusDays(29)
        val to = args.str("to")?.let { parseDate(it, "to") } ?: today
        if (to.isBefore(from)) throw ToolArgException("to 不能早于 from")
        // 模型可以传 `0000-01-01` ~ `9999-12-31` 这类极端跨度，DAO 侧也没有 LIMIT，
        // 不夹一下就会把全部睡眠记录原样塞进请求体与上下文。
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ToolArgException("查询跨度不能超过 $MAX_RANGE_DAYS 天，请缩小 from / to 范围")
        }

        val all = sleepRecordDao.getRange(dayStartMillis(from), dayEndMillis(to))
        val records = all.take(MAX_LIST_ROWS)
        return successJson {
            putJsonObject("range") {
                put("from", from.toString())
                put("to", to.toString())
            }
            put("count", all.size)
            put("returned_count", records.size)
            put("truncated", all.size > records.size)
            putJsonArray("by_kind") {
                SleepKind.entries.forEach { kind ->
                    val list = records.filter { it.kind == kind.name }
                    val total = list.sumOf { it.minutes ?: 0 }
                    add(buildJsonObject {
                        put("type", kind.name.lowercase())
                        put("count", list.size)
                        put("total_minutes", total)
                        put("average_minutes", if (list.isEmpty()) 0 else total / list.size)
                    })
                }
            }
            putJsonArray("records") {
                records.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("type", if (r.kind == SleepKind.NIGHT.name) "night" else "nap")
                        put("sleep_at", formatMillis(r.sleepAtEpochMs))
                        put("wake_at", formatMillis(r.wakeAtEpochMs))
                        put("minutes", r.minutes)
                        put("note", r.note)
                    })
                }
            }
        }
    }

    private suspend fun createSleepRecord(args: JsonObject): String {
        val kind = args.str("type")?.let { raw ->
            when (raw.lowercase()) {
                "night", "夜睡" -> SleepKind.NIGHT
                "nap", "午休" -> SleepKind.NAP
                else -> throw ToolArgException("type 只能是 night（夜睡）或 nap（午休）")
            }
        } ?: SleepKind.NIGHT
        val minutes = args.int("minutes")
            ?: throw ToolArgException("缺少睡眠时长 minutes（分钟）")
        if (minutes <= 0) throw ToolArgException("minutes 必须是正整数")
        val date = args.str("date")?.let { parseDate(it, "date") } ?: LocalDate.now()
        val clock = args.str("sleep_time")?.let { normalizeHhmm(it, "sleep_time") }
            ?: if (kind == SleepKind.NIGHT) "23:30" else "13:00"

        val sleepAt = LocalDateTime.of(date, LocalTime.parse(clock))
            .atZone(ZONE).toInstant().toEpochMilli()
        val wakeAt = sleepAt + minutes * 60_000L
        val record = SleepRecord(
            kind = kind.name,
            sleepAtEpochMs = sleepAt,
            wakeAtEpochMs = wakeAt,
            minutes = minutes,
            note = args.str("note").orEmpty()
        )
        val id = sleepRecordDao.insert(record)
        val kindLabel = if (kind == SleepKind.NIGHT) "夜睡" else "午休"
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已补记$kindLabel：$date $clock 入睡，共 $minutes 分钟")
        }
    }

    private suspend fun deleteSleepRecord(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少睡眠记录 id")
        sleepRecordDao.getById(id) ?: throw ToolArgException("睡眠记录不存在：id=$id")
        sleepRecordDao.deleteById(id)
        // 见 deleteCourse：回执不回显既有内容，避免只授「写」时绕过读权限
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已删除 1 条睡眠记录：id=$id")
        }
    }

    // ================= 随身记 =================

    private suspend fun listNotes(args: JsonObject): String {
        var notes = noteDao.getAll()
        args.long("folder_id")?.let { folderId ->
            notes = notes.filter { it.folderId == folderId }
        }
        notes = notes.sortedWith(
            compareByDescending<cn.sanxing.thrice.data.domain.model.Note> { it.pinned }
                .thenBy { it.sortOrder }
                .thenByDescending { it.updatedAt }
        )
        val tagsById = noteTagDao.getAll().associateBy { it.id }
        val tagNamesByNote = noteTagDao.getAllRefs()
            .groupBy({ it.noteId }, { tagsById[it.tagId]?.name })
        val shown = notes.take(MAX_LIST_ROWS)
        return successJson {
            put("ok", true)
            put("count", notes.size)
            put("returned_count", shown.size)
            put("truncated", notes.size > shown.size)
            putJsonArray("notes") {
                shown.forEach { n ->
                    add(buildJsonObject {
                        put("id", n.id)
                        put("title", n.title.ifBlank { "未命名" })
                        put("excerpt", makeExcerpt(n.content))
                        put("pinned", n.pinned)
                        put("folder_id", n.folderId)
                        put("updated_at", formatMillis(n.updatedAt))
                        putJsonArray("tags") {
                            tagNamesByNote[n.id].orEmpty().filterNotNull().sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        }
                    })
                }
            }
        }
    }

    private suspend fun getNote(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少笔记 id")
        val note = notesRepository.getNote(id)
            ?: throw ToolArgException("笔记不存在：id=$id")
        val tags = noteTagDao.getAllRefs()
            .filter { it.noteId == id }
            .mapNotNull { noteTagDao.getById(it.tagId)?.name }
            .sorted()
        return successJson {
            put("ok", true)
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("pinned", note.pinned)
            put("folder_id", note.folderId)
            put("created_at", formatMillis(note.createdAt))
            put("updated_at", formatMillis(note.updatedAt))
            putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private suspend fun listFolders(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val folders = notesRepository.getFolders().sortedWith(compareBy { it.sortOrder })
        val notes = noteDao.getAll()
        return successJson {
            put("ok", true)
            putJsonArray("folders") {
                add(buildJsonObject {
                    put("id", JsonNull)
                    put("name", "未分类")
                    put("note_count", notes.count { it.folderId == null })
                })
                folders.forEach { f ->
                    add(buildJsonObject {
                        put("id", f.id)
                        put("name", f.name)
                        put("sort_order", f.sortOrder)
                        put("note_count", notes.count { it.folderId == f.id })
                    })
                }
            }
        }
    }

    private suspend fun createNote(args: JsonObject): String {
        val folderId = args.long("folder_id")?.also {
            notesRepository.getFolder(it) ?: throw ToolArgException("文件夹不存在：id=$it")
        }
        val title = args.str("title").orEmpty()
        val tags = resolveNoteTagIds(args)
        val id = notesRepository.createNote(
            folderId = folderId,
            title = title,
            content = args.str("content").orEmpty(),
            pinned = args.bool("pinned") ?: false
        )
        if (tags != null) noteTagDao.replaceTagsForNote(id, tags.map { it.id })
        val tagText = tags?.takeIf { it.isNotEmpty() }
            ?.let { "，标签：${it.joinToString("、") { t -> t.name }}" }
            .orEmpty()
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已新建笔记：${title.ifBlank { "未命名" }}$tagText")
        }
    }

    private suspend fun updateNote(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少笔记 id")
        val existing = notesRepository.getNote(id)
            ?: throw ToolArgException("笔记不存在：id=$id")
        var note = existing
        args.str("title")?.let { note = note.copy(title = it) }
        args.str("content")?.let { note = note.copy(content = it) }
        args.bool("pinned")?.let { note = note.copy(pinned = it) }
        val tags = resolveNoteTagIds(args)
        notesRepository.saveNote(note)
        if (tags != null) noteTagDao.replaceTagsForNote(id, tags.map { it.id })
        val tagText = tags?.let {
            if (it.isEmpty()) "，已清空标签"
            else "，标签：${it.joinToString("、") { t -> t.name }}"
        }.orEmpty()
        return successJson {
            put("ok", true)
            put("id", id)
            // 见 updateCourse：只回显 AI 自己传入的标题，不回显库里既有的
            put("message", "已更新笔记：${args.str("title") ?: "id=$id"}$tagText")
        }
    }

    /** 校验并解析笔记 tag_ids 参数：数组元素必须是存在的标签 id，返回去重后的标签实体。 */
    private suspend fun resolveNoteTagIds(args: JsonObject): List<NoteTag>? {
        if (!args.containsKey("tag_ids")) return null
        return resolveTagIds(
            raw = args["tag_ids"],
            listAll = { noteTagDao.getAll() },
            idOf = { it.id },
            label = "笔记标签"
        )
    }

    private suspend fun listNoteTags(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val tags = noteTagDao.getAll()
        return successJson {
            put("ok", true)
            put("count", tags.size)
            putJsonArray("tags") {
                tags.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        put("color_hex", argbToHex(t.colorArgb))
                        put("sort_order", t.sortOrder)
                    })
                }
            }
        }
    }

    private suspend fun createNoteTag(args: JsonObject): String {
        val name = args.str("name") ?: throw ToolArgException("缺少标签名称 name")
        if (noteTagDao.getAll().any { it.name == name }) {
            throw ToolArgException("笔记标签已存在：$name")
        }
        val colorArgb = args.str("color_hex")?.let { parseColorArgb(it, "color_hex") }
            ?: 0xFF7E57C2.toInt()
        val sortOrder = noteTagDao.getAll().size
        val id = noteTagDao.insert(NoteTag(name = name, colorArgb = colorArgb, sortOrder = sortOrder))
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已创建笔记标签：$name")
        }
    }

    private suspend fun moveNote(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少笔记 id")
        val existing = notesRepository.getNote(id)
            ?: throw ToolArgException("笔记不存在：id=$id")
        if (!args.containsKey("folder_id")) {
            throw ToolArgException("缺少 folder_id（传 null 表示移入「未分类」）")
        }
        val raw = args["folder_id"]
        val folderId = if (raw == null || raw is JsonNull) {
            null
        } else {
            val fid = (raw as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw ToolArgException("folder_id 必须是整数或 null")
            notesRepository.getFolder(fid) ?: throw ToolArgException("文件夹不存在：id=$fid")
            fid
        }
        notesRepository.moveNote(id, folderId)
        val targetName = folderId?.let { notesRepository.getFolder(it)?.name } ?: "未分类"
        return successJson {
            put("ok", true)
            put("message", "已把笔记《${existing.title.ifBlank { "未命名" }}》移动到「$targetName」")
        }
    }

    private suspend fun deleteNote(args: JsonObject): String {
        val id = args.long("id") ?: throw ToolArgException("缺少笔记 id")
        val existing = notesRepository.getNote(id)
            ?: throw ToolArgException("笔记不存在：id=$id")
        notesRepository.deleteNote(id)
        // 见 deleteCourse：回执不回显既有内容，避免只授「写」时绕过读权限
        return successJson {
            put("ok", true)
            put("id", id)
            put("message", "已删除笔记：id=$id")
        }
    }

    // ================= 设置 =================

    private suspend fun getSettings(@Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        // 先在 suspend 上下文读取，再进入非挂起的 JSON builder
        val appLanguage = settingsRepository.appLanguage.first() ?: "system"
        val themeMode = settingsRepository.themeMode.first()
        val themeColor = settingsRepository.themeColor.first()
        val customColor = settingsRepository.customColor.first()
        val dynamicColor = settingsRepository.dynamicColor.first()
        val appFont = settingsRepository.appFont.first()
        val customBgPath = settingsRepository.customBgPath.first()
        val customBgAlpha = settingsRepository.customBgAlpha.first()
        val uiMaskAlpha = settingsRepository.uiMaskAlpha.first()
        val bgOffsetX = settingsRepository.bgOffsetX.first()
        val bgOffsetY = settingsRepository.bgOffsetY.first()
        val bgScale = settingsRepository.bgScale.first()
        val backgroundAnimation = settingsRepository.backgroundAnimation.first()
        val reminderEnabled = settingsRepository.reminderEnabled.first()
        val reminderMinutes = settingsRepository.reminderMinutes.first()
        val notificationSound = settingsRepository.notificationSound.first()
        val notificationVibrate = settingsRepository.notificationVibrate.first()
        val focusKeepScreenOn = settingsRepository.focusKeepScreenOn.first()
        val focusLeaveBehavior = settingsRepository.focusLeaveBehavior.first()
        val focusCountdownMin = settingsRepository.focusCountdownMinutes.first()
        val focusNotifyOnFinish = settingsRepository.focusNotifyOnFinish.first()
        val sleepNapEnabled = settingsRepository.sleepNapEnabled.first()
        val sleepGoalMode = settingsRepository.sleepGoalMode.first()
        val sleepNightGoalMin = settingsRepository.sleepNightGoalMinutes.first()
        val sleepBedTimeMinute = settingsRepository.sleepBedTimeMinute.first()
        val sleepWakeTimeMinute = settingsRepository.sleepWakeTimeMinute.first()
        val sleepBedReminderEnabled = settingsRepository.sleepBedReminderEnabled.first()
        val sleepBedReminderMinute = settingsRepository.sleepBedReminderMinute.first()
        val sleepNapGoalMode = settingsRepository.sleepNapGoalMode.first()
        val sleepNapGoalMin = settingsRepository.sleepNapGoalMinutes.first()
        val sleepNapStartMinute = settingsRepository.sleepNapStartMinute.first()
        val sleepNapEndMinute = settingsRepository.sleepNapEndMinute.first()
        val sleepNapReminderEnabled = settingsRepository.sleepNapReminderEnabled.first()
        val sleepNapReminderMinute = settingsRepository.sleepNapReminderMinute.first()

        return successJson {
            put("ok", true)
            putJsonObject("settings") {
                put("app_language", appLanguage)
                put("theme_mode", themeMode)
                put("theme_color", themeColor)
                put("custom_color", customColor)
                put("dynamic_color", dynamicColor)
                put("app_font", appFont)
                put("custom_wallpaper_set", !customBgPath.isNullOrBlank())
                put("custom_bg_alpha", customBgAlpha)
                put("ui_mask_alpha", uiMaskAlpha)
                put("bg_offset_x", bgOffsetX)
                put("bg_offset_y", bgOffsetY)
                put("bg_scale", bgScale)
                put("background_animation", backgroundAnimation)
                put("reminder_enabled", reminderEnabled)
                put("reminder_minutes", reminderMinutes)
                put("notification_sound", notificationSound)
                put("notification_vibrate", notificationVibrate)
                put("focus_keep_screen_on", focusKeepScreenOn)
                put("focus_leave_behavior", focusLeaveBehavior)
                put("focus_countdown_min", focusCountdownMin)
                put("focus_notify_on_finish", focusNotifyOnFinish)
                put("sleep_nap_enabled", sleepNapEnabled)
                put("sleep_goal_mode", sleepGoalMode)
                put("sleep_night_goal_min", sleepNightGoalMin)
                put("sleep_bed_time_minute", sleepBedTimeMinute)
                put("sleep_wake_time_minute", sleepWakeTimeMinute)
                put("sleep_bed_reminder_enabled", sleepBedReminderEnabled)
                put("sleep_bed_reminder_minute", sleepBedReminderMinute)
                put("sleep_nap_goal_mode", sleepNapGoalMode)
                put("sleep_nap_goal_min", sleepNapGoalMin)
                put("sleep_nap_start_minute", sleepNapStartMinute)
                put("sleep_nap_end_minute", sleepNapEndMinute)
                put("sleep_nap_reminder_enabled", sleepNapReminderEnabled)
                put("sleep_nap_reminder_minute", sleepNapReminderMinute)
            }
            put("value_ranges", buildJsonObject {
                put("ui_mask_alpha", "15..100（整数，百分比；15 为最低浓度）")
                put("custom_bg_alpha", "0..100（整数，百分比）")
                put("bg_offset_x", "-1.0..1.0")
                put("bg_offset_y", "-1.0..1.0")
                put("bg_scale", "1.0..6.0")
                put("reminder_minutes", "1..120")
                put("sleep_nap_start_minute", "0..1439")
                put("sleep_nap_end_minute", "0..1439")
            })
            putJsonArray("writable_keys") {
                settingSpecs.forEach { add(JsonPrimitive(it.key)) }
            }
        }
    }

    private suspend fun updateSetting(args: JsonObject): String {
        val key = args.str("key") ?: throw ToolArgException("缺少设置项 key")
        if (key.startsWith("ai_") || key.startsWith("ai_perm_")) {
            throw ToolArgException("AI 不能修改 API Key、服务商配置或数据授权设置")
        }
        val spec = settingSpecs.firstOrNull { it.key == key }
            ?: throw ToolArgException("不支持修改的设置项：$key（仅允许修改用户偏好白名单中的项目）")
        val value = args["value"] ?: throw ToolArgException("缺少新值 value")
        spec.apply(value)
        return successJson {
            put("ok", true)
            put("key", key)
            put("message", "已更新设置：${spec.label} = ${displaySettingValue(key, value)}")
        }
    }

    /**
     * 设置写白名单：仅映射到 [SettingsRepository] 已有 setter 的用户偏好；
     * 不含 ai_*（API Key / 权限矩阵）、壁纸文件路径与底部导航布局等易误操作项。
     */
    private val settingSpecs: List<SettingSpec> = listOf(
        SettingSpec("app_language", "界面语言") { v ->
            // system = 清除选择、跟随系统语言
            val raw = asSettingString(v, "app_language").lowercase()
            val value = when (raw) {
                SettingsRepository.APP_LANGUAGE_ZH -> SettingsRepository.APP_LANGUAGE_ZH
                SettingsRepository.APP_LANGUAGE_EN -> SettingsRepository.APP_LANGUAGE_EN
                "system", "auto" -> null
                else -> throw ToolArgException("app_language 只能是 zh / en / system")
            }
            settingsRepository.setAppLanguage(value)
        },
        SettingSpec("theme_mode", "主题模式") { v ->
            val raw = asSettingString(v, "theme_mode")
            val normalized = when (raw.uppercase()) {
                SettingsRepository.THEME_MODE_SYSTEM -> SettingsRepository.THEME_MODE_SYSTEM
                SettingsRepository.THEME_MODE_LIGHT -> SettingsRepository.THEME_MODE_LIGHT
                SettingsRepository.THEME_MODE_DARK -> SettingsRepository.THEME_MODE_DARK
                else -> throw ToolArgException("theme_mode 只能是 SYSTEM / LIGHT / DARK")
            }
            settingsRepository.setThemeMode(normalized)
        },
        SettingSpec("theme_color", "主题颜色") { v ->
            val raw = asSettingString(v, "theme_color").uppercase()
            val allowed = setOf(
                SettingsRepository.THEME_COLOR_BLUE,
                SettingsRepository.THEME_COLOR_PINK,
                SettingsRepository.THEME_COLOR_MONO,
                SettingsRepository.THEME_COLOR_MINT,
                SettingsRepository.THEME_COLOR_GRAPE,
                SettingsRepository.THEME_COLOR_SUNSET,
                SettingsRepository.THEME_COLOR_TEAL,
                SettingsRepository.THEME_COLOR_CRIMSON,
                SettingsRepository.THEME_COLOR_INDIGO,
                SettingsRepository.THEME_COLOR_MOCHA,
                SettingsRepository.THEME_COLOR_CUSTOM
            )
            if (raw !in allowed) {
                throw ToolArgException(
                    "theme_color 只能是 BLUE / PINK / MONO / MINT / GRAPE / SUNSET / " +
                        "TEAL / CRIMSON / INDIGO / MOCHA / CUSTOM"
                )
            }
            settingsRepository.setThemeColor(raw)
        },
        SettingSpec("custom_color", "自定义主题色") { v ->
            val raw = parseHexColor(asSettingString(v, "custom_color"), "custom_color")
            settingsRepository.setCustomColor(raw)
            // 选择自定义颜色后自动把主题色切到 CUSTOM，与设置页行为一致
            settingsRepository.setThemeColor(SettingsRepository.THEME_COLOR_CUSTOM)
        },
        SettingSpec("dynamic_color", "动态取色") {
            settingsRepository.setDynamicColor(asSettingBool(it, "dynamic_color"))
        },
        SettingSpec("app_font", "应用字体") { v ->
            val raw = asSettingString(v, "app_font")
            if (!FONT_KEY_REGEX.matches(raw)) {
                throw ToolArgException("app_font 必须是两位数字字体编号（如 00）")
            }
            settingsRepository.setAppFont(raw)
        },
        SettingSpec("custom_bg_alpha", "壁纸不透明度") {
            val value = asSettingInt(it, "custom_bg_alpha")
            if (value !in 0..100) throw ToolArgException("custom_bg_alpha 必须在 0..100 之间")
            settingsRepository.setCustomBgAlpha(value)
        },
        SettingSpec("ui_mask_alpha", "壁纸下主体浓度") {
            // 最低为 15：拒绝 0 等越界值，避免模型谎报“已调到 0”
            val value = asSettingInt(it, "ui_mask_alpha")
            if (value !in 15..100) {
                throw ToolArgException("ui_mask_alpha 必须在 15..100 之间（15 为最低浓度）")
            }
            settingsRepository.setUiMaskAlpha(value)
        },
        SettingSpec("wallpaper_reset", "恢复默认壁纸") {
            if (!asSettingBool(it, "wallpaper_reset")) {
                throw ToolArgException("wallpaper_reset 传 true 才会清除自定义壁纸")
            }
            // AI 无法提供本地图片文件，仅支持清除自定义壁纸、恢复系统默认壁纸
            settingsRepository.setCustomBgPath(null)
        },
        SettingSpec("bg_offset_x", "壁纸水平平移") {
            updateBgTransform(
                x = (it as? JsonPrimitive)?.content?.toFloatOrNull()
                    ?.also { v -> if (v !in -1f..1f) throw ToolArgException("bg_offset_x 必须在 -1..1 之间") }
                    ?: throw ToolArgException("bg_offset_x 必须是 -1..1 的数字")
            )
        },
        SettingSpec("bg_offset_y", "壁纸垂直平移") {
            updateBgTransform(
                y = (it as? JsonPrimitive)?.content?.toFloatOrNull()
                    ?.also { v -> if (v !in -1f..1f) throw ToolArgException("bg_offset_y 必须在 -1..1 之间") }
                    ?: throw ToolArgException("bg_offset_y 必须是 -1..1 的数字")
            )
        },
        SettingSpec("bg_scale", "壁纸缩放") {
            updateBgTransform(
                scale = (it as? JsonPrimitive)?.content?.toFloatOrNull()
                    ?.also { v -> if (v !in 1f..6f) throw ToolArgException("bg_scale 必须在 1..6 之间") }
                    ?: throw ToolArgException("bg_scale 必须是 1..6 的数字")
            )
        },
        SettingSpec("background_animation", "背景动画") {
            settingsRepository.setBackgroundAnimation(asSettingBool(it, "background_animation"))
        },
        SettingSpec("reminder_enabled", "提醒总开关") {
            settingsRepository.setReminderEnabled(asSettingBool(it, "reminder_enabled"))
        },
        SettingSpec("reminder_minutes", "提前提醒分钟数") {
            // 合法区间 1..120 分钟：越界值收敛到边界，避免写入无效提醒
            settingsRepository.setReminderMinutes(
                asSettingInt(it, "reminder_minutes").coerceIn(1, 120)
            )
        },
        SettingSpec("notification_sound", "通知铃声") {
            settingsRepository.setNotificationSound(asSettingBool(it, "notification_sound"))
        },
        SettingSpec("notification_vibrate", "通知振动") {
            settingsRepository.setNotificationVibrate(asSettingBool(it, "notification_vibrate"))
        },
        SettingSpec("focus_keep_screen_on", "专注时屏幕常亮") {
            settingsRepository.setFocusKeepScreenOn(asSettingBool(it, "focus_keep_screen_on"))
        },
        SettingSpec("focus_leave_behavior", "专注离开处理") { v ->
            val raw = asSettingString(v, "focus_leave_behavior").uppercase()
            if (raw !in setOf(
                    SettingsRepository.FOCUS_LEAVE_PAUSE,
                    SettingsRepository.FOCUS_LEAVE_FAIL,
                    SettingsRepository.FOCUS_LEAVE_KEEP
                )
            ) {
                throw ToolArgException("focus_leave_behavior 只能是 PAUSE / FAIL / KEEP")
            }
            settingsRepository.setFocusLeaveBehavior(raw)
        },
        SettingSpec("focus_countdown_min", "默认专注倒计时分钟") {
            settingsRepository.setFocusCountdownMinutes(asSettingInt(it, "focus_countdown_min"))
        },
        SettingSpec("focus_notify_on_finish", "专注结束通知") {
            settingsRepository.setFocusNotifyOnFinish(asSettingBool(it, "focus_notify_on_finish"))
        },
        SettingSpec("sleep_nap_enabled", "午休记录开关") {
            settingsRepository.setSleepNapEnabled(asSettingBool(it, "sleep_nap_enabled"))
        },
        SettingSpec("sleep_goal_mode", "睡眠目标模式") { v ->
            val raw = asSettingString(v, "sleep_goal_mode").uppercase()
            if (raw != SettingsRepository.SLEEP_GOAL_MODE_DURATION &&
                raw != SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE
            ) {
                throw ToolArgException("sleep_goal_mode 只能是 DURATION / BED_WAKE")
            }
            settingsRepository.setSleepGoalMode(raw)
        },
        SettingSpec("sleep_night_goal_min", "夜睡目标分钟") {
            settingsRepository.setSleepNightGoalMinutes(asSettingInt(it, "sleep_night_goal_min"))
        },
        SettingSpec("sleep_bed_time_minute", "目标入睡时刻") {
            settingsRepository.setSleepBedTimeMinute(asSettingInt(it, "sleep_bed_time_minute"))
        },
        SettingSpec("sleep_wake_time_minute", "目标起床时刻") {
            settingsRepository.setSleepWakeTimeMinute(asSettingInt(it, "sleep_wake_time_minute"))
        },
        SettingSpec("sleep_bed_reminder_enabled", "入睡提醒开关") {
            settingsRepository.setSleepBedReminderEnabled(asSettingBool(it, "sleep_bed_reminder_enabled"))
        },
        SettingSpec("sleep_bed_reminder_minute", "入睡提醒时刻") {
            settingsRepository.setSleepBedReminderMinute(asSettingInt(it, "sleep_bed_reminder_minute"))
        },
        SettingSpec("sleep_nap_goal_min", "午休目标分钟") {
            settingsRepository.setSleepNapGoalMinutes(asSettingInt(it, "sleep_nap_goal_min"))
        },
        SettingSpec("sleep_nap_goal_mode", "午休目标模式") { v ->
            val raw = asSettingString(v, "sleep_nap_goal_mode").uppercase()
            if (raw != SettingsRepository.SLEEP_GOAL_MODE_DURATION &&
                raw != SettingsRepository.SLEEP_GOAL_MODE_BED_WAKE
            ) {
                throw ToolArgException("sleep_nap_goal_mode 只能是 DURATION / BED_WAKE")
            }
            settingsRepository.setSleepNapGoalMode(raw)
        },
        SettingSpec("sleep_nap_start_minute", "午休开始时刻") {
            val value = asSettingInt(it, "sleep_nap_start_minute")
            if (value !in 0..1439) throw ToolArgException("sleep_nap_start_minute 必须在 0..1439 之间")
            settingsRepository.setSleepNapStartMinute(value)
        },
        SettingSpec("sleep_nap_end_minute", "午休结束时刻") {
            val value = asSettingInt(it, "sleep_nap_end_minute")
            if (value !in 0..1439) throw ToolArgException("sleep_nap_end_minute 必须在 0..1439 之间")
            settingsRepository.setSleepNapEndMinute(value)
        },
        SettingSpec("sleep_nap_reminder_enabled", "午休提醒开关") {
            settingsRepository.setSleepNapReminderEnabled(asSettingBool(it, "sleep_nap_reminder_enabled"))
        },
        SettingSpec("sleep_nap_reminder_minute", "午休提醒时刻") {
            settingsRepository.setSleepNapReminderMinute(asSettingInt(it, "sleep_nap_reminder_minute"))
        }
    )

    /** 壁纸平移 / 缩放的单项更新：未提供的维度沿用当前值（setter 一次写三项）。 */
    private suspend fun updateBgTransform(x: Float? = null, y: Float? = null, scale: Float? = null) {
        settingsRepository.setBgTransform(
            offsetX = x ?: settingsRepository.bgOffsetX.first(),
            offsetY = y ?: settingsRepository.bgOffsetY.first(),
            scale = scale ?: settingsRepository.bgScale.first()
        )
    }

    private class SettingSpec(
        val key: String,
        val label: String,
        val apply: suspend (JsonElement) -> Unit
    )

    private fun asSettingBool(element: JsonElement, key: String): Boolean =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            ?: throw ToolArgException("$key 的值必须是布尔值 true / false")

    private fun asSettingInt(element: JsonElement, key: String): Int =
        (element as? JsonPrimitive)?.content?.toIntOrNull()
            ?: throw ToolArgException("$key 的值必须是整数")

    private fun asSettingString(element: JsonElement, key: String): String {
        val p = element as? JsonPrimitive
            ?: throw ToolArgException("$key 的值必须是字符串")
        return p.content.trim().ifEmpty { throw ToolArgException("$key 不能为空") }
    }

    private fun displaySettingValue(key: String, value: JsonElement): String {
        if (key.endsWith("_enabled") || key in setOf(
                "dynamic_color", "background_animation", "notification_sound",
                "notification_vibrate", "wallpaper_reset"
            )
        ) {
            return (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?.let { if (it) "开" else "关" } ?: value.toString()
        }
        if (key == "app_language") {
            return when ((value as? JsonPrimitive)?.content?.lowercase()) {
                "zh" -> "中文"
                "en" -> "English"
                else -> "跟随系统"
            }
        }
        if (key.endsWith("_minute")) {
            val m = (value as? JsonPrimitive)?.content?.toIntOrNull()
            if (m != null) return "${m / 60}时${m % 60}分"
        }
        return (value as? JsonPrimitive)?.content ?: value.toString()
    }

    /** HH:mm → 分钟数（仅用于同日先后比较）。 */
    private fun hmMinutes(hhmm: String): Int =
        hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

    private val HEX_COLOR_REGEX = Regex("#[0-9A-Fa-f]{6}")

    /** 校验并规范化 #RRGGBB 颜色字符串（大写返回）。 */
    private fun parseHexColor(raw: String, label: String): String {
        val trimmed = raw.trim()
        if (!HEX_COLOR_REGEX.matches(trimmed)) {
            throw ToolArgException("$label 颜色格式应为 #RRGGBB（如 #1E88E5）")
        }
        return trimmed.uppercase()
    }

    /** 校验 #RRGGBB 并转为不透明 ARGB Int。 */
    private fun parseColorArgb(raw: String, label: String): Int {
        val hex = parseHexColor(raw, label).substring(1)
        return 0xFF000000.toInt() or hex.toInt(16)
    }

    /** ARGB Int → #RRGGBB（丢弃 alpha 通道，标签颜色均不透明）。 */
    private fun argbToHex(argb: Int): String = "#%06X".format(argb and 0x00FFFFFF)

    /**
     * 通用 tag_ids 解析：键存在时必须是整数数组（可为空数组表示清空标签），
     * 每个 id 必须能在 [listAll] 中找到；返回按传入顺序去重后的标签实体。
     */
    private suspend inline fun <reified T> resolveTagIds(
        raw: JsonElement?,
        listAll: suspend () -> List<T>,
        crossinline idOf: (T) -> Long,
        label: String
    ): List<T> {
        val array = raw as? JsonArray
            ?: throw ToolArgException("$label tag_ids 必须是整数数组")
        val ids = array.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        if (ids.size != array.size) {
            throw ToolArgException("$label tag_ids 中存在非整数元素")
        }
        if (ids.isEmpty()) return emptyList()
        val byId = listAll().associateBy(idOf)
        val missing = ids.distinct().filterNot { it in byId }
        if (missing.isNotEmpty()) {
            throw ToolArgException("${label}不存在，id=$missing（可先查询已有标签）")
        }
        return ids.distinct().mapNotNull { byId[it] }
    }

    // ================= JSON 输出 / 元素组装 =================

    private fun successJson(block: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("ok", true)
            block()
        }.toString()

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()

    private fun courseElement(
        course: Course,
        sections: Map<Int, cn.sanxing.thrice.data.domain.model.SectionTime>,
        includeWeeks: Boolean
    ): JsonElement = buildJsonObject {
        put("id", course.id)
        put("name", course.name)
        put("teacher", course.teacher)
        put("location", course.location)
        put("day_of_week", course.dayOfWeek)
        put("weekday", weekday(course.dayOfWeek))
        put("start_section", course.startSection)
        put("end_section", course.endSection)
        sectionLabel(course, sections)?.let { put("section_time", it) }
        if (course.overrideDate != null) put("override_date", course.overrideDate.toString())
        if (course.overrideNote.isNotBlank()) put("override_note", course.overrideNote)
        if (includeWeeks) putJsonArray("weeks") { course.weeks.sorted().forEach { add(JsonPrimitive(it)) } }
    }

    private fun billElement(bill: Bill): JsonElement = buildJsonObject {
        put("id", bill.id)
        put("type", if (bill.type == BillType.EXPENSE) "expense" else "income")
        put("amount", bill.amount)
        put("category", bill.category)
        put("date", bill.date)
        put("note", bill.note)
        put("created_at", formatMillis(bill.createdAt))
    }

    private fun focusSessionElement(session: FocusSession, tagName: String?): JsonElement = buildJsonObject {
        put("id", session.id)
        put("tag_id", session.tagId)
        put("tag_name", tagName ?: "未分类")
        put("mode", session.mode.lowercase())
        put("status", session.status.lowercase())
        put("planned_minutes", session.plannedSeconds / 60)
        put("focused_minutes", session.focusedSeconds / 60)
        put("started_at", formatMillis(session.startedAtEpochMs))
        put("ended_at", formatMillis(session.endedAtEpochMs))
    }

    // ================= 参数 / 格式化工具 =================

    private val argJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim().ifEmpty { "{}" }
        val element = try {
            argJson.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            throw ToolArgException("参数不是合法的 JSON：${e.message ?: "解析失败"}")
        }
        return element as? JsonObject
            ?: throw ToolArgException("参数必须是一个 JSON 对象")
    }

    private fun JsonObject.str(key: String): String? {
        val p = this[key] ?: return null
        if (p is JsonNull) return null
        return (p as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? {
        val p = this[key]
        if (p is JsonNull) return null
        return (p as? JsonPrimitive)?.content?.toLongOrNull()
            ?: int(key)?.toLong()
    }

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
            ?.content?.toBooleanStrictOrNull()

    private fun JsonObject.intList(key: String): List<Int>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }

    /** 取「可显式置空」的字符串参数：键存在且为 null/空白 → null；键不存在 → 缺省信号由调用方判断。 */
    private fun presentString(args: JsonObject, key: String): String? {
        val p = args[key] ?: return null
        if (p is JsonNull) return null
        return (p as? JsonPrimitive)?.content?.trim()?.ifEmpty { null }
    }

    private fun parseDate(raw: String, label: String): LocalDate =
        runCatching { LocalDate.parse(raw.trim()) }.getOrElse {
            throw ToolArgException("$label 格式应为 yyyy-MM-dd")
        }

    private fun normalizeHhmm(raw: String, label: String): String {
        val m = HHMM_REGEX.matchEntire(raw.trim())
            ?: throw ToolArgException("$label 时间格式应为 HH:mm")
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) {
            throw ToolArgException("$label 时间不合法（小时 0..23，分钟 0..59）")
        }
        return "%02d:%02d".format(hour, minute)
    }

    private fun parseBillType(raw: String): BillType? = when (raw.trim().lowercase()) {
        "expense", "支出" -> BillType.EXPENSE
        "income", "收入" -> BillType.INCOME
        else -> null
    }

    private fun weekOf(startDate: LocalDate, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(startDate, date) / 7).toInt() + 1

    private fun courseOnDate(
        course: Course,
        date: LocalDate,
        week: Int,
        dayOfWeek: Int,
        totalWeeks: Int
    ): Boolean {
        if (course.kind != CourseKind.GRID) return false
        // 调课 / 补课钉到具体日期的课程优先按 overrideDate 命中
        if (course.overrideDate == date) return true
        if (course.overrideDate != null) return false
        return week in 1..totalWeeks &&
            week in course.weeks &&
            course.dayOfWeek == dayOfWeek
    }

    private fun sectionLabel(
        course: Course,
        sections: Map<Int, cn.sanxing.thrice.data.domain.model.SectionTime>
    ): String? {
        if (course.startSection <= 0) return null
        val start = sections[course.startSection]?.startTime
        val end = sections[course.endSection]?.endTime
        return if (start != null && end != null) {
            "第${course.startSection}-${course.endSection}节 $start-$end"
        } else {
            "第${course.startSection}-${course.endSection}节"
        }
    }

    private fun weekday(dayOfWeek: Int): String =
        WEEKDAYS.getOrElse(dayOfWeek - 1) { "未知" }

    private fun makeExcerpt(content: String): String {
        val oneLine = content.replace(WHITESPACE_REGEX, " ").trim()
        return if (oneLine.length <= EXCERPT_MAX) oneLine else oneLine.take(EXCERPT_MAX) + "…"
    }

    private fun round2(value: Double): Double =
        (value * 100).roundToLong() / 100.0

    private fun trimAmount(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun dayStartMillis(date: LocalDate): Long =
        date.atStartOfDay(ZONE).toInstant().toEpochMilli()

    private fun dayEndMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1

    private fun formatMillis(millis: Long?): String? =
        millis?.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZONE).format(DATE_TIME_FORMAT)
        }

    private fun formatWeekRanges(weeks: Collection<Int>): String {
        val sorted = weeks.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var rangeStart = sorted.first()
        var prev = sorted.first()
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) {
                prev = v
            } else {
                parts += if (rangeStart == prev) "$rangeStart" else "$rangeStart-$prev"
                rangeStart = v
                prev = v
            }
        }
        parts += if (rangeStart == prev) "$rangeStart" else "$rangeStart-$prev"
        return parts.joinToString(",")
    }

    // ================= JSON Schema DSL =================

    private fun schema(block: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties", block)
            put("additionalProperties", false)
        }

    private fun schema(required: List<String>, block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties", block)
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            put("additionalProperties", false)
        }

    private fun JsonObjectBuilder.pStr(name: String, desc: String, enum: List<String>? = null) {
        putJsonObject(name) {
            put("type", "string")
            put("description", desc)
            if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun JsonObjectBuilder.pInt(name: String, desc: String) {
        putJsonObject(name) {
            put("type", "integer")
            put("description", desc)
        }
    }

    private fun JsonObjectBuilder.pLong(name: String, desc: String) {
        putJsonObject(name) {
            put("type", "integer")
            put("format", "int64")
            put("description", desc)
        }
    }

    private fun JsonObjectBuilder.pNumber(name: String, desc: String) {
        putJsonObject(name) {
            put("type", "number")
            put("description", desc)
        }
    }

    private fun JsonObjectBuilder.pBool(name: String, desc: String) {
        putJsonObject(name) {
            put("type", "boolean")
            put("description", desc)
        }
    }

    private fun JsonObjectBuilder.pIntArray(name: String, desc: String) {
        putJsonObject(name) {
            put("type", "array")
            put("description", desc)
            putJsonObject("items") { put("type", "integer") }
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val HHMM_REGEX = Regex("^(\\d{1,2}):(\\d{2})$")
        val WHITESPACE_REGEX = Regex("\\s+")
        val FONT_KEY_REGEX = Regex("^\\d{2}$")
        const val EXCERPT_MAX = 60

        /**
         * 列表类工具单次要回传的条目上限。
         *
         * 工具结果会原样进入 HTTP 请求体并回灌上下文：不设上限时，一次调用就能构造出
         * 数百 KB～数 MB 的载荷，既可能 OOM，也会让 token 费用与上下文长度失控。
         * 超限时返回 `"truncated": true` 提示模型用更窄的筛选条件再查。
         */
        const val MAX_LIST_ROWS = 200

        /**
         * 按日期区间查询类工具允许的最大跨度（天）。
         *
         * 模型可能传 `0000-01-01` ~ `9999-12-31` 这类极端区间；DAO 侧没有 LIMIT，
         * 不夹住跨度就等于放开了一次性全表回灌。
         */
        const val MAX_RANGE_DAYS = 366L
    }
}
