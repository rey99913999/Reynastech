package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_memory_summaries",
    indices = [Index(value = ["conversation_id", "level"], unique = true)]
)
data class ConversationMemorySummaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "level")
    val level: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "source_revision", defaultValue = "0")
    val sourceRevision: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
