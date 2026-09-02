package com.shizuku.rwmiao.app;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.shizuku.rwmiao.BuildConfig;

public final class LauncherIconController {
    private static final Uri AUTHORITY = Uri.parse(
            "content://" + BuildConfig.APPLICATION_ID + ".icon-theme");
    private static final ComponentName RECEIVER = new ComponentName(
            BuildConfig.APPLICATION_ID,
            BuildConfig.APPLICATION_ID + ".app.ThemeSyncReceiver");

    private LauncherIconController() {
    }

    public static void sync(Context context, int themeMode, boolean dynamicColor) {
        sync(
                context,
                themeMode,
                dynamicColor
                        ? com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DYNAMIC
                        : com.shizuku.rwmiao.config.SettingsContract.UI_COLOR_DEFAULT,
                dynamicColor);
    }

    public static void sync(
            Context context, int themeMode, int colorMode, boolean dynamicColor) {
        try {
            Bundle args = new Bundle();
            args.putInt(IconThemeProvider.ARG_THEME_MODE, themeMode);
            args.putInt(IconThemeProvider.ARG_COLOR_MODE, colorMode);
            args.putBoolean(IconThemeProvider.ARG_DYNAMIC_COLOR, dynamicColor);
            context.getContentResolver().call(
                    AUTHORITY,
                    IconThemeProvider.METHOD_SET_THEME,
                    null,
                    args);
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(ThemeSyncReceiver.ACTION_SYNC_THEME)
                    .setComponent(RECEIVER)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(IconThemeProvider.ARG_THEME_MODE, themeMode)
                    .putExtra(IconThemeProvider.ARG_COLOR_MODE, colorMode)
                    .putExtra(IconThemeProvider.ARG_DYNAMIC_COLOR, dynamicColor);
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    static void disableLegacyLauncherEntry(Context context) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(
                            context,
                            BuildConfig.APPLICATION_ID + ".app.ModuleInfoActivity"),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }
    }
}
