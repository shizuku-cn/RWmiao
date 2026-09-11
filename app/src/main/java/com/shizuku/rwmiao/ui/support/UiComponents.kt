package com.shizuku.rwmiao.ui.support

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flask.colorpicker.ColorPickerView
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.DEFAULT_FACTORY_PLAYER_FILTER
import java.util.Locale

@Composable
internal fun SectionCard(
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val animatedContainerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(240),
        label = "block background color"
    )
    Card(
        modifier = Modifier
            .padding(0.dp)
            .fillMaxWidth()
            .animateContentSize(tween(300)),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = animatedContainerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 16.dp)) {
            content()
        }
    }
}

@Composable
internal fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressColor by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        animationSpec = tween(120),
        label = "setting press color"
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        animationSpec = tween(200),
        label = "setting title color"
    )
    val descriptionColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        animationSpec = tween(200),
        label = "setting description color"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(pressColor, MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = descriptionColor
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            interactionSource = interactionSource
        )
    }
}

@Composable
internal fun SmallSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun NumberSetting(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    onValue: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValue((value - 1).coerceAtLeast(min)) },
                enabled = value > min
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                value.toString(),
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(
                onClick = { onValue((value + 1).coerceAtMost(max)) },
                enabled = value < max
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
internal fun DividerSetting() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 3.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}

@Composable
internal fun ThresholdRow(
    title: String,
    description: String,
    value: Int,
    onValue: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }.take(3)
                text = filtered
                filtered.toIntOrNull()?.let { onValue(it.coerceIn(1, 200)) }
            },
            modifier = Modifier
                .widthIn(min = 86.dp, max = 110.dp)
                .heightIn(min = 56.dp),
            singleLine = true,
            label = { Text("阈值") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
internal fun PlayerFilter(title: String, selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("仅自己" to 0, "仅敌人" to 1, "所有人" to 2).forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(label) },
                    leadingIcon = if (selected == value) {
                        {
                            Icon(
                                imageVector = Icons.check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
internal fun PlayerFilterWithAlly(title: String, selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "仅自己" to 0,
                "仅敌人" to 1,
                "仅队友" to 2,
                "所有人" to DEFAULT_FACTORY_PLAYER_FILTER
            ).forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(label) },
                    leadingIcon = if (selected == value) {
                        {
                            Icon(
                                imageVector = Icons.check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
internal fun UnitTypeFilter(mask: Int, onMask: (Int) -> Unit) {
    val names = listOf("建筑" to 1, "陆军" to 2, "海军" to 4, "空军" to 8, "悬浮" to 16)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "单位类型",
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            names.forEach { (name, bit) ->
                val selected = (mask and bit) != 0
                FilterChip(
                    selected = selected,
                    onClick = {
                        onMask(if (selected) mask and bit.inv() else mask or bit)
                    },
                    label = { Text(name) },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
internal fun ColorEditor(
    title: String,
    color: Int,
    modifier: Modifier = Modifier,
    onColor: (Int) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 32.dp)
                .background(Color(color), MaterialTheme.shapes.small)
                .clickable { showPicker = true }
        )
    }
    if (showPicker) {
        ColorPickerDialog(
            title = title,
            initialColor = color,
            onDismiss = { showPicker = false },
            onConfirm = {
                onColor(it)
                showPicker = false
            }
        )
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val context = LocalContext.current
    val pickerContext = remember(context) {
        runCatching {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                android.content.Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrElse { context }
    }
    var selectedColor by remember(initialColor) { mutableStateOf(initialColor) }
    var argbText by remember(initialColor) { mutableStateOf(formatArgb(initialColor)) }
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var brightness by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var alpha by remember(initialColor) {
        mutableStateOf(android.graphics.Color.alpha(initialColor) / 255f)
    }
    val controls = remember(pickerContext, initialColor) {
        val wheel = ColorPickerView(pickerContext).apply {
            setDensity(12)
            setShowBorder(false)
        }
        wheel.setInitialColor(initialColor, true)
        wheel.addOnColorChangedListener {
            selectedColor = it
            argbText = formatArgb(it)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(it, hsv)
            brightness = hsv[2]
            alpha = android.graphics.Color.alpha(it) / 255f
        }
        PickerControls(wheel)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 420.dp, max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.width(280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        factory = { controls.wheel }
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color(selectedColor), MaterialTheme.shapes.medium)
                        )
                        OutlinedTextField(
                            value = argbText,
                            onValueChange = { value ->
                                argbText = value.uppercase(Locale.US).take(9)
                                parseArgb(argbText)?.let {
                                    controls.wheel.setColor(it, true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("ARGB") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            )
                        )
                    }
                    Text("亮度", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = brightness,
                        onValueChange = {
                            brightness = it
                            controls.wheel.setLightness(it)
                            selectedColor = controls.wheel.selectedColor
                            argbText = formatArgb(selectedColor)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("透明度", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = alpha,
                        onValueChange = {
                            alpha = it
                            controls.wheel.setAlphaValue(it)
                            selectedColor = controls.wheel.selectedColor
                            argbText = formatArgb(selectedColor)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(onClick = { onConfirm(selectedColor) }) { Text("保存") }
                    }
                }
            }
        }
    }
}

private data class PickerControls(
    val wheel: ColorPickerView
)

private fun formatArgb(color: Int): String =
    String.format(Locale.US, "#%08X", color)

private fun parseArgb(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 8 || normalized.any { it !in "0123456789abcdefABCDEF" }) {
        return null
    }
    return normalized.toLongOrNull(16)?.toInt()
}

@Composable
internal fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            message,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
