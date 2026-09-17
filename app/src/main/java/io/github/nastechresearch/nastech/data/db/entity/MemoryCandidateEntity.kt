package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_memory_candidates",
    indices = [Index(value = ["conversation_id", "status"])]
)
data class MemoryCandidateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "importance")
    val importance: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "source_message_id")
    val sourceMessageId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "status", defaultValue = "PENDING")
    val status: String = "PENDING",
)
