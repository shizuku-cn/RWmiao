package com.shizuku.rwmiao.ui.main

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shizuku.rwmiao.config.SettingsContract.*
import com.shizuku.rwmiao.app.LauncherIconController
import com.shizuku.rwmiao.module.RWmiaoModule
import com.shizuku.rwmiao.module.script.ScriptManager

class SettingsPage(
    internal val hostActivity: Activity,
    private val closeAction: Runnable?,
    private val savedAction: Runnable?,
    internal val scriptManager: ScriptManager?
) : FrameLayout(hostActivity) {

    internal val preferences = hostActivity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val owner = ComposeViewTreeOwner()
    private val composeView = ComposeView(hostActivity)
    internal var closing by mutableStateOf(false)
        private set
    private var contentInstalled = false
    private var closeCompleted = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setFocusable(false)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
    }

    override fun onAttachedToWindow() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        owner.attach()
        super.onAttachedToWindow()
        if (composeView.parent == null) {
            composeView.setViewTreeLifecycleOwner(owner)
            composeView.setViewTreeSavedStateRegistryOwner(owner)
            addView(
                composeView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        if (!contentInstalled) {
            contentInstalled = true
            composeView.setContent { SettingsScreen(this@SettingsPage) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        owner.detach()
    }

    internal fun saveAndClose(state: SettingsState) {
        val threshold = state.smartPathingThreshold.coerceIn(1, 200)
        preferences.edit()
            .putBoolean(KEY_NO_FOG, state.noFog)
            .putBoolean(KEY_ENEMY_TEAM_CHAT, state.enemyTeamChat)
            .putBoolean(KEY_ENEMY_MAP_PINGS, state.enemyMapPings)
            .putBoolean(KEY_SHOW_RANGE_ACTION, state.showRangeAction)
            .putBoolean(KEY_SHOW_LINE_ACTION, state.showLineAction)
            .putBoolean(KEY_SHOW_ATTACK_RANGE, state.showAttackRange)
            .putBoolean(KEY_SHOW_TARGET_LINE, state.showTargetLine)
            .putBoolean(KEY_SHOW_FACTORY_COUNTDOWN, state.showFactoryCountdown)
            .putBoolean(KEY_VIEW_ALL, state.viewAll)
            .putBoolean(KEY_PLAYER_INFO_PANEL, state.playerInfoPanel)
            .putBoolean(KEY_FACTORY_OPT, state.factoryOpt)
            .putBoolean(KEY_FACTORY_EXIT_THROUGH, state.factoryExitThrough)
            .putBoolean(KEY_REINFORCE_ON, state.reinforceOn)
            .putBoolean(KEY_REINFORCE_WEIGHT_MODE, state.reinforceWeightMode)
            .putBoolean(KEY_SHOW_REINFORCE_PANEL, state.showReinforcePanel)
            .putBoolean(KEY_FREE_SELECTION, state.freeSelection)
            .putBoolean(KEY_FREE_BUILD, state.freeBuild)
            .putBoolean(KEY_SELECT_ALL, state.selectAll)
            .putBoolean(KEY_COMBAT_VIEW, state.combatView)
            .putBoolean(KEY_SEGMENT_COMMAND, state.segmentCommand)
            .putBoolean(KEY_SMART_PATHING, state.smartPathing)
            .putBoolean(KEY_SHOW_SMART_PATH_ACTION, state.showSmartPathAction)
            .putInt(KEY_SMART_PATHING_THRESHOLD, threshold)
            .putInt(KEY_RANGE_PLAYER_FILTER, state.rangePlayerFilter)
            .putInt(KEY_RANGE_UNIT_TYPES, state.rangeUnitTypes)
            .putInt(KEY_LINE_PLAYER_FILTER, state.linePlayerFilter)
            .putInt(KEY_LINE_UNIT_TYPES, state.lineUnitTypes)
            .putInt(KEY_FACTORY_PLAYER_FILTER, state.factoryPlayerFilter)
            .putInt(KEY_RANGE_COLOR_SELF, state.rangeColors[0])
            .putInt(KEY_RANGE_COLOR_ENEMY, state.rangeColors[1])
            .putInt(KEY_RANGE_COLOR_ALLY, state.rangeColors[2])
            .putInt(KEY_LINE_COLOR_SELF, state.lineColors[0])
            .putInt(KEY_LINE_COLOR_ENEMY, state.lineColors[1])
            .putInt(KEY_LINE_COLOR_ALLY, state.lineColors[2])
            .putInt(KEY_FACTORY_COLOR_SELF, state.factoryColors[0])
            .putInt(KEY_FACTORY_COLOR_ENEMY, state.factoryColors[1])
            .putInt(KEY_FACTORY_COLOR_ALLY, state.factoryColors[2])
            .apply()
        savedAction?.run()
        close()
    }

    internal fun close() {
        if (!closing) closing = true
    }

    internal fun finishClose() {
        if (closeCompleted) return
        closeCompleted = true
        closeAction?.run()
    }

    internal fun saveUiPreferences(settings: UiPreferences) {
        preferences.edit()
            .putInt(KEY_UI_THEME_MODE, settings.themeMode)
            .putInt(KEY_UI_COLOR_MODE, settings.colorMode)
            .putBoolean(KEY_UI_DYNAMIC_COLOR, settings.dynamicColor)
            .putBoolean(KEY_UI_AUTO_CHECK_UPDATE, settings.autoCheckUpdate)
            .apply()
        LauncherIconController.sync(
            hostActivity,
            settings.themeMode,
            settings.colorMode,
            settings.dynamicColor
        )
    }

    internal fun syncLauncherIcon(settings: UiPreferences) {
        LauncherIconController.sync(
            hostActivity,
            settings.themeMode,
            settings.colorMode,
            settings.dynamicColor
        )
    }

    internal fun refreshRuntimeHooks() {
        savedAction?.run()
    }

    internal fun formationButtonDefaultCount(): Int =
        RWmiaoModule.defaultFormationButtonCount()

    internal fun selectionPanelColumns(): Int {
        return preferences.getInt(
            KEY_SELECTION_PANEL_COLUMNS,
            DEFAULT_SELECTION_PANEL_COLUMNS
        ).coerceIn(MIN_SELECTION_PANEL_COLUMNS, MAX_SELECTION_PANEL_COLUMNS)
    }

    internal fun saveSelectedPage(index: Int) {
        preferences.edit().putInt(KEY_UI_SELECTED_PAGE, index).apply()
    }
}

internal data class SettingsState(
    val noFog: Boolean,
    val enemyTeamChat: Boolean,
    val enemyMapPings: Boolean,
    val showRangeAction: Boolean,
    val showLineAction: Boolean,
    val showAttackRange: Boolean,
    val showTargetLine: Boolean,
    val showFactoryCountdown: Boolean,
    val viewAll: Boolean,
    val playerInfoPanel: Boolean,
    val factoryOpt: Boolean,
    val factoryExitThrough: Boolean,
    val reinforceOn: Boolean,
    val reinforceWeightMode: Boolean,
    val showReinforcePanel: Boolean,
    val freeSelection: Boolean,
    val freeBuild: Boolean,
    val selectAll: Boolean,
    val combatView: Boolean,
    val segmentCommand: Boolean,
    val smartPathing: Boolean,
    val smartPathingThreshold: Int,
    val showSmartPathAction: Boolean,
    val rangePlayerFilter: Int,
    val rangeUnitTypes: Int,
    val linePlayerFilter: Int,
    val lineUnitTypes: Int,
    val factoryPlayerFilter: Int,
    val rangeColors: List<Int>,
    val lineColors: List<Int>,
    val factoryColors: List<Int>
) {
    companion object {
        fun load(prefs: android.content.SharedPreferences): SettingsState {
            return SettingsState(
                noFog = prefs.getBoolean(KEY_NO_FOG, false),
                enemyTeamChat = prefs.getBoolean(KEY_ENEMY_TEAM_CHAT, false),
                enemyMapPings = prefs.getBoolean(KEY_ENEMY_MAP_PINGS, false),
                showRangeAction = prefs.getBoolean(KEY_SHOW_RANGE_ACTION, false),
                showLineAction = prefs.getBoolean(KEY_SHOW_LINE_ACTION, false),
                showAttackRange = prefs.getBoolean(KEY_SHOW_ATTACK_RANGE, false),
                showTargetLine = prefs.getBoolean(KEY_SHOW_TARGET_LINE, false),
                showFactoryCountdown = prefs.getBoolean(KEY_SHOW_FACTORY_COUNTDOWN, false),
                viewAll = prefs.getBoolean(KEY_VIEW_ALL, false),
                playerInfoPanel = prefs.getBoolean(KEY_PLAYER_INFO_PANEL, false),
                factoryOpt = prefs.getBoolean(KEY_FACTORY_OPT, false),
                factoryExitThrough = prefs.getBoolean(KEY_FACTORY_EXIT_THROUGH, false),
                reinforceOn = prefs.getBoolean(KEY_REINFORCE_ON, false),
                reinforceWeightMode = prefs.getBoolean(KEY_REINFORCE_WEIGHT_MODE, false),
                showReinforcePanel = prefs.getBoolean(KEY_SHOW_REINFORCE_PANEL, true),
                freeSelection = prefs.getBoolean(KEY_FREE_SELECTION, false),
                freeBuild = prefs.getBoolean(KEY_FREE_BUILD, false),
                selectAll = prefs.getBoolean(KEY_SELECT_ALL, false),
                combatView = prefs.getBoolean(KEY_COMBAT_VIEW, false),
                segmentCommand = prefs.getBoolean(KEY_SEGMENT_COMMAND, false),
                smartPathing = prefs.getBoolean(KEY_SMART_PATHING, false),
                smartPathingThreshold = prefs.getInt(
                    KEY_SMART_PATHING_THRESHOLD,
                    DEFAULT_SMART_PATHING_THRESHOLD
                ),
                showSmartPathAction = prefs.getBoolean(KEY_SHOW_SMART_PATH_ACTION, false),
                rangePlayerFilter = prefs.getInt(KEY_RANGE_PLAYER_FILTER, ALL_PLAYERS),
                rangeUnitTypes = prefs.getInt(KEY_RANGE_UNIT_TYPES, ALL_UNIT_TYPES),
                linePlayerFilter = prefs.getInt(KEY_LINE_PLAYER_FILTER, ALL_PLAYERS),
                lineUnitTypes = prefs.getInt(KEY_LINE_UNIT_TYPES, ALL_UNIT_TYPES),
                factoryPlayerFilter = prefs.getInt(
                    KEY_FACTORY_PLAYER_FILTER,
                    DEFAULT_FACTORY_PLAYER_FILTER
                ),
                rangeColors = listOf(
                    prefs.getInt(KEY_RANGE_COLOR_SELF, DEFAULT_RANGE_COLOR_SELF),
                    prefs.getInt(KEY_RANGE_COLOR_ENEMY, DEFAULT_RANGE_COLOR_ENEMY),
                    prefs.getInt(KEY_RANGE_COLOR_ALLY, DEFAULT_RANGE_COLOR_ALLY)
                ),
                lineColors = listOf(
                    prefs.getInt(KEY_LINE_COLOR_SELF, DEFAULT_LINE_COLOR_SELF),
                    prefs.getInt(KEY_LINE_COLOR_ENEMY, DEFAULT_LINE_COLOR_ENEMY),
                    prefs.getInt(KEY_LINE_COLOR_ALLY, DEFAULT_LINE_COLOR_ALLY)
                ),
                factoryColors = listOf(
                    prefs.getInt(KEY_FACTORY_COLOR_SELF, DEFAULT_FACTORY_COLOR_SELF),
                    prefs.getInt(KEY_FACTORY_COLOR_ENEMY, DEFAULT_FACTORY_COLOR_ENEMY),
                    prefs.getInt(KEY_FACTORY_COLOR_ALLY, DEFAULT_FACTORY_COLOR_ALLY)
                )
            )
        }
    }
}

internal data class UiPreferences(
    val themeMode: Int,
    val colorMode: Int,
    val autoCheckUpdate: Boolean
) {
    val dynamicColor: Boolean
        get() = colorMode == UI_COLOR_DYNAMIC

    companion object {
        fun load(prefs: android.content.SharedPreferences): UiPreferences {
            val colorMode = if (prefs.contains(KEY_UI_COLOR_MODE)) {
                prefs.getInt(KEY_UI_COLOR_MODE, UI_COLOR_DEFAULT)
            } else if (prefs.contains(KEY_UI_DYNAMIC_COLOR) &&
                prefs.getBoolean(KEY_UI_DYNAMIC_COLOR, false)
            ) {
                UI_COLOR_DYNAMIC
            } else {
                UI_COLOR_DEFAULT
            }
            return UiPreferences(
                themeMode = prefs.getInt(KEY_UI_THEME_MODE, UI_THEME_SYSTEM),
                colorMode = colorMode.coerceIn(UI_COLOR_DEFAULT, UI_COLOR_CYAN),
                autoCheckUpdate = prefs.getBoolean(KEY_UI_AUTO_CHECK_UPDATE, false)
            )
        }
    }
}

private class ComposeViewTreeOwner : SavedStateRegistryOwner {
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
