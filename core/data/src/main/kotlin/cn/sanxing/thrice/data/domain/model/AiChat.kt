package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 对话（会话）。
 *
 * @param title 会话标题（首条消息生成或用户改名；默认空串）
 * @param updatedAt 最近一条消息落库时间，列表按它倒序排列
 */
@Entity(tableName = "ai_conversations")
data class AiConversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * AI 对话中的一条消息（持久化形态）。
 *
 * @param role 角色名（对应 cn.sanxing.thrice.data.ai.AiChatRole 枚举名字符串）
 * @param toolCallId TOOL 角色消息对应的工具调用 id；其余角色为 null
 * @param toolName TOOL 角色消息对应的工具名；其余角色为 null
 * @param toolCallsJson ASSISTANT 消息携带的工具调用 JSON 数组，
 *   元素含 name / argumentsJson / id 三个字段；无工具调用时为空数组字符串
 * @param orderIndex 会话内单调递增的顺序号，回放历史时按它升序
 */
@Entity(tableName = "ai_messages", indices = [Index("conversationId")])
data class AiMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val orderIndex: Int = 0
)
