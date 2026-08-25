package com.shizuku.rwmiao.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.shizuku.rwmiao.ui.main.ModuleInfoApp
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_DYNAMIC_COLOR
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_THEME_MODE
import com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME
import com.shizuku.rwmiao.config.SettingsContract.UI_THEME_SYSTEM

/** Standalone launcher entry: a lightweight module overview, independent of the game process. */
open class ModuleInfoActivity : ComponentActivity() {
    private val themeMode = mutableIntStateOf(UI_THEME_SYSTEM)
    private val dynamicColor = mutableStateOf(true)
    private val themeListener = object : ThemeStateBus.Listener {
        override fun onThemeChanged(nextMode: Int, nextDynamicColor: Boolean) {
            themeMode.intValue = nextMode
            dynamicColor.value = nextDynamicColor
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (javaClass == ModuleInfoActivity::class.java) {
            // Migrate a shortcut created by versions that launched this
            // activity directly. New launcher aliases target the subclass.
            LauncherIconController.disableLegacyLauncherEntry(this)
            startActivity(Intent(this, LauncherEntryActivity::class.java))
            finish()
            return
        }
        reloadTheme()
        setContent {
            ModuleInfoApp(themeMode.intValue, dynamicColor.value)
        }
    }

    override fun onStart() {
        super.onStart()
        ThemeStateBus.addListener(themeListener)
        reloadTheme()
    }

    override fun onStop() {
        ThemeStateBus.removeListener(themeListener)
        super.onStop()
    }

    private fun reloadTheme() {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        themeMode.intValue = preferences.getInt(KEY_UI_THEME_MODE, UI_THEME_SYSTEM)
        dynamicColor.value = preferences.getBoolean(KEY_UI_DYNAMIC_COLOR, true)
        // Opening the APP is also a repair point for launcher component state.
        IconThemeProvider.applyTheme(this, themeMode.intValue, dynamicColor.value)
    }
}
