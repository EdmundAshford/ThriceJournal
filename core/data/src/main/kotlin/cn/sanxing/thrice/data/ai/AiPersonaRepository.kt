package cn.sanxing.thrice.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import cn.sanxing.thrice.data.data.repository.SettingsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 人设（系统提示词）仓库：内置默认人设 + 用户自建人设。
 *
 * 存储：复用应用设置 DataStore（"settings"，参与云备份），键结构：
 * - [KEY_PERSONA_IDS]：自建人设 id 顺序列表（CSV）；默认人设不入库
 * - 每套两字段：`pa_<id>_name` / `pa_<id>_content`
 * - [KEY_ACTIVE_PERSONA_ID]：当前启用的人设 id；缺省 / 失效时回落默认人设
 *
 * 默认人设随应用语言切换中英文（[defaultPersonaFor]），固定置于列表首位，
 * 只读、不可删改；用户自建人设内容按用户原文保存，不做翻译。
 */
@Singleton
class AiPersonaRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val settingsRepository: SettingsRepository
) {

    companion object {
        private val KEY_PERSONA_IDS = stringPreferencesKey("ai_persona_ids")
        private val KEY_ACTIVE_PERSONA_ID = stringPreferencesKey("ai_active_persona_id")

        private fun kName(id: String) = stringPreferencesKey("pa_${id}_name")
        private fun kContent(id: String) = stringPreferencesKey("pa_${id}_content")

        fun newPersonaId(): String = "a" + UUID.randomUUID().toString().take(8)
    }

    /** 提示词语言（zh/en），跟随应用内语言设置；system 跟随系统语言。 */
    val language: Flow<String> = settingsRepository.appLanguage
        .map { resolveAiLanguage(it) }
        .distinctUntilChanged()

    /** 全部人设：默认人设（随语言）恒在首位，其后按保存顺序排列自建人设。 */
    val personas: Flow<List<AiPersona>> =
        combine(language, dataStore.data) { lang, prefs ->
            buildList {
                add(defaultPersonaFor(lang))
                parseIds(prefs[KEY_PERSONA_IDS]).forEach { id ->
                    val name = prefs[kName(id)]
                    val content = prefs[kContent(id)]
                    if (name != null && content != null) {
                        add(AiPersona(id = id, name = name, content = content))
                    }
                }
            }
        }

    /** 当前启用人设 id；未选择或指向已删除项时为默认人设。 */
    val activePersonaId: Flow<String> = dataStore.data.map { prefs ->
        val active = prefs[KEY_ACTIVE_PERSONA_ID]
        val ids = parseIds(prefs[KEY_PERSONA_IDS])
        when {
            active == null -> AiPersona.DEFAULT_PERSONA_ID
            active == AiPersona.DEFAULT_PERSONA_ID -> active
            active in ids -> active
            else -> AiPersona.DEFAULT_PERSONA_ID
        }
    }

    /** 当前启用人设完整对象（默认人设随应用语言取中 / 英文版）。 */
    val activePersona: Flow<AiPersona> =
        combine(language, dataStore.data) { lang, prefs ->
            val ids = parseIds(prefs[KEY_PERSONA_IDS])
            val active = prefs[KEY_ACTIVE_PERSONA_ID]
            if (active != null && active in ids) {
                val name = prefs[kName(active)]
                val content = prefs[kContent(active)]
                if (name != null && content != null) {
                    return@combine AiPersona(id = active, name = name, content = content)
                }
            }
            defaultPersonaFor(lang)
        }

    /**
     * 新建或更新一套自建人设；不存在则追加到列表末尾。
     * @return 落盘后的人设 id
     */
    suspend fun savePersona(id: String?, name: String, content: String): String {
        val savedId = id ?: newPersonaId()
        dataStore.edit { prefs ->
            val ids = parseIds(prefs[KEY_PERSONA_IDS]).toMutableList()
            if (savedId !in ids) ids.add(savedId)
            prefs[KEY_PERSONA_IDS] = ids.joinToString(",")
            prefs[kName(savedId)] = name.trim()
            prefs[kContent(savedId)] = content.trim()
        }
        return savedId
    }

    /** 切换启用人设（默认人设或任一自建人设）。 */
    suspend fun setActive(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PERSONA_ID] = id
        }
    }

    /** 删除自建人设；若删的是当前启用项，回落到默认人设。默认人设忽略。 */
    suspend fun deletePersona(id: String) {
        if (id == AiPersona.DEFAULT_PERSONA_ID) return
        dataStore.edit { prefs ->
            val ids = parseIds(prefs[KEY_PERSONA_IDS]).toMutableList()
            if (id !in ids) return@edit
            ids.remove(id)
            prefs[KEY_PERSONA_IDS] = ids.joinToString(",")
            prefs.remove(kName(id))
            prefs.remove(kContent(id))
            if (prefs[KEY_ACTIVE_PERSONA_ID] == id) {
                prefs.remove(KEY_ACTIVE_PERSONA_ID)
            }
        }
    }

    /** 一次性读取当前提示词语言。 */
    suspend fun currentLanguage(): String = language.first()

    private fun parseIds(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
