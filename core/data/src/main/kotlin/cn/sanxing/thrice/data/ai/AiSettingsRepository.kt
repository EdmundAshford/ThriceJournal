package cn.sanxing.thrice.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * AI 问当前完整配置快照（活动配置 [AiProfile] 的平铺视图，供客户端工厂使用）。
 *
 * @param providerId 服务商稳定 id（见 [AiProvider.ID_DEEPSEEK] 等）；null 表示从未选择
 * @param baseUrl 接口根地址（Claude 协议下可含 /v1，客户端会自行判断是否再拼 /v1）
 * @param apiKey API 密钥，仅保存在本机 DataStore，不参与备份 / 日志
 * @param model 模型名（方舟场景为接入点 ID）
 * @param protocol [AiProtocol.OPENAI_COMPAT] / [AiProtocol.CLAUDE]
 * @param displayName 仅自定义服务商使用的显示名
 */
data class AiSettings(
    val providerId: String?,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val protocol: String,
    val displayName: String?
)

/**
 * 一套完整的 AI 服务配置。用户可保存多套（不同服务商 / 不同 Key / 不同模型），
 * 通过 [id] 切换；[id] 仅为本机 DataStore 内部标识（不含逗号）。
 */
data class AiProfile(
    val id: String,
    val providerId: String,
    val displayName: String = "",
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val protocol: String
) {
    fun toSettings(): AiSettings = AiSettings(
        providerId = providerId.ifBlank { null },
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        protocol = protocol,
        displayName = displayName.ifBlank { null }
    )
}

/**
 * AI 问配置仓库：支持多套配置（[AiProfile]）并存与随时切换。
 *
 * 存储隔离：API Key（与其余 AI 配置）使用独立 DataStore 文件 "ai_secrets"，
 * 与应用设置（"settings"）分开；该文件已在 backup_rules 与 data_extraction_rules
 * 的云备份段中排除，Key 不会离开本机（换机本地迁移除外）。
 *
 * 存储格式：
 * - [KEY_PROFILE_IDS]：配置 id 顺序列表（CSV）
 * - [KEY_ACTIVE_PROFILE_ID]：当前启用的配置 id
 * - 每个配置 6 个字段，键名带 `pf_<id>_` 前缀
 *
 * 兼容：R13 及以前的单配置（无前缀的旧键）在首次读 / 写时自动迁移为
 * id=[LEGACY_PROFILE_ID] 的一套配置并置为活动。
 *
 * 安全约束：本仓库不输出任何日志；可读导出与备份 JSON 也不包含这些键。
 */
@Singleton
class AiSettingsRepository @Inject constructor(
    @Named("ai_secrets") private val dataStore: DataStore<Preferences>
) {

    companion object {
        // 旧版单配置键（仅用于迁移读取，迁移后删除）
        val KEY_PROVIDER_ID = stringPreferencesKey("ai_provider_id")
        val KEY_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_API_KEY = stringPreferencesKey("ai_api_key")
        val KEY_MODEL = stringPreferencesKey("ai_model")
        val KEY_PROTOCOL = stringPreferencesKey("ai_protocol")
        val KEY_DISPLAY_NAME = stringPreferencesKey("ai_display_name")

        private val KEY_PROFILE_IDS = stringPreferencesKey("ai_profile_ids")
        private val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("ai_active_profile_id")

        /** 旧单配置迁移成的首套配置固定 id。 */
        const val LEGACY_PROFILE_ID = "default"

        private fun profileKey(id: String, suffix: String) =
            stringPreferencesKey("pf_${id}_$suffix")

        private fun kProvider(id: String) = profileKey(id, "provider")
        private fun kBaseUrl(id: String) = profileKey(id, "base_url")
        private fun kApiKey(id: String) = profileKey(id, "api_key")
        private fun kModel(id: String) = profileKey(id, "model")
        private fun kProtocol(id: String) = profileKey(id, "protocol")
        private fun kDisplayName(id: String) = profileKey(id, "display_name")

        fun newProfileId(): String = "p" + UUID.randomUUID().toString().take(8)
    }

    // ---- 读取 ----

    /** 全部配置（按保存顺序）；未迁移的旧单配置会以内存形式出现在首位。 */
    val profiles: Flow<List<AiProfile>> = dataStore.data.map { readProfiles(it) }

    /** 当前活动配置 id；无配置时为 null。 */
    val activeProfileId: Flow<String?> = dataStore.data.map { prefs ->
        resolveActiveId(prefs, readProfiles(prefs).map { it.id })
    }

    /** 当前活动配置；从未配置过时为 null。 */
    val activeProfile: Flow<AiProfile?> = dataStore.data.map { prefs ->
        val list = readProfiles(prefs)
        resolveActiveId(prefs, list.map { it.id })?.let { id -> list.firstOrNull { it.id == id } }
    }

    /** 旧调用方兼容：完整配置流（平铺活动配置），无配置时返回空快照。 */
    val aiSettings: Flow<AiSettings> = activeProfile.map { it?.toSettings() ?: emptySettings() }

    /** 一次性读取当前活动配置快照。 */
    suspend fun currentSettings(): AiSettings = aiSettings.first()

    /**
     * 是否已完成可用配置：活动配置存在，且 providerId / baseUrl / apiKey / model 非空。
     * （方舟与自定义服务商的 model 必须用户手填，因此也是必填项。）
     */
    suspend fun isConfigured(): Boolean {
        val p = activeProfile.first() ?: return false
        return p.providerId.isNotBlank() &&
            p.baseUrl.isNotBlank() &&
            p.apiKey.isNotBlank() &&
            p.model.isNotBlank()
    }

    // ---- 写入 ----

    /**
     * 新增或更新一整套配置；不存在则追加到列表末尾并在没有活动配置时自动启用。
     * @param apiKey null 表示保持原值（编辑时未重新填写密钥）；空白字符串表示清空。
     * @return 落盘后的配置 id
     */
    suspend fun saveProfile(
        id: String?,
        providerId: String,
        displayName: String,
        baseUrl: String,
        apiKey: String?,
        model: String,
        protocol: String
    ): String {
        var savedId: String = id ?: newProfileId()
        dataStore.edit { prefs ->
            migrateLegacy(prefs)
            val ids = parseIds(prefs[KEY_PROFILE_IDS]).toMutableList()
            if (id == null || id !in ids) {
                // id 冲突（极小概率）时重新生成
                while (savedId in ids) savedId = newProfileId()
                ids.add(savedId)
            }
            prefs[KEY_PROFILE_IDS] = ids.joinToString(",")
            prefs[kProvider(savedId)] = providerId.trim()
            prefs[kDisplayName(savedId)] = displayName.trim()
            prefs[kBaseUrl(savedId)] = baseUrl.trim()
            prefs[kModel(savedId)] = model.trim()
            prefs[kProtocol(savedId)] = AiProtocol.fromRaw(protocol)
            when {
                apiKey == null -> Unit
                apiKey.isBlank() -> prefs.remove(kApiKey(savedId))
                else -> prefs[kApiKey(savedId)] = apiKey.trim()
            }
            if (prefs[KEY_ACTIVE_PROFILE_ID] == null ||
                prefs[KEY_ACTIVE_PROFILE_ID] !in ids
            ) {
                prefs[KEY_ACTIVE_PROFILE_ID] = savedId
            }
        }
        return savedId
    }

    /** 切换活动配置。 */
    suspend fun setActiveProfile(id: String) {
        dataStore.edit { prefs ->
            migrateLegacy(prefs)
            val ids = parseIds(prefs[KEY_PROFILE_IDS])
            if (id in ids) prefs[KEY_ACTIVE_PROFILE_ID] = id
        }
    }

    /** 删除一套配置；若删的是活动配置，自动切到剩余的第一套；全部删完则活动置空。 */
    suspend fun deleteProfile(id: String) {
        dataStore.edit { prefs ->
            migrateLegacy(prefs)
            val ids = parseIds(prefs[KEY_PROFILE_IDS]).toMutableList()
            if (id !in ids) return@edit
            ids.remove(id)
            prefs[KEY_PROFILE_IDS] = ids.joinToString(",")
            prefs.remove(kProvider(id))
            prefs.remove(kDisplayName(id))
            prefs.remove(kBaseUrl(id))
            prefs.remove(kApiKey(id))
            prefs.remove(kModel(id))
            prefs.remove(kProtocol(id))
            val active = prefs[KEY_ACTIVE_PROFILE_ID]
            if (active == null || active == id) {
                if (ids.isEmpty()) prefs.remove(KEY_ACTIVE_PROFILE_ID)
                else prefs[KEY_ACTIVE_PROFILE_ID] = ids.first()
            }
        }
    }

    /** 清空全部 AI 配置（所有服务商配置 / 密钥等，设置页删除数据使用）。 */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    /**
     * 旧版整体保存接口（R13 设置页）：写入活动配置；当前没有任何配置时新建并启用。
     * apiKey 传 null 表示保持原值不变；传空白字符串表示清空。
     * @return 保存到的配置 id
     */
    suspend fun save(
        providerId: String?,
        baseUrl: String,
        apiKey: String?,
        model: String,
        protocol: String,
        displayName: String?
    ): String {
        val active = activeProfile.first()
        return saveProfile(
            id = active?.id,
            providerId = providerId.orEmpty(),
            displayName = displayName.orEmpty(),
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            protocol = protocol
        )
    }

    // ---- 解析 / 迁移 ----

    private fun emptySettings() = AiSettings(
        providerId = null,
        baseUrl = "",
        apiKey = "",
        model = "",
        protocol = AiProtocol.OPENAI_COMPAT,
        displayName = null
    )

    private fun parseIds(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun readProfiles(prefs: Preferences): List<AiProfile> {
        val ids = parseIds(prefs[KEY_PROFILE_IDS])
        if (ids.isNotEmpty()) {
            return ids.mapNotNull { id -> readOne(prefs, id) }
        }
        // 旧单配置迁移（只读内存视图；任一次写入时会落盘并清旧键）
        val legacyProvider = prefs[KEY_PROVIDER_ID]
        if (!legacyProvider.isNullOrBlank()) {
            return listOf(
                AiProfile(
                    id = LEGACY_PROFILE_ID,
                    providerId = legacyProvider,
                    displayName = prefs[KEY_DISPLAY_NAME].orEmpty(),
                    baseUrl = prefs[KEY_BASE_URL].orEmpty(),
                    apiKey = prefs[KEY_API_KEY].orEmpty(),
                    model = prefs[KEY_MODEL].orEmpty(),
                    protocol = AiProtocol.fromRaw(prefs[KEY_PROTOCOL])
                )
            )
        }
        return emptyList()
    }

    private fun readOne(prefs: Preferences, id: String): AiProfile? {
        val provider = prefs[kProvider(id)] ?: return null
        return AiProfile(
            id = id,
            providerId = provider,
            displayName = prefs[kDisplayName(id)].orEmpty(),
            baseUrl = prefs[kBaseUrl(id)].orEmpty(),
            apiKey = prefs[kApiKey(id)].orEmpty(),
            model = prefs[kModel(id)].orEmpty(),
            protocol = AiProtocol.fromRaw(prefs[kProtocol(id)])
        )
    }

    private fun resolveActiveId(prefs: Preferences, ids: List<String>): String? {
        val active = prefs[KEY_ACTIVE_PROFILE_ID]
        if (active != null && active in ids) return active
        // 迁移态 / 活动丢失：默认首个（旧单配置即 LEGACY_PROFILE_ID）
        return ids.firstOrNull()
    }

    /** 在写事务内把旧单配置落盘为 [LEGACY_PROFILE_ID] 并清除旧键（仅执行一次）。 */
    private fun migrateLegacy(prefs: MutablePreferences) {
        if (prefs[KEY_PROFILE_IDS] != null) return
        val legacyProvider = prefs[KEY_PROVIDER_ID]
        if (legacyProvider.isNullOrBlank()) {
            // 没有可迁移内容，仅打上空标记，避免重复判断
            prefs[KEY_PROFILE_IDS] = ""
            return
        }
        prefs[kProvider(LEGACY_PROFILE_ID)] = legacyProvider
        prefs[KEY_BASE_URL]?.let { prefs[kBaseUrl(LEGACY_PROFILE_ID)] = it }
        prefs[KEY_API_KEY]?.let { prefs[kApiKey(LEGACY_PROFILE_ID)] = it }
        prefs[KEY_MODEL]?.let { prefs[kModel(LEGACY_PROFILE_ID)] = it }
        prefs[kProtocol(LEGACY_PROFILE_ID)] = AiProtocol.fromRaw(prefs[KEY_PROTOCOL])
        prefs[KEY_DISPLAY_NAME]?.let { prefs[kDisplayName(LEGACY_PROFILE_ID)] = it }
        prefs[KEY_PROFILE_IDS] = LEGACY_PROFILE_ID
        if (prefs[KEY_ACTIVE_PROFILE_ID] == null) {
            prefs[KEY_ACTIVE_PROFILE_ID] = LEGACY_PROFILE_ID
        }
        prefs.remove(KEY_PROVIDER_ID)
        prefs.remove(KEY_BASE_URL)
        prefs.remove(KEY_API_KEY)
        prefs.remove(KEY_MODEL)
        prefs.remove(KEY_PROTOCOL)
        prefs.remove(KEY_DISPLAY_NAME)
    }
}
