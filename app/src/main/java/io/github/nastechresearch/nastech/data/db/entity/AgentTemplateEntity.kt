package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.Entity

@Entity(tableName = "agent_templates")
data class AgentTemplateEntity(
    @androidx.room.PrimaryKey
    val templateId: String,
    val name: String,
    val description: String,
    val configJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
