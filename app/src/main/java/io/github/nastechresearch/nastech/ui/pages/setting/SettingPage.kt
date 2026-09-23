package io.github.nastechresearch.nastech.ui.pages.setting

import androidx.compose.runtime.Composable
import org.koin.androidx.compose.koinViewModel
import io.github.nastechresearch.nastech.ui.settings.SettingsHomeScreen

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    SettingsHomeScreen(vm)
}
