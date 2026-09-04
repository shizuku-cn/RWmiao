package com.shizuku.rwmiao.ui.support

import android.content.SharedPreferences
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_COLOR_MODE
import com.shizuku.rwmiao.config.SettingsContract.KEY_UI_DYNAMIC_COLOR
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_CYAN
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DEFAULT
import com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DYNAMIC

internal fun SharedPreferences.readModuleColorMode(): Int {
    val colorMode = if (contains(KEY_UI_COLOR_MODE)) {
        getInt(KEY_UI_COLOR_MODE, UI_COLOR_DEFAULT)
    } else if (getBoolean(KEY_UI_DYNAMIC_COLOR, false)) {
        UI_COLOR_DYNAMIC
    } else {
        UI_COLOR_DEFAULT
    }
    return colorMode.coerceIn(UI_COLOR_DEFAULT, UI_COLOR_CYAN)
}
