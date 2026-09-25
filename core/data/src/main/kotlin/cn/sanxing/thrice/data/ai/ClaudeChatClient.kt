package cn.sanxing.thrice.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Anthropic Messages 协议客户端。
 * POST {baseUrl}/v1/messages —— 预设 baseUrl 已含 /v1（https://api.anthropic.com/v1），
 * 此时直接拼 /messages，不能重复追加 /v1。
 *
 * 鉴权头 x-api-key + anthropic-version: 2023-06-01；system 消息从 messages 中抽出为
 * 顶层 system 参数；max_tokens 必填（默认 4096）。
 */
internal class ClaudeChatClient(
    baseClient: OkHttpClient,
    baseUrl: String,
    apiKey: String,
    model: String
) : AiHttpTransport(baseClient, baseUrl, apiKey, model) {

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
    }

    override fun buildUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/messages" else "$trimmed/v1/messages"
    }

    override fun authHeaders(): Map<String, String> = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_VERSION
    )

    override fun buildRequestBody(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor>,
        maxTokens: Int?
    ): String = buildJsonObject {
        put("model", JsonPrimitive(model))
        put("max_tokens", JsonPrimitive(maxTokens ?: DEFAULT_MAX_TOKENS))

        // Claude 的 system 不放在 messages 内，多条 system 合并为一个顶层字符串
        val systemText = messages
            .filter { it.role == AiChatRole.SYSTEM }
            .joinToString("\n\n") { it.content.trim() }
            .trim()
        if (systemText.isNotEmpty()) put("system", JsonPrimitive(systemText))

        // Claude messages 仅接受 user / assistant 交替；
        // TOOL 角色消息聚合为紧随 assistant tool_use 的一条 user 消息（tool_result blocks）。
        put("messages", buildConversation(messages))

        if (tools.isNotEmpty()) {
            put("tools", buildTools(tools))
        }
    }.toString()

    /**
     * 构造 Claude messages 数组：
     * - 普通 user / assistant 文本消息保持字符串 content；
     * - 携带工具调用的 assistant 消息序列化为 content blocks（text 块与 tool_use 块可混排）；
     * - 连续的 TOOL 结果消息聚合为一条 user 消息，每个结果一个 tool_result block。
     */
    private fun buildConversation(messages: List<AiChatMessage>): JsonArray = buildJsonArray {
        var index = 0
        while (index < messages.size) {
            val msg = messages[index]
            when (msg.role) {
                AiChatRole.SYSTEM -> {
                    // system 已抽为顶层参数，这里跳过
                    index++
                }
                AiChatRole.USER -> {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(msg.content))
                    })
                    index++
                }
                AiChatRole.ASSISTANT -> {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("assistant"))
                        if (msg.toolCalls.isEmpty()) {
                            put("content", JsonPrimitive(msg.content))
                        } else {
                            put("content", buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(msg.content))
                                    })
                                }
                                msg.toolCalls.forEach { call ->
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("tool_use"))
                                        put("id", JsonPrimitive(call.id ?: "toolu_$index"))
                                        put("name", JsonPrimitive(call.name))
                                        // Claude 的 input 必须是 JSON 对象；非法时退化为空对象
                                        val input = runCatching {
                                            json.parseToJsonElement(call.argumentsJson).jsonObject
                                        }.getOrElse { JsonObject(emptyMap()) }
                                        put("input", input)
                                    })
                                }
                            })
                        }
                    })
                    index++
                }
                AiChatRole.TOOL -> {
                    // 收集连续的工具结果，合并为一条 user 消息（多个 tool_result blocks）
                    val results = mutableListOf<AiChatMessage>()
                    while (index < messages.size && messages[index].role == AiChatRole.TOOL) {
                        results += messages[index]
                        index++
                    }
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            results.forEach { result ->
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("tool_result"))
                                    put(
                                        "tool_use_id",
                                        JsonPrimitive(result.toolCallId.orEmpty())
                                    )
                                    put("content", JsonPrimitive(result.content))
                                })
                            }
                        })
                    })
                }
            }
        }
    }

    private fun buildTools(tools: List<AiToolDescriptor>): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("input_schema", tool.parameters)
            })
        }
    }

    override fun parseSuccessBody(body: String): AiChatResult {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["content"]?.jsonArray
            ?: throw IllegalStateException("missing content")

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()

        content.forEach { block: JsonElement ->
            val obj = block.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    obj["text"]?.jsonPrimitive?.content?.let { textBuilder.append(it) }
                }
                "tool_use" -> {
                    val name = obj["name"]?.jsonPrimitive?.content
                    if (name != null) {
                        // Claude 的 input 为 JSON 对象，统一序列化为参数 JSON 字符串；
                        // id（toolu_xxx）需原样保留，供下一轮 tool_result 回灌
                        val arguments = obj["input"]?.toString() ?: "{}"
                        toolCalls += AiToolCall(
                            id = obj["id"]?.jsonPrimitive?.content,
                            name = name,
                            argumentsJson = arguments
                        )
                    }
                }
            }
        }

        return AiChatResult(text = textBuilder.toString(), toolCalls = toolCalls)
    }
}
