package com.shizuku.rwmiao.ui.main

import android.os.Handler
import android.os.Looper
import com.shizuku.rwmiao.ui.support.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_SYSTEM
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_BLUE
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DEFAULT
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DYNAMIC
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_GREEN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_ORANGE
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_PINK
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_RED
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_CYAN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_YELLOW
import com.shizuku.rwmiao.config.SettingsContract.KEY_SELECTION_ACTION_SCALE_MASK
import com.shizuku.rwmiao.config.SettingsContract.KEY_SHOW_GAME_DURATION
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_UPDATE_IGNORED_VERSION
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_ALL
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_DESELECT
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_FREE_BUILD
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_GUARD
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_LINE
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_MOTHER_RALLY
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_PATROL
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_RANGE
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_SCRIPTS
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_SEGMENT
import com.shizuku.rwmiao.config.SettingsContract.SELECTION_ACTION_SCALE_SMART_PATH
import com.shizuku.rwmiao.config.SettingsContract.KEY_VOLUME_ACTION
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_DEVICE_ID
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_INTEGRITY
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_PACKAGE_NAME
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_PLAYER_NAME
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM
import com.shizuku.rwmiao.config.SettingsContract.KEY_NETWORK_INFO_SPOOF_ENABLED
import com.shizuku.rwmiao.module.RWmiaoModule
import com.shizuku.rwmiao.module.proxy.ProxyPool
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_ACTIVE_ENDPOINT
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_ANONYMITY
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_AUTO_FIND
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_COUNTRY
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_LIST_CACHE
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_MAX_LATENCY_MS
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_SELECTED_LIST
import com.shizuku.rwmiao.config.SettingsContract.KEY_PROXY_SERVICE_ENABLED
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_FREE_BUILD
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_LINE
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_NONE
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_RANGE
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_SEGMENT
import com.shizuku.rwmiao.config.SettingsContract.VOLUME_ACTION_SMART_PATH
import com.shizuku.rwmiao.module.ModuleUpdateStateBus
import com.shizuku.rwmiao.ui.ConfigurationFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val selectionActionScaleOptions = listOf(
    "取消选择" to SELECTION_ACTION_SCALE_DESELECT,
    "保护单位" to SELECTION_ACTION_SCALE_GUARD,
    "设置巡逻区" to SELECTION_ACTION_SCALE_PATROL,
    "分段指令" to SELECTION_ACTION_SCALE_SEGMENT,
    "智能寻路" to SELECTION_ACTION_SCALE_SMART_PATH,
    "自由建造" to SELECTION_ACTION_SCALE_FREE_BUILD,
    "母单位集结点" to SELECTION_ACTION_SCALE_MOTHER_RALLY,
    "绘制范围" to SELECTION_ACTION_SCALE_RANGE,
    "指示索敌" to SELECTION_ACTION_SCALE_LINE,
    "脚本管理" to SELECTION_ACTION_SCALE_SCRIPTS
)

private val volumeActionOptions = listOf(
    "无" to VOLUME_ACTION_NONE,
    "分段指令" to VOLUME_ACTION_SEGMENT,
    "智能寻路" to VOLUME_ACTION_SMART_PATH,
    "自由建造" to VOLUME_ACTION_FREE_BUILD,
    "绘制范围" to VOLUME_ACTION_RANGE,
    "指示索敌" to VOLUME_ACTION_LINE
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Preferences(
    page: SettingsPage,
    settings: UiPreferences,
    listState: LazyListState,
    onSettings: (UiPreferences) -> Unit,
    onConfigurationImported: () -> Unit
) {
    val context = LocalContext.current
    val updateManager = remember { GithubUpdateManager() }
    val scope = rememberCoroutineScope()
    var updateState by remember(settings.autoCheckUpdate) {
        mutableStateOf(
            if (settings.autoCheckUpdate) {
                ModuleUpdateStateBus.snapshot().toUpdateUiState()
            } else {
                UpdateUiState.Idle
            }
        )
    }
    var promptedUpdateVersion by remember { mutableStateOf<String?>(null) }
    var selectionActionScaleMask by remember {
        mutableStateOf(
            page.preferences.getInt(KEY_SELECTION_ACTION_SCALE_MASK, 0)
                .and(SELECTION_ACTION_SCALE_ALL)
        )
    }
    var volumeAction by remember {
        mutableStateOf(
            page.preferences.getInt(KEY_VOLUME_ACTION, VOLUME_ACTION_NONE)
                .coerceIn(VOLUME_ACTION_NONE, VOLUME_ACTION_LINE)
        )
    }
    var showGameDuration by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_SHOW_GAME_DURATION, false))
    }
    var proxyServiceEnabled by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_PROXY_SERVICE_ENABLED, false))
    }
    var proxyActiveEndpoint by remember {
        mutableStateOf(page.preferences.getString(KEY_PROXY_ACTIVE_ENDPOINT, "").orEmpty())
    }
    var networkInfoDialog by remember { mutableStateOf<NetworkInfoDialog?>(null) }
    var proxyDialog by remember { mutableStateOf(false) }

    fun applyImportedConfiguration() {
        selectionActionScaleMask = page.preferences.getInt(
            KEY_SELECTION_ACTION_SCALE_MASK,
            0
        ).and(SELECTION_ACTION_SCALE_ALL)
        volumeAction = page.preferences.getInt(
            KEY_VOLUME_ACTION,
            VOLUME_ACTION_NONE
        ).coerceIn(VOLUME_ACTION_NONE, VOLUME_ACTION_LINE)
        showGameDuration = page.preferences.getBoolean(KEY_SHOW_GAME_DURATION, false)
        proxyServiceEnabled = page.preferences.getBoolean(KEY_PROXY_SERVICE_ENABLED, false)
        proxyActiveEndpoint = page.preferences.getString(KEY_PROXY_ACTIVE_ENDPOINT, "").orEmpty()
        page.refreshRuntimeHooks()
        onConfigurationImported()
    }

    fun checkForUpdates() {
        if (updateState is UpdateUiState.Checking) return
        promptedUpdateVersion = null
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = when (val result = updateManager.checkLatest()) {
                UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.update)
                is UpdateCheckResult.Error -> UpdateUiState.Failed(result.message)
            }
        }
    }

    DisposableEffect(settings.autoCheckUpdate) {
        if (settings.autoCheckUpdate) {
            val mainHandler = Handler(Looper.getMainLooper())
            val listener = object : ModuleUpdateStateBus.Listener {
                override fun onUpdateStateChanged(state: ModuleUpdateStateBus.Snapshot) {
                    mainHandler.post { updateState = state.toUpdateUiState() }
                }
            }
            ModuleUpdateStateBus.addListener(listener)
            onDispose {
                ModuleUpdateStateBus.removeListener(listener)
                mainHandler.removeCallbacksAndMessages(null)
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(updateState) {
        val available = updateState as? UpdateUiState.Available ?: return@LaunchedEffect
        val version = available.update.version
        if (promptedUpdateVersion == version) return@LaunchedEffect
        if (page.preferences.getString(KEY_UI_UPDATE_IGNORED_VERSION, "").orEmpty() == version) {
            return@LaunchedEffect
        }
        if (page.hostActivity.isFinishing || page.hostActivity.isDestroyed) {
            return@LaunchedEffect
        }
        promptedUpdateVersion = version
        RuntimePanels.showUpdateDialog(
            activity = page.hostActivity,
            version = version,
            onUpdate = Runnable {
                openModuleLink(page.hostActivity, MODULE_RELEASES_URL)
            },
            onIgnore = Runnable {
                page.preferences.edit()
                    .putString(KEY_UI_UPDATE_IGNORED_VERSION, version)
                    .apply()
            }
        )
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
                    Text("主题设置", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeChoice(
                            "跟随系统",
                            UI_THEME_SYSTEM,
                            selected = settings.themeMode,
                            onSelected = { onSettings(settings.copy(themeMode = it)) }
                        )
                        ThemeChoice("浅色", UI_THEME_LIGHT, settings.themeMode) {
                            onSettings(settings.copy(themeMode = it))
                        }
                        ThemeChoice("深色", UI_THEME_DARK, settings.themeMode) {
                            onSettings(settings.copy(themeMode = it))
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "默认" to UI_COLOR_DEFAULT,
                            "动态取色" to UI_COLOR_DYNAMIC,
                            "绿色" to UI_COLOR_GREEN,
                            "蓝色" to UI_COLOR_BLUE,
                            "粉色" to UI_COLOR_PINK,
                            "黄色" to UI_COLOR_YELLOW,
                            "橙色" to UI_COLOR_ORANGE,
                            "红色" to UI_COLOR_RED,
                            "青色" to UI_COLOR_CYAN
                        ).forEach { (label, value) ->
                            ThemeChoice(
                                label,
                                value,
                                settings.colorMode
                            ) { onSettings(settings.copy(colorMode = it)) }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(compact = true) {
                Column {
                    Text(
                        "放大选中面板快捷按钮",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectionActionScaleOptions.forEach { (label, bit) ->
                            val selected = (selectionActionScaleMask and bit) != 0
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) {
                                        selectionActionScaleMask and bit.inv()
                                    } else {
                                        selectionActionScaleMask or bit
                                    }
                                    selectionActionScaleMask = next
                                    page.preferences.edit()
                                        .putInt(KEY_SELECTION_ACTION_SCALE_MASK, next)
                                        .apply()
                                    page.refreshRuntimeHooks()
                                },
                                label = { Text(label) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(compact = true) {
                Column {
                    Text(
                        "音量键控制按钮开关",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "选中单位时若选中单位面板有对应按钮,可使用音量+开启指定按钮,-关闭",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        volumeActionOptions.forEach { (label, value) ->
                            val selected = volumeAction == value
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    volumeAction = value
                                    page.preferences.edit()
                                        .putInt(KEY_VOLUME_ACTION, value)
                                        .apply()
                                    page.refreshRuntimeHooks()
                                },
                                label = { Text(label) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "显示对局时长",
                    "在对局界面左上角显示实际对局时长",
                    showGameDuration
                ) {
                    showGameDuration = it
                    page.preferences.edit()
                        .putBoolean(KEY_SHOW_GAME_DURATION, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item {
            NetworkInfoSection(
                onView = { networkInfoDialog = NetworkInfoDialog.REAL },
                onSpoof = { networkInfoDialog = NetworkInfoDialog.SPOOF }
            )
        }
        item {
            ProxyServiceSection(
                enabled = proxyServiceEnabled,
                activeEndpoint = proxyActiveEndpoint,
                onConfigure = { proxyDialog = true },
                onEnabled = { enabled ->
                    proxyServiceEnabled = enabled
                    page.preferences.edit()
                        .putBoolean(KEY_PROXY_SERVICE_ENABLED, enabled)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            )
        }
        item {
            UpdateSection(
                state = updateState,
                autoCheck = settings.autoCheckUpdate,
                onCheck = ::checkForUpdates,
                onOpenReleases = {
                    openModuleLink(context, MODULE_RELEASES_URL)
                },
                onAutoCheck = { onSettings(settings.copy(autoCheckUpdate = it)) }
            )
        }
        item {
            SectionCard(compact = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "配置管理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "分享配置&更新转移",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                ConfigurationFilePicker.import(
                                    page,
                                    ::applyImportedConfiguration
                                )
                            }
                        ) {
                            Text("导入")
                        }
                        FilledTonalButton(
                            onClick = { ConfigurationFilePicker.export(page) }
                        ) {
                            Text("导出")
                        }
                    }
                }
            }
        }
        item {
            DeveloperAndOpenSourceSection()
        }
    }

    when (networkInfoDialog) {
        NetworkInfoDialog.REAL -> {
            RealNetworkInfoDialog { networkInfoDialog = null }
        }
        NetworkInfoDialog.SPOOF -> {
            SpoofNetworkInfoDialog(
                page = page,
                onDismiss = { networkInfoDialog = null }
            )
        }
        null -> Unit
    }
    if (proxyDialog) {
        ProxySelectionDialog(
            page = page,
            onDismiss = { proxyDialog = false },
            onSaved = { endpoint ->
                proxyActiveEndpoint = endpoint
                page.refreshRuntimeHooks()
                proxyDialog = false
            }
        )
    }
}

@Composable
private fun DeveloperAndOpenSourceSection() {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "开发人员",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            DeveloperRow(
                name = "Shizuku",
                contribution = "主要开发",
                bilibiliUrl = SHIZUKU_BILIBILI_URL
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            DeveloperRow(
                name = "YumeLotus",
                contribution = "维护"
            )

            Text(
                "开源项目使用声明",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            OpenSourceProjectRow(
                name = "libxposed API",
                url = LIBXPOSED_API_URL,
                context = context
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            OpenSourceProjectRow(
                name = "QuadFlask colorpicker",
                url = QUADFLASK_COLORPICKER_URL,
                context = context
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            OpenSourceProjectRow(
                name = "LuaJ",
                url = LUAJ_PROJECT_URL,
                context = context
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeveloperRow(
    name: String,
    contribution: String,
    bilibiliUrl: String? = null
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                contribution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (bilibiliUrl != null) {
            FilledTonalButton(
                onClick = { openModuleLink(context, bilibiliUrl) }
            ) {
                Text("BiliBili")
            }
        }
    }
}

@Composable
private fun OpenSourceProjectRow(
    name: String,
    url: String,
    context: android.content.Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openModuleLink(context, url) }
            .padding(start = 28.dp, top = 9.dp, end = 16.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            url,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class NetworkInfoDialog {
    REAL,
    SPOOF
}

private const val PROXY_WARNING_TEXT =
    "危险功能：使用代理服务进行游戏，该功能可能造成网络延迟和信息泄露"

private val PROXY_COUNTRY_NAMES = mapOf(
    "AE" to "阿联酋", "AL" to "阿尔巴尼亚", "AM" to "亚美尼亚", "AR" to "阿根廷",
    "AT" to "奥地利", "AU" to "澳大利亚", "AZ" to "阿塞拜疆", "BA" to "波黑",
    "BD" to "孟加拉国", "BE" to "比利时", "BG" to "保加利亚", "BH" to "巴林",
    "BO" to "玻利维亚", "BR" to "巴西", "BY" to "白俄罗斯", "CA" to "加拿大",
    "CH" to "瑞士", "CL" to "智利", "CN" to "中国", "CO" to "哥伦比亚",
    "CR" to "哥斯达黎加", "CY" to "塞浦路斯", "CZ" to "捷克", "DE" to "德国",
    "DK" to "丹麦", "DO" to "多米尼加", "DZ" to "阿尔及利亚", "EC" to "厄瓜多尔",
    "EE" to "爱沙尼亚", "EG" to "埃及", "ES" to "西班牙", "ET" to "埃塞俄比亚",
    "FI" to "芬兰", "FR" to "法国", "GB" to "英国", "GE" to "格鲁吉亚",
    "GH" to "加纳", "GR" to "希腊", "GT" to "危地马拉", "HK" to "中国香港",
    "HR" to "克罗地亚", "HU" to "匈牙利", "ID" to "印度尼西亚", "IE" to "爱尔兰",
    "IL" to "以色列", "IN" to "印度", "IQ" to "伊拉克", "IR" to "伊朗",
    "IS" to "冰岛", "IT" to "意大利", "JO" to "约旦", "JP" to "日本",
    "KE" to "肯尼亚", "KH" to "柬埔寨", "KR" to "韩国", "KW" to "科威特",
    "KZ" to "哈萨克斯坦", "LA" to "老挝", "LB" to "黎巴嫩", "LK" to "斯里兰卡",
    "LT" to "立陶宛", "LU" to "卢森堡", "LV" to "拉脱维亚", "LY" to "利比亚",
    "MA" to "摩洛哥", "MD" to "摩尔多瓦", "ME" to "黑山", "MG" to "马达加斯加",
    "MK" to "北马其顿", "MM" to "缅甸", "MN" to "蒙古", "MO" to "中国澳门",
    "MT" to "马耳他", "MX" to "墨西哥", "MY" to "马来西亚", "MZ" to "莫桑比克",
    "NG" to "尼日利亚", "NI" to "尼加拉瓜", "NL" to "荷兰", "NO" to "挪威",
    "NP" to "尼泊尔", "NZ" to "新西兰", "OM" to "阿曼", "PA" to "巴拿马",
    "PE" to "秘鲁", "PH" to "菲律宾", "PK" to "巴基斯坦", "PL" to "波兰",
    "PR" to "波多黎各", "PT" to "葡萄牙", "PY" to "巴拉圭", "QA" to "卡塔尔",
    "RO" to "罗马尼亚", "RS" to "塞尔维亚", "RU" to "俄罗斯", "SA" to "沙特阿拉伯",
    "SD" to "苏丹", "SE" to "瑞典", "SG" to "新加坡", "SI" to "斯洛文尼亚",
    "SK" to "斯洛伐克", "SY" to "叙利亚", "TH" to "泰国", "TN" to "突尼斯",
    "TR" to "土耳其", "TW" to "中国台湾", "TZ" to "坦桑尼亚", "UA" to "乌克兰",
    "US" to "美国", "UY" to "乌拉圭", "UZ" to "乌兹别克斯坦", "VE" to "委内瑞拉",
    "VN" to "越南", "YE" to "也门", "ZA" to "南非", "ZM" to "赞比亚",
    "ZW" to "津巴布韦"
)

private fun proxyCountryLabel(endpoint: ProxyPool.Endpoint, filterCountry: String): String {
    val code = endpoint.countryCode.trim().uppercase()
    PROXY_COUNTRY_NAMES[code]?.let { return it }
    val name = endpoint.countryName.trim()
    if (name.isNotEmpty()) {
        return when (name.lowercase()) {
            "china" -> "中国"
            "united states", "usa" -> "美国"
            "united kingdom", "uk" -> "英国"
            "south korea", "republic of korea" -> "韩国"
            "japan" -> "日本"
            "singapore" -> "新加坡"
            "germany" -> "德国"
            "france" -> "法国"
            "russia" -> "俄罗斯"
            "netherlands" -> "荷兰"
            else -> name
        }
    }
    return if (filterCountry.equals("CN", ignoreCase = true)) "中国" else "未知国家"
}

private fun proxyAnonymityLabel(value: String): String {
    return when (value.trim().lowercase()) {
        "elite", "high", "high_anonymous", "high anonymity" -> "高匿名"
        "anonymous", "anonymous proxy" -> "匿名"
        "transparent", "transparent proxy" -> "透明"
        "" , "unknown" -> "未知"
        else -> value
    }
}

@Composable
private fun ProxyServiceSection(
    enabled: Boolean,
    activeEndpoint: String,
    onConfigure: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    SectionCard(compact = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "代理服务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (enabled && activeEndpoint.isNotEmpty()) {
                        activeEndpoint
                    } else {
                        PROXY_WARNING_TEXT
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                AnimatedVisibility(
                    visible = enabled,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    IconButton(onClick = onConfigure) {
                        Icon(Icons.settings, contentDescription = "代理选择")
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProxySelectionDialog(
    page: SettingsPage,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit
) {
    val preferences = page.preferences
    val scope = rememberCoroutineScope()
    var country by remember {
        mutableStateOf(preferences.getString(KEY_PROXY_COUNTRY, ProxyPool.DEFAULT_COUNTRY).orEmpty())
    }
    var maxLatencyMs by remember {
        mutableStateOf(
            preferences.getInt(KEY_PROXY_MAX_LATENCY_MS, ProxyPool.DEFAULT_MAX_LATENCY_MS)
                .coerceIn(500, 1000)
        )
    }
    var anonymity by remember {
        mutableStateOf(
            preferences.getString(KEY_PROXY_ANONYMITY, ProxyPool.DEFAULT_ANONYMITY)
                .orEmpty()
                .ifEmpty { ProxyPool.DEFAULT_ANONYMITY }
        )
    }
    var autoFind by remember {
        mutableStateOf(preferences.getBoolean(KEY_PROXY_AUTO_FIND, true))
    }
    var endpoints by remember {
        mutableStateOf(
            ProxyPool.deserialize(preferences.getString(KEY_PROXY_LIST_CACHE, "").orEmpty())
        )
    }
    var selectedKeys by remember {
        mutableStateOf(
            ProxyPool.deserialize(preferences.getString(KEY_PROXY_SELECTED_LIST, "").orEmpty())
                .map { it.key() }
                .toSet()
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshList() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            val result = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        ProxyPool.fetch(country, maxLatencyMs, anonymity)
                    }
                )
            } catch (failure: Throwable) {
                Result.failure<List<ProxyPool.Endpoint>>(failure)
            }
            result.onSuccess { fresh ->
                endpoints = fresh
                if (fresh.isNotEmpty()) {
                    preferences.edit()
                        .putString(KEY_PROXY_LIST_CACHE, ProxyPool.serialize(fresh))
                        .apply()
                } else {
                    error = "筛选结果为空，可放宽地区、延迟或安全性条件"
                }
            }.onFailure { failure ->
                error = failure.message?.take(80) ?: "获取代理列表失败"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (endpoints.isEmpty()) refreshList()
    }

    fun save() {
        val saved = ProxyPool.deserialize(
            preferences.getString(KEY_PROXY_SELECTED_LIST, "").orEmpty()
        )
        val all = ArrayList<ProxyPool.Endpoint>(endpoints.size + saved.size)
        val seen = HashSet<String>()
        (endpoints + saved).forEach { endpoint ->
            if (selectedKeys.contains(endpoint.key()) && seen.add(endpoint.key())) {
                all.add(endpoint)
            }
        }
        val active = all.firstOrNull()?.display().orEmpty()
        preferences.edit()
            .putBoolean(KEY_PROXY_AUTO_FIND, autoFind)
            .putString(KEY_PROXY_COUNTRY, country)
            .putInt(KEY_PROXY_MAX_LATENCY_MS, maxLatencyMs)
            .putString(KEY_PROXY_ANONYMITY, anonymity)
            .putString(KEY_PROXY_SELECTED_LIST, ProxyPool.serialize(all))
            .putString(KEY_PROXY_ACTIVE_ENDPOINT, active)
            .apply()
        onSaved(active)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).heightIn(max = 650.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                DialogTitle("代理选择(SOCKS5)", onDismiss, onSave = ::save)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = country == "CN",
                                onClick = { country = "CN" },
                                label = { Text("中国") }
                            )
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = country == "ALL",
                                onClick = { country = "ALL" },
                                label = { Text("全部地区") }
                            )
                        }
                        Spacer(Modifier.width(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = maxLatencyMs == 500,
                                onClick = { maxLatencyMs = 500 },
                                label = { Text("≤500ms") }
                            )
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = maxLatencyMs == 1000,
                                onClick = { maxLatencyMs = 1000 },
                                label = { Text("≤1000ms") }
                            )
                        }
                        Spacer(Modifier.width(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = anonymity == "elite",
                                onClick = { anonymity = "elite" },
                                label = { Text("高匿名") }
                            )
                            FilterChip(
                                modifier = Modifier.height(36.dp),
                                selected = anonymity == "all",
                                onClick = { anonymity = "all" },
                                label = { Text("不限") }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = autoFind,
                            onCheckedChange = { autoFind = it }
                        )
                        Text(
                            "选择代理皆不可使用时自动寻找可用代理",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(
                            onClick = ::refreshList,
                            enabled = !loading
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.refresh, contentDescription = "刷新代理列表")
                            }
                        }
                    }
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (endpoints.isEmpty() && !loading) {
                        Text(
                            "暂无代理列表，点击右侧刷新按钮获取。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    endpoints.forEachIndexed { index, endpoint ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedKeys.contains(endpoint.key()),
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) {
                                        selectedKeys + endpoint.key()
                                    } else {
                                        selectedKeys - endpoint.key()
                                    }
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    endpoint.display(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${proxyCountryLabel(endpoint, country)} · "
                                            + "${if (endpoint.latencyMs >= 0) "${endpoint.latencyMs}ms" else "未知延迟"} · "
                                            + proxyAnonymityLabel(endpoint.anonymity),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkInfoSection(
    onView: () -> Unit,
    onSpoof: () -> Unit
) {
    SectionCard(compact = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "信息管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "查看或伪造部分向网络端发送的信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onView) { Text("查看") }
                FilledTonalButton(onClick = onSpoof) { Text("伪造") }
            }
        }
    }
}

@Composable
private fun RealNetworkInfoDialog(onDismiss: () -> Unit) {
    val real = remember { RWmiaoModule.networkInfoSnapshot() }
    var publicIp by remember { mutableStateOf("查询中…") }
    LaunchedEffect(Unit) {
        publicIp = withContext(Dispatchers.IO) { RWmiaoModule.networkPublicIp() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f).heightIn(max = 460.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                DialogTitle("真实信息", onDismiss)
                Text(
                    real.toDisplayTextForUi(publicIp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SpoofNetworkInfoDialog(
    page: SettingsPage,
    onDismiss: () -> Unit
) {
    val real = remember { RWmiaoModule.networkInfoSnapshot() }
    var form by remember { mutableStateOf(NetworkInfoSpoofForm.load(page, real)) }
    var publicIp by remember { mutableStateOf("查询中…") }
    LaunchedEffect(Unit) {
        publicIp = withContext(Dispatchers.IO) { RWmiaoModule.networkPublicIp() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f).heightIn(max = 520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                DialogTitle(
                    title = "伪造信息",
                    onDismiss = onDismiss,
                    onSave = {
                        saveNetworkInfoSpoof(page, form)
                        onDismiss()
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用伪造", style = MaterialTheme.typography.titleSmall)
                        }
                        Switch(
                            checked = form.enabled,
                            onCheckedChange = { form = form.copy(enabled = it) }
                        )
                    }
                    Text(
                        "可伪造信息",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    NetworkInfoInput("玩家名称", form.playerName, real.playerName) {
                        form = form.copy(playerName = it)
                    }
                    NetworkInfoInput("客户端包名", form.packageName, real.packageName) {
                        form = form.copy(packageName = it)
                    }
                    NetworkInfoInput(
                        "设备 ID（networkClientId）",
                        form.deviceId,
                        real.deviceId
                    ) { form = form.copy(deviceId = it) }
                    NetworkInfoInput("核心单位校验值", form.unitChecksum, real.unitChecksum) {
                        form = form.copy(unitChecksum = it)
                    }
                    NetworkInfoInput(
                        "完整性挑战响应",
                        form.integrity,
                        real.integrity
                    ) { form = form.copy(integrity = it) }
                    NetworkInfoInput(
                        "Mod/额外内容校验值",
                        form.extraChecksum,
                        real.extraChecksum
                    ) { form = form.copy(extraChecksum = it) }

                    Text(
                        "不可伪造信息",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        real.toNonSpoofedTextForUi(publicIp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun saveNetworkInfoSpoof(page: SettingsPage, form: NetworkInfoSpoofForm) {
    page.preferences.edit()
        .putBoolean(KEY_NETWORK_INFO_SPOOF_ENABLED, form.enabled)
        .putString(KEY_NETWORK_INFO_FAKE_DEVICE_ID, form.deviceId)
        .putString(KEY_NETWORK_INFO_FAKE_PLAYER_NAME, form.playerName)
        .putString(KEY_NETWORK_INFO_FAKE_PACKAGE_NAME, form.packageName)
        .putString(KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM, form.unitChecksum)
        .putString(KEY_NETWORK_INFO_FAKE_INTEGRITY, form.integrity)
        .putString(KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM, form.extraChecksum)
        .apply()
    page.refreshRuntimeHooks()
}

@Composable
private fun DialogTitle(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        if (onSave != null) {
            IconButton(onClick = onSave) {
                Icon(Icons.save, contentDescription = "保存")
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun NetworkInfoInput(
    label: String,
    value: String,
    realValue: String?,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "真实值:${realValue.orEmpty().ifEmpty { "（空）" }}",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        singleLine = true
    )
}

private data class NetworkInfoSpoofForm(
    val enabled: Boolean,
    val deviceId: String,
    val playerName: String,
    val packageName: String,
    val unitChecksum: String,
    val integrity: String,
    val extraChecksum: String
) {
    companion object {
        fun load(
            page: SettingsPage,
            real: com.shizuku.rwmiao.module.network.NetworkInfo.Snapshot
        ): NetworkInfoSpoofForm {
            fun override(key: String, actual: String?): String {
                val saved = page.preferences.getString(key, "").orEmpty()
                return if (saved.isEmpty() || saved == actual.orEmpty()) "" else saved
            }
            return NetworkInfoSpoofForm(
                enabled = page.preferences.getBoolean(KEY_NETWORK_INFO_SPOOF_ENABLED, false),
                deviceId = override(KEY_NETWORK_INFO_FAKE_DEVICE_ID, real.deviceId),
                playerName = override(KEY_NETWORK_INFO_FAKE_PLAYER_NAME, real.playerName),
                packageName = override(KEY_NETWORK_INFO_FAKE_PACKAGE_NAME, real.packageName),
                unitChecksum = override(KEY_NETWORK_INFO_FAKE_UNIT_CHECKSUM, real.unitChecksum),
                integrity = override(KEY_NETWORK_INFO_FAKE_INTEGRITY, real.integrity),
                extraChecksum = override(KEY_NETWORK_INFO_FAKE_EXTRA_CHECKSUM, real.extraChecksum)
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
                    BuildConfig.VERSION_NAME,
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
                    FilledTonalIconButton(onClick = {
                        openModuleLink(context, MODULE_BILIBILI_URL)
                    }) {
                        Text(
                            "B",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
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
    data class Failed(val message: String) : UpdateUiState()
}

private fun ModuleUpdateStateBus.Snapshot.toUpdateUiState(): UpdateUiState = when (kind) {
    ModuleUpdateStateBus.Kind.CHECKING -> UpdateUiState.Checking
    ModuleUpdateStateBus.Kind.UP_TO_DATE -> UpdateUiState.UpToDate
    ModuleUpdateStateBus.Kind.AVAILABLE -> UpdateUiState.Available(
        UpdateInfo(version, downloadUrl, sizeBytes)
    )
    ModuleUpdateStateBus.Kind.FAILED -> UpdateUiState.Failed(message)
    ModuleUpdateStateBus.Kind.IDLE -> UpdateUiState.Idle
}

@Composable
private fun UpdateSection(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpenReleases: () -> Unit,
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
                    UpdateUiState.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    is UpdateUiState.Available -> FilledTonalButton(
                        onClick = onOpenReleases
                    ) { Text("立即更新") }
                    else -> IconButton(
                        onClick = onCheck,
                        enabled = state !is UpdateUiState.Checking
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
    selected: Int,
    onSelected: (Int) -> Unit
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelected(value) },
        label = { Text(label) },
        leadingIcon = if (value == selected) {
            {
                Icon(Icons.check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        } else null
    )
}
