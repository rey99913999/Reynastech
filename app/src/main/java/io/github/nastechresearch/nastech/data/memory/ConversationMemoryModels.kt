package io.github.nastechresearch.nastech.data.memory

import kotlinx.serialization.Serializable
import kotlin.math.exp

@Serializable
enum class ConversationMemoryMode {
    OFF,
    ON,
    MANUAL_APPROVAL,
}

@Serializable
enum class MemoryType {
    FACT,
    DECISION,
    PREFERENCE,
    TASK,
    GOAL,
    CURRENT_STATE,
    CONSTRAINT,
    ENTITY,
    TEMPORARY,
    SUMMARY,
}

@Serializable
enum class MemoryImportance {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
enum class MemoryScope {
    CONVERSATION_WIDE,
    TASK_SPECIFIC,
    PROJECT_SPECIFIC,
    TEMPORARY,
}

@Serializable
enum class MemoryStatus {
    ACTIVE,
    SUPERSEDED,
    ARCHIVED,
}

@Serializable
enum class MemoryCandidateStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

@Serializable
enum class SummaryLevel {
    DETAILED,
    HIGH_LEVEL,
}

data class MemoryRetrievalWeights(
    val semanticRelevance: Double = 0.35,
    val keywordRelevance: Double = 0.20,
    val importance: Double = 0.15,
    val recency: Double = 0.15,
    val contextRelation: Double = 0.10,
    val usageFrequency: Double = 0.05,
)

data class MemoryRetrievalResult(
    val memoryId: Int,
    val score: Double,
    val memory: ConversationMemory,
)

@Serializable
data class ConversationMemory(
    val id: Int,
    val conversationId: String,
    val type: MemoryType,
    val content: String,
    val importance: MemoryImportance,
    val confidence: Double,
    val scope: MemoryScope,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
    val expiresAt: Long?,
    val sourceMessageId: String?,
    val status: MemoryStatus,
    val frozen: Boolean,
)

@Serializable
data class ConversationMemorySettings(
    val conversationId: String,
    val mode: ConversationMemoryMode = ConversationMemoryMode.OFF,
    val useInContext: Boolean = true,
    val tokenBudget: Int = 2_000,
)

@Serializable
data class CurrentConversationState(
    val conversationId: String,
    val goal: String = "",
    val completed: String = "",
    val currentProblem: String = "",
    val lastAction: String = "",
    val nextExpectedAction: String = "",
    val constraints: String = "",
    val updatedAt: Long = 0L,
)

data class ConversationMemoryCandidate(
    val id: Long,
    val conversationId: String,
    val type: MemoryType,
    val content: String,
    val importance: MemoryImportance,
    val confidence: Double,
    val scope: MemoryScope,
    val sourceMessageId: String?,
    val createdAt: Long,
)

internal fun MemoryImportance.weight(): Double = when (this) {
    MemoryImportance.CRITICAL -> 1.0
    MemoryImportance.HIGH -> 0.8
    MemoryImportance.MEDIUM -> 0.55
    MemoryImportance.LOW -> 0.25
}

internal fun recencyScore(updatedAt: Long, now: Long): Double {
    if (updatedAt <= 0L) return 0.0
    val ageDays = ((now - updatedAt).coerceAtLeast(0L) / 86_400_000.0)
    return exp(-ageDays / 30.0)
}

internal fun estimateTokens(text: String): Int = ((text.length + 3) / 4).coerceAtLeast(1)
