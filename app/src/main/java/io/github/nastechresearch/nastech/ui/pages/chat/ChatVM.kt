package io.github.nastechresearch.nastech.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.data.datastore.Settings
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.data.datastore.getCurrentAssistant
import io.github.nastechresearch.nastech.data.datastore.getCurrentChatModel
import io.github.nastechresearch.nastech.data.files.FilesManager
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryEngine
import io.github.nastechresearch.nastech.data.memory.ConversationMemoryRuntime
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.data.model.Avatar
import io.github.nastechresearch.nastech.data.model.Conversation
import io.github.nastechresearch.nastech.data.model.MessageNode
import io.github.nastechresearch.nastech.data.model.NodeFavoriteTarget
import io.github.nastechresearch.nastech.data.repository.ConversationRepository
import io.github.nastechresearch.nastech.data.repository.FavoriteRepository
import io.github.nastechresearch.nastech.service.ChatError
import io.github.nastechresearch.nastech.service.ChatService
import io.github.nastechresearch.nastech.ui.hooks.writeStringPreference
import io.github.nastechresearch.nastech.ui.hooks.ChatInputState
import io.github.nastechresearch.nastech.utils.UiState
import io.github.nastechresearch.nastech.utils.UpdateChecker
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlin.uuid.Uuid
import org.koin.core.context.GlobalContext

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false)

    val inputState = ChatInputState()

    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        chatService.addConversationReference(_conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(_conversationId)
        ConversationMemoryRuntime.clear(_conversationId.toString())
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow
    val mcpManager = chatService.mcpManager

    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar
        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) it.copy(chatModelId = model.id) else it
                    })
            }
        }
    }

    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        viewModelScope.launch(Dispatchers.Default) {
            val text = content
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()

            if (text.isNotEmpty()) {
                ConversationMemoryRuntime.register(_conversationId.toString(), text)
                try {
                    GlobalContext.get().get<ConversationMemoryEngine>()
                        .ingestUserMessage(_conversationId.toString(), text)
                } catch (_: Exception) {
                    // Memory is optional; a local memory failure must never block chat sending.
                }
            }

            chatService.sendMessage(_conversationId, content, answer)
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun queueMessage(content: List<UIMessagePart>): Boolean =
        chatService.queueMessage(_conversationId, content)

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return chatService.compressConversationAsync(
            conversationId = _conversationId,
            conversation = conversation.value,
            additionalPrompt = additionalPrompt,
            targetTokens = targetTokens,
            keepRecentMessages = keepRecentMessages,
        )
    }

    suspend fun forkMessage(message: UIMessage): Conversation =
        chatService.forkConversationAtMessage(_conversationId, message.id)

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_stop_generation_before_delete)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(message: UIMessage, regenerateAssistantMsg: Boolean = true) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        scope: ChatService.ApprovalScope = ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        chatService.handleToolApproval(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    fun handleToolAnswer(toolCallId: String, answer: String) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch { chatService.stopGeneration(_conversationId) }
    }

    fun saveConversationAsync() {
        viewModelScope.launch { chatService.saveConversation(_conversationId, conversation.value) }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value.copy(title = title))
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            try {
                GlobalContext.get().get<ConversationMemoryEngine>().clearMemory(conversation.id.toString())
            } catch (_: Exception) {
                // Keep deletion of the raw conversation independent from optional memory cleanup.
            }
            ConversationMemoryRuntime.clear(conversation.id.toString())
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch { conversationRepo.togglePinStatus(conversation.id) }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            io.github.nastechresearch.nastech.data.ai.tools.ToolApprovalAllowList.clearChat(conversation.id)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch { chatService.generateSuggestion(_conversationId, conversation) }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) { newConversation }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) existingNode.copy(isFavorite = !currentlyFavorited) else existingNode
                    }
                )
            }
        }
    }
}
