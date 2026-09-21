package io.github.nastechresearch.nastech.workflow.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "workflow_revisions",
    indices = [Index(value = ["workflowId", "createdAtMs"])],
)
data class WorkflowRevisionEntity(
    @androidx.room.PrimaryKey val revisionId: String,
    val workflowId: String,
    val sourceRunId: Long? = null,
    val createdAtMs: Long,
    val reason: String,
    val definitionJson: String,
    val status: String = "DRAFT",
)

@Dao
interface WorkflowRevisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WorkflowRevisionEntity)

    @Query("SELECT * FROM workflow_revisions WHERE revisionId = :revisionId LIMIT 1")
    suspend fun getById(revisionId: String): WorkflowRevisionEntity?

    @Query("SELECT * FROM workflow_revisions WHERE workflowId = :workflowId ORDER BY createdAtMs DESC")
    fun observeForWorkflow(workflowId: String): Flow<List<WorkflowRevisionEntity>>

    @Query("UPDATE workflow_revisions SET status = :status WHERE revisionId = :revisionId")
    suspend fun setStatus(revisionId: String, status: String): Int
}
