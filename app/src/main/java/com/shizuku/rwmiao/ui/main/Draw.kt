package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
                Spacer(Modifier.height(8.dp))
                ColorGroup(state.rangeColors) { index, color ->
                    onState(state.copy(rangeColors = state.rangeColors.replace(index, color)))
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
                Spacer(Modifier.height(8.dp))
                ColorGroup(state.lineColors) { index, color ->
                    onState(state.copy(lineColors = state.lineColors.replace(index, color)))
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
                ColorGroup(state.factoryColors) { index, color ->
                    onState(state.copy(factoryColors = state.factoryColors.replace(index, color)))
                }
            }
        }
        item {
            SectionCard(compact = true) {
                SwitchSetting(
                    "玩家信息面板",
                    "对局内添加一个悬浮窗，显示所有玩家的信息",
                    state.playerInfoPanel
                ) { onState(state.copy(playerInfoPanel = it)) }
            }
        }
    }
}

private fun List<Int>.replace(index: Int, value: Int): List<Int> =
    toMutableList().also { it[index] = value }

@Composable
private fun ColorGroup(colors: List<Int>, onColor: (Int, Int) -> Unit) {
    val names = listOf("自己", "敌人", "队友")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "颜色",
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            names.forEachIndexed { index, name ->
                ColorEditor(name, colors[index]) { onColor(index, it) }
            }
        }
    }
}
