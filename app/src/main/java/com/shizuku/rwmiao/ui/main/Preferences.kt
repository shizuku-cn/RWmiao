package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_SYSTEM
import kotlinx.coroutines.launch

@Composable
internal fun Preferences(
    page: SettingsPage,
    settings: UiPreferences,
    listState: LazyListState,
    onSettings: (UiPreferences) -> Unit
) {
    val context = LocalContext.current
    val updateManager = remember(context) {
        GithubUpdateManager(context)
    }
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

    fun checkForUpdates() {
        if (updateState is UpdateUiState.Checking ||
            updateState is UpdateUiState.Downloading
        ) return
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = when (val result = updateManager.checkLatest()) {
                UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.update)
                is UpdateCheckResult.Error -> UpdateUiState.Failed(result.message)
            }
        }
    }

    LaunchedEffect(settings.autoCheckUpdate) {
        if (settings.autoCheckUpdate) checkForUpdates()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            ModuleHeader()
        }
        item {
            SectionCard(compact = true) {
                Column {
                    Text("主题模式", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeChoice(
                            "跟随系统",
                            UI_THEME_SYSTEM,
                            settings,
                            onSettings
                        )
                        ThemeChoice("浅色", UI_THEME_LIGHT, settings, onSettings)
                        ThemeChoice("深色", UI_THEME_DARK, settings, onSettings)
                    }
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "动态取色",
                    "使用系统动态颜色生成界面主题",
                    settings.dynamicColor,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    onSettings(settings.copy(dynamicColor = it))
                }
            }
        }
        item {
            UpdateSection(
                state = updateState,
                autoCheck = settings.autoCheckUpdate,
                onCheck = ::checkForUpdates,
                onInstall = { update ->
                    if (updateState !is UpdateUiState.Downloading) {
                        updateState = UpdateUiState.Downloading(update)
                        scope.launch {
                            val result = updateManager.downloadAndInstall(update)
                            if (result.isFailure) {
                                updateState = UpdateUiState.Failed(
                                    result.exceptionOrNull()?.message ?: "更新失败"
                                )
                            }
                        }
                    }
                },
                onAutoCheck = { onSettings(settings.copy(autoCheckUpdate = it)) }
            )
        }
    }
}

@Composable
private fun ModuleHeader() {
    val context = LocalContext.current
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(144.dp),
                contentAlignment = Alignment.Center
            ) {
                ModuleLogo(
                    contentDescription = "RW miao",
                    modifier = Modifier.size(136.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "RW  miao",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = {
                        openModuleLink(context, MODULE_QQ_GROUP_URL)
                    }) {
                        Text(
                            "Q",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    FilledTonalIconButton(onClick = {
                        openModuleLink(context, MODULE_GITHUB_URL)
                    }) {
                        Icon(Icons.github, contentDescription = "GitHub")
                    }
                }
            }
        }
    }
}

private sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    object UpToDate : UpdateUiState()
    data class Available(val update: UpdateInfo) : UpdateUiState()
    data class Downloading(val update: UpdateInfo) : UpdateUiState()
    data class Failed(val message: String) : UpdateUiState()
}

@Composable
private fun UpdateSection(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit,
    autoCheck: Boolean,
    onAutoCheck: (Boolean) -> Unit
) {
    SectionCard(compact = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "检查更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(
                        "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (state) {
                    UpdateUiState.Checking,
                    is UpdateUiState.Downloading -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    is UpdateUiState.Available -> FilledTonalButton(
                        onClick = { onInstall(state.update) }
                    ) { Text("立即更新") }
                    else -> IconButton(
                        onClick = onCheck,
                        enabled = state !is UpdateUiState.Checking &&
                            state !is UpdateUiState.Downloading
                    ) {
                        Icon(Icons.refresh, contentDescription = "检查更新")
                    }
                }
            }
            when (state) {
                UpdateUiState.UpToDate -> Text(
                    "已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UpdateUiState.Available -> Text(
                    "发现新版本 ${state.update.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                is UpdateUiState.Downloading -> Text(
                    "正在下载 ${state.update.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is UpdateUiState.Failed -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> Unit
            }
            SwitchSetting(
                "自动检查更新",
                "启动时自动检查是否有最新版本资源",
                autoCheck,
                onCheckedChange = onAutoCheck
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    label: String,
    value: Int,
    settings: UiPreferences,
    onSettings: (UiPreferences) -> Unit
) {
    FilterChip(
        selected = value == settings.themeMode,
        onClick = {
            onSettings(settings.copy(themeMode = value))
        },
        label = { Text(label) },
        leadingIcon = if (value == settings.themeMode) {
            {
                Icon(Icons.check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        } else null
    )
}
