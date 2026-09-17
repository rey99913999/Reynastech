package io.github.nastechresearch.nastech.data.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived bridge between the chat entry point and input transformers.
 *
 * Transformers operate on provider-bound message copies and historically did not receive a
 * conversationId. The bridge keeps only the latest user text -> conversation mapping for a few
 * seconds, so the memory context can be resolved without changing the raw chat schema or the
 * existing transformer contract.
 */
object ConversationMemoryRuntime {
    private const val TTL_MS = 30_000L
    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(conversationId: String, userText: String) {
        prune()
        val text = userText.trim()
        if (text.isEmpty()) return
        entries[stableKey(text)] = Entry(conversationId, System.currentTimeMillis())
    }

    fun resolve(userText: String): String? {
        prune()
        return entries[stableKey(userText.trim())]?.conversationId
    }

    fun clear(conversationId: String) {
        entries.entries.removeIf { it.value.conversationId == conversationId }
    }

    private fun stableKey(text: String): String = text
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        entries.entries.removeIf { it.value.createdAt < cutoff }
    }

    private data class Entry(
        val conversationId: String,
        val createdAt: Long,
    )
}
