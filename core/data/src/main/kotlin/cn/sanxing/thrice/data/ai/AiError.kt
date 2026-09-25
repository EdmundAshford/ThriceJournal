package cn.sanxing.thrice.data.ai

/**
 * AI 网络层错误（已映射为面向用户的中文文案，UI 可直接展示 [userMessage]）。
 */
sealed class AiError(val userMessage: String) {

    /** 网络不可达 / 连接被中断等 IOException。 */
    data object Network : AiError("网络连接失败，请检查网络")

    /** 401 / 403：密钥错误、权限或额度问题。 */
    data object Auth : AiError("API Key 无效或额度不足")

    /** 429：触发限流。 */
    data object RateLimit : AiError("请求过于频繁，请稍后再试")

    /** 请求超时。 */
    data object Timeout : AiError("请求超时")

    /** 其它非 2xx HTTP 状态码。 */
    data class Http(val status: Int) : AiError("服务返回错误：$status")

    /**
     * 本地配置不合法：接口地址格式错误 / 不是 HTTPS / 指向的地址不被允许等。
     * 必须校验后再发请求，否则用户的 API Key 会被无条件发往任意主机。
     */
    data class Config(val detail: String) : AiError(detail)

    /**
     * 响应不符合协议预期（缺字段 / JSON 解析失败等）。
     * @param reason 技术细节，仅用于排查，不直接展示给用户
     */
    data class Protocol(val reason: String? = null) : AiError("响应解析失败")
}

/** AI 请求失败统一异常，携带分类错误与中文消息。 */
class AiException(
    val error: AiError,
    cause: Throwable? = null
) : Exception(error.userMessage, cause)
