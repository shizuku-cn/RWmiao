package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DrawPage(
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
            SectionCard {
                SwitchSetting(
                    "显示“绘制范围”按钮",
                    "在选中单位面板添加“绘制范围”按钮，常驻显示单位攻击范围",
                    state.showRangeAction
                ) { onState(state.copy(showRangeAction = it)) }
                DividerSetting()
                SwitchSetting(
                    "常驻攻击范围",
                    "常驻显示单位攻击范围",
                    state.showAttackRange
                ) { onState(state.copy(showAttackRange = it)) }
                DividerSetting()
                PlayerFilter("绘制对象", state.rangePlayerFilter) {
                    onState(state.copy(rangePlayerFilter = it))
                }
                Spacer(Modifier.height(8.dp))
                UnitTypeFilter(state.rangeUnitTypes) {
                    onState(state.copy(rangeUnitTypes = it))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorEditor("自己", state.rangeColors[0], Modifier.weight(1f)) { color ->
                        onState(state.copy(rangeColors = state.rangeColors.replace(0, color)))
                    }
                    ColorEditor("敌人", state.rangeColors[1], Modifier.weight(1f)) { color ->
                        onState(state.copy(rangeColors = state.rangeColors.replace(1, color)))
                    }
                    ColorEditor("队友", state.rangeColors[2], Modifier.weight(1f)) { color ->
                        onState(state.copy(rangeColors = state.rangeColors.replace(2, color)))
                    }
                }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "显示“指示索敌”按钮",
                    "在选中单位面板添加“指示索敌”按钮，在单位攻击时指示其攻击目标",
                    state.showLineAction
                ) { onState(state.copy(showLineAction = it)) }
                DividerSetting()
                SwitchSetting(
                    "常驻索敌指示线",
                    "在单位攻击时指示其攻击目标",
                    state.showTargetLine
                ) { onState(state.copy(showTargetLine = it)) }
                DividerSetting()
                PlayerFilter("绘制对象", state.linePlayerFilter) {
                    onState(state.copy(linePlayerFilter = it))
                }
                Spacer(Modifier.height(8.dp))
                UnitTypeFilter(state.lineUnitTypes) {
                    onState(state.copy(lineUnitTypes = it))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorEditor("自己", state.lineColors[0], Modifier.weight(1f)) { color ->
                        onState(state.copy(lineColors = state.lineColors.replace(0, color)))
                    }
                    ColorEditor("敌人", state.lineColors[1], Modifier.weight(1f)) { color ->
                        onState(state.copy(lineColors = state.lineColors.replace(1, color)))
                    }
                    ColorEditor("队友", state.lineColors[2], Modifier.weight(1f)) { color ->
                        onState(state.copy(lineColors = state.lineColors.replace(2, color)))
                    }
                }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "显示核弹和反核数量",
                    "在核弹发射井与反核装置上方显示弹药数量",
                    state.showAmmoCount
                ) { onState(state.copy(showAmmoCount = it)) }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorEditor("自己", state.ammoColors[0], Modifier.weight(1f)) { color ->
                        onState(state.copy(ammoColors = state.ammoColors.replace(0, color)))
                    }
                    ColorEditor("敌人", state.ammoColors[1], Modifier.weight(1f)) { color ->
                        onState(state.copy(ammoColors = state.ammoColors.replace(1, color)))
                    }
                    ColorEditor("队友", state.ammoColors[2], Modifier.weight(1f)) { color ->
                        onState(state.copy(ammoColors = state.ammoColors.replace(2, color)))
                    }
                }
            }
        }
        item {
            SectionCard {
                SwitchSetting(
                    "显示工厂生产单位倒计时",
                    "在工厂位置显示生产倒计时与总时长",
                    state.showFactoryCountdown
                ) { onState(state.copy(showFactoryCountdown = it)) }
                Spacer(Modifier.height(8.dp))
                PlayerFilterWithAlly("显示对象", state.factoryPlayerFilter) {
                    onState(state.copy(factoryPlayerFilter = it))
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorEditor("自己", state.factoryColors[0], Modifier.weight(1f)) { color ->
                        onState(state.copy(factoryColors = state.factoryColors.replace(0, color)))
                    }
                    ColorEditor("敌人", state.factoryColors[1], Modifier.weight(1f)) { color ->
                        onState(state.copy(factoryColors = state.factoryColors.replace(1, color)))
                    }
                    ColorEditor("队友", state.factoryColors[2], Modifier.weight(1f)) { color ->
                        onState(state.copy(factoryColors = state.factoryColors.replace(2, color)))
                    }
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "经济面板",
                    "常驻显示游戏回放中的经济面板",
                    state.economicPanel
                ) { onState(state.copy(economicPanel = it)) }
            }
        }
    }
}

private fun List<Int>.replace(index: Int, value: Int): List<Int> =
    toMutableList().also { it[index] = value }
