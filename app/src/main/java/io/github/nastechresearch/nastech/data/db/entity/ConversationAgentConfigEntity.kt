package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.Entity

@Entity(tableName = "conversation_agent_configs")
data class ConversationAgentConfigEntity(
    @androidx.room.PrimaryKey
    val conversationId: String,
    val configJson: String,
    val updatedAt: Long,
)
