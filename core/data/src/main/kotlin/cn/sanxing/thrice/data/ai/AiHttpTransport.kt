package cn.sanxing.thrice.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 两种协议共用的 HTTP 传输层：OkHttp 同步调用 + 统一错误映射，运行在 Dispatchers.IO。
 * 不挂载任何日志拦截器，请求体中的 API Key 不会被打印。
 */
internal abstract class AiHttpTransport(
    /** 工厂持有的共享基础客户端（默认 60s 超时）；每次调用按 timeoutSeconds 派生。 */
    private val baseClient: OkHttpClient,
    protected val baseUrl: String,
    protected val apiKey: String,
    protected val model: String
) : AiClient {

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 响应体上限：异常 / 恶意服务端返回超大 body 会直接把 App 撑到 OOM。 */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /**
         * 按上限读取响应体。
         *
         * `Response.body.string()` 会一次性把整个响应读进内存，不能直接用；
         * 这里先按 `contentLength`（可能为 -1，chunked 场景）快速判断，
         * 不确定时再按字节上限读取，超限抛 [AiError.Protocol]。
         */
        fun readBodyLimited(response: okhttp3.Response): String {
            val body = response.body ?: return ""
            val declared = body.contentLength()
            if (declared > MAX_RESPONSE_BYTES) {
                throw AiException(AiError.Protocol("响应过大（${declared} 字节）"))
            }
            val source = body.source()
            source.request(MAX_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RESPONSE_BYTES) {
                throw AiException(AiError.Protocol("响应超过 ${MAX_RESPONSE_BYTES} 字节上限"))
            }
            return source.readString(java.nio.charset.StandardCharsets.UTF_8)
        }
    }

    /** 完整请求地址（各协议自行按 baseUrl 拼接）。 */
    protected abstract fun buildUrl(): String

    /** 鉴权 / 协议版本等请求头（Content-Type 由 RequestBody 自动携带）。 */
    protected abstract fun authHeaders(): Map<String, String>

    /** 手写 JSON 请求体；[maxTokens] 非 null 时为连通性测试的极小取值。 */
    protected abstract fun buildRequestBody(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor>,
        maxTokens: Int?
    ): String

    /** 解析成功响应体为统一结果；结构异常会被外层统一映射为 [AiError.Protocol]。 */
    protected abstract fun parseSuccessBody(body: String): AiChatResult

    final override suspend fun chat(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor>,
        timeoutSeconds: Long
    ): AiChatResult = withContext(Dispatchers.IO) {
        execute(messages, tools, timeoutSeconds, maxTokens = null)
    }

    final override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        // 用最小负载做一次真实补全，同时验证地址、密钥与模型可用性
        execute(
            messages = listOf(AiChatMessage.user("ping")),
            tools = emptyList(),
            timeoutSeconds = AiClient.TEST_TIMEOUT_SECONDS,
            maxTokens = 1
        )
        true
    }

    private fun execute(
        messages: List<AiChatMessage>,
        tools: List<AiToolDescriptor>,
        timeoutSeconds: Long,
        maxTokens: Int?
    ): AiChatResult {
        val payload = buildRequestBody(messages, tools, maxTokens)
        val requestBuilder = Request.Builder().url(buildUrl())
        authHeaders().forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()

        // OkHttp 不支持单请求覆盖超时，这里用轻量的 newBuilder() 派生本次调用的客户端
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AiException(AiError.Timeout, e)
        } catch (e: IOException) {
            throw AiException(AiError.Network, e)
        }

        response.use { resp ->
            val body = readBodyLimited(resp)
            when (resp.code) {
                401, 403 -> throw AiException(AiError.Auth)
                429 -> throw AiException(AiError.RateLimit)
            }
            if (!resp.isSuccessful) throw AiException(AiError.Http(resp.code))

            return try {
                parseSuccessBody(body)
            } catch (e: AiException) {
                throw e
            } catch (e: RuntimeException) {
                // JsonException / IllegalStateException / NoSuchElementException 等结构异常
                throw AiException(AiError.Protocol(e.message), e)
            }
        }
    }
}
