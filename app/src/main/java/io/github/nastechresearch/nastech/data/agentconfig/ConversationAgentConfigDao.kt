package io.github.nastechresearch.nastech.data.agentconfig

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "conversation_agent_configs",
    indices = [Index(value = ["updated_at"])],
)
data class ConversationAgentConfigEntity(
    @PrimaryKey val conversation_id: String,
    val config_json: String,
    val updated_at: Long,
)

@Entity(
    tableName = "conversation_agent_templates",
    indices = [Index(value = ["name"], unique = true)],
)
data class ConversationAgentTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val config_json: String,
    val updated_at: Long,
)

@Dao
interface ConversationAgentConfigDao {
    @Query("SELECT * FROM conversation_agent_configs WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getConfig(conversationId: String): ConversationAgentConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(entity: ConversationAgentConfigEntity)

    @Query("DELETE FROM conversation_agent_configs WHERE conversation_id = :conversationId")
    suspend fun deleteConfig(conversationId: String)

    @Query("SELECT * FROM conversation_agent_templates ORDER BY name COLLATE NOCASE")
    fun observeTemplates(): Flow<List<ConversationAgentTemplateEntity>>

    @Query("SELECT * FROM conversation_agent_templates ORDER BY name COLLATE NOCASE")
    suspend fun getTemplates(): List<ConversationAgentTemplateEntity>

    @Query("SELECT * FROM conversation_agent_templates WHERE id = :templateId LIMIT 1")
    suspend fun getTemplate(templateId: String): ConversationAgentTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplate(entity: ConversationAgentTemplateEntity)

    @Query("DELETE FROM conversation_agent_templates WHERE id = :templateId")
    suspend fun deleteTemplate(templateId: String)
}
