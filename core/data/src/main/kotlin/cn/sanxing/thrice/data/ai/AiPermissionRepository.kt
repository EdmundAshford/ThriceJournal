package cn.sanxing.thrice.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 可访问的本地数据域。[key] 同时用于权限键名（ai_perm_<key>_read/write）与内部映射，
 * [label] 为中文域名、[labelEn] 为英文域名，用于 system prompt 与授权失败回执。
 */
enum class AiPermDomain(val key: String, val label: String, val labelEn: String) {
    COURSES("courses", "课程表", "Courses"),
    TASKS("tasks", "任务", "Tasks"),
    BILLS("bills", "账单", "Bills"),
    FOCUS("focus", "专注", "Focus"),
    SLEEP("sleep", "睡眠", "Sleep"),
    NOTES("notes", "随身记", "Notes"),
    SETTINGS("settings", "设置", "Settings");

    /** 按提示词语言（zh/en）取域名。 */
    fun label(lang: String): String = if (lang == AI_LANG_EN) labelEn else label

    companion object {
        fun fromKey(key: String?): AiPermDomain? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 七个数据域的 AI 授权矩阵快照（每域 read / write 各一个布尔，默认全 false）。
 *
 * 不可变值对象；内部以 "domain.read" / "domain.write" 为键，[AiPermissionRepository]
 * 负责与 DataStore 的布尔键互转。
 */
data class AiPermissions(
    private val flags: Map<String, Boolean> = emptyMap()
) {
    private fun flagKey(domain: AiPermDomain, write: Boolean): String =
        "${domain.key}.${if (write) "write" else "read"}"

    fun isAllowed(domain: AiPermDomain, write: Boolean): Boolean =
        flags[flagKey(domain, write)] ?: false

    /** 是否至少拥有一项授权（无任何授权时 AI 只能纯对话）。 */
    val anyPermission: Boolean
        get() = flags.values.any { it }

    /** 拥有读取权限的域（写权限不隐含读权限，展示时单独判断）。 */
    fun readableDomains(): List<AiPermDomain> =
        AiPermDomain.entries.filter { isAllowed(it, write = false) }

    /** 拥有修改权限的域。 */
    fun writableDomains(): List<AiPermDomain> =
        AiPermDomain.entries.filter { isAllowed(it, write = true) }

    companion object {
        /** 全量关闭的默认矩阵。 */
        val NONE = AiPermissions(emptyMap())
    }
}

/**
 * AI 数据授权矩阵仓库（与应用设置共用 "settings" DataStore，键名 ai_perm_ 前缀隔离）。
 *
 * 安全约束：
 * - 权限只能由用户在 UI 中显式开关，AI 工具自身无权修改本矩阵（settings 写工具白名单不含这些键）；
 * - 不输出日志。
 */
@Singleton
class AiPermissionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        fun readKey(domain: AiPermDomain) =
            booleanPreferencesKey("ai_perm_${domain.key}_read")

        fun writeKey(domain: AiPermDomain) =
            booleanPreferencesKey("ai_perm_${domain.key}_write")
    }

    /** 授权矩阵流（未写入的键默认 false）。 */
    fun permsFlow(): Flow<AiPermissions> = dataStore.data.map { prefs ->
        val flags = LinkedHashMap<String, Boolean>(AiPermDomain.entries.size * 2)
        AiPermDomain.entries.forEach { domain ->
            flags["${domain.key}.read"] = prefs[readKey(domain)] ?: false
            flags["${domain.key}.write"] = prefs[writeKey(domain)] ?: false
        }
        AiPermissions(flags)
    }

    /** 一次性读取当前矩阵。 */
    suspend fun current(): AiPermissions = permsFlow().first()

    /**
     * 设置单项权限。
     * @param write true = 修改权限，false = 读取权限
     */
    suspend fun setPermission(domain: AiPermDomain, write: Boolean, enabled: Boolean) {
        dataStore.edit { prefs ->
            val key = if (write) writeKey(domain) else readKey(domain)
            prefs[key] = enabled
        }
    }

    /** 一键开启 / 关闭全部 14 项权限。 */
    suspend fun setAll(enabled: Boolean) {
        dataStore.edit { prefs ->
            AiPermDomain.entries.forEach { domain ->
                prefs[readKey(domain)] = enabled
                prefs[writeKey(domain)] = enabled
            }
        }
    }

    /** 执行前的实时权限校验（写操作会再次读取最新值，不只依赖会话开始时的快照）。 */
    suspend fun isAllowed(domain: AiPermDomain, write: Boolean): Boolean =
        current().isAllowed(domain, write)
}
