package cn.sanxing.thrice.data.ai

import kotlinx.serialization.json.JsonObject

/** 聊天消息角色。 */
enum class AiChatRole {
    USER,
    ASSISTANT,
    SYSTEM,

    /**
     * 工具结果回灌角色。
     * OpenAI 协议：独立的 role=tool 消息（携带 tool_call_id）。
     * Claude 协议：映射为下一条 user 消息中的 tool_result content block。
     */
    TOOL
}

/**
 * 一条聊天消息。
 *
 * @param toolCallId TOOL 角色消息对应的工具调用 id（OpenAI 协议 tool 消息必填；
 * Claude 协议映射为 tool_result block 的 tool_use_id）
 * @param toolName TOOL 角色消息对应的工具名（部分 OpenAI 兼容服务商要求）
 * @param toolCalls ASSISTANT 消息中模型请求的工具调用。回灌时必须原样带回：
 * OpenAI 以 assistant 消息的 tool_calls 字段发送，Claude 以 tool_use content block 发送。
 */
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<AiToolCall> = emptyList()
) {
    companion object {
        fun system(content: String) = AiChatMessage(AiChatRole.SYSTEM, content)
        fun user(content: String) = AiChatMessage(AiChatRole.USER, content)
        fun assistant(content: String, toolCalls: List<AiToolCall> = emptyList()) =
            AiChatMessage(AiChatRole.ASSISTANT, content, toolCalls = toolCalls)

        /** 构造一条工具执行结果回灌消息。 */
        fun toolResult(
            toolCallId: String,
            content: String,
            toolName: String? = null
        ) = AiChatMessage(
            role = AiChatRole.TOOL,
            content = content,
            toolCallId = toolCallId,
            toolName = toolName
        )
    }
}

/**
 * 可调用工具描述（function calling）。
 *
 * @param parameters JSON Schema 对象，描述工具参数，例如
 * `{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}`
 */
data class AiToolDescriptor(
    val name: String,
    val description: String,
    val parameters: JsonObject = EmptyJsonObject
) {
    companion object {
        val EmptyJsonObject = JsonObject(emptyMap())
    }
}

/**
 * 模型返回的一次工具调用。
 *
 * @param id 服务商分配的调用 id（OpenAI 为 call_xxx，Claude 为 toolu_xxx）；
 * 工具结果回灌时必须原样带回，个别服务商缺失时由调用方生成兜底 id。
 * @param argumentsJson 参数 JSON 字符串（通常是 object 序列化结果）。
 */
data class AiToolCall(
    val name: String,
    val argumentsJson: String,
    val id: String? = null
)

/** 一轮聊天结果：正文文本 + 模型请求的工具调用（二者可能同时存在）。 */
data class AiChatResult(
    val text: String,
    val toolCalls: List<AiToolCall> = emptyList()
)
