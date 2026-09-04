package com.shizuku.rwmiao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.shizuku.rwmiao.config.SettingsContract;

public final class ThemeSyncReceiver extends BroadcastReceiver {
    static final String ACTION_SYNC_THEME =
            "com.shizuku.rwmiao.action.SYNC_THEME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SYNC_THEME.equals(intent.getAction())) {
            return;
        }
        int mode = intent.getIntExtra(
                IconThemeProvider.ARG_THEME_MODE,
                SettingsContract.UI_THEME_SYSTEM);
        int colorMode = intent.getIntExtra(
                IconThemeProvider.ARG_COLOR_MODE,
                intent.getBooleanExtra(IconThemeProvider.ARG_DYNAMIC_COLOR, false)
                        ? SettingsContract.UI_COLOR_DYNAMIC
                        : SettingsContract.UI_COLOR_DEFAULT);
        boolean dynamicColor = intent.getBooleanExtra(
                IconThemeProvider.ARG_DYNAMIC_COLOR, false);
        IconThemeProvider.applyTheme(context, mode, colorMode, dynamicColor);
    }
}
