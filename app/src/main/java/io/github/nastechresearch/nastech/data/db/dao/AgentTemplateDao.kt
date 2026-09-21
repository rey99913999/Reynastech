package io.github.nastechresearch.nastech.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.nastechresearch.nastech.data.db.entity.AgentTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTemplateDao {
    @Query("SELECT * FROM agent_templates ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AgentTemplateEntity>>

    @Query("SELECT * FROM agent_templates ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<AgentTemplateEntity>

    @Query("SELECT * FROM agent_templates WHERE templateId = :templateId LIMIT 1")
    suspend fun getById(templateId: String): AgentTemplateEntity?

    @Upsert
    suspend fun upsert(entity: AgentTemplateEntity)

    @Query("DELETE FROM agent_templates WHERE templateId = :templateId")
    suspend fun delete(templateId: String)
}
