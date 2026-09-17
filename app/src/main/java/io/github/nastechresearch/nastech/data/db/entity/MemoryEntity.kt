package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["conversation_id", "status", "updated_at"]),
        Index(value = ["conversation_id", "type"]),
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("type", defaultValue = "FACT")
    val type: String = "FACT",
    @ColumnInfo("importance", defaultValue = "MEDIUM")
    val importance: String = "MEDIUM",
    @ColumnInfo("confidence", defaultValue = "1.0")
    val confidence: Double = 1.0,
    @ColumnInfo("scope", defaultValue = "CONVERSATION_WIDE")
    val scope: String = "CONVERSATION_WIDE",
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
    @ColumnInfo("last_used_at")
    val lastUsedAt: Long? = null,
    @ColumnInfo("expires_at")
    val expiresAt: Long? = null,
    @ColumnInfo("source_message_id")
    val sourceMessageId: String? = null,
    @ColumnInfo("status", defaultValue = "ACTIVE")
    val status: String = "ACTIVE",
    @ColumnInfo("frozen", defaultValue = "0")
    val frozen: Boolean = false,
)
