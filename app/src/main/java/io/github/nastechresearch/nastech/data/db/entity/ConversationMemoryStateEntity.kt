package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_memory_state")
data class ConversationMemoryStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "goal", defaultValue = "")
    val goal: String = "",
    @ColumnInfo(name = "completed", defaultValue = "")
    val completed: String = "",
    @ColumnInfo(name = "current_problem", defaultValue = "")
    val currentProblem: String = "",
    @ColumnInfo(name = "last_action", defaultValue = "")
    val lastAction: String = "",
    @ColumnInfo(name = "next_expected_action", defaultValue = "")
    val nextExpectedAction: String = "",
    @ColumnInfo(name = "constraints", defaultValue = "")
    val constraints: String = "",
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
