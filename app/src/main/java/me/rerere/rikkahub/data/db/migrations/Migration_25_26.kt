package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * v25 → v26：为高频查询补索引。
 * - conversationentity.assistant_id：会话列表/搜索（WHERE assistant_id = ?）
 * - conversationentity.folder_id：文件夹筛选（WHERE folder_id = ?）
 * - memoryentity.assistant_id：记忆注入（每次生成都查）
 * 此前无索引 → 全表扫描；会话/记忆多时查询变慢。
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(25, 26)
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversationentity_assistant_id ON conversationentity(assistant_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversationentity_folder_id ON conversationentity(folder_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memoryentity_assistant_id ON memoryentity(assistant_id)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
