package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shizuku.rwmiao.config.SettingsContract.DEFAULT_GLOBAL_UNIT_CAP
import com.shizuku.rwmiao.config.SettingsContract.DEFAULT_SELECTION_PANEL_COLUMNS
import com.shizuku.rwmiao.config.SettingsContract.KEY_EXTENDED_GAME_OPTIONS
import com.shizuku.rwmiao.config.SettingsContract.KEY_GLOBAL_TEAM_LIMIT_ENABLED
import com.shizuku.rwmiao.config.SettingsContract.KEY_GLOBAL_UNIT_CAP
import com.shizuku.rwmiao.config.SettingsContract.KEY_GLOBAL_UNIT_CAP_ENABLED
import com.shizuku.rwmiao.config.SettingsContract.KEY_BATCH_PLACEMENT_UNLIMITED
import com.shizuku.rwmiao.config.SettingsContract.KEY_FORMATION_BUTTON_COUNT
import com.shizuku.rwmiao.config.SettingsContract.KEY_HOST_MUTE_PANEL
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_SEARCHER
import com.shizuku.rwmiao.config.SettingsContract.KEY_MOTHER_RALLY
import com.shizuku.rwmiao.config.SettingsContract.KEY_SELECTION_PANEL_COLUMNS
import com.shizuku.rwmiao.config.SettingsContract.KEY_SMART_BUILD_SERIALIZATION
import com.shizuku.rwmiao.config.SettingsContract.MAX_FORMATION_BUTTON_COUNT
import com.shizuku.rwmiao.config.SettingsContract.MAX_SELECTION_PANEL_COLUMNS
import com.shizuku.rwmiao.config.SettingsContract.MIN_FORMATION_BUTTON_COUNT
import com.shizuku.rwmiao.config.SettingsContract.MIN_SELECTION_PANEL_COLUMNS
import com.shizuku.rwmiao.config.SettingsContract.MAX_GLOBAL_UNIT_CAP

@Composable
internal fun EnvironmentPage(page: SettingsPage, listState: LazyListState) {
    var extendedGameOptions by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_EXTENDED_GAME_OPTIONS, false))
    }
    var motherRally by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_MOTHER_RALLY, false))
    }
    var globalUnitCapEnabled by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_GLOBAL_UNIT_CAP_ENABLED, false))
    }
    var globalUnitCapText by remember {
        mutableStateOf(
            page.preferences.getInt(KEY_GLOBAL_UNIT_CAP, DEFAULT_GLOBAL_UNIT_CAP).toString()
        )
    }
    var globalTeamLimitEnabled by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_GLOBAL_TEAM_LIMIT_ENABLED, false))
    }
    var batchPlacementUnlimited by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_BATCH_PLACEMENT_UNLIMITED, false))
    }
    var smartBuildSerialization by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_SMART_BUILD_SERIALIZATION, false))
    }
    var hostMutePanel by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_HOST_MUTE_PANEL, false))
    }
    var lobbySearcher by remember {
        mutableStateOf(page.preferences.getBoolean(KEY_LOBBY_SEARCHER, false))
    }
    val nativeFormationDefault = remember {
        page.formationButtonDefaultCount().coerceIn(
            MIN_FORMATION_BUTTON_COUNT,
            MAX_FORMATION_BUTTON_COUNT
        )
    }
    var formationButtonCount by remember {
        mutableStateOf(
            page.preferences.getInt(KEY_FORMATION_BUTTON_COUNT, nativeFormationDefault)
                .coerceIn(MIN_FORMATION_BUTTON_COUNT, MAX_FORMATION_BUTTON_COUNT)
        )
    }
    var selectionPanelColumns by remember {
        mutableStateOf(page.selectionPanelColumns())
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "拓展游戏选项设置面板",
                    "为房间游戏选项设置提供更多自定义和额外功能",
                    extendedGameOptions
                ) {
                    extendedGameOptions = it
                    page.preferences.edit()
                        .putBoolean(KEY_EXTENDED_GAME_OPTIONS, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "自定义全局单位上限",
                    "*仅房主有效*替换原生游戏设置内的单位上限数量",
                    globalUnitCapEnabled
                ) {
                    globalUnitCapEnabled = it
                    page.preferences.edit()
                        .putBoolean(KEY_GLOBAL_UNIT_CAP_ENABLED, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
                OutlinedTextField(
                    value = globalUnitCapText,
                    onValueChange = { input ->
                        val filtered = input.filter { character -> character.isDigit() }.take(5)
                        globalUnitCapText = filtered
                        filtered.toIntOrNull()?.let { value ->
                            if (value in 1..MAX_GLOBAL_UNIT_CAP) {
                                page.preferences.edit()
                                    .putInt(KEY_GLOBAL_UNIT_CAP, value)
                                    .apply()
                                if (globalUnitCapEnabled) page.refreshRuntimeHooks()
                            }
                        }
                    },
                    enabled = globalUnitCapEnabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text("自定义数量") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "解除队伍数量上限",
                    "解除游戏的10队伍数量上限",
                    globalTeamLimitEnabled
                ) {
                    globalTeamLimitEnabled = it
                    page.preferences.edit()
                        .putBoolean(KEY_GLOBAL_TEAM_LIMIT_ENABLED, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "解除批量放置或建造单位的上限",
                    "解除例如沙盒模式中批量放置或者建造炮塔存在的上限",
                    batchPlacementUnlimited
                ) {
                    batchPlacementUnlimited = it
                    page.preferences.edit()
                        .putBoolean(KEY_BATCH_PLACEMENT_UNLIMITED, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "拓展多人游戏大厅列表",
                    "重构多人游戏大厅列表，实现拓展功能如筛选，过滤等",
                    lobbySearcher
                ) {
                    lobbySearcher = it
                    page.preferences.edit()
                        .putBoolean(KEY_LOBBY_SEARCHER, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item(key = "formation-button-count") {
            SectionCard(compact = true) {
                NumberSetting(
                    title = "自定义编队按钮数量",
                    description = "编队按钮数量：3--10",
                    value = formationButtonCount,
                    min = MIN_FORMATION_BUTTON_COUNT,
                    max = MAX_FORMATION_BUTTON_COUNT
                ) { next ->
                    formationButtonCount = next
                    page.preferences.edit().apply {
                        if (next == nativeFormationDefault) {
                            remove(KEY_FORMATION_BUTTON_COUNT)
                        } else {
                            putInt(KEY_FORMATION_BUTTON_COUNT, next)
                        }
                    }.apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item(key = "selection-panel-columns") {
            SectionCard(compact = true) {
                NumberSetting(
                    title = "自定义单位选中面板列数",
                    description = "自定义选中单位面板的按钮呈几列排布(1-6)",
                    value = selectionPanelColumns,
                    min = MIN_SELECTION_PANEL_COLUMNS,
                    max = MAX_SELECTION_PANEL_COLUMNS
                ) { next ->
                    selectionPanelColumns = next
                    page.preferences.edit().apply {
                        if (next == DEFAULT_SELECTION_PANEL_COLUMNS) {
                            remove(KEY_SELECTION_PANEL_COLUMNS)
                        } else {
                            putInt(KEY_SELECTION_PANEL_COLUMNS, next)
                        }
                    }.apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item(key = "mother-rally") {
            SectionCard(compact = true) {
                SwitchSetting(
                    "母单位集结点",
                    "给可生产单位的单位添加“设置集结点”按钮",
                    motherRally
                ) {
                    motherRally = it
                    page.preferences.edit()
                        .putBoolean(KEY_MOTHER_RALLY, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item(key = "smart-build-serialization") {
            SectionCard(compact = true) {
                SwitchSetting(
                    "智能建造序列化",
                    "使用模块框架处理建造序列，避免原版建造断链和跳过建造的问题",
                    smartBuildSerialization
                ) {
                    smartBuildSerialization = it
                    page.preferences.edit()
                        .putBoolean(KEY_SMART_BUILD_SERIALIZATION, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
        item(key = "host-mute-panel") {
            SectionCard(compact = true) {
                SwitchSetting(
                    "房主禁言面板",
                    "仅房主可用：在界面右侧添加按钮，可在面板内禁言指定玩家",
                    hostMutePanel
                ) {
                    hostMutePanel = it
                    page.preferences.edit()
                        .putBoolean(KEY_HOST_MUTE_PANEL, it)
                        .apply()
                    page.refreshRuntimeHooks()
                }
            }
        }
    }
}
