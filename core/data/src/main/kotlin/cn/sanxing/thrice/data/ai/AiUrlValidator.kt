package cn.sanxing.thrice.data.ai

import java.net.URI

/**
 * AI 服务商接口地址的合法性校验。
 *
 * 存在意义：接口地址由用户手填，而 App 会**无条件**把 API Key 以
 * `Authorization: Bearer …` / `x-api-key` 头发给该主机。不校验的话，
 * 任何被诱导填进去的地址（甚至只是少写了 scheme）都会导致
 * 1) 密钥外泄给第三方主机；2) 把内网响应回灌给模型的 SSRF 通道。
 *
 * 规则：
 * - 必须是合法的绝对 URL；
 * - 必须是 `https`（`targetSdk 35` 起明文 HTTP 已被平台默认禁用，
 *   这里提前拦截并给出可读提示，而不是让用户面对一次莫名的网络失败）；
 * - host 必须存在，且只允许 DNS 名 / 常规 IP 字符（挡掉注入字符）。
 */
internal object AiUrlValidator {

    private val HOST_CHARS = Regex("^[A-Za-z0-9._:\\-\\[\\]]+$")

    /** 校验通过则返回去掉尾部 `/` 的地址；不合法抛 [AiException]（[AiError.Config]）。 */
    fun requireHttps(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw AiException(AiError.Config("接口地址不能为空"))

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw AiException(AiError.Config("接口地址格式不正确，请填写完整地址，例如 https://api.deepseek.com/v1"))

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            throw AiException(
                AiError.Config(
                    if (scheme == "http") "接口地址必须使用 HTTPS（HTTP 会明文传输 API Key）"
                    else "接口地址必须以 https:// 开头"
                )
            )
        }
        val host = uri.host
            ?: throw AiException(AiError.Config("接口地址缺少主机名，请填写完整地址"))
        if (!HOST_CHARS.matches(host)) {
            throw AiException(AiError.Config("接口地址含有非法字符：$host"))
        }
        return trimmed.trimEnd('/')
    }
}
