package com.shizuku.rwmiao.ui.support

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.os.Build
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_DYNAMIC_COLOR
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_THEME_MODE
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import kotlin.math.max
import kotlin.math.roundToInt

object RuntimePanels {
    private val reinforceUpdates = java.util.WeakHashMap<Dialog, (ReinforcePanelData) -> Unit>()

    class ReinforceRow(
        @JvmField val key: String,
        @JvmField val title: String,
        @JvmField val subtitle: String,
        @JvmField val targetValue: Int,
        @JvmField val targetText: String,
        @JvmField val weight: Int,
        @JvmField val weightEnabled: Boolean,
        @JvmField val current: Boolean,
        @JvmField val preview: Bitmap?
    )

    class ReinforceToken(
        @JvmField val text: String,
        @JvmField val state: Int
    )

    class ReinforceGroup(
        @JvmField val label: String,
        @JvmField val tokens: List<ReinforceToken>
    )

    class ReinforcePanelData(
        @JvmField val rows: List<ReinforceRow>,
        @JvmField val groups: List<ReinforceGroup>,
        @JvmField val weightMode: Boolean
    )

    interface ScriptApplyCallback {
        fun onApply(values: BooleanArray)
    }

    class ScriptSettingOption(@JvmField val value: String, @JvmField val label: String)

    class ScriptSettingRow(
        @JvmField val key: String,
        @JvmField val name: String,
        @JvmField val description: String,
        @JvmField val type: String,
        @JvmField val component: String,
        @JvmField val value: String,
        @JvmField val min: Double?,
        @JvmField val max: Double?,
        @JvmField val step: Double?,
        @JvmField val options: List<ScriptSettingOption>
    )

    interface ScriptSettingsCallback { fun onApply(values: Array<String>) }

    @JvmStatic
    fun showScriptDeleteConfirmation(
        activity: Activity,
        scriptName: String,
        onConfirm: Runnable
    ): Dialog = showDialog(activity, 0.72f, 0.30f) { dialog ->
        RuntimeDialogSurface(
            title = "删除脚本",
            onDismiss = { dialog.dismiss() },
            body = {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("确定删除“$scriptName”吗？", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "删除后无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                TextButton(onClick = { dialog.dismiss() }) { Text("取消") }
                Button(onClick = {
                    // Dismiss the independent dialog composition before mutating the
                    // script list owned by the settings-page composition.
                    dialog.dismiss()
                    onConfirm.run()
                }) { Text("删除") }
            }
        )
    }

    interface ReinforcePanelCallback {
        fun onTargetChanged(key: String, value: Int): ReinforcePanelData
        fun onWeightChanged(key: String, value: Int): ReinforcePanelData
        fun onRemove(key: String): ReinforcePanelData
        fun onRefresh(): ReinforcePanelData
    }

    @JvmStatic
    fun showScriptManager(
        activity: Activity,
        title: String,
        labels: Array<String>,
        initial: BooleanArray,
        callback: ScriptApplyCallback
    ): Dialog {
        return showDialog(activity, 0.72f, 0.76f) { dialog ->
            var selected by remember { mutableStateOf(initial.toList()) }
            RuntimeDialogSurface(
                title = title,
                onDismiss = { dialog.dismiss() },
                body = {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        labels.forEachIndexed { index, label ->
                            val checked = selected.getOrNull(index) == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = selected.toMutableList().also {
                                            it[index] = !it[index]
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        selected = selected.toMutableList().also {
                                            it[index] = value
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { dialog.dismiss() }) { Text("取消") }
                    Button(onClick = {
                        callback.onApply(selected.toBooleanArray())
                        dialog.dismiss()
                    }) { Text("应用") }
                }
            )
        }
    }

    @JvmStatic
    fun showScriptSettings(
        activity: Activity,
        title: String,
        rows: List<ScriptSettingRow>,
        callback: ScriptSettingsCallback
    ): Dialog = showDialog(activity, 0.78f, 0.82f) { dialog ->
        var values by remember(rows) { mutableStateOf(rows.map { it.value }) }
        RuntimeDialogSurface(
            title = title,
            onDismiss = { dialog.dismiss() },
            body = {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rows.forEachIndexed { index, row ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (row.description.isNotBlank()) Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when (row.component) {
                                    "switch" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (values[index].toBoolean()) "已开启" else "已关闭", Modifier.weight(1f))
                                        Switch(checked = values[index].toBoolean(), onCheckedChange = { checked -> values = values.toMutableList().also { it[index] = checked.toString() } })
                                    }
                                    "slider" -> {
                                        val min = row.min?.toFloat() ?: 0f
                                        val max = row.max?.toFloat() ?: 1f
                                        val current = values[index].toFloatOrNull()?.coerceIn(min, max) ?: min
                                        val steps = row.step?.takeIf { it > 0.0 }?.let { ((max - min) / it.toFloat()).roundToInt().minus(1).coerceAtLeast(0) } ?: 0
                                        Text(values[index], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        Slider(
                                            value = current,
                                            onValueChange = { number -> values = values.toMutableList().also { it[index] = number.toString() } },
                                            valueRange = min..max,
                                            steps = steps
                                        )
                                    }
                                    "choice" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        row.options.forEach { option ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable { values = values.toMutableList().also { it[index] = option.value } }.padding(vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(option.label, Modifier.weight(1f))
                                                Checkbox(checked = values[index] == option.value, onCheckedChange = { if (it) values = values.toMutableList().also { list -> list[index] = option.value } })
                                            }
                                        }
                                    }
                                    else -> OutlinedTextField(
                                        value = values[index],
                                        onValueChange = { text -> values = values.toMutableList().also { it[index] = text.take(256) } },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        supportingText = if (row.type == "number" && (row.min != null || row.max != null)) { { Text("范围：${row.min ?: "-∞"} ～ ${row.max ?: "+∞"}${row.step?.let { "，步长 $it" } ?: ""}") } } else null,
                                        keyboardOptions = KeyboardOptions(keyboardType = if (row.type == "number") KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = {
                TextButton(onClick = { dialog.dismiss() }) { Text("取消") }
                Button(onClick = { callback.onApply(values.toTypedArray()); dialog.dismiss() }) { Text("应用") }
            }
        )
    }

    @JvmStatic
    fun showReinforcePanel(
        activity: Activity,
        initial: ReinforcePanelData,
        callback: ReinforcePanelCallback
    ): Dialog {
        return showDialog(activity, 0.80f, 0.90f) { dialog ->
            var data by remember { mutableStateOf(initial) }
            DisposableEffect(dialog) {
                val update: (ReinforcePanelData) -> Unit = { value -> data = value }
                synchronized(reinforceUpdates) {
                    reinforceUpdates[dialog] = update
                }
                onDispose {
                    synchronized(reinforceUpdates) {
                        reinforceUpdates.remove(dialog)
                    }
                }
            }
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
                            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "自动补兵",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { data = callback.onRefresh() }) {
                            Icon(Icons.refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { dialog.dismiss() }) {
                            Icon(Icons.close, contentDescription = "关闭")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    if (data.rows.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无补兵序列",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(data.rows, key = { it.key }) { row ->
                            ReinforceRowView(
                                row = row,
                                onTarget = { value ->
                                    data = callback.onTargetChanged(row.key, value)
                                },
                                onWeight = { value ->
                                    data = callback.onWeightChanged(row.key, value)
                                },
                                onRemove = {
                                    data = callback.onRemove(row.key)
                                }
                            )
                        }
                    }
                    if (data.groups.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                data.groups.forEach { group ->
                                    ReinforceGroupView(group)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun refreshReinforcePanel(dialog: Dialog, data: ReinforcePanelData) {
        val update = synchronized(reinforceUpdates) { reinforceUpdates[dialog] }
        update?.invoke(data)
    }

    private fun showDialog(
        activity: Activity,
        widthFraction: Float,
        heightFraction: Float,
        content: @Composable (Dialog) -> Unit
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val owner = DialogComposeViewTreeOwner()
        val composeView = ComposeView(activity)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)
        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                owner.detach()
            }
        })
        owner.attach()
        dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialog.setContentView(composeView)
        dialog.show()
        composeView.setContent {
            RuntimeTheme(activity) {
                content(dialog)
            }
        }
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attributes = window.attributes
            attributes.dimAmount = 0.32f
            window.attributes = attributes
            val metrics = activity.resources.displayMetrics
            val width = (metrics.widthPixels * widthFraction).toInt()
            val height = if (heightFraction > 0f) {
                (metrics.heightPixels * heightFraction).toInt()
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            window.setLayout(width, height)
        }
        return dialog
    }
}

private class DialogComposeViewTreeOwner : SavedStateRegistryOwner {
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

@Composable
private fun RuntimeTheme(activity: Activity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val moduleContext = remember(context) {
        runCatching {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrElse { context }
    }
    val preferences = remember(moduleContext) {
        moduleContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (preferences.getInt(KEY_UI_THEME_MODE, 0)) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        else -> systemDark
    }
    val dynamic = preferences.getBoolean(KEY_UI_DYNAMIC_COLOR, true) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = if (dynamic) {
        if (dark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun RuntimeDialogSurface(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, top = 6.dp, end = 6.dp, bottom = 2.dp).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(modifier = Modifier.size(38.dp), onClick = onDismiss) {
                    Icon(Icons.close, contentDescription = "关闭")
                }
            }
            body()
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp).heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
private fun ReinforceRowView(
    row: RuntimePanels.ReinforceRow,
    onTarget: (Int) -> Unit,
    onWeight: (Int) -> Unit,
    onRemove: () -> Unit
) {
    var targetText by remember(row.key, row.targetText) {
        mutableStateOf(row.targetText)
    }
    var weightText by remember(row.key, row.weight) {
        mutableStateOf(row.weight.toString())
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 2.dp)
                    .size(8.dp)
                    .background(
                        if (row.current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        CircleShape
                    )
            )
            if (row.preview != null) {
                androidx.compose.foundation.Image(
                    bitmap = row.preview.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.shapes.small
                        )
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (row.subtitle.isNotBlank()) {
                    Text(
                        row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FilledTonalIconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        onTarget(if (row.targetValue == -1) 0 else max(0, row.targetValue - 1))
                    }
                ) {
                    Icon(Icons.remove, contentDescription = "减少")
                }
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { value ->
                        targetText = value
                        parseTarget(value)?.let(onTarget)
                    },
                    modifier = Modifier.width(64.dp).height(48.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions {
                        parseTarget(targetText)?.let(onTarget)
                    }
                )
                FilledTonalIconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        onTarget(if (row.targetValue == -1) 1 else row.targetValue + 1)
                    }
                ) {
                    Icon(Icons.add, contentDescription = "增加")
                }
                FilledTonalIconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = { onTarget(-1) }
                ) {
                    Icon(Icons.infinite, contentDescription = "无限")
                }
                if (row.weightEnabled) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { value ->
                            weightText = value
                            value.toIntOrNull()?.let { onWeight(it.coerceAtLeast(0)) }
                        },
                        modifier = Modifier.width(58.dp).height(48.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = onRemove
                ) {
                    Icon(Icons.delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun ReinforceGroupView(group: RuntimePanels.ReinforceGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                group.label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        group.tokens.forEach { token ->
            val color = when (token.state) {
                1 -> MaterialTheme.colorScheme.primaryContainer
                2 -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Surface(shape = MaterialTheme.shapes.small, color = color) {
                Text(
                    token.text,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

private fun parseTarget(value: String): Int? {
    val normalized = value.trim()
    if (normalized == "∞") return -1
    return normalized.toIntOrNull()?.coerceAtLeast(0)
}

