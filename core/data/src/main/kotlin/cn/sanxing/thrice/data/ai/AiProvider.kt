package cn.sanxing.thrice.data.ai

/**
 * AI 服务通信协议（持久化字符串）。
 * - [OPENAI_COMPAT]：OpenAI Chat Completions 兼容协议（DeepSeek / GLM / Qwen / Kimi / 方舟 /
 *   SiliconFlow / OpenAI / Gemini OpenAI 兼容入口）
 * - [CLAUDE]：Anthropic Messages 协议
 */
object AiProtocol {
    const val OPENAI_COMPAT = "openai_compat"
    const val CLAUDE = "claude"

    /** 非法 / 旧值一律回落为 OpenAI 兼容协议。 */
    fun fromRaw(value: String?): String = if (value == CLAUDE) CLAUDE else OPENAI_COMPAT
}

/**
 * AI 服务商预设（[id] 为稳定持久化标识，升级版本不得变更）。
 *
 * @param defaultModel 预设默认模型；为空字符串表示该服务商必须由用户手填（如方舟接入点 ID）
 * @param requiresManualModel true 表示默认模型不可直接使用，UI 应强提示用户自填
 * @param custom true 表示完全自定义服务商（显示名 / baseUrl / key / 模型全部用户填写）
 */
data class AiProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: String,
    val requiresManualModel: Boolean = false,
    val custom: Boolean = false
) {
    companion object {
        const val ID_DEEPSEEK = "deepseek"
        const val ID_GLM = "glm"
        const val ID_QWEN = "qwen"
        const val ID_KIMI = "kimi"
        const val ID_DOUBAO = "doubao"
        const val ID_SILICONFLOW = "siliconflow"
        const val ID_OPENAI = "openai"
        const val ID_GEMINI = "gemini"
        const val ID_CLAUDE = "claude"
        const val ID_CUSTOM = "custom"

        // 模型名与接口地址按 2026-09 各服务商官方文档核实更新。
        val DEEPSEEK = AiProvider(
            id = ID_DEEPSEEK,
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            // deepseek-flash（DeepSeek-V4.1-Flash，2026-09 起的官方模型名）
            defaultModel = "deepseek-flash",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val GLM = AiProvider(
            id = ID_GLM,
            displayName = "GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            // GLM-5.3-Flash 2026-08 上线；glm-4.5-flash 已下线并自动路由
            defaultModel = "glm-5.3-flash",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val QWEN = AiProvider(
            id = ID_QWEN,
            displayName = "Qwen",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            // qwen-plus 为持续有效的别名（2026-09 指向 Qwen3 系列 Plus）
            defaultModel = "qwen-plus",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val KIMI = AiProvider(
            id = ID_KIMI,
            displayName = "Kimi",
            // 官方新域 api.moonshot.ai；旧 api.moonshot.cn 仍可能路由
            baseUrl = "https://api.moonshot.ai/v1",
            // kimi-k3 为当前旗舰；moonshot-v1-* / kimi-k2 系列已陆续下线
            defaultModel = "kimi-k3",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val DOUBAO = AiProvider(
            id = ID_DOUBAO,
            displayName = "Doubao",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            // 方舟不使用统一模型名，而是用户在控制台创建的接入点 ID（如 ep-xxxxxxxx），必须自填
            defaultModel = "",
            protocol = AiProtocol.OPENAI_COMPAT,
            requiresManualModel = true
        )

        val SILICONFLOW = AiProvider(
            id = ID_SILICONFLOW,
            displayName = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            // 可用模型更新频繁，配置页可用「获取模型」拉取最新列表选择
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val OPENAI = AiProvider(
            id = ID_OPENAI,
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            // 2026-09 产品线为 GPT-6 系列，代际模型 id 变化快，
            // 不内置可能过时的模型名：请用「获取模型」拉取后选择或手填
            defaultModel = "",
            protocol = AiProtocol.OPENAI_COMPAT,
            requiresManualModel = true
        )

        val GEMINI = AiProvider(
            id = ID_GEMINI,
            displayName = "Gemini",
            // OpenAI 兼容入口（结尾斜杠为官方示例形式）
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
            // 官方 OpenAI 兼容文档当前示例模型；更新的 3.7/3.8-flash 亦可手填
            defaultModel = "gemini-3.6-flash",
            protocol = AiProtocol.OPENAI_COMPAT
        )

        val CLAUDE = AiProvider(
            id = ID_CLAUDE,
            displayName = "Claude",
            baseUrl = "https://api.anthropic.com",
            // claude-sonnet-5（2026-06-30 起的当前 Sonnet，Active/latest）
            defaultModel = "claude-sonnet-5",
            protocol = AiProtocol.CLAUDE
        )

        /** 完全自定义：显示名 / baseUrl / key / 模型全填，协议可选 openai_compat 或 claude。 */
        val CUSTOM = AiProvider(
            id = ID_CUSTOM,
            displayName = "Custom",
            baseUrl = "",
            defaultModel = "",
            protocol = AiProtocol.OPENAI_COMPAT,
            requiresManualModel = true,
            custom = true
        )

        /** 预设顺序（UI 列表按此顺序展示，Custom 固定在末尾）。 */
        val PRESETS: List<AiProvider> = listOf(
            DEEPSEEK,
            GLM,
            QWEN,
            KIMI,
            DOUBAO,
            SILICONFLOW,
            OPENAI,
            GEMINI,
            CLAUDE,
            CUSTOM
        )

        /** 按稳定 id 查找预设；未知 id（含旧版本残留）返回 null。 */
        fun fromId(id: String?): AiProvider? =
            id?.let { v -> PRESETS.firstOrNull { it.id == v } }
    }
}
