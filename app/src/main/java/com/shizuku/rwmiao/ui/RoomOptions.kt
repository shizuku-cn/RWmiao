package com.shizuku.rwmiao.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_DYNAMIC_COLOR
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_THEME_MODE
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import com.shizuku.rwmiao.module.RoomOptions as RoomOptionsFeature

private class ApplyRoomOptionsException(cause: Throwable) : RuntimeException(cause)

/** M3 replacement for the game-options dialog. / 游戏选项面板。 */
class RoomOptions private constructor(
    private val activity: Activity,
    private val feature: RoomOptionsFeature,
    private val network: Any,
    private val initial: RoomOptionsFeature.RoomOptionsState
) : Dialog(activity) {
    private val owner = DialogTreeOwner()
    private val composeView = ComposeView(activity)
    private var contentInstalled = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)
        setContentView(composeView)
        setOnShowListener {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onStart() {
        super.onStart()
        owner.attach()
        if (!contentInstalled) {
            contentInstalled = true
            composeView.setContent {
                RoomOptionsTheme(activity = activity) {
                    RoomOptionsContent(
                        initial = initial,
                        onApply = { values ->
                            try {
                                feature.apply(
                                    network,
                                    values.maxPlayers,
                                    values.unitCap,
                                    values.incomeMultiplier,
                                    values.noNukes,
                                    values.sharedControl,
                                    values.creditsIndex,
                                    values.fogMode,
                                    values.startingUnits,
                                    values.aiDifficulty,
                                    values.teamLayout,
                                    values.bannedUnits
                                )
                                dismiss()
                            } catch (t: Throwable) {
                                throw ApplyRoomOptionsException(t)
                            }
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
        val metrics = activity.resources.displayMetrics
        window?.setLayout(
            (metrics.widthPixels * 0.84f).toInt(),
            (metrics.heightPixels * 0.88f).toInt()
        )
    }

    override fun onStop() {
        owner.detach()
        super.onStop()
    }

    companion object {
        @JvmStatic
        fun show(
            activity: Activity,
            feature: RoomOptionsFeature,
            network: Any
        ) {
            val state = feature.readState(network)
            RoomOptions(activity, feature, network, state).show()
        }
    }
}

private data class DialogValues(
    val maxPlayers: Int,
    val unitCap: Int,
    val incomeMultiplier: Float,
    val noNukes: Boolean,
    val sharedControl: Boolean,
    val creditsIndex: Int,
    val fogMode: Int,
    val startingUnits: Int,
    val aiDifficulty: Int,
    val teamLayout: Int,
    val bannedUnits: String
)

@Composable
private fun RoomOptionsContent(
    initial: RoomOptionsFeature.RoomOptionsState,
    onApply: (DialogValues) -> Unit,
    onCancel: () -> Unit
) {
    var maxPlayersText by remember { mutableStateOf(initial.maxPlayers.toString()) }
    var unitCapText by remember { mutableStateOf(initial.unitCap.toString()) }
    var incomeText by remember { mutableStateOf(initial.incomeMultiplier.toString()) }
    var noNukes by remember { mutableStateOf(initial.noNukes) }
    var sharedControl by remember { mutableStateOf(initial.sharedControl) }
    var creditsIndex by remember { mutableStateOf(initial.creditsIndex) }
    var fogMode by remember { mutableStateOf(initial.fogMode) }
    var startingUnits by remember { mutableStateOf(initial.startingUnits) }
    var aiDifficulty by remember { mutableStateOf(initial.aiDifficulty) }
    var teamLayout by remember { mutableStateOf(-1) }
    var banSearch by remember { mutableStateOf("") }
    var bannedSelection by remember {
        mutableStateOf(parseBannedUnits(initial.bannedUnits))
    }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 选项文案由 Java 从目标游戏资源/翻译接口读取，不在 M3 层重复翻译。
    // Labels come from the target game's resources/translation API, not duplicated here.
    val creditOptions = initial.creditsOptions.orEmpty()
    val startingUnitOptions = initial.startingUnitOptions.orEmpty()
    val fogOptions = initial.fogOptions.orEmpty()
    val aiOptions = initial.aiOptions.orEmpty()
    val teamOptions = initial.teamLayoutOptions.orEmpty()
    val incomeLabels = initial.incomeOptions?.toList().orEmpty().ifEmpty {
        listOf("1x", "1.5x", "2x", "2.5x")
    }
    val incomeValues = listOf("1", "1.5", "2", "2.5")
    val incomePresets = incomeLabels.mapIndexedNotNull { index, label ->
        incomeValues.getOrNull(index)?.let { label to it }
    }
    val availableUnits = initial.availableUnits.orEmpty()
    val filteredUnits = availableUnits.filter { option ->
        banSearch.isBlank() || option.label.contains(banSearch, ignoreCase = true) ||
            option.id.contains(banSearch, ignoreCase = true)
    }
    val knownIds = availableUnits.map { it.id.lowercase() }.toSet()
    val unknownBanned = bannedSelection.filterNot { it in knownIds }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 5.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OptionPicker("初始资金", creditsIndex, creditOptions) { creditsIndex = it }
                    OptionPicker("初始单位", startingUnits, startingUnitOptions) { startingUnits = it }
                    OptionPicker("战争迷雾", fogMode, fogOptions) { fogMode = it }
                    OptionPicker("AI 难度", aiDifficulty, aiOptions) { aiDifficulty = it }
                    OptionPicker("团队布局", teamLayout, teamOptions) { teamLayout = it }
                    NumberField(
                        value = incomeText,
                        label = "资金增长倍率",
                        keyboardType = KeyboardType.Decimal,
                        onValueChange = { incomeText = it }
                    )
                    PresetRow(incomeText, incomePresets) {
                        incomeText = it
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberField(
                        value = maxPlayersText,
                        label = "房间人数上限",
                        keyboardType = KeyboardType.Number,
                        onValueChange = { maxPlayersText = it }
                    )
                    PresetRow(maxPlayersText, listOf("10" to "10", "20" to "20", "50" to "50", "100" to "100")) { maxPlayersText = it }
                    NumberField(
                        value = unitCapText,
                        label = "单位上限",
                        keyboardType = KeyboardType.Number,
                        onValueChange = { unitCapText = it }
                    )
                    PresetRow(unitCapText, listOf("100" to "100", "250" to "250", "500" to "500", "1000" to "1000", "5000" to "5000", "10000" to "10000")) {
                        unitCapText = it
                    }
                    ToggleLine("禁用核弹", noNukes) { noNukes = it }
                    ToggleLine("允许共享控制", sharedControl) { sharedControl = it }

                    var banExpanded by remember { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { banExpanded = !banExpanded },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("全局 Ban 单位", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (bannedSelection.isEmpty()) "未选择"
                                    else "已选择 ${bannedSelection.size} 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(if (banExpanded) "▲" else "▼")
                        }
                    }

                    if (banExpanded) {
                        OutlinedTextField(
                            value = banSearch,
                            onValueChange = { banSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("搜索单位") },
                            singleLine = true
                        )
                        if (availableUnits.isEmpty()) {
                            Text(
                                "没有读取到沙盒 All 单位列表",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (filteredUnits.isEmpty()) {
                            Text("没有匹配的单位", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Column {
                                filteredUnits.forEach { option ->
                                    UnitChoiceRow(
                                        option = option,
                                        checked = option.id.lowercase() in bannedSelection,
                                        onCheckedChange = { checked ->
                                            val id = option.id.lowercase()
                                            bannedSelection = if (checked) {
                                                bannedSelection + id
                                            } else {
                                                bannedSelection - id
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (unknownBanned.isNotEmpty()) {
                            Text(
                                "未列出的已保存 Ban：${unknownBanned.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            if (errorText != null) {
                Text(
                    errorText!!,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Spacer(Modifier.padding(horizontal = 2.dp))
                Button(onClick = {
                    val maxPlayers = maxPlayersText.toIntOrNull()
                    val unitCap = unitCapText.toIntOrNull()
                    val income = incomeText.toFloatOrNull()
                    if (maxPlayers == null || unitCap == null || income == null) {
                        errorText = "人数、单位上限和倍率必须是有效数字"
                    } else {
                        errorText = null
                        try {
                            onApply(
                                DialogValues(
                                    maxPlayers,
                                    unitCap,
                                    income,
                                    noNukes,
                                    sharedControl,
                                    creditsIndex,
                                    fogMode,
                                    startingUnits,
                                    aiDifficulty,
                                    teamLayout,
                                    bannedSelection.joinToString(",")
                                )
                            )
                        } catch (t: ApplyRoomOptionsException) {
                            errorText = t.cause?.message ?: "应用房间设置失败"
                        }
                    }
                }) { Text("应用") }
            }
        }
    }
}

private fun parseBannedUnits(raw: String?): Set<String> {
    return raw.orEmpty().split(',', ';', ' ', '\n', '\r', '\t')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
private fun ToggleLine(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun UnitChoiceRow(
    option: RoomOptionsFeature.UnitOption,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                option.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OptionPicker(
    title: String,
    selected: Int,
    options: List<RoomOptionsFeature.IntOption>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label ?: selected.toString()
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                        Text(
                            selectedLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
                Text(if (expanded) "▲" else "▼")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    onClick = {
                        onSelected(option.value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    // Keep all presets on one bounded row; do not nest a horizontal scroller.
    // 预设按钮组保持在有界的单行中，不再嵌套横向滚动容器。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = {
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RoomOptionsTheme(activity: Activity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val moduleContext = remember(context) {
        runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse { context }
    }
    val localPreferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val modulePreferences = remember(moduleContext) {
        moduleContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val preferences: SharedPreferences = if (
        localPreferences.contains(KEY_UI_THEME_MODE) ||
        localPreferences.contains(KEY_UI_DYNAMIC_COLOR)
    ) localPreferences else modulePreferences
    val dark = when (preferences.getInt(KEY_UI_THEME_MODE, 0)) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val dynamic = preferences.getBoolean(KEY_UI_DYNAMIC_COLOR, true) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = if (dynamic) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(activity)
        else androidx.compose.material3.dynamicLightColorScheme(activity)
    } else {
        if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private class DialogTreeOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(Bundle())
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun attach() {
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun detach() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }
}
