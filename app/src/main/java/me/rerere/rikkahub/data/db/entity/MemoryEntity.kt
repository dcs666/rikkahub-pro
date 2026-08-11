package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    indices = [
        androidx.room.Index(value = ["assistant_id"]),
    ]
)
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    // [M1] 记忆分层：FACT（事实）/ PREFERENCE（偏好）/ SESSION（会话临时）。
    // 注入时按层加权（FACT 优先全量，SESSION 最低优先级）。
    @ColumnInfo("category")
    val category: String = MemoryCategory.FACT.name,
)

/** 记忆分层枚举：FACT 事实 > PREFERENCE 偏好 > SESSION 会话临时。 */
enum class MemoryCategory {
    FACT, PREFERENCE, SESSION;
}
