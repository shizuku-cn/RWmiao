package com.shizuku.rwmiao.ui.lobby

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shizuku.rwmiao.config.SettingsContract.DEFAULT_LOBBY_AD_KEYWORDS
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_AD_KEYWORDS
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_AD_KEYWORDS_VERSION
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_BLACKLIST
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_PINNED_NAMES
import com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_RECENT_ROOMS
import com.shizuku.rwmiao.config.SettingsContract.LOBBY_AD_KEYWORDS_VERSION
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_SYSTEM
import com.shizuku.rwmiao.module.lobby.LobbyRoom
import com.shizuku.rwmiao.ui.support.*
import com.shizuku.rwmiao.ui.main.UiPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class RecentLobbyRoomV6(
    val historyKey: String,
    val hostName: String,
    val playersLabel: String,
    val mapName: String,
    val modName: String,
    val versionName: String,
    val visibility: String
) {
    companion object {
        fun from(room: LobbyRoom): RecentLobbyRoomV6 = RecentLobbyRoomV6(
            historyKey = room.historyKey,
            hostName = room.hostName,
            playersLabel = room.playersLabel,
            mapName = room.mapName,
            modName = room.modName,
            versionName = room.versionName,
            visibility = room.visibility
        )
    }
}

internal fun loadLobbyRecentRooms(preferences: SharedPreferences): List<RecentLobbyRoomV6> {
    val raw = preferences.getString(KEY_LOBBY_RECENT_ROOMS, "[]").orEmpty()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RecentLobbyRoomV6(
                historyKey = item.optString("historyKey"),
                hostName = item.optString("hostName"),
                playersLabel = item.optString("playersLabel"),
                mapName = item.optString("mapName"),
                modName = item.optString("modName"),
                versionName = item.optString("versionName"),
                visibility = item.optString("visibility")
            )
        }.filter { it.historyKey.isNotBlank() }.take(30)
    }.getOrDefault(emptyList())
}

internal fun saveLobbyRecentRooms(
    preferences: SharedPreferences,
    rooms: List<RecentLobbyRoomV6>
) {
    val array = JSONArray()
    rooms.take(30).forEach { room ->
        array.put(
            JSONObject()
                .put("historyKey", room.historyKey)
                .put("hostName", room.hostName)
                .put("playersLabel", room.playersLabel)
                .put("mapName", room.mapName)
                .put("modName", room.modName)
                .put("versionName", room.versionName)
                .put("visibility", room.visibility)
        )
    }
    preferences.edit().putString(KEY_LOBBY_RECENT_ROOMS, array.toString()).apply()
}

private data class LobbyBlacklistEntryV6(
    val nameKey: String,
    val nameDisplay: String,
    val addressKey: String,
    val addressDisplay: String
)

@Composable
internal fun LobbyRoomListV6(
    rooms: List<LobbyRoom>,
    recentRooms: List<RecentLobbyRoomV6>,
    callbacks: LobbyPanelHost.Callbacks
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyJoinable by rememberSaveable { mutableStateOf(false) }
    var onlySlots by rememberSaveable { mutableStateOf(false) }
    var onlyBattleReady by rememberSaveable { mutableStateOf(false) }
    var hidePassword by rememberSaveable { mutableStateOf(false) }
    var version115 by rememberSaveable { mutableStateOf(false) }
    var onlyVanilla by rememberSaveable { mutableStateOf(false) }
    var onlyModded by rememberSaveable { mutableStateOf(false) }
    var pinnedNames by remember(preferences) {
        mutableStateOf(loadLobbyPinnedNames(preferences))
    }
    var blacklist by remember(preferences) {
        mutableStateOf(loadLobbyBlacklist(preferences))
    }
    var actionRoom by remember { mutableStateOf<LobbyRoom?>(null) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var showRecentDialog by remember { mutableStateOf(false) }

    val normalizedQuery = query.trim().lowercase()
    val duplicateServerNames = remember(rooms) {
        rooms.asSequence()
            .map { it.serverNameKey }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val visibleRooms = rooms.asSequence()
        .filter { !it.isOfficialLinkAd }
        .filter { !isLobbyBlacklisted(it, blacklist) }
        .filter { normalizedQuery.isEmpty() || it.searchText.contains(normalizedQuery) }
        .filter { !onlyJoinable || it.isJoinable }
        .filter { !onlySlots || it.isHasSlots }
        .filter { !onlyBattleReady || it.status == "战役室" }
        .filter { !hidePassword || !it.isPassword }
        .filter { !version115 || it.versionName.equals("V1.15", ignoreCase = true) }
        .filter { !onlyVanilla || !it.isModded }
        .filter { !onlyModded || it.isModded }
        // Manual pins outrank the official server. For the remaining rooms,
        // campaign rooms outrank in-game rooms, then public Y rooms outrank
        // password/LAN N/L rooms, and ad/duplicate rooms remain at the end.
        // Native order is the stable tie-breaker inside every priority group.
        .sortedWith(compareBy<LobbyRoom> {
            when {
                pinnedNames.contains(it.serverNameKey) -> 0
                it.isOfficial -> 1
                else -> {
                    val statusRank = if (it.status == "战役室") 0 else 1
                    val visibilityRank = if (it.visibility == "Y") 0 else 1
                    val adRank = if (
                        it.isAdLike || duplicateServerNames.contains(it.serverNameKey)
                    ) 1 else 0
                    2 + statusRank * 4 + visibilityRank * 2 + adRank
                }
            }
        })
        .toList()

    LobbyThemeV6 {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.width(300.dp),
                        singleLine = true,
                        label = { Text("搜索房间") },
                        placeholder = { Text("房主、地图、模组、地址或ID") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showKeywordDialog = true }) {
                        Text("过滤词")
                    }
                    TextButton(onClick = { showBlacklistDialog = true }) {
                        Text("黑名单")
                    }
                    TextButton(onClick = { showRecentDialog = true }) {
                        Text("最近加入")
                    }
                    IconButton(onClick = callbacks::onRefreshClicked) {
                        Icon(Icons.refresh, contentDescription = "刷新")
                    }
                }

                Text(
                    "筛选",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = onlyJoinable,
                            onClick = { onlyJoinable = !onlyJoinable },
                            label = { Text("仅可加入", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = onlySlots,
                            onClick = { onlySlots = !onlySlots },
                            label = { Text("仅有空位", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = onlyBattleReady,
                            onClick = { onlyBattleReady = !onlyBattleReady },
                            label = { Text("备战中", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = hidePassword,
                            onClick = { hidePassword = !hidePassword },
                            label = { Text("隐藏密码房", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = onlyVanilla,
                            onClick = {
                                onlyVanilla = !onlyVanilla
                                if (onlyVanilla) onlyModded = false
                            },
                            label = { Text("只看原版", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = onlyModded,
                            onClick = {
                                onlyModded = !onlyModded
                                if (onlyModded) onlyVanilla = false
                            },
                            label = { Text("只看模组", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Row {
                        FilterChip(
                            modifier = Modifier.heightIn(min = 30.dp, max = 32.dp),
                            selected = version115,
                            onClick = { version115 = !version115 },
                            label = { Text("V1.15", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "搜索结果：${visibleRooms.size} / ${rooms.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LobbyHeaderV6()
                if (visibleRooms.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有匹配的房间",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        visibleRooms.forEachIndexed { index, room ->
                            LobbyRoomRowV6(
                                room = room,
                                index = index,
                                pinned = pinnedNames.contains(room.serverNameKey),
                                duplicateServerName = duplicateServerNames.contains(
                                    room.serverNameKey
                                ),
                                onClick = { callbacks.onRoomClicked(room) },
                                onLongClick = { actionRoom = room }
                            )
                        }
                    }
                }
            }
        }

    actionRoom?.let { room ->
        LobbyRoomActionDialogV6(
            room = room,
            pinned = pinnedNames.contains(room.serverNameKey),
            onDismiss = { actionRoom = null },
            onTogglePin = {
                pinnedNames = if (pinnedNames.contains(room.serverNameKey)) {
                    pinnedNames - room.serverNameKey
                } else {
                    pinnedNames + room.serverNameKey
                }
                saveLobbyPinnedNames(preferences, pinnedNames)
                actionRoom = null
            },
            onBlacklist = {
                if (room.serverNameKey.isNotBlank()) {
                    val entry = LobbyBlacklistEntryV6(
                        room.serverNameKey,
                        room.hostName,
                        room.addressKey,
                        room.addressDisplay
                    )
                    if (blacklist.none {
                            it.nameKey == entry.nameKey && it.addressKey == entry.addressKey
                        }
                    ) {
                        blacklist = blacklist + entry
                        saveLobbyBlacklist(preferences, blacklist)
                    }
                }
                actionRoom = null
            }
        )
    }
    if (showKeywordDialog) {
        LobbyKeywordDialogV6(
            preferences = preferences,
            onDismiss = { showKeywordDialog = false },
            onSaved = {
                callbacks.onKeywordsChanged()
                showKeywordDialog = false
            }
        )
    }
    if (showBlacklistDialog) {
        LobbyBlacklistDialogV6(
            entries = blacklist,
            onDismiss = { showBlacklistDialog = false },
            onRemove = { entry ->
                blacklist = blacklist - entry
                saveLobbyBlacklist(preferences, blacklist)
            }
        )
    }
    if (showRecentDialog) {
        val currentRooms = remember(rooms) {
            rooms.associateBy { it.historyKey }
        }
        LobbyRecentDialogV6(
            recentRooms = recentRooms,
            currentRooms = currentRooms,
            onDismiss = { showRecentDialog = false },
            onJoin = { room ->
                showRecentDialog = false
                callbacks.onRoomClicked(room)
            }
        )
    }
    }
}

@Composable
private fun LobbyHeaderV6() {
    LobbyCellsV6(
        values = listOf("状态", "房主/服务器", "人数", "地图", "模组", "版本", "可见性"),
        header = true,
        official = false
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LobbyRoomRowV6(
    room: LobbyRoom,
    index: Int,
    pinned: Boolean,
    duplicateServerName: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val rowColor = when {
        pinned -> MaterialTheme.colorScheme.tertiaryContainer
        room.isOfficial -> MaterialTheme.colorScheme.primaryContainer
        room.isAdLike || duplicateServerName ->
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
        index % 3 == 0 -> MaterialTheme.colorScheme.surfaceContainerLow
        index % 3 == 1 -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.medium,
        color = rowColor,
        tonalElevation = if (pinned || room.isOfficial) 2.dp else 0.dp
    ) {
        LobbyCellsV6(
            values = listOf(
                room.status,
                room.hostName,
                room.playersLabel,
                room.mapName,
                room.modName,
                room.versionName,
                room.visibility
            ),
            header = false,
            official = room.isOfficial
        )
    }
}

@Composable
private fun LobbyCellsV6(
    values: List<String>,
    header: Boolean,
    official: Boolean
) {
    val weights = listOf(0.45f, 2.15f, 0.60f, 1.95f, 1.05f, 0.60f, 0.35f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = if (header) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        values.forEachIndexed { index, value ->
            val important = index == 1 || index == 3
            Text(
                text = value,
                modifier = Modifier.weight(weights[index]).padding(end = 2.dp),
                style = when {
                    header -> MaterialTheme.typography.labelSmall
                    important -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.labelSmall
                },
                fontWeight = when {
                    header -> FontWeight.Medium
                    official && index == 1 -> FontWeight.Bold
                    important -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                color = if (header) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LobbyKeywordDialogV6(
    preferences: SharedPreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var draft by remember {
        mutableStateOf(
            loadLobbyKeywordsV6(
                lobbyAdKeywordsRaw(preferences)
            )
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(390.dp).height(520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.fillMaxHeight().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "广告房过滤关键词",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "包含任意关键词的房间会自动降权，每行一个关键词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    draft.forEachIndexed { index, keyword ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = keyword,
                                onValueChange = { value ->
                                    draft = draft.toMutableList().also { it[index] = value }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("关键词 ${index + 1}") }
                            )
                            IconButton(
                                onClick = {
                                    draft = draft.toMutableList().also { it.removeAt(index) }
                                }
                            ) {
                                Icon(Icons.delete, contentDescription = "删除关键词")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { draft = draft + "" }) {
                        Icon(Icons.add, contentDescription = null)
                        Text("新增关键词", modifier = Modifier.padding(start = 6.dp))
                    }
                    Row {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(
                            onClick = {
                                val serialized = draft
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .distinct()
                                    .joinToString("\n")
                                preferences.edit()
                                    .putString(KEY_LOBBY_AD_KEYWORDS, serialized)
                                    .putString(
                                        KEY_LOBBY_AD_KEYWORDS_VERSION,
                                        LOBBY_AD_KEYWORDS_VERSION
                                    )
                                    .apply()
                                onSaved()
                            }
                        ) {
                            Text("应用")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LobbyRoomActionDialogV6(
    room: LobbyRoom,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onBlacklist: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(330.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("房间操作", style = MaterialTheme.typography.titleLarge)
                Text(room.hostName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${room.addressDisplay}  ${room.mapName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onTogglePin
                ) {
                    Text(if (pinned) "取消置顶" else "置顶")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBlacklist
                ) {
                    Text("拉黑")
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                ) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun LobbyBlacklistDialogV6(
    entries: List<LobbyBlacklistEntryV6>,
    onDismiss: () -> Unit,
    onRemove: (LobbyBlacklistEntryV6) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(380.dp).heightIn(max = 500.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("黑名单", style = MaterialTheme.typography.titleLarge)
                if (entries.isEmpty()) {
                    Text(
                        "暂无黑名单房间",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 350.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.nameDisplay,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        entry.addressDisplay,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onRemove(entry) }) {
                                    Icon(Icons.delete, contentDescription = "移除黑名单")
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun LobbyRecentDialogV6(
    recentRooms: List<RecentLobbyRoomV6>,
    currentRooms: Map<String, LobbyRoom>,
    onDismiss: () -> Unit,
    onJoin: (LobbyRoom) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.width(400.dp).height(520.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 5.dp
        ) {
            Column(
                modifier = Modifier.fillMaxHeight().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("最近加入", style = MaterialTheme.typography.titleLarge)
                if (recentRooms.isEmpty()) {
                    Text(
                        "暂无最近加入的房间",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        recentRooms.forEach { recent ->
                            val current = currentRooms[recent.historyKey]
                            val summary = listOf(
                                recent.mapName.takeIf { it.isNotBlank() }?.let { "地图 $it" },
                                recent.playersLabel.takeIf { it.isNotBlank() }?.let { "人数 $it" },
                                recent.modName.takeIf { it.isNotBlank() }?.let { "模组 $it" },
                                recent.versionName.takeIf { it.isNotBlank() },
                                recent.visibility.takeIf { it.isNotBlank() }
                            ).filterNotNull().joinToString(" · ")
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            recent.hostName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (summary.isNotBlank()) {
                                            Text(
                                                summary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (current != null) {
                                        TextButton(onClick = { onJoin(current) }) {
                                            Text("加入")
                                        }
                                    } else {
                                        Text(
                                            "已离线",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun loadLobbyKeywordsV6(raw: String?): List<String> =
    raw.orEmpty().split("\r?\n".toRegex())
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private fun lobbyAdKeywordsRaw(preferences: SharedPreferences): String =
    if (preferences.getString(KEY_LOBBY_AD_KEYWORDS_VERSION, null)
        != LOBBY_AD_KEYWORDS_VERSION
    ) {
        preferences.edit()
            .remove(KEY_LOBBY_AD_KEYWORDS)
            .putString(KEY_LOBBY_AD_KEYWORDS_VERSION, LOBBY_AD_KEYWORDS_VERSION)
            .apply()
        DEFAULT_LOBBY_AD_KEYWORDS
    } else {
        preferences.getString(KEY_LOBBY_AD_KEYWORDS, DEFAULT_LOBBY_AD_KEYWORDS)
            ?: DEFAULT_LOBBY_AD_KEYWORDS
    }

private fun loadLobbyPinnedNames(preferences: SharedPreferences): Set<String> =
    preferences.getStringSet(KEY_LOBBY_PINNED_NAMES, emptySet())?.toSet().orEmpty()

private fun saveLobbyPinnedNames(
    preferences: SharedPreferences,
    names: Set<String>
) {
    preferences.edit().putStringSet(KEY_LOBBY_PINNED_NAMES, names).apply()
}

private fun loadLobbyBlacklist(
    preferences: SharedPreferences
): List<LobbyBlacklistEntryV6> {
    val raw = preferences.getString(KEY_LOBBY_BLACKLIST, "[]").orEmpty()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            LobbyBlacklistEntryV6(
                item.optString("nameKey"),
                item.optString("nameDisplay"),
                item.optString("addressKey"),
                item.optString("addressDisplay")
            )
        }
    }.getOrDefault(emptyList())
}

private fun saveLobbyBlacklist(
    preferences: SharedPreferences,
    entries: List<LobbyBlacklistEntryV6>
) {
    val array = JSONArray()
    entries.forEach { entry ->
        array.put(
            JSONObject()
                .put("nameKey", entry.nameKey)
                .put("nameDisplay", entry.nameDisplay)
                .put("addressKey", entry.addressKey)
                .put("addressDisplay", entry.addressDisplay)
        )
    }
    preferences.edit().putString(KEY_LOBBY_BLACKLIST, array.toString()).apply()
}

private fun isLobbyBlacklisted(
    room: LobbyRoom,
    entries: List<LobbyBlacklistEntryV6>
): Boolean = entries.any {
    it.nameKey == room.serverNameKey && it.addressKey == room.addressKey
}

@Composable
private fun LobbyThemeV6(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val uiPreferences = remember(context) {
        UiPreferences.load(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (uiPreferences.themeMode) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        UI_THEME_SYSTEM -> systemDark
        else -> systemDark
    }
    val colorScheme = if (uiPreferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (useDarkTheme) {
            androidx.compose.material3.dynamicDarkColorScheme(context)
        } else {
            androidx.compose.material3.dynamicLightColorScheme(context)
        }
    } else if (useDarkTheme) {
        androidx.compose.material3.darkColorScheme()
    } else {
        androidx.compose.material3.lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
