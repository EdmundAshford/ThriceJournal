package cn.sanxing.thrice.data.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 随身记全局设置（DataStore Preferences）：
 * 正文字号 / 默认字体 / 单双列布局 / 排序字段 / 升降序。
 *
 * 单篇笔记的字体 / 字号覆盖存在 Room（Note.fontKey / Note.fontSizeSp），
 * null 表示「跟随全局设置」；全局字体再为 null 时跟随应用整体字体。
 */
@Singleton
class NotesSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        val KEY_NOTES_FONT_SIZE = intPreferencesKey("notes_font_size")       // 正文字号 sp，默认 15
        val KEY_NOTES_FONT_KEY = stringPreferencesKey("notes_font_key")      // 默认字体 "00".."32"；null = 跟随全局
        val KEY_NOTES_TWO_COLUMN = booleanPreferencesKey("notes_two_column") // 双列布局，默认 false
        val KEY_NOTES_SORT_FIELD = intPreferencesKey("notes_sort_field")     // 0 更新时间 / 1 创建时间 / 2 标题
        val KEY_NOTES_SORT_ASC = booleanPreferencesKey("notes_sort_asc")     // true 升序 / false 降序

        const val DEFAULT_FONT_SIZE = 15
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 24

        // 单篇字号覆盖范围（比全局设置上限略宽）
        const val NOTE_MAX_FONT_SIZE = 28

        const val SORT_FIELD_UPDATED = 0
        const val SORT_FIELD_CREATED = 1
        const val SORT_FIELD_TITLE = 2
    }

    /** 笔记正文字号（sp），默认 15。 */
    val fontSize: Flow<Int> = dataStore.data.map { it[KEY_NOTES_FONT_SIZE] ?: DEFAULT_FONT_SIZE }

    /** 笔记默认字体 key；null = 跟随应用全局字体。 */
    val fontKey: Flow<String?> = dataStore.data.map { it[KEY_NOTES_FONT_KEY] }

    /** 是否双列布局。 */
    val twoColumn: Flow<Boolean> = dataStore.data.map { it[KEY_NOTES_TWO_COLUMN] ?: false }

    /** 排序字段（见 SORT_FIELD_*）。 */
    val sortField: Flow<Int> = dataStore.data.map { it[KEY_NOTES_SORT_FIELD] ?: SORT_FIELD_UPDATED }

    /** 是否升序；置顶项始终排在最前，不受此设置影响。 */
    val sortAscending: Flow<Boolean> = dataStore.data.map { it[KEY_NOTES_SORT_ASC] ?: false }

    suspend fun setFontSize(value: Int) {
        dataStore.edit { it[KEY_NOTES_FONT_SIZE] = value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) }
    }

    /** 设为 null 表示跟随应用全局字体。 */
    suspend fun setFontKey(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_NOTES_FONT_KEY) else prefs[KEY_NOTES_FONT_KEY] = value
        }
    }

    suspend fun setTwoColumn(value: Boolean) {
        dataStore.edit { it[KEY_NOTES_TWO_COLUMN] = value }
    }

    suspend fun setSortField(value: Int) {
        dataStore.edit {
            it[KEY_NOTES_SORT_FIELD] = value.coerceIn(SORT_FIELD_UPDATED, SORT_FIELD_TITLE)
        }
    }

    suspend fun setSortAscending(value: Boolean) {
        dataStore.edit { it[KEY_NOTES_SORT_ASC] = value }
    }
}
