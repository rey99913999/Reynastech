package io.github.nastechresearch.nastech.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.nastechresearch.nastech.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDAO {
    // Legacy assistant/global memory API. Kept intact for existing assistants.
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    // Conversation-scoped memory engine API.
    @Query("SELECT * FROM memoryentity WHERE conversation_id = :conversationId AND status = 'ACTIVE' ORDER BY updated_at DESC")
    suspend fun getActiveByConversation(conversationId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE conversation_id = :conversationId AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > :now) ORDER BY updated_at DESC")
    suspend fun getNonExpiredActiveByConversation(conversationId: String, now: Long): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE conversation_id = :conversationId AND id = :id LIMIT 1")
    suspend fun getConversationMemory(conversationId: String, id: Int): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memoryentity WHERE conversation_id = :conversationId AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > :now)")
    suspend fun countActiveByConversation(conversationId: String, now: Long): Int

    @Query("DELETE FROM memoryentity WHERE conversation_id = :conversationId")
    suspend fun deleteConversationMemories(conversationId: String)
}
