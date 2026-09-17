package io.github.nastechresearch.nastech.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySettingsEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemoryStateEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySummaryEntity
import io.github.nastechresearch.nastech.data.db.entity.MemoryCandidateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMemorySettingsDao {
    @Query("SELECT * FROM conversation_memory_settings WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun get(conversationId: String): ConversationMemorySettingsEntity?

    @Upsert
    suspend fun upsert(settings: ConversationMemorySettingsEntity)
}

@Dao
interface ConversationMemoryStateDao {
    @Query("SELECT * FROM conversation_memory_state WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun get(conversationId: String): ConversationMemoryStateEntity?

    @Upsert
    suspend fun upsert(state: ConversationMemoryStateEntity)

    @Query("DELETE FROM conversation_memory_state WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)
}

@Dao
interface ConversationMemorySummaryDao {
    @Query("SELECT * FROM conversation_memory_summaries WHERE conversation_id = :conversationId ORDER BY level")
    suspend fun getAll(conversationId: String): List<ConversationMemorySummaryEntity>

    @Query("SELECT * FROM conversation_memory_summaries WHERE conversation_id = :conversationId ORDER BY level")
    fun observe(conversationId: String): Flow<List<ConversationMemorySummaryEntity>>

    @Upsert
    suspend fun upsert(summary: ConversationMemorySummaryEntity)

    @Query("DELETE FROM conversation_memory_summaries WHERE conversation_id = :conversationId")
    suspend fun delete(conversationId: String)
}

@Dao
interface MemoryCandidateDao {
    @Query("SELECT * FROM conversation_memory_candidates WHERE conversation_id = :conversationId AND status = 'PENDING' ORDER BY created_at DESC")
    fun observePending(conversationId: String): Flow<List<MemoryCandidateEntity>>

    @Query("SELECT * FROM conversation_memory_candidates WHERE conversation_id = :conversationId AND status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getPending(conversationId: String): List<MemoryCandidateEntity>

    @Query("SELECT * FROM conversation_memory_candidates WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): MemoryCandidateEntity?

    @Upsert
    suspend fun upsert(candidate: MemoryCandidateEntity)

    @Query("DELETE FROM conversation_memory_candidates WHERE conversation_id = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("UPDATE conversation_memory_candidates SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)
}
