package cn.sanxing.thrice.data.data.repository

import cn.sanxing.thrice.data.data.local.NoteDao
import cn.sanxing.thrice.data.data.local.NoteFolderDao
import cn.sanxing.thrice.data.data.local.NoteTagDao
import cn.sanxing.thrice.data.domain.model.Note
import cn.sanxing.thrice.data.domain.model.NoteFolder
import cn.sanxing.thrice.data.domain.model.NoteTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 随身记数据仓库：文件夹与笔记的增删改查 / 置顶 / 移动，以及笔记多标签。
 *
 * 列表顺序统一在本层组合：置顶优先 → sortOrder 升序 → updatedAt 降序 →
 * createdAt 降序 → title 升序（DAO 只负责取数，便于备份恢复时按原 id 批量落库）。
 */
@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val folderDao: NoteFolderDao,
    private val noteTagDao: NoteTagDao
) {

    // ---------------- 文件夹 ----------------

    fun observeFolders(): Flow<List<NoteFolder>> = folderDao.observeAll()

    suspend fun getFolders(): List<NoteFolder> = folderDao.getAll()

    suspend fun getFolder(id: Long): NoteFolder? = folderDao.getById(id)

    /** 新建文件夹；sortOrder 缺省追加到末尾。返回新 id。 */
    suspend fun createFolder(name: String, sortOrder: Int? = null): Long =
        folderDao.upsert(
            NoteFolder(
                name = name.trim().ifEmpty { "未命名" },
                sortOrder = sortOrder ?: (folderDao.maxSortOrder() + 1)
            )
        )

    suspend fun renameFolder(id: Long, name: String) {
        val current = folderDao.getById(id) ?: return
        folderDao.update(current.copy(name = name.trim().ifEmpty { current.name }))
    }

    suspend fun updateFolder(folder: NoteFolder) = folderDao.update(folder)

    /** 删除文件夹；其下笔记在同一事务内置为「未分类」（folderId = null）。 */
    suspend fun deleteFolder(id: Long) = folderDao.deleteFolderAndDetachNotes(id)

    // ---------------- 笔记 ----------------

    fun observeAllNotes(): Flow<List<Note>> = noteDao.observeAll().map { it.sortedWith(NOTE_ORDER) }

    fun observeNotesInFolder(folderId: Long): Flow<List<Note>> =
        noteDao.observeInFolder(folderId).map { it.sortedWith(NOTE_ORDER) }

    fun observeUncategorizedNotes(): Flow<List<Note>> =
        noteDao.observeUncategorized().map { it.sortedWith(NOTE_ORDER) }

    suspend fun getNote(id: Long): Note? = noteDao.getById(id)

    /** 新建空白笔记并返回新 id。 */
    suspend fun createNote(
        folderId: Long? = null,
        title: String = "",
        content: String = "",
        pinned: Boolean = false,
        sortOrder: Int = 0,
        fontKey: String? = null,
        fontSizeSp: Int? = null
    ): Long {
        val now = System.currentTimeMillis()
        return noteDao.insert(
            Note(
                folderId = folderId,
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
                pinned = pinned,
                sortOrder = sortOrder,
                fontKey = fontKey,
                fontSizeSp = fontSizeSp
            )
        )
    }

    /**
     * 保存笔记：id = 0 走插入，否则更新；更新时自动刷新 updatedAt（createdAt 保持不变）。
     * 返回笔记 id。
     */
    suspend fun saveNote(note: Note): Long =
        if (note.id == 0L) {
            val now = System.currentTimeMillis()
            noteDao.insert(note.copy(createdAt = now, updatedAt = now))
        } else {
            noteDao.update(note.copy(updatedAt = System.currentTimeMillis()))
            note.id
        }

    suspend fun setPinned(id: Long, pinned: Boolean) =
        noteDao.setPinned(id, pinned, System.currentTimeMillis())

    /** 移动笔记；folderId = null 表示移入「未分类」。 */
    suspend fun moveNote(id: Long, folderId: Long?) =
        noteDao.moveToFolder(id, folderId, System.currentTimeMillis())

    /**
     * 删除笔记。先清它的标签关联、再删笔记本身（顺序保证任何时刻都不会留下
     * 指向不存在笔记的悬挂关联）。
     */
    suspend fun deleteNote(id: Long) {
        noteTagDao.deleteRefsForNote(id)
        noteDao.deleteById(id)
    }

    // ---------------- 笔记标签（多对多） ----------------

    fun observeTags(): Flow<List<NoteTag>> = noteTagDao.observeAll()

    suspend fun getTags(): List<NoteTag> = noteTagDao.getAll()

    suspend fun getTag(id: Long): NoteTag? = noteTagDao.getById(id)

    /**
     * 新建标签并返回新 id。
     * 空名回落为「未命名标签」；重名由数据库唯一索引拒绝并抛出约束异常，调用方负责提示。
     */
    suspend fun createTag(
        name: String,
        colorArgb: Int? = null,
        sortOrder: Int? = null
    ): Long = noteTagDao.insert(
        NoteTag(
            name = name.trim().ifEmpty { "未命名标签" },
            colorArgb = colorArgb ?: 0xFF7E57C2.toInt(),
            sortOrder = sortOrder ?: 0
        )
    )

    /** 重命名标签；新名为空时保持原名不变。重名时唯一索引抛异常，由调用方处理。 */
    suspend fun renameTag(id: Long, name: String) {
        val current = noteTagDao.getById(id) ?: return
        noteTagDao.update(current.copy(name = name.trim().ifEmpty { current.name }))
    }

    suspend fun updateTag(tag: NoteTag) = noteTagDao.update(tag)

    /** 删除标签；同一事务内清掉全部笔记与它的关联。 */
    suspend fun deleteTag(id: Long) = noteTagDao.deleteTagAndRefs(id)

    /** 观察某篇笔记当前挂载的标签 id 集合。 */
    fun observeTagIdsForNote(noteId: Long): Flow<List<Long>> =
        noteTagDao.observeTagIdsForNote(noteId)

    /** 整体替换某篇笔记的标签（事务内先清后写）。 */
    suspend fun setNoteTags(noteId: Long, tagIds: List<Long>) =
        noteTagDao.replaceTagsForNote(noteId, tagIds.distinct())

    /** 观察挂了某个标签的全部笔记 id。 */
    fun observeNoteIdsForTag(tagId: Long): Flow<List<Long>> =
        noteTagDao.observeNoteIdsForTag(tagId)

    private companion object {
        val NOTE_ORDER = compareByDescending<Note> { it.pinned }
            .thenBy { it.sortOrder }
            .thenByDescending { it.updatedAt }
            .thenByDescending { it.createdAt }
            .thenBy { it.title }
    }
}
