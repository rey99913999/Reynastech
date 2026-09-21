package io.github.nastechresearch.nastech.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.nastechresearch.nastech.data.db.entity.ConversationAgentConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationAgentConfigDao {
    @Query("SELECT * FROM conversation_agent_configs WHERE conversationId = :conversationId LIMIT 1")
    suspend fun get(conversationId: String): ConversationAgentConfigEntity?

    @Query("SELECT * FROM conversation_agent_configs WHERE conversationId = :conversationId LIMIT 1")
    fun observe(conversationId: String): Flow<ConversationAgentConfigEntity?>

    @Upsert
    suspend fun upsert(entity: ConversationAgentConfigEntity)

    @Query("DELETE FROM conversation_agent_configs WHERE conversationId = :conversationId")
    suspend fun delete(conversationId: String)
}
