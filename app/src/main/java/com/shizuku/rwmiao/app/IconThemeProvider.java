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

/**
 * Applies the module page's light/dark/system choice from the injected process.
 * The provider runs under the module APK UID, which is required for changing
 * the enabled launcher alias through PackageManager.
 */
public final class IconThemeProvider extends ContentProvider {
    static final String METHOD_SET_THEME = "set_theme";
    static final String ARG_THEME_MODE = "themeMode";
    static final String ARG_DYNAMIC_COLOR = "dynamicColor";

    private static final String SYSTEM_ALIAS = "com.shizuku.rwmiao.app.LauncherSystemAlias";
    private static final String LIGHT_ALIAS = "com.shizuku.rwmiao.app.LauncherLightAlias";
    private static final String DARK_ALIAS = "com.shizuku.rwmiao.app.LauncherDarkAlias";
    private static final String STATIC_LIGHT_ALIAS =
            "com.shizuku.rwmiao.app.LauncherStaticLightAlias";
    private static final String STATIC_DARK_ALIAS =
            "com.shizuku.rwmiao.app.LauncherStaticDarkAlias";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            // Re-apply the persisted choice whenever Android starts the
            // provider. This repairs launcher component state after an app
            // update or launcher process restart.
            android.content.SharedPreferences preferences = context.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            applyTheme(
                    context,
                    preferences.getInt(
                            SettingsContract.KEY_UI_THEME_MODE,
                            SettingsContract.UI_THEME_SYSTEM),
                    preferences.getBoolean(SettingsContract.KEY_UI_DYNAMIC_COLOR, true));
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
        boolean dynamicColor = extras == null || extras.getBoolean(ARG_DYNAMIC_COLOR, true);
        applyTheme(context, mode, dynamicColor);
        return Bundle.EMPTY;
    }

    static void applyTheme(Context context, int mode, boolean dynamicColor) {
        if (mode < SettingsContract.UI_THEME_SYSTEM || mode > SettingsContract.UI_THEME_DARK) {
            mode = SettingsContract.UI_THEME_SYSTEM;
        }
        // Older builds exposed ModuleInfoActivity directly as the launcher.
        // Disable that legacy component so a cached old desktop entry cannot
        // keep displaying the application icon instead of the selected alias.
        LauncherIconController.disableLegacyLauncherEntry(context);

        // Keep a copy in the module APK's own data. The standalone launcher
        // activity is a different process from the injected settings page and
        // cannot read the target game's SharedPreferences directly.
        context.getSharedPreferences(SettingsContract.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(SettingsContract.KEY_UI_THEME_MODE, mode)
                .putBoolean(SettingsContract.KEY_UI_DYNAMIC_COLOR, dynamicColor)
                // This state is consumed by the standalone activity in a
                // later process/lifecycle. Commit before returning so a
                // provider/receiver process stop cannot lose the update.
                .commit();

        String selected;
        if (dynamicColor) {
            selected = mode == SettingsContract.UI_THEME_LIGHT
                    ? LIGHT_ALIAS
                    : mode == SettingsContract.UI_THEME_DARK ? DARK_ALIAS : SYSTEM_ALIAS;
        } else {
            boolean systemDark = (context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            boolean dark = mode == SettingsContract.UI_THEME_DARK
                    || (mode == SettingsContract.UI_THEME_SYSTEM && systemDark);
            selected = dark ? STATIC_DARK_ALIAS : STATIC_LIGHT_ALIAS;
        }
        PackageManager packageManager = context.getPackageManager();

        try {
            // Enable first so the launcher never observes a package with no launcher entry.
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
        } finally {
            // The activity may already be open while the injected process changes
            // the preference. Do not wait for onResume; invalidate it now.
            ThemeStateBus.dispatch(mode, dynamicColor);
        }
    }

    private static void setState(Context context, PackageManager packageManager,
                                 String className, int state) {
        packageManager.setComponentEnabledSetting(
                new ComponentName(context, className),
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
