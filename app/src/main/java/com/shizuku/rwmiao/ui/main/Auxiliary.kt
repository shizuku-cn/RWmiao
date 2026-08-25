package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AuxiliaryPage(
    state: SettingsState,
    listState: LazyListState,
    onState: (SettingsState) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "强制无雾",
                    "强制将游戏迷雾模式修改为无雾状态",
                    state.noFog
                ) { onState(state.copy(noFog = it)) }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "可视化其它玩家操作",
                    "查看其它玩家建筑状态，单位移动路径等",
                    state.viewAll
                ) { onState(state.copy(viewAll = it)) }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "优化工厂产兵逻辑",
                    "生产单位时，智能分配工厂组的生产数量",
                    state.factoryOpt
                ) { onState(state.copy(factoryOpt = it)) }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "允许单位分段发送指令",
                    "在选中单位面板添加“分段指令”按钮，使单位可分段执行移动指令，分配攻击或建造指令时终止",
                    state.segmentCommand
                ) { onState(state.copy(segmentCommand = it)) }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "自动补兵",
                    "在工厂选择生产单位时添加自动补兵窗口，使工厂自动生产指定单位和数量",
                    state.reinforceOn
                ) { onState(state.copy(reinforceOn = it)) }
                DividerSetting()
                SwitchSetting(
                    "自动补兵允许多队列按权重顺序补兵",
                    "在自动补兵基础上新增权重设置，可让多个工厂组按权重轮流自动补兵",
                    state.reinforceWeightMode,
                    enabled = state.reinforceOn
                ) { onState(state.copy(reinforceWeightMode = it)) }
                DividerSetting()
                SwitchSetting(
                    "显示补兵面板按钮",
                    "在游戏界面右侧添加“自动补兵”按钮，可查看已有队列和分配情况",
                    state.showReinforcePanel
                ) { onState(state.copy(showReinforcePanel = it)) }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "单位智能寻路",
                    "使选中单位在受到指令后按特定算法走最优路径",
                    state.smartPathing
                ) { onState(state.copy(smartPathing = it)) }
                ThresholdRow(
                    "智能寻路单位阈值",
                    "使选中单位的数量低于阈值时才触发智能寻路",
                    state.smartPathingThreshold
                ) { onState(state.copy(smartPathingThreshold = it)) }
                DividerSetting()
                SwitchSetting(
                    "显示单位智能寻路按钮",
                    "在选中单位面板添加“智能寻路”按钮，使单位按智能寻路算法走最优路径",
                    state.showSmartPathAction
                ) { onState(state.copy(showSmartPathAction = it)) }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "可见其它玩家队伍消息",
                    "仅房主可用：查看敌方玩家的队伍消息（[队伍 X]标记）",
                    state.enemyTeamChat
                ) {
                    onState(state.copy(
                        enemyTeamChat = it,
                        enemyMapPings = if (it) state.enemyMapPings else false
                    ))
                }
                DividerSetting()
                SwitchSetting(
                    "可见地图标记",
                    "可见敌方队伍的地图标记，如：“表示快乐”“开始撤退”",
                    state.enemyMapPings,
                    enabled = state.enemyTeamChat
                ) { onState(state.copy(enemyMapPings = it)) }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "集结点出厂穿透",
                    "工厂有集结点时，单位出厂时穿透至集结点",
                    state.factoryExitThrough
                ) { onState(state.copy(factoryExitThrough = it)) }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "自由框选",
                    "在界面右侧添加按钮,自由框选模式下手势画封闭图形后框选其中单位",
                    state.freeSelection
                ) { onState(state.copy(freeSelection = it)) }
            }
        }
    }
}
