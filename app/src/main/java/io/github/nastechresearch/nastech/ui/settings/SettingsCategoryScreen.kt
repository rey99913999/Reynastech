package io.github.nastechresearch.nastech.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.components.ui.CardGroup
import io.github.nastechresearch.nastech.ui.components.ui.CardGroupScope
import io.github.nastechresearch.nastech.ui.context.Navigator
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.joinQQGroup
import io.github.nastechresearch.nastech.utils.openUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Telegram
import me.rerere.hugeicons.stroke.Wrench01

private data class SettingsItem(
    val title: Int,
    val description: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val destination: Screen? = null,
    val action: (() -> Unit)? = null,
)

@Composable
fun SettingsCategoryScreen(category: SettingsCategoryId) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val items = categoryItems(category, context)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(categoryTitle(category))) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    items.forEach { entry ->
                        item(
                            onClick = {
                                entry.destination?.let(navController::navigate)
                                entry.action?.invoke()
                            },
                            leadingContent = { Icon(entry.icon, null) },
                            headlineContent = { Text(stringResource(entry.title)) },
                            supportingContent = { Text(stringResource(entry.description)) },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                        )
                    }
                }
            }
        }
    }
}

private fun categoryTitle(category: SettingsCategoryId): Int = when (category) {
    SettingsCategoryId.AI -> R.string.settings_category_ai
    SettingsCategoryId.AGENT_AUTOMATION -> R.string.settings_category_agent_automation
    SettingsCategoryId.APPEARANCE -> R.string.settings_category_appearance
    SettingsCategoryId.BEHAVIOR -> R.string.settings_category_behavior
    SettingsCategoryId.DEVICE_INTEGRATIONS -> R.string.settings_category_device_integrations
    SettingsCategoryId.DATA -> R.string.settings_category_data
    SettingsCategoryId.SYSTEM -> R.string.settings_category_system
}

private fun categoryItems(
    category: SettingsCategoryId,
    context: android.content.Context,
): List<SettingsItem> {
    fun nav(
        title: Int,
        desc: Int,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        destination: Screen,
    ) = SettingsItem(title, desc, icon, destination)

    fun action(
        title: Int,
        desc: Int,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        block: () -> Unit,
    ) = SettingsItem(title, desc, icon, action = block)

    return when (category) {
        SettingsCategoryId.AI -> listOf(
            nav(
                R.string.setting_page_default_model,
                R.string.setting_page_default_model_desc,
                HugeIcons.AiMagic,
                Screen.SettingModels,
            ),
            nav(
                R.string.setting_page_providers,
                R.string.setting_page_providers_desc,
                HugeIcons.Brain02,
                Screen.SettingProvider,
            ),
            nav(
                R.string.setting_page_search_service,
                R.string.setting_page_search_service_desc,
                HugeIcons.GlobalSearch,
                Screen.SettingSearch,
            ),
            nav(
                R.string.speech_page_title,
                R.string.speech_tab_tts,
                HugeIcons.Megaphone01,
                Screen.SettingSpeech,
            ),
            nav(
                R.string.setting_page_mcp,
                R.string.setting_page_mcp_desc,
                HugeIcons.McpServer,
                Screen.SettingMcp,
            ),
            nav(
                R.string.setting_page_sub_agents,
                R.string.setting_page_sub_agents_desc,
                HugeIcons.Robot01,
                Screen.SettingSubAgents,
            ),
        )

        SettingsCategoryId.AGENT_AUTOMATION -> listOf(
            nav(
                R.string.settings_nastech_agent,
                R.string.settings_nastech_agent_desc,
                HugeIcons.Robot01,
                Screen.AgentBridge,
            ),
            nav(
                R.string.setting_page_assistant,
                R.string.setting_page_assistant_desc,
                HugeIcons.LookTop,
                Screen.Assistant,
            ),
            nav(
                R.string.settings_skills,
                R.string.setting_page_extensions_desc,
                HugeIcons.Package,
                Screen.Extensions,
            ),
            nav(
                R.string.settings_plugins,
                R.string.settings_plugins_desc,
                HugeIcons.Package,
                Screen.PluginManager,
            ),
            nav(
                R.string.setting_page_workflows,
                R.string.setting_page_workflows_desc,
                HugeIcons.Connect,
                Screen.SettingWorkflows,
            ),
            nav(
                R.string.setting_page_scheduled_jobs,
                R.string.setting_page_scheduled_jobs_desc,
                HugeIcons.Clock02,
                Screen.SettingScheduledJobs,
            ),
            nav(
                R.string.settings_tasks,
                R.string.settings_tasks_desc,
                HugeIcons.Robot01,
                Screen.Tasks,
            ),
            nav(
                R.string.setting_page_tool_approvals,
                R.string.setting_page_tool_approvals_desc,
                HugeIcons.Shield01,
                Screen.SettingToolApprovals,
            ),
        )

        SettingsCategoryId.APPEARANCE -> listOf(
            nav(
                R.string.setting_page_theme_setting,
                R.string.setting_page_theme_setting_desc,
                HugeIcons.Sun01,
                Screen.SettingTheme,
            ),
            nav(
                R.string.settings_glass_material,
                R.string.settings_glass_material_desc,
                HugeIcons.Sun01,
                Screen.SettingGlassAppearance,
            ),
            nav(
                R.string.settings_chat_ui,
                R.string.setting_page_preferences_ui_desc,
                HugeIcons.SmartPhone01,
                Screen.SettingPreferencesUI,
            ),
        )

        SettingsCategoryId.BEHAVIOR -> listOf(
            nav(
                R.string.settings_chat_interaction,
                R.string.settings_chat_interaction_desc,
                HugeIcons.SmartPhone01,
                Screen.SettingPreferencesGeneral,
            ),
            nav(
                R.string.setting_page_notifications,
                R.string.setting_page_notifications_desc,
                HugeIcons.Telegram,
                Screen.SettingNotifications,
            ),
            nav(
                R.string.settings_notification_behavior,
                R.string.settings_notification_behavior_desc,
                HugeIcons.Telegram,
                Screen.SettingPreferencesNotification,
            ),
            nav(
                R.string.setting_page_accessibility,
                R.string.setting_page_accessibility_desc,
                HugeIcons.SmartPhone01,
                Screen.SettingAccessibility,
            ),
        )

        SettingsCategoryId.DEVICE_INTEGRATIONS -> listOf(
            nav(
                R.string.setting_page_browser,
                R.string.setting_page_browser_desc,
                HugeIcons.Earth,
                Screen.SettingBrowser,
            ),
            nav(
                R.string.setting_page_web_server,
                R.string.setting_page_web_server_desc,
                HugeIcons.ServerStack01,
                Screen.SettingWeb,
            ),
            nav(
                R.string.setting_page_termux,
                R.string.setting_page_termux_desc,
                HugeIcons.Console,
                Screen.SettingTermux,
            ),
            nav(
                R.string.setting_page_shizuku,
                R.string.setting_page_shizuku_desc,
                HugeIcons.Console,
                Screen.SettingShizuku,
            ),
            nav(
                R.string.setting_page_telegram,
                R.string.setting_page_telegram_desc,
                HugeIcons.Telegram,
                Screen.SettingTelegram,
            ),
        )

        SettingsCategoryId.DATA -> listOf(
            nav(
                R.string.setting_page_chat_storage,
                R.string.setting_page_chat_storage_desc,
                HugeIcons.Database02,
                Screen.SettingChatStorage,
            ),
            nav(
                R.string.settings_files,
                R.string.settings_files_desc,
                HugeIcons.File01,
                Screen.SettingFiles,
            ),
            nav(
                R.string.setting_page_data_backup,
                R.string.setting_page_data_backup_desc,
                HugeIcons.Database02,
                Screen.Backup,
            ),
        )

        SettingsCategoryId.SYSTEM -> listOf(
            nav(
                R.string.setting_page_permissions,
                R.string.setting_page_permissions_desc,
                HugeIcons.Shield01,
                Screen.SettingPermissions,
            ),
            nav(
                R.string.setting_page_doctor,
                R.string.setting_page_doctor_desc,
                HugeIcons.Wrench01,
                Screen.SettingDoctor,
            ),
            nav(
                R.string.settings_debug,
                R.string.settings_debug_desc,
                HugeIcons.Wrench01,
                Screen.Debug,
            ),
            nav(
                R.string.setting_page_request_logs,
                R.string.setting_page_request_logs_desc,
                HugeIcons.Database02,
                Screen.Log,
            ),
            nav(
                R.string.accessibility_developer_options,
                R.string.accessibility_developer_options,
                HugeIcons.Wrench01,
                Screen.Developer,
            ),
            nav(
                R.string.setting_page_about,
                R.string.setting_page_about_desc,
                HugeIcons.AiMagic,
                Screen.SettingAbout,
            ),
            action(
                R.string.settings_documentation,
                R.string.settings_documentation_desc,
                HugeIcons.Book01,
            ) {
                context.openUrl("https://nastechresearch.github.io/nastech/")
            },
            action(
                R.string.settings_discord,
                R.string.settings_discord_desc,
                HugeIcons.Telegram,
            ) {
                context.openUrl("https://discord.gg/9weBqxe5c4")
            },
            action(
                R.string.settings_qq_group_1,
                R.string.settings_qq_groups_desc,
                HugeIcons.Telegram,
            ) {
                context.joinQQGroup("4POE46u9e_zoy1TkNfWdCvueR9CKFJdk")
            },
            action(
                R.string.settings_qq_group_2,
                R.string.settings_qq_groups_desc,
                HugeIcons.Telegram,
            ) {
                context.joinQQGroup("Qsm0whzbPsm1UyNpR683ulLyMZ2Pqrw0")
            },
            action(
                R.string.settings_qq_group_3,
                R.string.settings_qq_groups_desc,
                HugeIcons.Telegram,
            ) {
                context.joinQQGroup("Qc9oP-9tXioZeQEvEvI2_owWtBAIx3lS")
            },
            action(
                R.string.settings_share,
                R.string.settings_share_desc,
                HugeIcons.Share04,
            ) {
                val shareText = context.getString(R.string.setting_page_share_text)
                val chooser = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                try {
                    context.startActivity(
                        Intent.createChooser(
                            chooser,
                            context.getString(R.string.setting_page_share),
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.setting_page_no_share_app),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}
