package io.github.nastechresearch.nastech.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_memory_settings")
data class ConversationMemorySettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "mode", defaultValue = "OFF")
    val mode: String = "OFF",
    @ColumnInfo(name = "use_in_context", defaultValue = "1")
    val useInContext: Boolean = true,
    @ColumnInfo(name = "token_budget", defaultValue = "2000")
    val tokenBudget: Int = 2_000,
)
