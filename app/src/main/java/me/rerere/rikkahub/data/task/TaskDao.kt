package me.rerere.rikkahub.data.task

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM background_tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM background_tasks WHERE status IN ('pending', 'running') ORDER BY created_at ASC")
    suspend fun getActiveTasks(): List<TaskEntity>

    @Query("SELECT * FROM background_tasks WHERE status IN ('pending', 'running') ORDER BY created_at ASC")
    fun observeActiveTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM background_tasks ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentTasks(limit: Int = 20): List<TaskEntity>

    @Query("SELECT * FROM background_tasks ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentTasks(limit: Int = 20): Flow<List<TaskEntity>>

    @Query("SELECT * FROM background_tasks WHERE conversation_id = :conversationId ORDER BY created_at DESC")
    suspend fun getTasksOfConversation(conversationId: String): List<TaskEntity>

    /** [③ CI 历史] 最近完成的 CI 监控任务（内存过滤 repo/branch，用于成功率统计）。 */
    @Query("SELECT * FROM background_tasks WHERE type = 'ci_monitor' AND status = 'completed' ORDER BY created_at DESC LIMIT :limit")
    suspend fun getCompletedCITasks(limit: Int = 30): List<TaskEntity>

    /**
     * 条件取消单个任务：仅当仍处于活跃状态（pending/running）时生效。
     * [FIX] 无条件 UPDATE 会把已完成任务的 COMPLETED 状态覆盖成 CANCELLED。
     * 返回受影响行数（0 = 任务不存在或已是终态）。
     */
    @Query("UPDATE background_tasks SET status = 'cancelled', updated_at = :updatedAt WHERE id = :id AND status IN ('pending', 'running')")
    suspend fun cancelIfActive(id: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE background_tasks SET status = 'running', updated_at = :updatedAt WHERE id = :id AND status = 'pending'")
    suspend fun markRunningIfPending(id: String, updatedAt: Long = System.currentTimeMillis()): Int

    /**
     * 条件递增轮询计数：仅当任务仍处于活跃状态（pending/running）时生效。
     * [FIX] 轮询请求在途时 webhook 可能已完成任务（status → completed），
     * 若此时用全量 UPDATE 会把终态覆盖回 running 导致任务"复活"且永不完成。
     * 返回受影响行数（0 = 任务已不在活跃状态，调用方应放弃本轮）。
     */
    @Query("UPDATE background_tasks SET poll_count = poll_count + 1, updated_at = :updatedAt, status = 'running' WHERE id = :id AND status IN ('pending', 'running')")
    suspend fun incrementPollCountIfActive(id: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE background_tasks SET status = 'cancelled', updated_at = :updatedAt WHERE status IN ('pending', 'running')")
    suspend fun cancelAllActive(updatedAt: Long = System.currentTimeMillis())

    @Query(
        "DELETE FROM background_tasks WHERE " +
            "(status IN ('completed', 'failed', 'cancelled') AND updated_at < :terminalBefore) " +
            "OR (status IN ('pending', 'running') AND created_at < :activeBefore)"
    )
    suspend fun cleanupOld(terminalBefore: Long, activeBefore: Long)

    @Query("SELECT COUNT(*) FROM background_tasks WHERE status IN ('pending', 'running')")
    suspend fun countActive(): Int
}
