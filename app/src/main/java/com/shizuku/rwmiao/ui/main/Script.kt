package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.module.script.ScriptManager
import com.shizuku.rwmiao.ui.support.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
internal fun ScriptPage(page: SettingsPage, listState: LazyListState) {
    var refreshKey by remember(page.scriptManager) { mutableIntStateOf(0) }
    var master by remember(page.scriptManager) {
        mutableStateOf(page.scriptManager?.masterEnabled() ?: false)
    }
    var records by remember(page.scriptManager) {
        mutableStateOf(emptyList<ScriptManager.Record>())
    }
    var dragRecords by remember(page.scriptManager) {
        mutableStateOf(emptyList<ScriptManager.Record>())
    }
    var editMode by remember(page.scriptManager) { mutableStateOf(false) }
    var selectedIds by remember(page.scriptManager) { mutableStateOf(emptySet<String>()) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedTop by remember { mutableFloatStateOf(0f) }
    var draggedBaseOffset by remember { mutableFloatStateOf(0f) }

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
    LaunchedEffect(refreshKey, page.scriptManager) {
        val loaded = page.scriptManager?.records()?.toList().orEmpty()
        records = loaded
        if (draggedId == null) dragRecords = loaded
    }
    LaunchedEffect(records) {
        val validIds = records.mapTo(HashSet()) { it.id }
        selectedIds = selectedIds.filterTo(linkedSetOf()) { it in validIds }
    }
    LaunchedEffect(master) {
        if (!master) {
            editMode = false
            selectedIds = emptySet()
        }
    }
    fun toggleSelected(id: String) {
        selectedIds = selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun moveRecord(id: String, delta: Float) {
        if (draggedId != id) return
        val currentIndex = dragRecords.indexOfFirst { it.id == id }
        val draggedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
        if (currentIndex < 0 || draggedItem == null) return

        val nextTop = draggedTop + delta
        draggedTop = nextTop
        val direction = delta.compareTo(0f)
        if (direction == 0) return
        val center = nextTop + draggedItem.size / 2f
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val targetIndex = if (direction > 0) {
            (currentIndex + 1..dragRecords.lastIndex).lastOrNull { index ->
                visibleItems.firstOrNull { it.key == dragRecords[index].id }
                    ?.let { center > it.offset + it.size / 2f } == true
            }
        } else {
            (currentIndex - 1 downTo 0).lastOrNull { index ->
                visibleItems.firstOrNull { it.key == dragRecords[index].id }
                    ?.let { center < it.offset + it.size / 2f } == true
            }
        } ?: return

        val target = visibleItems.firstOrNull { it.key == dragRecords[targetIndex].id } ?: return
        val reordered = dragRecords.toMutableList()
        reordered.add(targetIndex, reordered.removeAt(currentIndex))
        dragRecords = reordered
        draggedBaseOffset = target.offset.toFloat()
    }

    val currentMoveRecord = rememberUpdatedState<(String, Float) -> Unit> { id, delta ->
        moveRecord(id, delta)
    }
    val displayedRecords = if (draggedId == null) records else dragRecords
    val draggedOffset = if (draggedId != null) {
        draggedTop - draggedBaseOffset
    } else {
        0f
    }
    val dragHandleHitWidth = with(LocalDensity.current) { 64.dp.toPx() }
    val currentDragTarget = rememberUpdatedState<(Float, Float) -> String?> { x, y ->
        if (!editMode || x < listState.layoutInfo.viewportSize.width - dragHandleHitWidth) {
            null
        } else {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { item ->
                    y >= item.offset && y < item.offset + item.size &&
                        records.any { record -> record.id == item.key }
                }
                ?.key as? String
        }
    }
    val currentDragStart = rememberUpdatedState<(String) -> Unit> { id ->
        dragRecords = records
        draggedId = id
        draggedBaseOffset = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == id }?.offset?.toFloat() ?: 0f
        draggedTop = draggedBaseOffset
    }
    val currentDragEnd = rememberUpdatedState {
        val finalRecords = dragRecords
        records = finalRecords
        page.scriptManager?.reorder(finalRecords.map { it.id })
        draggedId = null
        draggedTop = 0f
        draggedBaseOffset = 0f
        dragRecords = emptyList()
    }

    fun requestDelete() {
        val selected = records.filter { it.id in selectedIds }
        if (selected.isEmpty()) return
        RuntimePanels.showScriptBatchDeleteConfirmation(
            page.hostActivity,
            selected.map { it.name },
            Runnable {
                selected.forEach { record -> page.scriptManager?.delete(record.id) }
                selectedIds = emptySet()
                editMode = false
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = currentDragTarget.value(down.position.x, down.position.y)
                    if (id == null) {
                        waitForUpOrCancellation()
                    } else {
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress != null) {
                            currentDragStart.value(id)
                            var previousPosition = longPress.position.y
                            drag(longPress.id) { change ->
                                val delta = change.position.y - previousPosition
                                previousPosition = change.position.y
                                if (delta != 0f) {
                                    change.consume()
                                    currentMoveRecord.value(id, delta)
                                }
                            }
                            currentDragEnd.value()
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item(key = "script-master-switch") {
                SmallSwitchRow("脚本总开关", master) {
                    master = it
                    page.scriptManager?.setMasterEnabled(it)
                }
            }
            if (master) {
                item(key = "script-master-gap") { Spacer(Modifier.size(8.dp)) }
                item(key = "script-header") {
                    SectionCard {
                        ScriptListHeader(
                            count = records.size,
                            editMode = editMode,
                            canImport = page.scriptManager != null,
                            onImport = {
                                page.scriptManager?.beginImport(page.hostActivity, null)
                            },
                            onFinishEdit = {
                                editMode = false
                                selectedIds = emptySet()
                            }
                        )
                    }
                }
                when {
                    page.scriptManager == null -> item(key = "script-load-error") {
                        Spacer(Modifier.size(8.dp))
                        EmptyCard("脚本框架未能加载，请查看脚本日志。")
                    }
                    records.isEmpty() -> item(key = "script-empty") {
                        Spacer(Modifier.size(8.dp))
                        EmptyCard("暂无脚本")
                    }
                    else -> {
                        item(key = "script-list-gap") { Spacer(Modifier.size(8.dp)) }
                        itemsIndexed(
                            items = displayedRecords,
                            key = { _, record -> record.id }
                        ) { index, record ->
                            val itemModifier = if (record.id == draggedId) {
                                Modifier
                            } else {
                                Modifier.animateItem(placementSpec = tween(180))
                            }
                            ScriptRow(
                                record = record,
                                index = index,
                                isLast = index == displayedRecords.lastIndex,
                                editMode = editMode,
                                selected = record.id in selectedIds,
                                dragging = record.id == draggedId,
                                draggedOffset = draggedOffset,
                                onToggleSelected = ::toggleSelected,
                                onDetails = {
                                    RuntimePanels.showScriptDetails(
                                        page.hostActivity,
                                        record.name,
                                        record.description,
                                        record.units
                                    )
                                },
                                onSettings = {
                                    page.scriptManager?.openSettings(page.hostActivity, record.id)
                                },
                                onEnabledChange = { enabled ->
                                    page.scriptManager?.setEnabled(record.id, enabled)
                                    refreshKey += 1
                                },
                                onLongPress = { editMode = true },
                                modifier = itemModifier
                                    .padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        ScriptEditFab(
            editMode = editMode,
            canDelete = selectedIds.isNotEmpty(),
            onEdit = { editMode = true },
            onDelete = ::requestDelete,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalAnimationApi::class)
private fun ScriptListHeader(
    count: Int,
    editMode: Boolean,
    canImport: Boolean,
    onImport: () -> Unit,
    onFinishEdit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Bottom
        ) {
            Text("脚本列表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "（$count）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedContent(
            targetState = editMode,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(180))) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(140)))
            },
            label = "script header action"
        ) { editing ->
            if (editing) {
                TextButton(onClick = onFinishEdit) { Text("完成") }
            } else {
                FilledTonalButton(onClick = onImport, enabled = canImport) {
                    Icon(Icons.import, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("导入脚本")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
private fun ScriptRow(
    record: ScriptManager.Record,
    index: Int,
    isLast: Boolean,
    editMode: Boolean,
    selected: Boolean,
    dragging: Boolean,
    draggedOffset: Float,
    onToggleSelected: (String) -> Unit,
    onDetails: () -> Unit,
    onSettings: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowInteraction = if (editMode) {
        Modifier
    } else {
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
    }
    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1.03f else 1f,
        animationSpec = if (dragging) snap() else tween(180),
        label = "script drag scale"
    )
    val dragElevation by animateDpAsState(
        targetValue = if (dragging) 8.dp else 0.dp,
        animationSpec = tween(180),
        label = "script drag elevation"
    )
    val dragBorderAlpha by animateFloatAsState(
        targetValue = if (dragging) 0.45f else 0f,
        animationSpec = tween(180),
        label = "script drag border"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .then(rowInteraction),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = if (dragging) draggedOffset else 0f
                    scaleX = dragScale
                    scaleY = dragScale
                },
            shape = RoundedCornerShape(
                topStart = if (index == 0) 16.dp else 0.dp,
                topEnd = if (index == 0) 16.dp else 0.dp,
                bottomStart = if (isLast) 16.dp else 0.dp,
                bottomEnd = if (isLast) 16.dp else 0.dp
            ),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = dragElevation,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = dragBorderAlpha)
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = editMode,
                        modifier = Modifier.size(48.dp),
                        transitionSpec = {
                            (fadeIn(tween(140)) + scaleIn(tween(160))) togetherWith
                                (fadeOut(tween(100)) + scaleOut(tween(120)))
                        },
                        label = "script leading action"
                    ) { editing ->
                        if (editing) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { onToggleSelected(record.id) }
                            )
                        } else {
                            IconButton(onClick = onDetails) {
                                Icon(Icons.scriptDetails, contentDescription = "脚本详情")
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(record.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            record.description.ifBlank { "暂无描述" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    AnimatedVisibility(
                        visible = !editMode && record.enabled && record.hasSettings,
                        enter = fadeIn(tween(180)) + expandHorizontally(tween(220)) + scaleIn(tween(220)),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180)) + scaleOut(tween(160))
                    ) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.settings, contentDescription = "脚本设置")
                        }
                    }
                    Switch(
                        checked = record.enabled,
                        onCheckedChange = onEnabledChange
                    )
                    val sortSlotWidth by animateDpAsState(
                        targetValue = if (editMode) 48.dp else 0.dp,
                        animationSpec = tween(220),
                        label = "script reorder slot"
                    )
                    Box(
                        modifier = Modifier.width(sortSlotWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        if (editMode) {
                            Icon(Icons.reorder, contentDescription = "长按拖动排序")
                        }
                    }
                }
                if (!isLast) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 58.dp, end = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalAnimationApi::class)
private fun ScriptEditFab(
    editMode: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = editMode to canDelete,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(160)) + scaleIn(tween(180))) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(140)))
        },
        label = "script floating action"
    ) { (editing, canDeleteSelection) ->
        when {
            !editing -> FloatingActionButton(onClick = onEdit) {
                Icon(Icons.draw, contentDescription = "编辑脚本")
            }
            canDeleteSelection -> FloatingActionButton(
                onClick = onDelete,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Icon(Icons.delete, contentDescription = "删除脚本")
            }
            else -> Surface(
                modifier = Modifier.size(56.dp),
                shape = FloatingActionButtonDefaults.shape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                tonalElevation = 6.dp
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.delete, contentDescription = "未选择脚本")
                }
            }
        }
    }
}
