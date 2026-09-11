package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ScriptPage(page: SettingsPage, scrollState: ScrollState) {
    var refreshKey by remember { mutableStateOf(0) }
    var master by remember { mutableStateOf(page.scriptManager?.masterEnabled() ?: false) }
    val uiRefresh = remember(page.scriptManager) {
        Runnable {
            page.hostActivity.runOnUiThread {
                master = page.scriptManager?.masterEnabled() ?: false
                refreshKey += 1
            }
        }
    }
    DisposableEffect(page.scriptManager, uiRefresh) {
        page.scriptManager?.setUiListener(uiRefresh)
        page.scriptManager?.refreshForUi()
        onDispose { page.scriptManager?.clearUiListener(uiRefresh) }
    }
    val records = remember(refreshKey) { page.scriptManager?.records()?.toList().orEmpty() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallSwitchRow("脚本总开关", master) {
            master = it
            page.scriptManager?.setMasterEnabled(it)
        }
        AnimatedVisibility(
            visible = master,
            modifier = Modifier.fillMaxWidth(),
            enter = fadeIn(tween(240)) + expandVertically(tween(300)) + slideInVertically(tween(240)) { it / 10 },
            exit = fadeOut(tween(180)) + shrinkVertically(tween(300)) + slideOutVertically(tween(180)) { -it / 10 }
        ) {
            SectionCard {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                            Text("脚本列表", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(4.dp))
                            Text("（${records.size}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(
                            onClick = { page.scriptManager?.beginImport(page.hostActivity, null) },
                            enabled = page.scriptManager != null
                        ) {
                            Icon(Icons.import, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("导入脚本")
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    when {
                        page.scriptManager == null -> EmptyCard("脚本框架未能加载，请查看脚本日志。")
                        records.isEmpty() -> EmptyCard("暂无脚本")
                        else -> Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column {
                                records.forEachIndexed { index, record ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            RuntimePanels.showScriptDeleteConfirmation(
                                                page.hostActivity,
                                                record.name,
                                                Runnable {
                                                    page.scriptManager?.delete(record.id)
                                                }
                                            )
                                        }) {
                                            Icon(Icons.delete, contentDescription = "删除脚本")
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(record.name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${record.fileName} · 作用兵种：${record.units}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        AnimatedVisibility(
                                            visible = record.enabled && record.hasSettings,
                                            enter = fadeIn(tween(180)) + scaleIn(tween(220)),
                                            exit = fadeOut(tween(120)) + scaleOut(tween(160))
                                        ) {
                                            IconButton(onClick = { page.scriptManager?.openSettings(page.hostActivity, record.id) }) {
                                                Icon(Icons.settings, contentDescription = "脚本设置")
                                            }
                                        }
                                        Switch(
                                            checked = record.enabled,
                                            onCheckedChange = {
                                                page.scriptManager?.setEnabled(record.id, it)
                                                refreshKey += 1
                                            }
                                        )
                                    }
                                    if (index != records.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 58.dp, end = 10.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
