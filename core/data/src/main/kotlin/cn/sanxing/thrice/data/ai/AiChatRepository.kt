package cn.sanxing.thrice.data.ai

import cn.sanxing.thrice.data.data.local.AiChatDao
import cn.sanxing.thrice.data.domain.model.AiConversation
import cn.sanxing.thrice.data.domain.model.AiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 对话持久化仓库：会话增删改、消息追加 / 回放。
 *
 * 消息在库内以 [AiMessage] 形态存储（角色为枚举名字符串、工具调用为 JSON 数组），
 * 对外统一暴露 AI 层既有的 [AiChatMessage] / [AiToolCall] 领域模型，
 * 编排器与 UI 无需感知 Room。
 */
@Singleton
class AiChatRepository @Inject constructor(
    private val dao: AiChatDao
) {
    // ---------------- 会话 ----------------

    fun observeConversations(): Flow<List<AiConversation>> = dao.observeConversations()

    suspend fun getConversation(id: Long): AiConversation? = dao.getConversationById(id)

    /** 新建会话并返回新 id。 */
    suspend fun createConversation(title: String = ""): Long =
        dao.insertConversation(AiConversation(title = title))

    /** 改名同时刷新 updatedAt。 */
    suspend fun renameConversation(id: Long, title: String) =
        dao.updateTitleAndTime(id, title, System.currentTimeMillis())

    /** 删除会话；同一事务内先删其全部消息。 */
    suspend fun deleteConversation(id: Long) = dao.deleteConversationAndMessages(id)

    // ---------------- 消息 ----------------

    fun observeMessages(conversationId: Long): Flow<List<AiChatMessage>> =
        dao.observeMessages(conversationId).map { it.toChatHistory() }

    suspend fun getMessages(conversationId: Long): List<AiChatMessage> =
        dao.getMessages(conversationId).toChatHistory()

    /**
     * 追加一条消息：orderIndex 取当前会话最大值加一，工具调用序列化为 JSON 数组落库，
     * 顺带刷新会话 updatedAt（使列表按最近活跃排序）。返回新消息 id。
     */
    suspend fun appendMessage(conversationId: Long, msg: AiChatMessage): Long {
        val now = System.currentTimeMillis()
        val orderIndex = (dao.maxOrderIndex(conversationId) ?: -1) + 1
        val messageId = dao.insertMessage(
            AiMessage(
                conversationId = conversationId,
                role = msg.role.name,
                content = msg.content,
                toolCallId = msg.toolCallId,
                toolName = msg.toolName,
                toolCallsJson = msg.encodeToolCalls(),
                createdAt = now,
                orderIndex = orderIndex
            )
        )
        dao.touchConversation(conversationId, now)
        return messageId
    }

    suspend fun updateMessageContent(id: Long, content: String) =
        dao.updateMessageContent(id, content)

    suspend fun deleteMessage(id: Long) = dao.deleteMessageById(id)

    /** 清空全部消息与会话（先消息后会话，避免悬挂）。 */
    suspend fun clearAll() {
        dao.clearMessages()
        dao.clearConversations()
    }
}

/** 专用 JSON 实例：容错读取历史数据中缺字段 / 多字段的工具调用数组。 */
private val chatJson = Json { ignoreUnknownKeys = true }

/**
 * 库内消息转领域模型：角色名无法识别时回落为 USER；
 * 工具调用 JSON 损坏 / 为空时回落为空列表，不影响其余历史回放。
 */
fun AiMessage.toChatMessage(): AiChatMessage = AiChatMessage(
    role = runCatching { AiChatRole.valueOf(role) }.getOrDefault(AiChatRole.USER),
    content = content,
    toolCallId = toolCallId,
    toolName = toolName,
    toolCalls = decodeToolCalls(toolCallsJson)
)

fun List<AiMessage>.toChatHistory(): List<AiChatMessage> = map { it.toChatMessage() }

/**
 * 工具调用序列化为 JsonArray 字符串；每个元素固定含
 * name / argumentsJson / id 三个字段（id 可能为 null）。
 * 任何序列化异常都回落为 "[]"，保证消息本身一定能落库。
 */
private fun AiChatMessage.encodeToolCalls(): String = runCatching {
    chatJson.encodeToString(
        buildJsonArray {
            toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("name", call.name)
                        put("argumentsJson", call.argumentsJson)
                        put("id", call.id)
                    }
                )
            }
        }
    )
}.getOrDefault("[]")

/** 反序列化工具调用数组；缺 name 的元素跳过，整体失败返回空列表。 */
private fun decodeToolCalls(raw: String): List<AiToolCall> = runCatching {
    if (raw.isBlank()) return@runCatching emptyList()
    chatJson.decodeFromString<JsonArray>(raw).mapNotNull { element ->
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        AiToolCall(
            name = name,
            argumentsJson = obj["argumentsJson"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            id = obj["id"]?.jsonPrimitive?.contentOrNull
        )
    }
}.getOrDefault(emptyList())
