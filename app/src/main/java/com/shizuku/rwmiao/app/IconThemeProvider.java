package com.shizuku.rwmiao.app;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.shizuku.rwmiao.config.SettingsContract;

public final class IconThemeProvider extends ContentProvider {
    static final String METHOD_SET_THEME = "set_theme";
    static final String ARG_THEME_MODE = "themeMode";
    static final String ARG_COLOR_MODE = "colorMode";
    static final String ARG_DYNAMIC_COLOR = "dynamicColor";

    private static final String SYSTEM_ALIAS = "com.shizuku.rwmiao.app.LauncherSystemAlias";
    private static final String LIGHT_ALIAS = "com.shizuku.rwmiao.app.LauncherLightAlias";
    private static final String DARK_ALIAS = "com.shizuku.rwmiao.app.LauncherDarkAlias";
    private static final String STATIC_LIGHT_ALIAS =
            "com.shizuku.rwmiao.app.LauncherStaticLightAlias";
    private static final String STATIC_DARK_ALIAS =
            "com.shizuku.rwmiao.app.LauncherStaticDarkAlias";
    private static final String GREEN_ALIAS =
            "com.shizuku.rwmiao.app.LauncherGreenAlias";
    private static final String BLUE_ALIAS =
            "com.shizuku.rwmiao.app.LauncherBlueAlias";
    private static final String PINK_ALIAS =
            "com.shizuku.rwmiao.app.LauncherPinkAlias";
    private static final String YELLOW_ALIAS =
            "com.shizuku.rwmiao.app.LauncherYellowAlias";
    private static final String ORANGE_ALIAS =
            "com.shizuku.rwmiao.app.LauncherOrangeAlias";
    private static final String RED_ALIAS =
            "com.shizuku.rwmiao.app.LauncherRedAlias";
    private static final String CYAN_ALIAS =
            "com.shizuku.rwmiao.app.LauncherCyanAlias";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            android.content.SharedPreferences preferences = context.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            applyTheme(
                    context,
                    preferences.getInt(
                            SettingsContract.KEY_UI_THEME_MODE,
                            SettingsContract.UI_THEME_SYSTEM),
                    preferences.getInt(
                            SettingsContract.KEY_UI_COLOR_MODE,
                            preferences.getBoolean(
                                    SettingsContract.KEY_UI_DYNAMIC_COLOR,
                                    false)
                                    ? SettingsContract.UI_COLOR_DYNAMIC
                                    : SettingsContract.UI_COLOR_DEFAULT),
                    preferences.getBoolean(SettingsContract.KEY_UI_DYNAMIC_COLOR, false));
        }
        return true;
    }

    @Nullable
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (!METHOD_SET_THEME.equals(method) || context == null) {
            return super.call(method, arg, extras);
        }

        int mode = extras == null
                ? SettingsContract.UI_THEME_SYSTEM
                : extras.getInt(ARG_THEME_MODE, SettingsContract.UI_THEME_SYSTEM);
        int colorMode = extras != null && extras.containsKey(ARG_COLOR_MODE)
                ? extras.getInt(ARG_COLOR_MODE, SettingsContract.UI_COLOR_DEFAULT)
                : (extras != null && extras.getBoolean(ARG_DYNAMIC_COLOR, false)
                        ? SettingsContract.UI_COLOR_DYNAMIC
                        : SettingsContract.UI_COLOR_DEFAULT);
        boolean dynamicColor = extras != null && extras.getBoolean(ARG_DYNAMIC_COLOR, false);
        applyTheme(context, mode, colorMode, dynamicColor);
        return Bundle.EMPTY;
    }

    static void applyTheme(Context context, int mode, boolean dynamicColor) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
        int colorMode = preferences.getInt(
                SettingsContract.KEY_UI_COLOR_MODE,
                dynamicColor ? SettingsContract.UI_COLOR_DYNAMIC
                        : SettingsContract.UI_COLOR_DEFAULT);
        applyTheme(context, mode, colorMode, dynamicColor);
    }

    static void applyTheme(Context context, int mode, int colorMode, boolean dynamicColor) {
        if (mode < SettingsContract.UI_THEME_SYSTEM || mode > SettingsContract.UI_THEME_DARK) {
            mode = SettingsContract.UI_THEME_SYSTEM;
        }
        if (colorMode < SettingsContract.UI_COLOR_DEFAULT
                || colorMode > SettingsContract.UI_COLOR_CYAN) {
            colorMode = dynamicColor
                    ? SettingsContract.UI_COLOR_DYNAMIC
                    : SettingsContract.UI_COLOR_DEFAULT;
        }
        dynamicColor = colorMode == SettingsContract.UI_COLOR_DYNAMIC;
        LauncherIconController.disableLegacyLauncherEntry(context);

        context.getSharedPreferences(SettingsContract.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(SettingsContract.KEY_UI_THEME_MODE, mode)
                .putInt(SettingsContract.KEY_UI_COLOR_MODE, colorMode)
                .putBoolean(SettingsContract.KEY_UI_DYNAMIC_COLOR, dynamicColor)
                .commit();

        String selected = colorAlias(colorMode);
        if (selected == null && dynamicColor) {
            selected = mode == SettingsContract.UI_THEME_LIGHT
                    ? LIGHT_ALIAS
                    : mode == SettingsContract.UI_THEME_DARK ? DARK_ALIAS : SYSTEM_ALIAS;
        } else if (selected == null) {
            boolean systemDark = (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            boolean dark = mode == SettingsContract.UI_THEME_DARK
                    || (mode == SettingsContract.UI_THEME_SYSTEM && systemDark);
            selected = dark ? STATIC_DARK_ALIAS : STATIC_LIGHT_ALIAS;
        }
        PackageManager packageManager = context.getPackageManager();

        try {
            setState(context, packageManager, selected,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            setState(context, packageManager, SYSTEM_ALIAS, selected.equals(SYSTEM_ALIAS)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, LIGHT_ALIAS, selected.equals(LIGHT_ALIAS)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, DARK_ALIAS, selected.equals(DARK_ALIAS)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, STATIC_LIGHT_ALIAS,
                    selected.equals(STATIC_LIGHT_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, STATIC_DARK_ALIAS,
                    selected.equals(STATIC_DARK_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, GREEN_ALIAS,
                    selected.equals(GREEN_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, BLUE_ALIAS,
                    selected.equals(BLUE_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, PINK_ALIAS,
                    selected.equals(PINK_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, YELLOW_ALIAS,
                    selected.equals(YELLOW_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, ORANGE_ALIAS,
                    selected.equals(ORANGE_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, RED_ALIAS,
                    selected.equals(RED_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            setState(context, packageManager, CYAN_ALIAS,
                    selected.equals(CYAN_ALIAS)
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } finally {
            ThemeStateBus.dispatch(mode, dynamicColor);
        }
    }

    @Nullable
    private static String colorAlias(int colorMode) {
        switch (colorMode) {
            case SettingsContract.UI_COLOR_GREEN:
                return GREEN_ALIAS;
            case SettingsContract.UI_COLOR_BLUE:
                return BLUE_ALIAS;
            case SettingsContract.UI_COLOR_PINK:
                return PINK_ALIAS;
            case SettingsContract.UI_COLOR_YELLOW:
                return YELLOW_ALIAS;
            case SettingsContract.UI_COLOR_ORANGE:
                return ORANGE_ALIAS;
            case SettingsContract.UI_COLOR_RED:
                return RED_ALIAS;
            case SettingsContract.UI_COLOR_CYAN:
                return CYAN_ALIAS;
            default:
                return null;
        }
    }

    private static void setState(Context context, PackageManager packageManager,
                                 String className, int state) {
        ComponentName component = new ComponentName(context, className);
        if (packageManager.getComponentEnabledSetting(component) == state) {
            return;
        }
        packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
