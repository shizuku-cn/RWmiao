package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT

@Composable
internal fun ModuleInfoApp(themeMode: Int, colorMode: Int, moduleActive: Boolean) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        else -> systemDark
    }
    val colors = moduleColorScheme(colorMode, darkTheme, context)

    MaterialTheme(colorScheme = colors) {
        HomeScreen(moduleActive)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeScreen(moduleActive: Boolean) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("RWmiao", fontWeight = FontWeight.Bold)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(420)) + expandVertically(tween(420))
                ) {
                    HomeStatusCard(moduleActive)
                }
            }
            item { ModuleDetailsCard() }
            item { ProjectLinksCard() }
        }
    }
}

@Composable
private fun HomeStatusCard(moduleActive: Boolean) {
    val motion = rememberInfiniteTransition(label = "module status motion")
    val logoOffset by motion.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo float"
    )
    val logoRotation by motion.animateFloat(
        initialValue = -1.2f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo rotation"
    )
    val statusPulse by motion.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "模块状态",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    if (moduleActive) "运行正常" else "未激活",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer {
                                alpha = statusPulse
                                scaleX = statusPulse
                                scaleY = statusPulse
                            }
                            .background(
                                color = if (moduleActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Modern Xposed API 102",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "RWmiao",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "作者 Shizuku",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            ModuleLogo(
                contentDescription = "RWmiao 图标",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 118.dp, height = 102.dp)
                    .graphicsLayer {
                        translationY = logoOffset
                        rotationZ = logoRotation
                    }
            )
        }
    }
}

@Composable
private fun ModuleDetailsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Text(
                "模块信息",
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            ListItem(
                headlineContent = { Text("目标应用") },
                trailingContent = { Text("Rusted Warfare") },
                leadingContent = { Icon(Icons.module, contentDescription = null) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("最低 Android") },
                trailingContent = { Text("8.0+") },
                leadingContent = { Icon(Icons.environment, contentDescription = null) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            ListItem(
                headlineContent = { Text("作者") },
                trailingContent = { Text("Shizuku") },
                leadingContent = { Icon(Icons.settings, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun ProjectLinksCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "快速访问",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = { openModuleLink(context, MODULE_QQ_GROUP_URL) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("QQ群")
                }
                FilledTonalButton(
                    onClick = { openModuleLink(context, MODULE_GITHUB_URL) },
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Github仓库")
                }
                FilledTonalButton(
                    onClick = { openModuleLink(context, MODULE_BILIBILI_URL) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BiliBili")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.close, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ModuleLogo(
                            contentDescription = "RWmiao 图标",
                            modifier = Modifier.size(width = 150.dp, height = 130.dp)
                        )
                        Text(
                            "RWmiao",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME} · 作者 Shizuku",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("加入QQ群") },
                            supportingContent = { Text("加入QQ群聊，获取使用帮助与更新消息") },
                            leadingContent = {
                                Text(
                                    "Q",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable {
                                openModuleLink(context, MODULE_QQ_GROUP_URL)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("Github仓库") },
                            supportingContent = { Text(MODULE_GITHUB_URL) },
                            leadingContent = {
                                Icon(Icons.github, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                openModuleLink(context, MODULE_GITHUB_URL)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("哔哩哔哩") },
                            supportingContent = { Text(MODULE_BILIBILI_URL) },
                            leadingContent = {
                                Text(
                                    "B",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable {
                                openModuleLink(context, MODULE_BILIBILI_URL)
                            }
                        )
                    }
                }
            }
        }
    }
}
