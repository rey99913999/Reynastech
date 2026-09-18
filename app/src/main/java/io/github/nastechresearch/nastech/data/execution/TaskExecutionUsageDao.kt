package io.github.nastechresearch.nastech.data.execution

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskExecutionUsageDao {
    @Query("SELECT * FROM task_execution_usage WHERE taskId = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskExecutionUsageEntity?

    @Query("SELECT * FROM task_execution_usage WHERE taskId = :taskId LIMIT 1")
    fun observe(taskId: String): Flow<TaskExecutionUsageEntity?>

    @Upsert
    suspend fun upsert(entity: TaskExecutionUsageEntity)
}
