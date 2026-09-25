package cn.sanxing.thrice.data.ai

import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 对话编排器：组装「用户人设（系统提示词）+ 运行时上下文」的 system 消息、
 * 携带授权工具发起对话，收到模型的工具调用后交给 [AiToolEngine] 执行并把结果按
 * 协议回灌，循环至多 [MAX_TOOL_ROUNDS] 轮后返回最终 assistant 文本。
 *
 * - 人设（默认 / 用户自建）由 [AiPersonaRepository] 提供，用户可在 AI 界面切换；
 * - 当前时间与已授权能力清单等运行时信息始终追加在人设之后，保证工具调用正常；
 * - 未完成服务商配置时抛 [IllegalStateException]（中文固定文案，UI 可直接提示）；
 * - 网络 / 协议层错误为携带中文消息的 [AiException]，此处不捕获、原样透传；
 * - 无任何数据授权时不带 tools，仍可进行纯对话。
 */
@Singleton
class AiChatOrchestrator @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val toolEngine: AiToolEngine,
    private val permissionRepository: AiPermissionRepository,
    private val aiSettingsRepository: AiSettingsRepository,
    private val personaRepository: AiPersonaRepository
) {

    /**
     * 完成一整轮「用户消息 →（可能多次）工具调用 → 最终回复」。
     *
     * @param userText 本轮用户输入
     * @param history 既有对话（仅取其中的纯文本 user / assistant 消息；
     *   同角色相邻消息会被合并以满足 Claude 的交替要求）
     * @return 最终面向用户的 assistant 文本
     * @throws IllegalStateException 服务商未配置
     */
    suspend fun send(userText: String, history: List<AiChatMessage> = emptyList()): String {
        check(aiSettingsRepository.isConfigured()) { notConfiguredMessage(personaRepository.currentLanguage()) }

        val client = clientFactory.create()
        val perms = permissionRepository.current()
        val tools = toolEngine.availableTools(perms)
        val lang = personaRepository.currentLanguage()

        val messages = mutableListOf<AiChatMessage>()
        messages += AiChatMessage.system(
            buildSystemPrompt(personaRepository.activePersona.first(), perms, lang)
        )
        messages += normalizeHistory(history)
        appendUserMessage(messages, userText)

        var lastAssistantText = ""

        repeat(MAX_TOOL_ROUNDS) { round ->
            val result = client.chat(messages, tools)
            if (result.toolCalls.isEmpty()) {
                return result.text.ifBlank { lastAssistantText }
            }

            // 先为缺失 id 的工具调用补稳定 id，使 assistant 块（tool_calls / tool_use）
            // 与后续结果回灌块（tool_call_id / tool_use_id）严格配对
            val calls = result.toolCalls.mapIndexed { index, call ->
                if (call.id != null) call else call.copy(id = "call_${round}_$index")
            }

            // 回灌带工具调用的 assistant 回合，再逐条附上工具执行结果
            messages += AiChatMessage.assistant(result.text, calls)
            calls.forEach { call ->
                val toolResult = toolEngine.execute(call.name, call.argumentsJson)
                messages += AiChatMessage.toolResult(
                    toolCallId = call.id.orEmpty(),
                    content = toolResult,
                    toolName = call.name
                )
            }
            lastAssistantText = result.text
        }

        // 连续 MAX_TOOL_ROUNDS 轮均为工具调用：再请求一次以取得文字总结。
        // 注意：此时历史中已含 assistant(tool_use) 与 tool_result 块，Claude 协议
        // 要求这种请求必须同时声明 tools，否则 400；OpenAI 兼容协议对此无限制。
        val finalResult = client.chat(messages, tools)
        return finalResult.text.ifBlank {
            // 模型在收尾轮仍只给工具调用：不再继续执行，返回通用操作回执
            if (finalResult.toolCalls.isNotEmpty() || lastAssistantText.isBlank()) {
                "已完成相关操作。"
            } else {
                lastAssistantText
            }
        }
    }

    /**
     * 组装完整 system 消息：用户选定的人设正文在前，其后追加运行时上下文
     * （当前时间、已授权数据能力清单），全部按提示词语言 [lang]（zh/en）本地化——
     * 人设可自由定制，工具调用所需的动态信息不受人设内容影响。
     */
    private fun buildSystemPrompt(persona: AiPersona, perms: AiPermissions, lang: String): String {
        val now = LocalDateTime.now()
        val weekdays = if (lang == AI_LANG_EN) WEEKDAYS_EN else WEEKDAYS_ZH
        val weekday = weekdays[now.dayOfWeek.value - 1]
        val timeText = now.format(TIME_FORMAT)

        return buildString {
            appendLine(persona.content.trim())
            appendLine()
            if (lang == AI_LANG_EN) {
                appendLine("Current system time: $timeText $weekday. All dates and times must be based on this.")
                appendLine("Date format: yyyy-MM-dd; time format: yyyy-MM-dd HH:mm.")
            } else {
                appendLine("当前系统时间：$timeText $weekday。涉及日期时间一律以此为准，")
                appendLine("日期格式 yyyy-MM-dd，时刻格式 yyyy-MM-dd HH:mm。")
            }
            appendLine()
            append(buildPermissionSection(perms, lang))
        }.trimEnd()
    }

    private fun buildPermissionSection(perms: AiPermissions, lang: String): String = buildString {
        if (lang == AI_LANG_EN) {
            if (!perms.anyPermission) {
                appendLine("You currently have no local data permissions and can only hold a regular conversation.")
                append("If the user asks about personal data or requests changes, tell them to enable the relevant data permissions on the AI settings page.")
                return@buildString
            }
            appendLine("The user has granted the following local data capabilities:")
            AiPermDomain.entries.forEach { domain ->
                val canRead = perms.isAllowed(domain, write = false)
                val canWrite = perms.isAllowed(domain, write = true)
                if (canRead || canWrite) {
                    val scope = when {
                        canRead && canWrite -> "read & write"
                        canRead -> "read only"
                        else -> "write only"
                    }
                    appendLine("- ${domain.labelEn}: $scope")
                }
            }
            appendLine(
                "Note: granting write access to a domain lets you adjust anything the user can adjust " +
                    "in that feature's UI (for example, the settings domain covers app language, theme " +
                    "mode/color, font, wallpaper opacity and framing, reminders, and focus/sleep options; " +
                    "the schedule domain covers section times and term info; the task/notes domains cover " +
                    "tags; the bills domain covers custom categories). You cannot change system-level " +
                    "permissions (battery optimization, autostart, exact alarms), AI API keys, the data " +
                    "permission matrix, or the bottom navigation layout; custom wallpapers can only be " +
                    "cleared, not replaced with a new image."
            )
            append("Domains not listed above are not granted — do not attempt to access them.")
            return@buildString
        }
        if (!perms.anyPermission) {
            appendLine("当前没有获得任何本地数据授权，你只能进行普通对话。")
            append("若用户询问个人数据或要求修改数据，请提示用户到 AI 设置页开启对应的数据权限。")
            return@buildString
        }
        appendLine("当前用户已授予以下本地数据能力：")
        AiPermDomain.entries.forEach { domain ->
            val canRead = perms.isAllowed(domain, write = false)
            val canWrite = perms.isAllowed(domain, write = true)
            if (canRead || canWrite) {
                val scope = when {
                    canRead && canWrite -> "可查询、可修改"
                    canRead -> "仅可查询"
                    else -> "仅可修改"
                }
                appendLine("- ${domain.label}：$scope")
            }
        }
        appendLine("说明：某域授予「可修改」后，该功能界面里用户本人能调整的项目你都可以代为调整" +
            "（例如设置域含界面语言、主题模式 / 主题色、字体、壁纸浓度与取景、各类提醒与专注睡眠选项；" +
            "课表域含每小节上下课时间与学期信息；任务 / 笔记域含标签；账单域含自定义分类），" +
            "但应用系统权限（电池白名单、开机自启、精确闹钟等）、AI API Key、数据授权矩阵" +
            "与底部导航布局不可由你修改；自定义壁纸只能清除、无法由你指定新图片。")
        append("未列出的数据域均未授权，不要尝试访问。")
    }

    /**
     * 规整历史消息：
     * - 只保留纯文本 user / assistant，合并相邻同角色消息；
     * - 丢弃首个 user 之前的消息（Claude 协议要求首轮必须是 user）；
     * - 超长对话只保留「首条 user（上下文锚点）+ 最近 [MAX_HISTORY_MESSAGES] 条」，
     *   单条超 [MAX_MESSAGE_CHARS] 字符截断，避免无限增长的历史撑爆上下文 / 费用。
     */
    private fun normalizeHistory(history: List<AiChatMessage>): List<AiChatMessage> {
        val merged = mutableListOf<AiChatMessage>()
        history.forEach { message ->
            if (message.role != AiChatRole.USER && message.role != AiChatRole.ASSISTANT) return@forEach
            if (message.toolCalls.isNotEmpty()) return@forEach
            var text = message.content.trim()
            if (text.isEmpty()) return@forEach
            if (text.length > MAX_MESSAGE_CHARS) {
                text = text.take(MAX_MESSAGE_CHARS) + " …"
            }
            val last = merged.lastOrNull()
            if (last != null && last.role == message.role) {
                merged[merged.lastIndex] = last.copy(content = last.content + "\n\n" + text)
            } else {
                merged += message.copy(content = text)
            }
        }
        val anchored = merged.dropWhile { it.role != AiChatRole.USER }
        if (anchored.size <= MAX_HISTORY_MESSAGES) return anchored

        // 首条 user 单独保留；尾部窗口若以 assistant 打头则丢弃至下一条 user，
        // 保证与首条拼起来后仍以 user 开头、角色交替。
        val first = anchored.first()
        val tail = anchored.takeLast(MAX_HISTORY_MESSAGES - 1)
            .dropWhile { it.role != AiChatRole.USER }
        return listOf(first) + tail
    }

    /** 追加本轮用户输入；若历史末尾已是 user 消息则合并，避免出现连续两条 user。 */
    private fun appendUserMessage(messages: MutableList<AiChatMessage>, userText: String) {
        val text = userText.trim()
        val last = messages.lastOrNull()
        if (last != null && last.role == AiChatRole.USER) {
            messages[messages.lastIndex] = last.copy(content = last.content + "\n\n" + text)
        } else {
            messages += AiChatMessage.user(text)
        }
    }

    private companion object {
        /** 工具调用最大轮数（防止模型反复调用工具形成死循环）。 */
        const val MAX_TOOL_ROUNDS = 5

        /** 随请求发送的历史消息条数上限（首条 user 锚点另计）。 */
        const val MAX_HISTORY_MESSAGES = 20

        /** 单条历史消息字符上限，超出截断（防止长文本无限累积 token）。 */
        const val MAX_MESSAGE_CHARS = 4_000

        const val NOT_CONFIGURED_ZH = "请先在右上角设置中完成 AI 服务商配置"
        const val NOT_CONFIGURED_EN =
            "Please complete the AI provider configuration in Settings (top-right) first."

        fun notConfiguredMessage(lang: String): String =
            if (lang == AI_LANG_EN) NOT_CONFIGURED_EN else NOT_CONFIGURED_ZH

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val WEEKDAYS_ZH = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val WEEKDAYS_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}
