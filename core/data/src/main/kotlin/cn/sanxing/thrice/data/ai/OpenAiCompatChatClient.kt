package cn.sanxing.thrice.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * OpenAI Chat Completions 兼容客户端：
 * POST {baseUrl}/chat/completions，Authorization: Bearer。
 * 适用于 DeepSeek / 智谱 GLM / 通义千问 / Kimi / 豆包方舟 / 硅基流动 / OpenAI / Gemini 兼容入口。
 */
internal class OpenAiCompatChatClient(
    baseClient: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    model: String
) : AiHttpTransport(baseClient, baseUrl, apiKey, model) {

    override fun buildUrl(): String =
        baseUrl.trimEnd('/') + "/chat/completions"

    override fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    override fun buildRequestBody(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor>,
        maxTokens: Int?
    ): String = buildJsonObject {
        put("model", JsonPrimitive(model))
        if (maxTokens != null) put("max_tokens", JsonPrimitive(maxTokens))
        put("messages", buildMessages(messages))
        if (tools.isNotEmpty()) {
            put("tools", buildTools(tools))
            put("tool_choice", JsonPrimitive("auto"))
        }
        put("stream", JsonPrimitive(false))
    }.toString()

    private fun buildMessages(messages: List<AiChatMessage>): JsonArray = buildJsonArray {
        messages.forEach { msg ->
            add(buildJsonObject {
                put("role", JsonPrimitive(roleName(msg.role)))
                when (msg.role) {
                    AiChatRole.TOOL -> {
                        // TOOL 角色消息必须关联工具调用 id；content 为工具执行结果（紧凑 JSON）
                        put("content", JsonPrimitive(msg.content))
                        msg.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
                        msg.toolName?.let { put("name", JsonPrimitive(it)) }
                    }
                    AiChatRole.ASSISTANT -> {
                        // 携带工具调用的 assistant 回合必须原样回灌 tool_calls；
                        // content 允许为空字符串（部分服务商要求字段存在）
                        put("content", JsonPrimitive(msg.content))
                        if (msg.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                msg.toolCalls.forEachIndexed { index, call ->
                                    add(buildJsonObject {
                                        put(
                                            "id",
                                            JsonPrimitive(call.id ?: "call_$index")
                                        )
                                        put("type", JsonPrimitive("function"))
                                        put("function", buildJsonObject {
                                            put("name", JsonPrimitive(call.name))
                                            put("arguments", JsonPrimitive(call.argumentsJson))
                                        })
                                    })
                                }
                            })
                        }
                    }
                    else -> put("content", JsonPrimitive(msg.content))
                }
            })
        }
    }

    private fun buildTools(tools: List<AiToolDescriptor>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("function", buildJsonObject {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put("parameters", tool.parameters)
                })
            })
        }
    }

    override fun parseSuccessBody(body: String): AiChatResult {
        val root = json.parseToJsonElement(body).jsonObject
        // 必须显式判空：直接取 choices[0] 会抛英文 IndexOutOfBoundsException，
        // 而异常 message 会经 AiError.Protocol 原样展示给用户。
        val choices = root["choices"]?.jsonArray
            ?: throw IllegalStateException("响应缺少 choices 字段")
        if (choices.isEmpty()) throw IllegalStateException("响应 choices 为空数组")
        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw IllegalStateException("响应缺少 message 字段")

        val text = (message["content"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()

        val toolCalls = message["tool_calls"]
            ?.jsonArray
            ?.mapNotNull { element -> parseToolCall(element) }
            .orEmpty()

        return AiChatResult(text = text, toolCalls = toolCalls)
    }

    private fun parseToolCall(element: JsonElement): AiToolCall? {
        val obj = element.jsonObject
        val function = obj["function"]?.jsonObject ?: return null
        val name = function["name"]?.jsonPrimitive?.content ?: return null
        val arguments = function["arguments"]?.jsonPrimitive?.content
        return AiToolCall(
            id = obj["id"]?.jsonPrimitive?.content,
            name = name,
            argumentsJson = arguments?.takeIf { it.isNotBlank() } ?: "{}"
        )
    }

    private fun roleName(role: AiChatRole): String = when (role) {
        AiChatRole.SYSTEM -> "system"
        AiChatRole.USER -> "user"
        AiChatRole.ASSISTANT -> "assistant"
        AiChatRole.TOOL -> "tool"
    }
}
