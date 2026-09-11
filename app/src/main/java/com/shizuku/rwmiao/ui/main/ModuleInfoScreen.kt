package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.BuildConfig
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_DARK
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_LIGHT
import com.shizuku.rwmiao.ui.support.*

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun ModuleInfoApp(themeMode: Int, colorMode: Int, moduleActive: Boolean) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colors = moduleColorScheme(colorMode, darkTheme, context)
    var page by rememberSaveable { mutableStateOf(ModulePage.HOME) }

    MaterialTheme(colorScheme = colors) {
        BackHandler(enabled = page != ModulePage.HOME) {
            page = ModulePage.HOME
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val navigatingBack = targetState == ModulePage.HOME
                    val enterScale = if (navigatingBack) 1.1f else 0.9f
                    val exitScale = if (navigatingBack) 0.9f else 1.1f
                    (fadeIn(animationSpec = tween(200)) +
                        scaleIn(initialScale = enterScale, animationSpec = tween(200))) togetherWith
                        (fadeOut(animationSpec = tween(200)) +
                            scaleOut(targetScale = exitScale, animationSpec = tween(200)))
                },
                label = "module page transition"
            ) { targetPage ->
                ModulePageContent(
                    page = targetPage,
                    moduleActive = moduleActive,
                    onPageChange = { page = it }
                )
            }
        }
    }
}

@Composable
private fun ModulePageContent(
    page: ModulePage,
    moduleActive: Boolean,
    onPageChange: (ModulePage) -> Unit
) {
    when (page) {
        ModulePage.HOME -> HomeScreen(
            moduleActive = moduleActive,
            onOpenLogs = { onPageChange(ModulePage.LOG) },
            onOpenAbout = { onPageChange(ModulePage.ABOUT) }
        )
        ModulePage.LOG -> LogScreen(onBack = { onPageChange(ModulePage.HOME) })
        ModulePage.ABOUT -> AboutScreen(onBack = { onPageChange(ModulePage.HOME) })
    }
}

private enum class ModulePage {
    HOME,
    LOG,
    ABOUT
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeScreen(
    moduleActive: Boolean,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("RWmiao", fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { HomeStatusCard(moduleActive) }
            item { SystemInfoCard() }
            item {
                ModuleActionRow("日志", Icons.log, onOpenLogs)
            }
            item {
                ModuleActionRow("关于", Icons.info, onOpenAbout)
            }
        }
    }
}

@Composable
private fun SystemInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoEntry("API版本", "Modern Xposed API 102")
            InfoEntry("RWmiao版本", BuildConfig.VERSION_NAME)
            InfoEntry(
                "系统版本",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            InfoEntry("设备", Build.MODEL.orEmpty().ifBlank { "未知设备" })
            InfoEntry(
                "系统架构",
                Build.SUPPORTED_ABIS.firstOrNull() ?: "未知架构"
            )
        }
    }
}

@Composable
private fun InfoEntry(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                                if (moduleActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                CircleShape
                            )
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Modern Xposed API 102",
                        style = MaterialTheme.typography.bodyLarge,
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
private fun ModuleActionRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text(title) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = { Icon(Icons.chevronRight, contentDescription = null) }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LogScreen(onBack: () -> Unit) {
    var logText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        loading = true
    }

    LaunchedEffect(loading) {
        if (loading) {
            logText = loadModuleLogs()
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.arrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = ::reload) {
                        Icon(Icons.refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                logText.isNullOrBlank() -> Text(
                    "暂无 RWmiao 详细日志",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item {
                            Text(
                                logText.orEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadModuleLogs(): String = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-s", "RWmiao:V", "*:S"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.trim()
    }.getOrDefault("")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("关于", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            CircleShape
                        )
                    ) {
                        Icon(Icons.arrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
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
            item { AboutHero() }
            item {
                QuickAccessCard(
                    onQq = { openModuleLink(context, MODULE_QQ_GROUP_URL) },
                    onBilibili = { openModuleLink(context, MODULE_BILIBILI_URL) },
                    onGithub = { openModuleLink(context, MODULE_GITHUB_URL) }
                )
            }
        }
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ModuleLogo(
            contentDescription = "RWmiao 图标",
            modifier = Modifier.size(144.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "RWmiao",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickAccessCard(
    onQq: () -> Unit,
    onBilibili: () -> Unit,
    onGithub: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            LinkRow("QQ群", "获取使用帮助与更新消息，提交改进建议", "Q", onQq)
            LinkRow("Bilibili", "查看宣传视频并快快关注我~", "B", onBilibili)
            LinkRow("Github", "提交 Issue 或查看项目源码", null, onGithub)
        }
    }
}

@Composable
private fun LinkRow(
    title: String,
    description: String,
    letter: String?,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { LinkIcon(letter) },
        trailingContent = { Icon(Icons.chevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun LinkIcon(letter: String?) {
    if (letter == null) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.github, contentDescription = null)
        }
    } else {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                letter,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
