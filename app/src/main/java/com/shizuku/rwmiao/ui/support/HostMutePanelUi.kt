package com.shizuku.rwmiao.ui.support

import android.app.Activity
import android.app.Dialog
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
object HostMutePanelUi {
    class MutePanelPlayer(
        @JvmField val index: Int,
        @JvmField val name: String,
        @JvmField val numberColor: Int,
        @JvmField val nameColor: Int,
        @JvmField val expiresAt: Long,
        @JvmField val blockMapPing: Boolean,
        @JvmField val durationMs: Long
    )

    class MutePanelData(
        @JvmField val allChatMuted: Boolean,
        @JvmField val players: List<MutePanelPlayer>
    )

    interface MutePanelCallback {
        fun onApply(data: MutePanelData)
    }

    @JvmStatic
    fun showHostMutePanel(
        activity: Activity,
        initial: MutePanelData,
        callback: MutePanelCallback
    ): Dialog = RuntimePanels.showDialog(activity, 0.94f, 0.90f) { dialog ->
        var allChatMuted by remember { mutableStateOf(initial.allChatMuted) }
        var players by remember {
            mutableStateOf(initial.players.map { MuteDraft.from(it) })
        }
        var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        var durationIndex by remember { mutableStateOf<Int?>(null) }
        var durationMinutes by remember { mutableStateOf("0") }
        var durationSeconds by remember { mutableStateOf("0") }
        var durationError by remember { mutableStateOf(false) }

        LaunchedEffect(dialog) {
            while (dialog.isShowing) {
                now = SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(1000L)
            }
        }

        fun updatePlayer(index: Int, transform: (MuteDraft) -> MuteDraft) {
            players = players.map { draft ->
                if (draft.index == index) transform(draft) else draft
            }
        }

        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 4.dp, end = 6.dp, bottom = 2.dp)
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "禁言面板",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allChatMuted,
                            onCheckedChange = { allChatMuted = it }
                        )
                        Text("全体禁言", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(modifier = Modifier.size(40.dp), onClick = { dialog.dismiss() }) {
                        Icon(Icons.close, contentDescription = "关闭")
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (players.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无可禁言的玩家", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(players, key = { it.index }) { draft ->
                            MutePanelRow(
                                draft = draft,
                                now = now,
                                onBlockMapPing = { checked ->
                                    updatePlayer(draft.index) { it.copy(blockMapPing = checked) }
                                },
                                onPermanent = {
                                    updatePlayer(draft.index) {
                                        it.copy(expiresAt = -1L, durationMs = -1L)
                                    }
                                },
                                onTimed = {
                                    val remaining = if (draft.expiresAt > now) {
                                        draft.expiresAt - now
                                    } else 0L
                                    durationMinutes = (remaining / 60_000L).toString()
                                    durationSeconds = ((remaining % 60_000L) / 1_000L).toString()
                                    durationError = false
                                    durationIndex = draft.index
                                },
                                onUnmute = {
                                    updatePlayer(draft.index) {
                                        it.copy(expiresAt = 0L, durationMs = 0L, blockMapPing = false)
                                    }
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
                        .heightIn(min = 40.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { dialog.dismiss() }) { Text("取消") }
                    Button(onClick = {
                        callback.onApply(
                            MutePanelData(
                                allChatMuted,
                                players.map {
                                    MutePanelPlayer(
                                        it.index, it.name, it.numberColor, it.nameColor,
                                        it.expiresAt,
                                        it.blockMapPing, it.durationMs
                                    )
                                }
                            )
                        )
                    }) { Text("应用") }
                }
            }
            }

            if (durationIndex != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(onClick = {}),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.82f),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "选择禁言时长",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = durationMinutes,
                                    onValueChange = {
                                        durationMinutes = it.filter(Char::isDigit).take(3)
                                        durationError = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("分钟") },
                                    isError = durationError,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    )
                                )
                                OutlinedTextField(
                                    value = durationSeconds,
                                    onValueChange = {
                                        durationSeconds = it.filter(Char::isDigit).take(2)
                                        durationError = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("秒") },
                                    isError = durationError,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    )
                                )
                            }
                            if (durationError) {
                                Text(
                                    "禁言时长必须大于 0 秒",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { durationIndex = null }) { Text("取消") }
                                Button(onClick = {
                                    val minutes = (durationMinutes.toLongOrNull() ?: 0L)
                                        .coerceIn(0L, 999L)
                                    val seconds = (durationSeconds.toLongOrNull() ?: 0L)
                                        .coerceIn(0L, 59L)
                                    val duration = minutes * 60_000L + seconds * 1_000L
                                    if (duration <= 0L) {
                                        durationError = true
                                    } else {
                                        val index = durationIndex
                                        if (index != null) {
                                            updatePlayer(index) {
                                                it.copy(
                                                    expiresAt = SystemClock.elapsedRealtime() + duration,
                                                    durationMs = duration
                                                )
                                            }
                                        }
                                        durationIndex = null
                                    }
                                }) { Text("确定") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MuteDraft(
    val index: Int,
    val name: String,
    val numberColor: Int,
    val nameColor: Int,
    val expiresAt: Long,
    val blockMapPing: Boolean,
    val durationMs: Long
) {
    companion object {
        fun from(value: HostMutePanelUi.MutePanelPlayer) = MuteDraft(
            value.index, value.name, value.numberColor, value.nameColor,
            value.expiresAt, value.blockMapPing, value.durationMs
        )
    }
}

@Composable
private fun MutePanelRow(
    draft: MuteDraft,
    now: Long,
    onBlockMapPing: (Boolean) -> Unit,
    onPermanent: () -> Unit,
    onTimed: () -> Unit,
    onUnmute: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    (draft.index + 1).toString(),
                    color = Color(draft.numberColor),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    draft.name,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(min = 40.dp, max = 150.dp),
                    color = Color(draft.nameColor),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    muteRemainingText(draft.expiresAt, now),
                    modifier = Modifier.widthIn(min = 52.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        onClick = onPermanent
                    ) { Text("永禁") }
                    FilledTonalButton(
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        onClick = onTimed
                    ) { Text("禁言") }
                    TextButton(
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                        onClick = onUnmute
                    ) { Text("解禁") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    modifier = Modifier.size(32.dp),
                    checked = draft.blockMapPing,
                    onCheckedChange = onBlockMapPing
                )
                Text("禁用标记", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

private fun muteRemainingText(expiresAt: Long, now: Long): String {
    if (expiresAt == -1L) return "永久"
    if (expiresAt <= now) return ""
    val seconds = ((expiresAt - now + 999L) / 1_000L).coerceAtLeast(1L)
    val minutesText = (seconds / 60L).toString().padStart(2, '0')
    val secondsText = (seconds % 60L).toString().padStart(2, '0')
    return "$minutesText:$secondsText"
}
