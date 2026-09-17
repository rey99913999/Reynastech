package io.github.nastechresearch.nastech.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryEngine
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryRuntime
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.data.model.InjectionPosition
import io.github.nastechresearch.nastech.data.model.Lorebook
import io.github.nastechresearch.nastech.data.model.PromptInjection
import io.github.nastechresearch.nastech.data.model.extractContextForMatching
import io.github.nastechresearch.nastech.data.model.isTriggered
import kotlin.uuid.Uuid
import org.koin.core.context.GlobalContext

/** Adds configured injections and, when enabled, selective conversation memory to provider input. */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val injected = transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
        )
        return try {
            appendConversationMemory(injected)
        } catch (_: Exception) {
            injected
        }
    }
}

private suspend fun appendConversationMemory(messages: List<UIMessage>): List<UIMessage> {
    val latestUserText = messages.lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        ?: return messages
    if (latestUserText.isBlank()) return messages

    val conversationId = ConversationMemoryRuntime.resolve(latestUserText) ?: return messages
    val engine = try {
        GlobalContext.get().get<ConversationMemoryEngine>()
    } catch (_: Exception) {
        return messages
    }
    val memoryContext = try {
        engine.buildContext(conversationId, latestUserText)
    } catch (_: Exception) {
        ""
    }
    if (memoryContext.isBlank()) return messages

    val result = messages.toMutableList()
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }
    if (systemIndex < 0) {
        result.add(0, UIMessage.system(memoryContext))
        return result
    }

    val system = result[systemIndex]
    val parts = system.parts.toMutableList()
    val lastTextIndex = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastTextIndex >= 0) {
        val part = parts[lastTextIndex] as UIMessagePart.Text
        parts[lastTextIndex] = part.copy(text = part.text + "\n\n" + memoryContext)
    } else {
        parts.add(UIMessagePart.Text(memoryContext))
    }
    result[systemIndex] = system.copy(parts = parts)
    return result
}

internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> {
    val injections = collectInjections(
        messages,
        assistant,
        modeInjections,
        lorebooks,
        conversationModeInjectionIds,
        conversationLorebookIds,
    )
    if (injections.isEmpty()) return messages
    return applyInjections(messages, injections.sortedByDescending { it.priority }.groupBy { it.position })
}

internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val result = mutableListOf<PromptInjection>()
    val modeIds = if (assistant.allowConversationPromptInjection) conversationModeInjectionIds else assistant.modeInjectionIds
    val loreIds = if (assistant.allowConversationPromptInjection) conversationLorebookIds else assistant.lorebookIds

    modeInjections.filter { it.enabled && it.id in modeIds }.forEach(result::add)

    val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
    lorebooks.filter { it.enabled && it.id in loreIds }.forEach { lorebook ->
        lorebook.entries.filter { entry ->
            entry.isTriggered(extractContextForMatching(nonSystemMessages, entry.scanDepth))
        }.forEach(result::add)
    }
    return result
}

internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>,
): List<UIMessage> {
    val result = messages.toMutableList()
    var systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    val before = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty().joinToString("\n") { it.content }
    val after = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty().joinToString("\n") { it.content }

    if (systemIndex >= 0) {
        val system = result[systemIndex]
        val parts = system.parts.toMutableList()
        val firstText = parts.indexOfFirst { it is UIMessagePart.Text }
        val lastText = parts.indexOfLast { it is UIMessagePart.Text }
        if (firstText < 0) {
            val combined = listOf(before, after).filter { it.isNotBlank() }.joinToString("\n")
            if (combined.isNotBlank()) parts.add(UIMessagePart.Text(combined))
        } else {
            if (before.isNotBlank()) {
                val part = parts[firstText] as UIMessagePart.Text
                parts[firstText] = part.copy(text = before + "\n" + part.text)
            }
            if (after.isNotBlank()) {
                val index = if (lastText >= 0) lastText else parts.lastIndex
                val part = parts[index] as UIMessagePart.Text
                parts[index] = part.copy(text = part.text + "\n" + after)
            }
        }
        result[systemIndex] = system.copy(parts = parts)
    } else {
        val combined = listOf(before, after).filter { it.isNotBlank() }.joinToString("\n")
        if (combined.isNotBlank()) {
            result.add(0, UIMessage.system(combined))
            systemIndex = 0
        }
    }

    val top = byPosition[InjectionPosition.TOP_OF_CHAT].orEmpty()
    if (top.isNotEmpty()) {
        var index = result.indexOfFirst { it.role == MessageRole.USER }.takeIf { it >= 0 } ?: result.size
        index = findSafeInsertIndex(result, index)
        createMergedInjectionMessages(top).forEach { result.add(index++, it) }
    }

    val bottom = byPosition[InjectionPosition.BOTTOM_OF_CHAT].orEmpty()
    if (bottom.isNotEmpty()) {
        var index = findSafeInsertIndex(result, (result.size - 1).coerceAtLeast(0))
        createMergedInjectionMessages(bottom).forEach { result.add(index++, it) }
    }

    byPosition[InjectionPosition.AT_DEPTH].orEmpty()
        .groupBy { it.injectDepth }
        .keys
        .sortedDescending()
        .forEach { depth ->
            var index = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            index = findSafeInsertIndex(result, index)
            createMergedInjectionMessages(byPosition[InjectionPosition.AT_DEPTH].orEmpty().filter { it.injectDepth == depth })
                .forEach { result.add(index++, it) }
        }

    return result
}

private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> =
    injections.groupBy { it.role }.map { (role, grouped) ->
        val content = grouped.joinToString("\n") { it.content }
        if (role == MessageRole.ASSISTANT) UIMessage.assistant(content) else UIMessage.user(content)
    }

internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)
    while (index > 0) {
        val previous = messages.getOrNull(index - 1)
        val current = messages.getOrNull(index)
        val unsafe = previous?.role == MessageRole.USER &&
            current?.role == MessageRole.ASSISTANT &&
            current.getTools().isNotEmpty()
        if (!unsafe) break
        index--
    }
    return index
}
