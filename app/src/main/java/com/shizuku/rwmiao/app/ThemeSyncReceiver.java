package com.shizuku.rwmiao.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.shizuku.rwmiao.config.SettingsContract;

/** Receives theme changes from the injected target-process settings page. */
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
        boolean dynamicColor = intent.getBooleanExtra(
                IconThemeProvider.ARG_DYNAMIC_COLOR, true);
        IconThemeProvider.applyTheme(context, mode, dynamicColor);
    }
}
