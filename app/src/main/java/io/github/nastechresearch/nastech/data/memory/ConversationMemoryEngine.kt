package io.github.nastechresearch.nastech.data.memory

import androidx.room.withTransaction
import io.github.nastechresearch.nastech.data.db.AppDatabase
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySettingsEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemoryStateEntity
import io.github.nastechresearch.nastech.data.db.entity.ConversationMemorySummaryEntity
import io.github.nastechresearch.nastech.data.db.entity.MemoryCandidateEntity
import io.github.nastechresearch.nastech.data.db.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.max

/**
 * Local, deterministic memory engine for one conversation at a time.
 *
 * This class intentionally does not invoke an LLM. Candidate extraction is conservative and
 * rule-based; later updates can replace individual extractors without changing storage or
 * retrieval APIs. Raw conversation history remains authoritative and untouched.
 */
class ConversationMemoryEngine(
    private val database: AppDatabase,
) {
    private val memoryDao get() = database.memoryDao()
    private val settingsDao get() = database.conversationMemorySettingsDao()
    private val stateDao get() = database.conversationMemoryStateDao()
    private val summaryDao get() = database.conversationMemorySummaryDao()
    private val candidateDao get() = database.memoryCandidateDao()

    suspend fun getSettings(conversationId: String): ConversationMemorySettings {
        val entity = settingsDao.get(conversationId)
        return entity?.toModel() ?: ConversationMemorySettings(conversationId = conversationId)
    }

    suspend fun setSettings(settings: ConversationMemorySettings) {
        settingsDao.upsert(settings.toEntity())
    }

    suspend fun setMode(conversationId: String, mode: ConversationMemoryMode) {
        setSettings(getSettings(conversationId).copy(mode = mode))
    }

    suspend fun setUseInContext(conversationId: String, enabled: Boolean) {
        setSettings(getSettings(conversationId).copy(useInContext = enabled))
    }

    suspend fun setTokenBudget(conversationId: String, tokenBudget: Int) {
        setSettings(getSettings(conversationId).copy(tokenBudget = tokenBudget.coerceIn(256, 16_000)))
    }

    suspend fun ingestUserMessage(
        conversationId: String,
        text: String,
        sourceMessageId: String? = null,
    ) {
        val settings = getSettings(conversationId)
        if (settings.mode == ConversationMemoryMode.OFF) return

        expireTemporaryMemories(conversationId)
        val candidates = extractCandidates(conversationId, text, sourceMessageId)
        if (candidates.isEmpty()) return

        when (settings.mode) {
            ConversationMemoryMode.MANUAL_APPROVAL -> candidates.forEach { candidate ->
                candidateDao.upsert(candidate.toEntity())
            }
            ConversationMemoryMode.ON -> candidates.forEach { candidate ->
                applyCandidate(candidate)
            }
            ConversationMemoryMode.OFF -> Unit
        }
        updateSummaries(conversationId)
    }

    suspend fun approveCandidate(conversationId: String, candidateId: Long): ConversationMemory? {
        val candidate = candidateDao.get(candidateId) ?: return null
        if (candidate.conversationId != conversationId || candidate.status != "PENDING") return null
        applyCandidate(candidate.toCandidate())
        candidateDao.setStatus(candidateId, "APPROVED")
        updateSummaries(conversationId)
        return findSimilar(conversationId, candidate.content).firstOrNull()
    }

    suspend fun rejectCandidate(conversationId: String, candidateId: Long) {
        val candidate = candidateDao.get(candidateId) ?: return
        if (candidate.conversationId == conversationId) {
            candidateDao.setStatus(candidateId, "REJECTED")
        }
    }

    fun observePendingCandidates(conversationId: String): Flow<List<ConversationMemoryCandidate>> =
        candidateDao.observePending(conversationId).let { flow ->
            kotlinx.coroutines.flow.map(flow) { list -> list.map { it.toCandidate() } }
        }

    suspend fun memorySearch(
        conversationId: String,
        query: String,
        tokenBudget: Int? = null,
    ): List<ConversationMemory> = retrieve(conversationId, query, tokenBudget).map { it.memory }

    suspend fun memoryGet(conversationId: String, memoryId: Int): ConversationMemory? {
        val entity = memoryDao.getConversationMemory(conversationId, memoryId) ?: return null
        return entity.toConversationMemory()
    }

    suspend fun memoryCreate(
        conversationId: String,
        content: String,
        type: MemoryType = MemoryType.FACT,
        importance: MemoryImportance = MemoryImportance.MEDIUM,
        scope: MemoryScope = MemoryScope.CONVERSATION_WIDE,
        confidence: Double = 0.75,
        sourceMessageId: String? = null,
        expiresAt: Long? = null,
    ): ConversationMemory {
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(
            assistantId = "conversation:$conversationId",
            content = content.trim(),
            conversationId = conversationId,
            type = type.name,
            importance = importance.name,
            confidence = confidence.coerceIn(0.0, 1.0),
            scope = scope.name,
            createdAt = now,
            updatedAt = now,
            expiresAt = expiresAt,
            sourceMessageId = sourceMessageId,
            status = MemoryStatus.ACTIVE.name,
        )
        val id = memoryDao.insertMemory(entity).toInt()
        return entity.copy(id = id).toConversationMemory()
    }

    suspend fun memoryUpdate(
        conversationId: String,
        memoryId: Int,
        content: String,
        importance: MemoryImportance? = null,
        confidence: Double? = null,
        expiresAt: Long? = null,
    ): ConversationMemory? {
        val old = memoryDao.getConversationMemory(conversationId, memoryId) ?: return null
        if (old.frozen) return old.toConversationMemory()
        val updated = old.copy(
            content = content.trim(),
            importance = importance?.name ?: old.importance,
            confidence = confidence?.coerceIn(0.0, 1.0) ?: old.confidence,
            expiresAt = expiresAt ?: old.expiresAt,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDao.updateMemory(updated)
        return updated.toConversationMemory()
    }

    suspend fun memoryDelete(conversationId: String, memoryId: Int): Boolean {
        val memory = memoryDao.getConversationMemory(conversationId, memoryId) ?: return false
        if (memory.frozen) return false
        memoryDao.deleteMemory(memoryId)
        return true
    }

    suspend fun clearMemory(conversationId: String) {
        database.withTransaction {
            memoryDao.deleteConversationMemories(conversationId)
            candidateDao.deleteForConversation(conversationId)
            stateDao.delete(conversationId)
            summaryDao.delete(conversationId)
        }
    }

    suspend fun freezeMemory(conversationId: String, memoryId: Int, frozen: Boolean = true): Boolean {
        val memory = memoryDao.getConversationMemory(conversationId, memoryId) ?: return false
        memoryDao.updateMemory(memory.copy(frozen = frozen, updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun getCurrentState(conversationId: String): CurrentConversationState =
        stateDao.get(conversationId)?.toModel()
            ?: CurrentConversationState(conversationId = conversationId)

    suspend fun updateCurrentState(
        conversationId: String,
        state: CurrentConversationState,
    ) {
        stateDao.upsert(
            ConversationMemoryStateEntity(
                conversationId = conversationId,
                goal = state.goal,
                completed = state.completed,
                currentProblem = state.currentProblem,
                lastAction = state.lastAction,
                nextExpectedAction = state.nextExpectedAction,
                constraints = state.constraints,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun getMemoryCount(conversationId: String): Int =
        memoryDao.countActiveByConversation(conversationId, System.currentTimeMillis())

    suspend fun buildContext(
        conversationId: String,
        query: String,
        tokenBudget: Int? = null,
    ): String {
        val settings = getSettings(conversationId)
        if (settings.mode == ConversationMemoryMode.OFF || !settings.useInContext) return ""

        val budget = tokenBudget ?: settings.tokenBudget
        if (budget <= 0) return ""

        val currentState = getCurrentState(conversationId)
        val summaries = summaryDao.getAll(conversationId)
        val results = retrieve(conversationId, query, budget)

        val blocks = mutableListOf<String>()
        var used = 0

        fun addBlock(label: String, text: String, force: Boolean = false) {
            if (text.isBlank()) return
            val block = "$label\n$text"
            val cost = estimateTokens(block)
            if (force || used + cost <= budget) {
                blocks += block
                used += cost
            }
        }

        val current = buildString {
            if (currentState.goal.isNotBlank()) append("Goal: ${currentState.goal}\n")
            if (currentState.completed.isNotBlank()) append("Completed: ${currentState.completed}\n")
            if (currentState.currentProblem.isNotBlank()) append("Current Problem: ${currentState.currentProblem}\n")
            if (currentState.lastAction.isNotBlank()) append("Last Action: ${currentState.lastAction}\n")
            if (currentState.nextExpectedAction.isNotBlank()) append("Next Expected Action: ${currentState.nextExpectedAction}\n")
            if (currentState.constraints.isNotBlank()) append("Constraints: ${currentState.constraints}")
        }
        addBlock("CURRENT_STATE", current, force = true)

        results.forEach { result ->
            val relevance = result.score
            val mustInclude = result.memory.importance == MemoryImportance.CRITICAL && relevance >= 0.25
            addBlock("${result.memory.type.name} [${result.memory.id}]", result.memory.content, force = mustInclude)
        }

        summaries.sortedBy { it.level }.forEach { summary ->
            addBlock("SUMMARY_${summary.level}", summary.content)
        }

        if (blocks.isEmpty()) return ""
        val output = blocks.joinToString("\n")
        return "<conversation_memory>\n$output\n</conversation_memory>"
    }

    suspend fun exportMemory(conversationId: String): String {
        val export = MemoryExport(
            conversationId = conversationId,
            settings = getSettings(conversationId),
            memories = memoryDao.getActiveByConversation(conversationId).map { it.toConversationMemory() },
            currentState = getCurrentState(conversationId),
            summaries = summaryDao.getAll(conversationId).map {
                MemoryExportSummary(it.level, it.content, it.sourceRevision, it.updatedAt)
            },
        )
        return Json { prettyPrint = true }.encodeToString(export)
    }

    private suspend fun applyCandidate(candidate: ConversationMemoryCandidate) {
        val existing = findSimilar(candidate.conversationId, candidate.content)
        val contradiction = findContradiction(existing, candidate)

        if (contradiction != null) {
            // Never silently destroy the old record. Explicit replacement language can supersede
            // an unfrozen old record; otherwise both records remain active for user review.
            if (isExplicitReplacement(candidate.content) && !contradiction.frozen) {
                memoryDao.updateMemory(
                    contradiction.copy(
                        status = MemoryStatus.SUPERSEDED.name,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }

        val closest = existing.firstOrNull { !isContradictory(it, candidate) }
        if (closest != null) {
            if (closest.frozen) return
            val merged = mergeContent(closest.content, candidate.content)
            memoryDao.updateMemory(
                closest.copy(
                    content = merged,
                    type = candidate.type.name,
                    importance = maxImportance(closest.importance, candidate.importance),
                    confidence = maxOf(closest.confidence, candidate.confidence),
                    scope = candidate.scope.name,
                    updatedAt = System.currentTimeMillis(),
                    sourceMessageId = candidate.sourceMessageId ?: closest.sourceMessageId,
                )
            )
            return
        }

        memoryCreate(
            conversationId = candidate.conversationId,
            content = candidate.content,
            type = candidate.type,
            importance = candidate.importance,
            scope = candidate.scope,
            confidence = candidate.confidence,
            sourceMessageId = candidate.sourceMessageId,
            expiresAt = if (candidate.type == MemoryType.TEMPORARY) {
                System.currentTimeMillis() + 24 * 60 * 60 * 1000L
            } else null,
        )
    }

    private suspend fun findSimilar(conversationId: String, content: String): List<MemoryEntity> {
        val tokens = normalizedTokens(content)
        if (tokens.isEmpty()) return emptyList()
        return memoryDao.getNonExpiredActiveByConversation(conversationId, System.currentTimeMillis())
            .mapNotNull { entity ->
                val overlap = jaccard(tokens, normalizedTokens(entity.content))
                entity.takeIf { overlap >= 0.45 }
            }
            .sortedByDescending { jaccard(tokens, normalizedTokens(it.content)) }
    }

    private fun findContradiction(
        existing: List<MemoryEntity>,
        candidate: ConversationMemoryCandidate,
    ): MemoryEntity? = existing.firstOrNull { isContradictory(it, candidate) }

    private fun isContradictory(existing: MemoryEntity, candidate: ConversationMemoryCandidate): Boolean {
        if (existing.type != candidate.type.name) return false
        val old = existing.content.lowercase(Locale.ROOT)
        val newer = candidate.content.lowercase(Locale.ROOT)
        val negativeShift = (old.contains("لا") || old.contains("don't") || old.contains("not")) xor
            (newer.contains("لا") || newer.contains("don't") || newer.contains("not"))
        val sameSubject = jaccard(normalizedTokens(old), normalizedTokens(newer)) >= 0.28
        return sameSubject && negativeShift
    }

    private fun extractCandidates(
        conversationId: String,
        rawText: String,
        sourceMessageId: String?,
    ): List<ConversationMemoryCandidate> {
        val text = rawText.trim().replace(Regex("\\s+"), " ")
        if (text.length < 12 || isLowInformation(text)) return emptyList()

        val now = System.currentTimeMillis()
        val candidates = mutableListOf<ConversationMemoryCandidate>()

        fun add(type: MemoryType, importance: MemoryImportance, confidence: Double, scope: MemoryScope = MemoryScope.CONVERSATION_WIDE) {
            candidates += ConversationMemoryCandidate(
                id = 0L,
                conversationId = conversationId,
                type = type,
                content = text,
                importance = importance,
                confidence = confidence,
                scope = scope,
                sourceMessageId = sourceMessageId,
                createdAt = now,
            )
        }

        val lower = text.lowercase(Locale.ROOT)
        when {
            containsAny(lower, "قررنا", "قررنا أن", "اخترنا", "سنعتمد", "اعتمدنا", "we decided", "we chose", "chosen") ->
                add(MemoryType.DECISION, MemoryImportance.HIGH, 0.96)
            containsAny(lower, "أفضل", "افضل", "لا أريد", "لا اريد", "أرغب", "ارغب", "أفضل أن", "i prefer", "i don't want", "my preference") ->
                add(MemoryType.PREFERENCE, MemoryImportance.HIGH, 0.95)
            containsAny(lower, "المشكلة الحالية", "المشكلة الآن", "المشكله الحاليه", "current issue", "current problem", "currently") ->
                add(MemoryType.CURRENT_STATE, MemoryImportance.HIGH, 0.94)
            containsAny(lower, "الهدف", "هدفنا", "أريد أن", "اريد ان", "نريد أن", "نريد ان", "goal", "objective") ->
                add(MemoryType.GOAL, MemoryImportance.HIGH, 0.92)
            containsAny(lower, "يجب", "ممنوع", "لا يمكن", "لا نستطيع", "must", "required", "constraint", "can't", "cannot") ->
                add(MemoryType.CONSTRAINT, MemoryImportance.HIGH, 0.93)
            containsAny(lower, "المشروع يستخدم", "المشروع مبني", "project uses", "built with", "uses kotlin", "uses java") ->
                add(MemoryType.FACT, MemoryImportance.MEDIUM, 0.96)
            containsAny(lower, "مؤقت", "لهذه المحاولة", "هذه المرة", "temporarily", "for this attempt", "this time") ->
                add(MemoryType.TEMPORARY, MemoryImportance.MEDIUM, 0.88, MemoryScope.TEMPORARY)
            containsAny(lower, "مهمة", "task", "todo", "سوف أنجز", "سننجز") ->
                add(MemoryType.TASK, MemoryImportance.MEDIUM, 0.89, MemoryScope.TASK_SPECIFIC)
            else -> {
                // Facts stated with a clear first-person/project assertion are useful even when
                // they do not match the more specific patterns above.
                if (Regex("^(أنا|انا|المشروع|هذا المشروع|this project|i am|i use|i work)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add(MemoryType.FACT, MemoryImportance.MEDIUM, 0.82)
                }
            }
        }
        return candidates.distinctBy { it.type to normalize(text) }
    }

    private suspend fun expireTemporaryMemories(conversationId: String) {
        val now = System.currentTimeMillis()
        memoryDao.getActiveByConversation(conversationId)
            .filter { it.expiresAt != null && it.expiresAt <= now && !it.frozen }
            .forEach { memoryDao.updateMemory(it.copy(status = MemoryStatus.ARCHIVED.name, updatedAt = now)) }
    }

    private suspend fun updateSummaries(conversationId: String) {
        val memories = memoryDao.getNonExpiredActiveByConversation(conversationId, System.currentTimeMillis())
            .sortedWith(compareByDescending<MemoryEntity> { importanceWeight(it.importance) }.thenByDescending { it.updatedAt })
        val details = memories.take(18).joinToString("\n") { "- ${it.content}" }.take(6_000)
        val highLevel = memories.take(8).joinToString("; ") { it.content }.take(2_000)
        val now = System.currentTimeMillis()
        summaryDao.upsert(
            ConversationMemorySummaryEntity(
                conversationId = conversationId,
                level = SummaryLevel.DETAILED.name,
                content = details,
                sourceRevision = memories.maxOfOrNull { it.updatedAt } ?: 0L,
                updatedAt = now,
            )
        )
        summaryDao.upsert(
            ConversationMemorySummaryEntity(
                conversationId = conversationId,
                level = SummaryLevel.HIGH_LEVEL.name,
                content = highLevel,
                sourceRevision = memories.maxOfOrNull { it.updatedAt } ?: 0L,
                updatedAt = now,
            )
        )
    }

    private suspend fun retrieve(
        conversationId: String,
        query: String,
        tokenBudget: Int?,
    ): List<MemoryRetrievalResult> {
        val now = System.currentTimeMillis()
        val memories = memoryDao.getNonExpiredActiveByConversation(conversationId, now)
        if (memories.isEmpty()) return emptyList()

        val queryTokens = normalizedTokens(query)
        val weights = MemoryRetrievalWeights()
        val scored = memories.map { entity ->
            val keyword = jaccard(queryTokens, normalizedTokens(entity.content))
            val semantic = keyword
            val importance = importanceWeight(entity.importance)
            val recency = recencyScore(entity.updatedAt, now)
            val contextRelation = relationScore(query, entity.content)
            val usage = recencyScore(entity.lastUsedAt ?: entity.updatedAt, now)
            val score = semantic * weights.semanticRelevance +
                keyword * weights.keywordRelevance +
                importance * weights.importance +
                recency * weights.recency +
                contextRelation * weights.contextRelation +
                usage * weights.usageFrequency
            MemoryRetrievalResult(entity.id, score, entity.toConversationMemory())
        }.sortedByDescending { it.score }

        val budget = (tokenBudget ?: 2_000).coerceAtLeast(128)
        var used = 0
        val selected = mutableListOf<MemoryRetrievalResult>()
        for (result in scored) {
            val cost = estimateTokens(result.memory.content)
            val relevant = result.score >= 0.25
            val mustInclude = result.memory.importance == MemoryImportance.CRITICAL && relevant
            if (mustInclude || used + cost <= budget) {
                if (used + cost <= budget) {
                    selected += result
                    used += cost
                }
            }
        }

        selected.forEach { result ->
            memoryDao.getConversationMemory(conversationId, result.memoryId)?.let { entity ->
                memoryDao.updateMemory(entity.copy(lastUsedAt = now))
            }
        }
        return selected
    }

    private fun isExplicitReplacement(text: String): Boolean = containsAny(
        text.lowercase(Locale.ROOT),
        "بدلاً من", "بدلا من", "استبدل", "غير ذلك", "replace", "instead of", "switch to"
    )

    private fun mergeContent(old: String, newer: String): String {
        if (normalize(old) == normalize(newer)) return old
        return listOf(old.trim(), newer.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
            .take(1_500)
    }

    private fun normalizedTokens(text: String): Set<String> = normalize(text)
        .split(' ')
        .filter { it.length > 1 }
        .toSet()

    private fun normalize(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun relationScore(query: String, memory: String): Double {
        val q = query.lowercase(Locale.ROOT)
        val m = memory.lowercase(Locale.ROOT)
        return when {
            q.isNotBlank() && m.contains(q.take(32)) -> 1.0
            q.split(' ').any { it.length > 3 && m.contains(it) } -> 0.5
            else -> 0.0
        }
    }

    private fun importanceWeight(raw: String): Double = runCatching {
        MemoryImportance.valueOf(raw).weight()
    }.getOrDefault(0.25)

    private fun maxImportance(a: String, b: MemoryImportance): String {
        val current = runCatching { MemoryImportance.valueOf(a) }.getOrDefault(MemoryImportance.LOW)
        return if (current.weight() >= b.weight()) current.name else b.name
    }

    private fun isLowInformation(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.length < 12) return true
        val casual = setOf("هههه", "haha", "lol", "ok", "okay", "نعم", "لا", "تمام", "شكرا", "thanks")
        return normalized in casual
    }

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any { text.contains(it) }

    private fun MemoryEntity.toConversationMemory(): ConversationMemory = ConversationMemory(
        id = id,
        conversationId = conversationId.orEmpty(),
        type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.FACT),
        content = content,
        importance = runCatching { MemoryImportance.valueOf(importance) }.getOrDefault(MemoryImportance.MEDIUM),
        confidence = confidence.coerceIn(0.0, 1.0),
        scope = runCatching { MemoryScope.valueOf(scope) }.getOrDefault(MemoryScope.CONVERSATION_WIDE),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastUsedAt = lastUsedAt,
        expiresAt = expiresAt,
        sourceMessageId = sourceMessageId,
        status = runCatching { MemoryStatus.valueOf(status) }.getOrDefault(MemoryStatus.ACTIVE),
        frozen = frozen,
    )

    private fun ConversationMemorySettings.toEntity() = ConversationMemorySettingsEntity(
        conversationId = conversationId,
        mode = mode.name,
        useInContext = useInContext,
        tokenBudget = tokenBudget,
    )

    private fun ConversationMemorySettingsEntity.toModel() = ConversationMemorySettings(
        conversationId = conversationId,
        mode = runCatching { ConversationMemoryMode.valueOf(mode) }.getOrDefault(ConversationMemoryMode.OFF),
        useInContext = useInContext,
        tokenBudget = tokenBudget.coerceIn(256, 16_000),
    )

    private fun ConversationMemoryStateEntity.toModel() = CurrentConversationState(
        conversationId = conversationId,
        goal = goal,
        completed = completed,
        currentProblem = currentProblem,
        lastAction = lastAction,
        nextExpectedAction = nextExpectedAction,
        constraints = constraints,
        updatedAt = updatedAt,
    )

    private fun ConversationMemoryCandidate.toEntity() = MemoryCandidateEntity(
        conversationId = conversationId,
        type = type.name,
        content = content,
        importance = importance.name,
        confidence = confidence,
        scope = scope.name,
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
        status = MemoryCandidateStatus.PENDING.name,
    )

    private fun MemoryCandidateEntity.toCandidate() = ConversationMemoryCandidate(
        id = id,
        conversationId = conversationId,
        type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.FACT),
        content = content,
        importance = runCatching { MemoryImportance.valueOf(importance) }.getOrDefault(MemoryImportance.MEDIUM),
        confidence = confidence,
        scope = runCatching { MemoryScope.valueOf(scope) }.getOrDefault(MemoryScope.CONVERSATION_WIDE),
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
    )

    @Serializable
    private data class MemoryExport(
        val conversationId: String,
        val settings: ConversationMemorySettings,
        val memories: List<ConversationMemory>,
        val currentState: CurrentConversationState,
        val summaries: List<MemoryExportSummary>,
    )

    @Serializable
    private data class MemoryExportSummary(
        val level: String,
        val content: String,
        val sourceRevision: Long,
        val updatedAt: Long,
    )
}
