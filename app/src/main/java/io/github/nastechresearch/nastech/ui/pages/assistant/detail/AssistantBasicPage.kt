package io.github.nastechresearch.nastech.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.data.db.entity.WorkspaceEntity
import io.github.nastechresearch.nastech.data.model.Assistant
import io.github.nastechresearch.nastech.ui.components.ai.ModelSelector
import io.github.nastechresearch.nastech.ui.components.ai.ModelListSheet
import io.github.nastechresearch.nastech.ui.components.ai.rememberModelListState
import io.github.nastechresearch.nastech.ui.components.ai.ReasoningButton
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.components.ui.FormItem
import io.github.nastechresearch.nastech.ui.components.ui.Select
import io.github.nastechresearch.nastech.ui.components.ui.TagsInput
import io.github.nastechresearch.nastech.ui.components.ui.UIAvatar
import io.github.nastechresearch.nastech.ui.hooks.heroAnimation
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import io.github.nastechresearch.nastech.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            defaultChatModelId = settings.chatModelId,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    defaultChatModelId: Uuid?,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    var strategistLimitText by remember(assistant.id) {
        mutableStateOf(assistant.strategistConsultationLimit?.toString() ?: "3")
    }

    val fallbackPickerState = rememberModelListState(
        modelId = null,
        providers = providers,
        type = ModelType.CHAT,
    )
    val availableChatModels = remember(providers) {
        providers.flatMap { provider ->
            provider.models.filter { it.type == ModelType.CHAT }
        }.distinctBy { it.id }
    }
    val activeModelId = assistant.chatModelId ?: defaultChatModelId
    val fallbackModels = assistant.modelFallbackIds
        .distinct()
        .mapNotNull { id -> availableChatModels.firstOrNull { it.id == id } }
        .filterNot { it.id == activeModelId }

    fun moveFallbackModel(modelId: Uuid, delta: Int) {
        vm.updateAssistant { current ->
            val currentModelId = current.chatModelId ?: defaultChatModelId
            val ids = current.modelFallbackIds.distinct()
                .filter { id -> availableChatModels.any { it.id == id } && id != currentModelId }
                .take(5)
                .toMutableList()
            val from = ids.indexOf(modelId)
            val to = from + delta
            if (from !in ids.indices || to !in ids.indices) {
                current.copy(modelFallbackIds = ids)
            } else {
                ids.add(to, ids.removeAt(from))
                current.copy(modelFallbackIds = ids.take(5))
            }
        }
    }
    var strategistPolicyText by remember(assistant.id) {
        mutableStateOf(assistant.strategistConsultationPolicy)
    }
    var strategistSystemPromptText by remember(assistant.id) {
        mutableStateOf(assistant.strategistSystemPrompt)
    }
    var strategistTemperatureText by remember(assistant.id) {
        mutableStateOf(assistant.strategistTemperature?.toString() ?: "0.2")
    }
    var strategistTopPText by remember(assistant.id) {
        mutableStateOf(assistant.strategistTopP?.toString() ?: "")
    }
    var strategistMaxTokensText by remember(assistant.id) {
        mutableStateOf(assistant.strategistMaxTokens?.toString() ?: "2048")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

                ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text(stringResource(R.string.assistant_page_fallback_chain_title)) },
                description = {
                    Text(stringResource(R.string.assistant_page_fallback_chain_desc))
                },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (fallbackModels.isEmpty()) {
                            Text(
                                text = stringResource(R.string.assistant_page_fallback_chain_empty),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        fallbackModels.forEachIndexed { index, fallbackModel ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "${index + 1}. ${fallbackModel.displayName.ifBlank { fallbackModel.modelId }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        enabled = index > 0,
                                        onClick = { moveFallbackModel(fallbackModel.id, -1) },
                                    ) {
                                        Text(stringResource(R.string.assistant_page_fallback_chain_move_up))
                                    }
                                    TextButton(
                                        enabled = index < fallbackModels.lastIndex,
                                        onClick = { moveFallbackModel(fallbackModel.id, 1) },
                                    ) {
                                        Text(stringResource(R.string.assistant_page_fallback_chain_move_down))
                                    }
                                    TextButton(
                                        onClick = {
                                            vm.updateAssistant { current ->
                                                current.copy(modelFallbackIds = current.modelFallbackIds.filterNot { it == fallbackModel.id })
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.assistant_page_fallback_chain_remove))
                                    }
                                }
                            }
                        }
                        TextButton(
                            enabled = fallbackModels.size < 5,
                            onClick = { fallbackPickerState.open() },
                        ) {
                            Text(stringResource(R.string.assistant_page_fallback_chain_add))
                        }
                        ModelListSheet(state = fallbackPickerState) { selectedModel ->
                            vm.updateAssistant { current ->
                                val currentModelId = current.chatModelId ?: defaultChatModelId
                                val normalized = current.modelFallbackIds.distinct()
                                    .filter { id -> availableChatModels.any { it.id == id } && id != currentModelId }
                                if (
                                    normalized.size >= 5 ||
                                    selectedModel.type != ModelType.CHAT ||
                                    selectedModel.id == currentModelId ||
                                    selectedModel.id in normalized
                                ) {
                                    current.copy(modelFallbackIds = normalized.take(5))
                                } else {
                                    current.copy(modelFallbackIds = (normalized + selectedModel.id).take(5))
                                }
                            }
                        }
                    }
                },
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = { Text("Strategist consultation") },
                description = {
                    Text("Let this Assistant's execution model consult a separately selected thinking model. Both models remain part of this Assistant.")
                },
                tail = {
                    Switch(
                        checked = assistant.strategistConsultationEnabled,
                        onCheckedChange = {
                            onUpdate(assistant.copy(strategistConsultationEnabled = it))
                        },
                    )
                },
            )
            if (assistant.strategistConsultationEnabled) {
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist model") },
                    description = { Text("Choose the model that will provide plans, analysis, and recommendations.") },
                    content = {
                        ModelSelector(
                            modelId = assistant.strategistModelId,
                            providers = providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            onSelect = { selected ->
                                onUpdate(
                                    assistant.copy(
                                        strategistModelId = selected.modelId
                                            .takeIf { it.isNotBlank() }
                                            ?.let { selected.id },
                                    ),
                                )
                            },
                        )
                        if (assistant.strategistModelId == null) {
                            Text(
                                "Select a strategist model to make consult_strategist available. The execution model must support tool calls, and the strategist provider must be enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
                if (!providers.any { provider ->
                        provider.enabled && provider.models.any { it.id == assistant.strategistModelId }
                    }) {
                    Text(
                        text = "Select a model from an enabled provider before using strategist consultation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Consultation limit") },
                    description = { Text("Maximum calls to the strategist per generation. Enter any non-negative whole number, or enable Unlimited.") },
                    content = {
                        androidx.compose.foundation.layout.Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Unlimited consultations")
                                Switch(
                                    checked = assistant.strategistConsultationLimit == null,
                                    onCheckedChange = { unlimited ->
                                        if (unlimited) {
                                            onUpdate(assistant.copy(strategistConsultationLimit = null))
                                        } else {
                                            val value = strategistLimitText.toLongOrNull()
                                                ?.takeIf { it >= 0L } ?: 3L
                                            strategistLimitText = value.toString()
                                            onUpdate(assistant.copy(strategistConsultationLimit = value))
                                        }
                                    },
                                )
                            }
                            OutlinedTextField(
                                value = strategistLimitText,
                                onValueChange = { text ->
                                    strategistLimitText = text
                                    text.toLongOrNull()?.takeIf { it >= 0L }?.let { value ->
                                        if (assistant.strategistConsultationLimit != null) {
                                            onUpdate(assistant.copy(strategistConsultationLimit = value))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = assistant.strategistConsultationLimit != null,
                                singleLine = true,
                                label = { Text("Number of consultations") },
                                isError = assistant.strategistConsultationLimit != null &&
                                    (strategistLimitText.toLongOrNull()?.let { it >= 0L } != true),
                            )
                        }
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("When should the execution model consult?") },
                    description = { Text("This policy is included in the tool instructions and can be written freely.") },
                    content = {
                        OutlinedTextField(
                            value = strategistPolicyText,
                            onValueChange = { text ->
                                strategistPolicyText = text
                                onUpdate(assistant.copy(strategistConsultationPolicy = text))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 10,
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist system instructions") },
                    description = { Text("Control how the thinking model analyzes problems and formats its advice.") },
                    content = {
                        OutlinedTextField(
                            value = strategistSystemPromptText,
                            onValueChange = { text ->
                                strategistSystemPromptText = text
                                onUpdate(assistant.copy(strategistSystemPrompt = text))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 10,
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist reasoning") },
                    content = {
                        ReasoningButton(
                            reasoningLevel = assistant.strategistReasoningLevel,
                            onUpdateReasoningLevel = { level ->
                                onUpdate(assistant.copy(strategistReasoningLevel = level))
                            },
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist temperature") },
                    description = { Text("Optional value from 0 to 2. Leave blank to use the provider/model default.") },
                    content = {
                        OutlinedTextField(
                            value = strategistTemperatureText,
                            onValueChange = { text ->
                                strategistTemperatureText = text
                                if (text.isBlank()) {
                                    onUpdate(assistant.copy(strategistTemperature = null))
                                } else {
                                    text.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { value ->
                                        onUpdate(assistant.copy(strategistTemperature = value))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = strategistTemperatureText.isNotBlank() &&
                                (strategistTemperatureText.toFloatOrNull()?.let { it in 0f..2f } != true),
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist top-p") },
                    description = { Text("Optional value from 0 to 1. Leave blank to use the provider/model default.") },
                    content = {
                        OutlinedTextField(
                            value = strategistTopPText,
                            onValueChange = { text ->
                                strategistTopPText = text
                                if (text.isBlank()) {
                                    onUpdate(assistant.copy(strategistTopP = null))
                                } else {
                                    text.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { value ->
                                        onUpdate(assistant.copy(strategistTopP = value))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = strategistTopPText.isNotBlank() &&
                                (strategistTopPText.toFloatOrNull()?.let { it in 0f..1f } != true),
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = { Text("Strategist max output tokens") },
                    description = { Text("Leave blank to use the provider/model default.") },
                    content = {
                        OutlinedTextField(
                            value = strategistMaxTokensText,
                            onValueChange = { text ->
                                strategistMaxTokensText = text
                                if (text.isBlank()) {
                                    onUpdate(assistant.copy(strategistMaxTokens = null))
                                } else {
                                    text.toIntOrNull()?.takeIf { it > 0 }?.let { value ->
                                        onUpdate(assistant.copy(strategistMaxTokens = value))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = strategistMaxTokensText.isNotBlank() &&
                                (strategistMaxTokensText.toIntOrNull()?.let { it > 0 } != true),
                        )
                    },
                )
                HorizontalDivider()
                Text(
                    "Context sent to the strategist",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Original user goal") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludeOriginalGoal,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludeOriginalGoal = it)) },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Current plan") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludeCurrentPlan,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludeCurrentPlan = it)) },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Completed work and observed results") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludeCompletedWork,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludeCompletedWork = it)) },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Current blocker") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludeBlocker,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludeBlocker = it)) },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Previous attempts") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludePreviousAttempts,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludePreviousAttempts = it)) },
                        )
                    },
                )
                FormItem(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    label = { Text("Additional context and tool results") },
                    tail = {
                        Switch(
                            checked = assistant.strategistIncludeAdditionalContext,
                            onCheckedChange = { onUpdate(assistant.copy(strategistIncludeAdditionalContext = it)) },
                        )
                    },
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_limit))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_desc),
                    )
                }
            ) {
                Slider(
                    value = assistant.contextMessageLimit.toFloat(),
                    onValueChange = { value ->
                        onUpdate(
                            assistant.copy(
                                contextMessageLimit = snapContextMessageLimit(value)
                            )
                        )
                    },
                    valueRange = 0f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (assistant.contextMessageLimit > 0) stringResource(
                        R.string.assistant_page_context_message_limit_count,
                        assistant.contextMessageLimit
                    ) else stringResource(R.string.assistant_page_context_message_limit_unlimited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )

                if (assistant.contextMessageLimit > 0) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_fast_path_router))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_fast_path_router_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.fastPathRouterEnabled,
                        onCheckedChange = {
                            onUpdate(assistant.copy(fastPathRouterEnabled = it))
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_gradient_background))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_gradient_background_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useGradientBackground,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useGradientBackground = it
                                )
                            )
                        }
                    )
                }
            )

            if (!assistant.useGradientBackground) {
                HorizontalDivider()

                BackgroundPicker(
                    modifier = Modifier.padding(8.dp),
                    background = assistant.background,
                    backgroundOpacity = assistant.backgroundOpacity,
                    onUpdate = { background ->
                        onUpdate(
                            assistant.copy(
                                background = background
                            )
                        )
                    }
                )
            }

            if (!assistant.useGradientBackground && assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacity * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/**
 * 上下文限制的最小有效值
 *
 * 低于此值时截断点几乎每轮都在移动, 提示词缓存命中率跌破 90%,
 * 且保留的上下文通常达不到可缓存的最小长度, 限制本身失去意义
 */
private const val MIN_CONTEXT_MESSAGE_LIMIT = 20

/**
 * 把滑块取值吸附到 0(不限制) 或不低于 [MIN_CONTEXT_MESSAGE_LIMIT] 的有效档位
 */
private fun snapContextMessageLimit(value: Float): Int {
    val raw = value.roundToInt()
    return when {
        raw < MIN_CONTEXT_MESSAGE_LIMIT / 2 -> 0
        raw < MIN_CONTEXT_MESSAGE_LIMIT -> MIN_CONTEXT_MESSAGE_LIMIT
        else -> raw
    }
}
