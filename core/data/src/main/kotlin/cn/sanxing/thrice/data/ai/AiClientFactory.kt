package cn.sanxing.thrice.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 客户端工厂：依据 [AiSettingsRepository] 当前配置返回对应协议实现。
 *
 * 用法（Hilt 注入后）：
 * ```
 * @Inject lateinit var aiClientFactory: AiClientFactory
 * // suspend 上下文：
 * val client = aiClientFactory.create()
 * client.chat(...)
 * ```
 */
@Singleton
class AiClientFactory @Inject constructor(
    private val settingsRepository: AiSettingsRepository
) {

    /** 进程级共享 OkHttpClient：默认 60s 超时，无日志拦截器（避免泄漏 API Key）。 */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AiClient.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AiClient.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AiClient.DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 读取当前持久化配置并创建客户端；配置不完整时抛 [IllegalStateException]。 */
    suspend fun create(): AiClient = create(settingsRepository.currentSettings())

    /**
     * 按给定配置创建客户端。
     *
     * 注意：[AiSettings.baseUrl] 会先过 [AiUrlValidator]：地址一旦非法就抛
     * [AiException]，绝不在未校验的情况下把 API Key 发往任意主机。
     *
     * @throws AiException 配置不完整，或接口地址不合法
     */
    fun create(settings: AiSettings): AiClient {
        val apiKey = settings.apiKey.trim()
        val model = settings.model.trim()
        if (apiKey.isEmpty() || model.isEmpty()) {
            throw AiException(AiError.Config("AI 服务尚未完成配置（API Key、模型均不可为空）"))
        }
        // 先校验地址再建客户端：地址非法时绝不能把 apiKey 交给下层
        val baseUrl = AiUrlValidator.requireHttps(settings.baseUrl)

        return when (settings.protocol) {
            AiProtocol.CLAUDE -> ClaudeChatClient(baseClient, baseUrl, apiKey, model)
            else -> OpenAiCompatChatClient(baseClient, baseUrl, apiKey, model)
        }
    }

    /**
     * 拉取服务商可用模型列表（GET /models），供配置页选择。
     *
     * OpenAI 兼容协议：`GET {baseUrl}/models`，Bearer 鉴权；
     * Claude 协议：`GET {baseUrl 或 baseUrl/v1}/models`，x-api-key 鉴权。
     * 两类响应均为 `{"data":[{"id": "..."}]}`。
     *
     * 已自动过滤明显不是对话用途的模型（嵌入 / 语音 / 重排等）。
     * @throws IllegalStateException baseUrl / apiKey 为空，或 HTTP / 解析失败
     */
    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String,
        protocol: String
    ): List<String> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AiException(AiError.Config("请先填写 API Key，再获取模型列表"))
        // 与 create() 同一套校验：拉模型列表同样会带上密钥
        val url0 = AiUrlValidator.requireHttps(baseUrl)
        val url = if (protocol == AiProtocol.CLAUDE) {
            if (url0.endsWith("/v1")) "$url0/models" else "$url0/v1/models"
        } else {
            "$url0/models"
        }
        val builder = Request.Builder().url(url).get()
        if (protocol == AiProtocol.CLAUDE) {
            builder.header("x-api-key", key)
            builder.header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer $key")
        }
        baseClient.newCall(builder.build()).execute().use { resp ->
            val body = AiHttpTransport.readBodyLimited(resp)
            if (!resp.isSuccessful) {
                // 只回显状态码：响应体可能含服务端内部信息，直接展示给用户既无帮助也有信息泄露风险
                throw IllegalStateException("获取模型失败（HTTP ${resp.code}）")
            }
            val arr = JSONObject(body).optJSONArray("data")
                ?: throw IllegalStateException("服务商返回的模型列表格式无法识别")
            val excludeKeywords = listOf(
                "embed", "tts", "whisper", "speech", "audio",
                "rerank", "moderation", "dall-e", "image-generation"
            )
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id").orEmpty().takeIf { it.isNotBlank() }
            }.filter { id ->
                val lower = id.lowercase()
                excludeKeywords.none { it in lower }
            }.distinct().sorted()
        }
    }
}
