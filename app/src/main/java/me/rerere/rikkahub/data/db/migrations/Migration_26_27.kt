package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * v26 → v27：记忆分层。
 * - memoryentity 加 category 列（FACT/PREFERENCE/SESSION），默认 'FACT' 兼容存量数据。
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(26, 27)
        try {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN category TEXT NOT NULL DEFAULT 'FACT'")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
