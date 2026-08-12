package me.rerere.rikkahub.data.sync

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

private const val TAG = "DatabaseBackupUtils"

/**
 * [修复] WAL 模式数据库备份一致性工具。
 *
 * 问题：Room 使用 WRITE_AHEAD_LOGGING（WAL），运行中直接复制 .db + -wal + -shm：
 *  1. -shm 是共享内存索引（临时文件），不该备份/恢复——陈旧 SHM 会让 SQLite 误判索引状态；
 *  2. 写事务进行中复制 WAL 会得到不一致快照 → 恢复后损坏/丢数据。
 *
 * 修复：
 *  - 备份前尽力执行 `PRAGMA wal_checkpoint(TRUNCATE)`，成功后主库文件自包含（WAL 清空），
 *    此时只需备份主库；checkpoint 失败（连接繁忙）时由调用方兜底备份 WAL。
 *  - 恢复主库前删除本地陈旧的 -wal/-shm，避免干扰新库。
 */
internal object DatabaseBackupUtils {

    /**
     * 尽力执行 WAL checkpoint(TRUNCATE)。成功返回 true（主库自包含，WAL 已清空）。
     * 失败返回 false（调用方应兜底备份 WAL）。
     */
    fun checkpoint(dbFile: File): Boolean {
        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    cursor.moveToFirst()
                    // busy=0/1 都算 checkpoint 完成；2=busy 超时（未完成）
                    val busy = cursor.getInt(0)
                    if (busy != 0) {
                        Log.w(TAG, "checkpoint: busy=$busy (incomplete)")
                    }
                    busy == 0
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "checkpoint failed: ${e.message}", e)
        }.getOrDefault(false)
    }

    /** 删除数据库旁路文件（-wal/-shm），恢复主库前调用避免陈旧文件干扰。 */
    fun deleteSidecarFiles(dbFile: File) {
        listOf("${dbFile.path}-wal", "${dbFile.path}-shm").forEach { path ->
            runCatching {
                File(path).delete()
            }.onFailure { e ->
                Log.w(TAG, "delete sidecar failed: $path: ${e.message}", e)
            }
        }
    }

    /** 判断 WAL 文件是否非空（checkpoint 后应为 0 字节）。 */
    fun walHasContent(dbFile: File): Boolean {
        val wal = File("${dbFile.path}-wal")
        return wal.exists() && wal.length() > 0
    }
}
