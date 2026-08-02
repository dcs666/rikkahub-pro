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

    @Query("UPDATE background_tasks SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE background_tasks SET status = 'cancelled', updated_at = :updatedAt WHERE status IN ('pending', 'running')")
    suspend fun cancelAllActive(updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM background_tasks WHERE status IN ('completed', 'failed', 'cancelled') AND updated_at < :before")
    suspend fun cleanupOld(before: Long)

    @Query("SELECT COUNT(*) FROM background_tasks WHERE status IN ('pending', 'running')")
    suspend fun countActive(): Int
}
