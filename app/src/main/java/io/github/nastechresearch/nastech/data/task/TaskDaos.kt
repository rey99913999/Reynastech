package io.github.nastechresearch.nastech.data.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId LIMIT 1")
    fun observeById(taskId: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY updatedAt DESC")
    suspend fun listByStatus(status: String): List<TaskEntity>

    @Query(
        "SELECT * FROM tasks WHERE conversationId = :conversationId " +
            "AND status IN ('RUNNING', 'WAITING_FOR_USER', 'PAUSED') " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun latestActiveForConversation(conversationId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)

    @Query("DELETE FROM tasks WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String): Int
}

@Dao
interface TaskStepDao {
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY orderIndex ASC")
    fun observeForTask(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY orderIndex ASC")
    suspend fun listForTask(taskId: String): List<TaskStepEntity>

    @Query("SELECT * FROM task_steps WHERE id = :stepId LIMIT 1")
    suspend fun getById(stepId: String): TaskStepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TaskStepEntity>)

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)
}

@Dao
interface TaskCheckpointDao {
    @Query("SELECT * FROM task_checkpoints WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: String): Flow<List<TaskCheckpointEntity>>

    @Query("SELECT * FROM task_checkpoints WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForTask(taskId: String): TaskCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TaskCheckpointEntity)
}

@Dao
interface TaskAuditLogDao {
    @Query("SELECT * FROM task_audit_logs WHERE taskId = :taskId ORDER BY timestamp DESC LIMIT :limit")
    fun observeForTask(taskId: String, limit: Int = 200): Flow<List<TaskAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TaskAuditLogEntity)
}

@Dao
interface TaskSettingsDao {
    @Query("SELECT * FROM task_settings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun get(conversationId: String): TaskSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskSettingsEntity)
}
