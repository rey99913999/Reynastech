package io.github.nastechresearch.nastech.workflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowLearningDraftScreen(
    recordingId: String,
    vm: WorkflowsViewModel = koinViewModel(),
) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(recordingId) {
        json = vm.draftJson(recordingId).orEmpty()
        loading = false
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Workflow Draft") },
                navigationIcon = { BackButton() },
                scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Review before approval. The raw recording remains stored separately.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Source recording: " + recordingId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Text("Loading draft...")
            } else {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 620.dp),
                    value = json,
                    onValueChange = { json = it; error = null },
                    label = { Text("Editable Workflow JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = {
                        Text("Edit steps, trigger, conditions, verification, and fallbacks before approving.")
                    },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            vm.saveLearnedDraft(recordingId, json).fold(
                                onSuccess = { nav.popBackStack() },
                                onFailure = { error = it.message ?: "Invalid workflow draft" },
                            )
                        }
                    },
                ) {
                    Text("Save Draft")
                }
                Button(
                    onClick = {
                        scope.launch {
                            vm.approveLearnedDraft(recordingId, json).fold(
                                onSuccess = { nav.popBackStack() },
                                onFailure = { error = it.message ?: "Approval failed" },
                            )
                        }
                    },
                ) {
                    Text("Approve & Save")
                }
            }
        }
    }
}
