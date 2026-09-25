package cn.sanxing.thrice.data.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 随身记文件夹。
 *
 * 不建外键约束：文件夹删除时由 [cn.sanxing.thrice.data.data.local.NoteFolderDao]
 * 在同一事务内把其笔记的 folderId 置空（归入「未分类」）。
 */
@Entity(tableName = "note_folders")
data class NoteFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 一篇随身记。
 *
 * @param folderId 所属文件夹；null = 未分类
 * @param content Markdown 子集纯文本
 * @param pinned 置顶（列表排序时优先）
 * @param sortOrder 同级手动排序权重，小者在前
 * @param fontKey 单篇字体覆盖（"00".."32"）；null = 跟随全局设置
 * @param fontSizeSp 单篇字号覆盖（sp）；null = 跟随全局设置
 */
@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["folderId"]),
        Index(value = ["updatedAt"]),
        Index(value = ["pinned"])
    ]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val fontKey: String? = null,
    val fontSizeSp: Int? = null
)

/**
 * 笔记标签（多对多：一篇笔记可挂多个标签，一个标签可属于多篇笔记）。
 *
 * 名称全局唯一（数据库唯一索引兜底，重名插入直接抛约束异常，由调用方提示）；
 * 不建外键，关联行的清理在 [cn.sanxing.thrice.data.data.local.NoteTagDao]
 * 的事务方法内显式完成。
 */
@Entity(tableName = "note_tags", indices = [Index(value = ["name"], unique = true)])
data class NoteTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB 颜色。 */
    val colorArgb: Int = 0xFF7E57C2.toInt(),
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 笔记-标签多对多关联表（复合主键，无外键）。
 * 两个方向各建普通索引，支撑「查笔记的标签」与「查标签下的笔记」。
 */
@Entity(
    tableName = "note_tag_cross_ref",
    primaryKeys = ["noteId", "tagId"],
    indices = [Index("tagId"), Index("noteId")]
)
data class NoteTagCrossRef(
    val noteId: Long,
    val tagId: Long
)
