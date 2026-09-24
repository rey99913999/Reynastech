package io.github.nastechresearch.nastech.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.data.datastore.SettingsStore
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.plus
import org.koin.compose.koinInject

/**
 * Compatibility entry for the former global approval page.
 *
 * Update 04 deliberately removes this screen as an editable global permission store. It now
 * routes the user to the current Assistant's canonical Tools/Tool Permissions center.
 */
@Composable
fun SettingToolApprovalsPage(
    settingsStore: SettingsStore = koinInject(),
) {
    val navController = LocalNavController.current
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Tool Permissions") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Tool permissions are now independent for each Assistant. " +
                        "The old global permission store is no longer authoritative.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Button(
                    onClick = {
                        navController.navigate(
                            Screen.AssistantTools(settings.assistantId.toString())
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open current Assistant → Tools")
                }
            }
        }
    }
}
