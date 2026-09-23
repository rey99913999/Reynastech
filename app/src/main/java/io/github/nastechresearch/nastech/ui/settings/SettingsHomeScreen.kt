package io.github.nastechresearch.nastech.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nastechresearch.nastech.R
import io.github.nastechresearch.nastech.Screen
import io.github.nastechresearch.nastech.data.datastore.isNotConfigured
import io.github.nastechresearch.nastech.ui.components.nav.BackButton
import io.github.nastechresearch.nastech.ui.components.ui.CardGroup
import io.github.nastechresearch.nastech.ui.components.ui.CardGroupScope
import io.github.nastechresearch.nastech.ui.components.ui.Select
import io.github.nastechresearch.nastech.ui.context.LocalNavController
import io.github.nastechresearch.nastech.ui.hooks.rememberColorMode
import io.github.nastechresearch.nastech.ui.pages.setting.SettingVM
import io.github.nastechresearch.nastech.ui.theme.ColorMode
import io.github.nastechresearch.nastech.ui.theme.CustomColors
import io.github.nastechresearch.nastech.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Robot01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Wrench01

@Composable
fun SettingsHomeScreen(vm: SettingVM = org.koin.androidx.compose.koinViewModel()) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var colorMode by rememberColorMode()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
            if (settings.isNotConfigured()) {
                item {
                    Card(
                        modifier = Modifier.padding(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_config_api_title))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_config_api_desc))
                            },
                            leadingContent = { Icon(HugeIcons.Alert01, null) },
                            trailingContent = {
                                TextButton(
                                    onClick = { navController.navigate(Screen.SettingProvider) }
                                ) {
                                    Text(stringResource(R.string.setting_page_config))
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.settings_quick_controls)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Sun01, null) },
                        headlineContent = {
                            Text(stringResource(R.string.settings_theme_mode))
                        },
                        supportingContent = {
                            Text(
                                when (colorMode) {
                                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                }
                            )
                        },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = { colorMode = it },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    settingCategoryItem(
                        title = R.string.settings_category_ai,
                        subtitle = R.string.settings_category_ai_desc,
                        icon = HugeIcons.AiMagic,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.AI)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_agent_automation,
                        subtitle = R.string.settings_category_agent_automation_desc,
                        icon = HugeIcons.Robot01,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.AGENT_AUTOMATION)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_appearance,
                        subtitle = R.string.settings_category_appearance_desc,
                        icon = HugeIcons.Sun01,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.APPEARANCE)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_behavior,
                        subtitle = R.string.settings_category_behavior_desc,
                        icon = HugeIcons.SmartPhone01,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.BEHAVIOR)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_device_integrations,
                        subtitle = R.string.settings_category_device_integrations_desc,
                        icon = HugeIcons.ServerStack01,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.DEVICE_INTEGRATIONS)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_data,
                        subtitle = R.string.settings_category_data_desc,
                        icon = HugeIcons.Database02,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.DATA)
                            )
                        },
                    )
                    settingCategoryItem(
                        title = R.string.settings_category_system,
                        subtitle = R.string.settings_category_system_desc,
                        icon = HugeIcons.Wrench01,
                        onClick = {
                            navController.navigate(
                                Screen.SettingsCategory(SettingsCategoryId.SYSTEM)
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun CardGroupScope.settingCategoryItem(
    title: Int,
    subtitle: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    item(
        onClick = onClick,
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(subtitle)) },
    )
}
