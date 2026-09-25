package cn.sanxing.thrice.data.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.sanxing.thrice.data.domain.model.AiConversation
import cn.sanxing.thrice.data.domain.model.AiMessage
import kotlinx.coroutines.flow.Flow

/**
 * AI 对话 / 消息 DAO。
 *
 * 会话列表固定按 updatedAt 倒序；消息按 orderIndex、createdAt、id 升序回放。
 * 不建外键：删除会话时由事务方法 [deleteConversationAndMessages] 先清消息。
 */
@Dao
interface AiChatDao {
    // ---------------- 会话 ----------------

    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<AiConversation>>

    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC, id DESC")
    suspend fun getAllConversations(): List<AiConversation>

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): AiConversation?

    /** 冲突替换：备份恢复时可保留条目自带 id。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: AiConversation): Long

    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitleAndTime(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE ai_conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("DELETE FROM ai_conversations")
    suspend fun clearConversations()

    /** 事务：先删会话下全部消息，再删会话本身。 */
    @Transaction
    suspend fun deleteConversationAndMessages(id: Long) {
        deleteMessagesByConversation(id)
        deleteConversationById(id)
    }

    // ---------------- 消息 ----------------

    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY orderIndex ASC, createdAt ASC, id ASC"
    )
    fun observeMessages(conversationId: Long): Flow<List<AiMessage>>

    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY orderIndex ASC, createdAt ASC, id ASC"
    )
    suspend fun getMessages(conversationId: Long): List<AiMessage>

    @Query("SELECT * FROM ai_messages ORDER BY conversationId ASC, orderIndex ASC, id ASC")
    suspend fun getAllMessages(): List<AiMessage>

    /** 冲突替换：备份恢复时可保留条目自带 id。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiMessage): Long

    /** 当前会话最大 orderIndex；空会话返回 null（调用方以 -1 兜底再加一）。 */
    @Query("SELECT MAX(orderIndex) FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun maxOrderIndex(conversationId: Long): Int?

    @Query("UPDATE ai_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("DELETE FROM ai_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: Long)

    @Query("DELETE FROM ai_messages")
    suspend fun clearMessages()
}
