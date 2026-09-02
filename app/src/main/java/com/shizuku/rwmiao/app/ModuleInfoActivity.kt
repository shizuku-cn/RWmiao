package com.shizuku.rwmiao.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.shizuku.rwmiao.ui.main.ModuleInfoApp
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_COLOR_MODE
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_DYNAMIC_COLOR
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_THEME_MODE
import com.shizuku.rwmiao.config.SettingsContract.KEY_MODULE_LAST_ACTIVE
import com.shizuku.rwmiao.config.SettingsContract.MODULE_ACTIVE_TIMEOUT_MS
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_CYAN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DEFAULT
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DYNAMIC
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_SYSTEM

open class ModuleInfoActivity : ComponentActivity() {
    private val themeMode = mutableIntStateOf(UI_THEME_SYSTEM)
    private val colorMode = mutableIntStateOf(UI_COLOR_DEFAULT)
    private val dynamicColor = mutableStateOf(true)
    private val moduleActive = mutableStateOf(false)
    private val themeListener = object : ThemeStateBus.Listener {
        override fun onThemeChanged(nextMode: Int, nextDynamicColor: Boolean) {
            themeMode.intValue = nextMode
            dynamicColor.value = nextDynamicColor
            colorMode.intValue = readColorMode()
        }
    }
    private val statusListener = object : ModuleStatusBus.Listener {
        override fun onModuleStatusChanged() {
            reloadActivation()
        }
    }
    private val statusRefreshHandler = Handler(Looper.getMainLooper())
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            reloadActivation()
            val fastProbe = !moduleActive.value &&
                SystemClock.elapsedRealtime() < activationProbeDeadline
            statusRefreshHandler.postDelayed(
                this,
                if (fastProbe) FAST_STATUS_REFRESH_INTERVAL_MS else STATUS_REFRESH_INTERVAL_MS
            )
        }
    }
    private var activationProbeDeadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (javaClass == ModuleInfoActivity::class.java) {
            LauncherIconController.disableLegacyLauncherEntry(this)
            startActivity(Intent(this, LauncherEntryActivity::class.java))
            finish()
            return
        }
        reloadTheme()
        setContent {
            ModuleInfoApp(themeMode.intValue, colorMode.intValue, moduleActive.value)
        }
    }

    override fun onStart() {
        super.onStart()
        ThemeStateBus.addListener(themeListener)
        ModuleStatusBus.addListener(statusListener)
        reloadTheme()
        reloadActivation()
        statusRefreshHandler.removeCallbacks(statusRefreshRunnable)
        activationProbeDeadline =
            SystemClock.elapsedRealtime() + FAST_STATUS_PROBE_WINDOW_MS
        statusRefreshHandler.post(statusRefreshRunnable)
    }

    override fun onStop() {
        ThemeStateBus.removeListener(themeListener)
        ModuleStatusBus.removeListener(statusListener)
        statusRefreshHandler.removeCallbacks(statusRefreshRunnable)
        super.onStop()
    }

    private fun reloadTheme() {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        themeMode.intValue = preferences.getInt(KEY_UI_THEME_MODE, UI_THEME_SYSTEM)
        colorMode.intValue = readColorMode()
        dynamicColor.value = colorMode.intValue == UI_COLOR_DYNAMIC
        IconThemeProvider.applyTheme(
            this,
            themeMode.intValue,
            colorMode.intValue,
            dynamicColor.value
        )
    }

    private fun readColorMode(): Int {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val legacyDynamic = preferences.contains(KEY_UI_DYNAMIC_COLOR) &&
            preferences.getBoolean(KEY_UI_DYNAMIC_COLOR, false)
        val storedColorMode = if (preferences.contains(KEY_UI_COLOR_MODE)) {
            preferences.getInt(KEY_UI_COLOR_MODE, UI_COLOR_DEFAULT)
        } else if (legacyDynamic) {
            UI_COLOR_DYNAMIC
        } else {
            UI_COLOR_DEFAULT
        }
        return storedColorMode.coerceIn(UI_COLOR_DEFAULT, UI_COLOR_CYAN)
    }

    private fun reloadActivation() {
        val lastActive = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_MODULE_LAST_ACTIVE, 0L)
        val age = System.currentTimeMillis() - lastActive
        moduleActive.value = lastActive > 0L && age in 0L..MODULE_ACTIVE_TIMEOUT_MS
    }

    private companion object {
        const val FAST_STATUS_REFRESH_INTERVAL_MS = 250L
        const val FAST_STATUS_PROBE_WINDOW_MS = 15_000L
        const val STATUS_REFRESH_INTERVAL_MS = 5_000L
    }
}
