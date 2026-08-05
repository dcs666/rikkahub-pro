package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * v24 → v25：新增 background_tasks 表（后台任务系统）。
 *
 * 原为 AutoMigration(from = 24, to = 25)，但 Room 编译期要求 to 版本 schema
 * （25.json）存在于 schemas 目录（仓库只提交到 24.json，25.json 由 CI 生成且
 * 未提交）；版本升到 26 后中间版本缺失 → KSP 编译失败。
 * 改手写 Migration 后 Room 不再要求 25.json（手写迁移不做编译期 schema 校验）。
 */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(24, 25)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS background_tasks (
                    id TEXT NOT NULL PRIMARY KEY,
                    type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    config TEXT NOT NULL,
                    result TEXT NOT NULL DEFAULT '',
                    conversation_id TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT NOT NULL DEFAULT '',
                    poll_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_background_tasks_status ON background_tasks(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_background_tasks_created_at ON background_tasks(created_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_background_tasks_conversation_id ON background_tasks(conversation_id)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
