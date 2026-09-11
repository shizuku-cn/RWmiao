package com.shizuku.rwmiao.ui.main

import com.shizuku.rwmiao.ui.support.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.isSystemInDarkTheme
import com.shizuku.rwmiao.config.SettingsContract.*
import kotlinx.coroutines.delay

internal data class MenuItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
internal fun SettingsScreen(page: SettingsPage) {
    var state by remember { mutableStateOf(SettingsState.load(page.preferences)) }
    var uiPreferences by remember { mutableStateOf(UiPreferences.load(page.preferences)) }
    LaunchedEffect(uiPreferences.themeMode, uiPreferences.colorMode) {
        page.syncLauncherIcon(uiPreferences)
    }
    var selectedPage by remember {
        mutableStateOf(page.preferences.getInt(KEY_UI_SELECTED_PAGE, 0).coerceIn(0, 5))
    }
    val auxiliaryListState = rememberLazyListState()
    val drawListState = rememberLazyListState()
    val environmentListState = rememberLazyListState()
    val scriptListState = rememberLazyListState()
    val preferencesListState = rememberLazyListState()
    val menu = remember {
        listOf(
            MenuItem("辅助", Icons.assist),
            MenuItem("绘制", Icons.draw),
            MenuItem("环境", Icons.environment),
            MenuItem("脚本", Icons.script),
            MenuItem("AI", Icons.ai),
            MenuItem("模块", Icons.module)
        )
    }

    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (uiPreferences.themeMode) {
        UI_THEME_LIGHT -> false
        UI_THEME_DARK -> true
        else -> systemDark
    }
    val colorScheme = moduleColorScheme(
        colorMode = uiPreferences.colorMode,
        darkTheme = useDarkTheme,
        context = LocalContext.current
    )
    val activeListState = when (selectedPage) {
        0 -> auxiliaryListState
        1 -> drawListState
        2 -> environmentListState
        3 -> scriptListState
        5 -> preferencesListState
        else -> null
    }
    val contentScrolled = activeListState?.let {
        it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0
    } == true

    MaterialTheme(colorScheme = colorScheme) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        LaunchedEffect(page.closing) {
            if (page.closing) {
                visible = false
                delay(220)
                page.finishClose()
            }
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.96f) +
                slideInVertically(tween(240)) { it / 18 },
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.98f) +
                slideOutVertically(tween(180)) { -it / 18 }
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = Color.Transparent
                ) {
                    Spacer(Modifier.weight(1f))
                    menu.forEachIndexed { index, item ->
                        val selected = selectedPage == index
                        val iconTint by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(280),
                            label = "menu icon color"
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (selected) 1.12f else 1f,
                            animationSpec = tween(280),
                            label = "menu icon scale"
                        )
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                selectedPage = index
                                page.saveSelectedPage(index)
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = iconTint,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                                )
                            },
                            label = { Text(item.title) },
                            alwaysShowLabel = true
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val selected = menu[selectedPage]
                    val topBarColor = if (contentScrolled) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.background
                    }
                    val topBarElevation by animateDpAsState(
                        targetValue = if (contentScrolled) 3.dp else 0.dp,
                        animationSpec = tween(220),
                        label = "top bar scroll elevation"
                    )
                    TopAppBar(
                        modifier = Modifier
                            .background(topBarColor)
                            .shadow(topBarElevation, clip = false)
                            .zIndex(1f),
                        title = {
                            Text(
                                selected.title,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        actions = {
                            IconButton(onClick = { page.saveAndClose(state) }) {
                                Icon(
                                    imageVector = Icons.save,
                                    contentDescription = "保存并关闭"
                                )
                            }
                            IconButton(onClick = { page.close() }) {
                                Icon(
                                    imageVector = Icons.close,
                                    contentDescription = "关闭"
                                )
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                    )
                    AnimatedContent(
                        targetState = selectedPage,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 8 })
                        },
                        label = "page transition"
                    ) { selectedIndex ->
                        when (selectedIndex) {
                            0 -> AuxiliaryPage(state, auxiliaryListState) { state = it }
                            1 -> DrawPage(state, drawListState) { state = it }
                            2 -> EnvironmentPage(page, environmentListState)
                            3 -> ScriptPage(page, scriptListState)
                            5 -> Preferences(
                                page = page,
                                settings = uiPreferences,
                                listState = preferencesListState,
                                onSettings = {
                                    uiPreferences = it
                                    page.saveUiPreferences(it)
                                },
                                onConfigurationImported = {
                                    state = SettingsState.load(page.preferences)
                                    uiPreferences = UiPreferences.load(page.preferences)
                                    selectedPage = page.preferences.getInt(
                                        KEY_UI_SELECTED_PAGE,
                                        0
                                    ).coerceIn(0, 5)
                                }
                            )
                            else -> PlaceholderPage()
                        }
                    }
                }
            }
        }
    }
}
