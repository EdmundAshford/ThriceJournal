package cn.sanxing.thrice.data.ai

/**
 * AI 聊天客户端（多协议统一抽象），具体实现由 [AiClientFactory] 按当前配置创建。
 * 所有方法均在 Dispatchers.IO 上执行；失败时抛出携带中文消息的 [AiException]。
 */
interface AiClient {

    /**
     * 发起一轮聊天补全。
     *
     * @param messages 上下文消息（支持 system / user / assistant / tool 角色）
     * @param tools 可供模型调用的工具列表；空列表表示纯对话
     * @param timeoutSeconds 本次请求超时（秒），默认 60
     * @return 正文文本与工具调用
     */
    suspend fun chat(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor> = emptyList(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): AiChatResult

    /**
     * 测试连通性与鉴权：成功返回 true；配置 / 网络 / 鉴权失败时抛出 [AiException]
     * （消息为可直接展示的中文文案）。
     */
    suspend fun testConnection(): Boolean

    companion object {
        /** 默认请求超时（秒）。 */
        const val DEFAULT_TIMEOUT_SECONDS = 60L

        /** 连通性测试使用更短的超时（秒），避免设置页长时间等待。 */
        const val TEST_TIMEOUT_SECONDS = 15L
    }
}
