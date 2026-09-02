package com.shizuku.rwmiao.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.shizuku.rwmiao.config.SettingsContract;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

public final class GameLimits {
    private static final String TAG = "RWmiao";
    private static final int MAX_EXTENDED_SLOT_COUNT = 99;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object hooksLock = new Object();

    private boolean unitCapHooksInstalled;
    private boolean teamLimitHooksInstalled;

    private XposedInterface.HookHandle gameStartHook;
    private XposedInterface.HookHandle spawnDropdownHook;
    private XposedInterface.HookHandle teamDropdownHook;
    private XposedInterface.HookHandle playerEditorApplyHook;

    private Field networkCapPrimary;
    private Field networkCapSecondary;
    private Field gameCapPrimary;
    private Field gameCapSecondary;
    private Field settingsSinglePlayerCap;
    private Field settingsHostedCap;

    private Object overriddenSettings;
    private int originalSinglePlayerCap;
    private int originalHostedCap;
    private boolean settingsOverrideSaved;

    private Field nativeItemsField;
    private Field nativeValueField;
    private Field editorSpawnSpinnerField;
    private Field slotCountField;
    private Constructor<?> nativeOptionConstructor;
    private Method nativeTeamNameMethod;
    private Method nativeTeamColorMethod;
    private Method expandSlotCountMethod;

    public GameLimits(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    void refreshSettings() throws Throwable {
        synchronized (hooksLock) {
            boolean unitCapEnabled = isUnitCapEnabled();
            boolean teamLimitEnabled = isTeamLimitEnabled();

            if (unitCapEnabled && !unitCapHooksInstalled) {
                try {
                    installUnitCapHooks();
                    unitCapHooksInstalled = true;
                } catch (Throwable t) {
                    uninstallUnitCapHooksLocked();
                    throw t;
                }
            } else if (!unitCapEnabled && unitCapHooksInstalled) {
                uninstallUnitCapHooksLocked();
            }

            if (teamLimitEnabled && !teamLimitHooksInstalled) {
                try {
                    installTeamLimitHooks();
                    teamLimitHooksInstalled = true;
                } catch (Throwable t) {
                    uninstallTeamLimitHooksLocked();
                    host.log(4, TAG, "安装队伍/出生点扩展 Hook 失败", t);
                }
            } else if (!teamLimitEnabled && teamLimitHooksInstalled) {
                uninstallTeamLimitHooksLocked();
            }
        }
    }

    private void installTeamLimitHooks() throws Throwable {
        Class<?> battleRoomClass = loader.loadClass(
                host.target("appFramework.MultiplayerBattleroomActivity"));
        Method spawnSetup = host.findCompatibleMethod(
                battleRoomClass, "setupSpawnPositionDropDown", Spinner.class, boolean.class);
        Method teamSetup = host.findCompatibleMethod(
                battleRoomClass, "setupTeamAllyDropDown", Spinner.class, boolean.class);
        if (spawnSetup == null || teamSetup == null) {
            throw new NoSuchMethodException("找不到原生出生点/队伍下拉框构造方法");
        }

        Class<?> teamClass = loader.loadClass(host.target("game.p"));
        slotCountField = host.findField(teamClass, "c");
        expandSlotCountMethod = host.findCompatibleMethod(
                teamClass, "b", int.class, boolean.class);
        nativeTeamNameMethod = host.findCompatibleMethod(teamClass, "a", int.class);
        nativeTeamColorMethod = host.findCompatibleMethod(teamClass, "g", int.class);
        if (expandSlotCountMethod == null || nativeTeamNameMethod == null
                || nativeTeamColorMethod == null) {
            throw new NoSuchMethodException("找不到原生队伍编号/槽位扩展方法");
        }

        Class<?> gbClass = loader.loadClass(host.target("appFramework.gb"));
        nativeOptionConstructor = gbClass.getDeclaredConstructor(
                String.class, String.class, Integer.class);
        nativeOptionConstructor.setAccessible(true);
        nativeValueField = findFieldAny(gbClass, "a", "f189a");
        Class<?> gaClass = loader.loadClass(host.target("appFramework.ga"));
        nativeItemsField = findFieldAny(gaClass, "a", "f188a");

        spawnDropdownHook = host.hookExecutable(spawnSetup, chain -> {
            Object result = chain.proceed();
            try {
                extendNativeDropdown(chain.getArg(0), false);
            } catch (Throwable t) {
                host.log(5, TAG, "扩展出生点下拉框失败", t);
            }
            return result;
        });
        teamDropdownHook = host.hookExecutable(teamSetup, chain -> {
            Object result = chain.proceed();
            try {
                extendNativeDropdown(chain.getArg(0), true);
            } catch (Throwable t) {
                host.log(5, TAG, "扩展队伍下拉框失败", t);
            }
            return result;
        });

        Class<?> playerEditorClass = loader.loadClass(host.target("appFramework.fb"));
        Method apply = host.findCompatibleMethod(playerEditorClass, "onClick", View.class);
        if (apply == null) {
            throw new NoSuchMethodException("找不到原生玩家编辑器应用按钮回调");
        }
        editorSpawnSpinnerField = host.findField(playerEditorClass, "e");
        playerEditorApplyHook = host.hookExecutable(apply, chain -> {
            try {
                expandSlotsForSelectedSpawn(chain.getThisObject());
            } catch (Throwable t) {
                host.log(5, TAG, "应用扩展出生点失败", t);
            }
            return chain.proceed();
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void extendNativeDropdown(Object spinnerObject, boolean team) throws Throwable {
        if (!(spinnerObject instanceof Spinner)) return;
        Spinner spinner = (Spinner) spinnerObject;
        Object adapterObject = spinner.getAdapter();
        if (!(adapterObject instanceof ArrayAdapter)) return;
        Object rawItems = nativeItemsField.get(adapterObject);
        if (!(rawItems instanceof List)) return;
        List items = (List) rawItems;

        int selected = spinner.getSelectedItemPosition();
        if (team) {
            for (int userValue = 11; userValue <= MAX_EXTENDED_SLOT_COUNT; userValue++) {
                String value = String.valueOf(userValue);
                if (containsNativeValue(items, value)) continue;
                int nativeTeam = userValue - 1;
                String name = String.valueOf(nativeTeamNameMethod.invoke(null, nativeTeam));
                int color = ((Number) nativeTeamColorMethod.invoke(null, nativeTeam)).intValue();
                items.add(newNativeOption(value, "Side " + name, color));
            }
        } else {
            int spectatorIndex = indexOfNativeValue(items, "-3");
            if (spectatorIndex < 0) spectatorIndex = items.size();
            for (int nativeValue = 10; nativeValue < MAX_EXTENDED_SLOT_COUNT; nativeValue++) {
                String value = String.valueOf(nativeValue);
                if (containsNativeValue(items, value)) continue;
                int side = nativeValue & 1;
                String label = (nativeValue + 1)
                        + " - Side " + (side == 0 ? "A" : "B");
                items.add(spectatorIndex++, newNativeOption(
                        value, label,
                        ((Number) nativeTeamColorMethod.invoke(null, side)).intValue()));
            }
        }
        ((ArrayAdapter) adapterObject).notifyDataSetChanged();
        if (selected >= 0 && selected < items.size()) {
            spinner.setSelection(selected, false);
        }
    }

    private Object newNativeOption(String value, String label, int color) throws Throwable {
        return nativeOptionConstructor.newInstance(value, label, Integer.valueOf(color));
    }

    private Field findFieldAny(Class<?> type, String... names) throws NoSuchFieldException {
        NoSuchFieldException last = null;
        for (String name : names) {
            try {
                return host.findField(type, name);
            } catch (NoSuchFieldException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new NoSuchFieldException(type.getName());
    }

    private boolean containsNativeValue(List<?> items, String value) throws IllegalAccessException {
        return indexOfNativeValue(items, value) >= 0;
    }

    private int indexOfNativeValue(List<?> items, String value) throws IllegalAccessException {
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item != null && value.equals(nativeValueField.get(item))) return i;
        }
        return -1;
    }

    private void expandSlotsForSelectedSpawn(Object editor) throws Throwable {
        if (!isTeamLimitEnabled() || editor == null) return;
        Object spinnerObject = editorSpawnSpinnerField.get(editor);
        if (!(spinnerObject instanceof Spinner)) return;
        Object selectedItem = ((Spinner) spinnerObject).getSelectedItem();
        if (selectedItem == null) return;
        Object rawValue = nativeValueField.get(selectedItem);
        if (!(rawValue instanceof String)) return;
        int nativeValue;
        try {
            nativeValue = Integer.parseInt((String) rawValue);
        } catch (NumberFormatException ignored) {
            return;
        }
        if (nativeValue < 0) return;
        int desiredCount = Math.min(MAX_EXTENDED_SLOT_COUNT, nativeValue + 1);
        if (desiredCount > slotCountField.getInt(null)) {
            expandSlotCountMethod.invoke(null, desiredCount, true);
        }
    }

    private void installUnitCapHooks() throws Throwable {
        Class<?> gameClass = loader.loadClass(host.target("game.i"));
        gameStartHook = host.hookExecutable(
                gameClass.getDeclaredMethod("a", boolean.class, boolean.class, int.class),
                chain -> {
                    int cap = readUnitCap();
                    saveAndApplyNativeUnitCap(cap);
                    forceNetworkUnitCap(currentNetwork(), cap);
                    Object result = chain.proceed();
                    forceNetworkUnitCap(currentNetwork(), cap);
                    forceGameUnitCap(chain.getThisObject(), cap);
                    return result;
                });
    }

    private void saveAndApplyNativeUnitCap(int cap) {
        try {
            Object engine = host.findEngine(loader);
            Object settings = host.findField(engine.getClass(), "bN").get(engine);
            if (!settingsOverrideSaved || overriddenSettings != settings) {
                settingsSinglePlayerCap = host.findField(settings.getClass(),
                        "teamUnitCapSinglePlayer");
                settingsHostedCap = host.findField(settings.getClass(),
                        "teamUnitCapHostedGame");
                originalSinglePlayerCap = settingsSinglePlayerCap.getInt(settings);
                originalHostedCap = settingsHostedCap.getInt(settings);
                overriddenSettings = settings;
                settingsOverrideSaved = true;
            }
            settingsSinglePlayerCap.setInt(settings, cap);
            settingsHostedCap.setInt(settings, cap);
        } catch (Throwable t) {
            host.log(4, TAG, "写入全局单位上限源设置失败", t);
        }
    }

    private void forceNetworkUnitCap(Object network, int cap) {
        try {
            if (network == null || !isUnitCapEnabled()) return;
            if (networkCapPrimary == null) {
                networkCapPrimary = host.findField(network.getClass(), "ay");
                networkCapSecondary = host.findField(network.getClass(), "az");
            }
            networkCapPrimary.setInt(network, cap);
            networkCapSecondary.setInt(network, cap);
        } catch (Throwable t) {
            host.log(4, TAG, "写入全局网络单位上限失败", t);
        }
    }

    private void forceGameUnitCap(Object game, int cap) {
        try {
            if (game == null || !isUnitCapEnabled()) return;
            if (gameCapPrimary == null) {
                gameCapPrimary = host.findField(game.getClass(), "by");
                gameCapSecondary = host.findField(game.getClass(), "bz");
            }
            gameCapPrimary.setInt(game, cap);
            gameCapSecondary.setInt(game, cap);
            Class<?> teamClass = loader.loadClass(host.target("game.p"));
            Method refreshTeams = host.findCompatibleMethod(teamClass, "M");
            if (refreshTeams != null) refreshTeams.invoke(null);
        } catch (Throwable t) {
            host.log(4, TAG, "写入全局游戏单位上限失败", t);
        }
    }

    private Object currentNetwork() throws Throwable {
        Object engine = host.findEngine(loader);
        return host.findField(engine.getClass(), "bU").get(engine);
    }

    private boolean isUnitCapEnabled() {
        return readBoolean(SettingsContract.KEY_GLOBAL_UNIT_CAP_ENABLED, false);
    }

    private boolean isTeamLimitEnabled() {
        return readBoolean(SettingsContract.KEY_GLOBAL_TEAM_LIMIT_ENABLED, false);
    }

    private boolean readBoolean(String key, boolean fallback) {
        try {
            Context context = host.preferenceContext();
            if (context == null) return fallback;
            SharedPreferences preferences = context.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            return preferences.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int readUnitCap() {
        try {
            Context context = host.preferenceContext();
            if (context == null) return SettingsContract.DEFAULT_GLOBAL_UNIT_CAP;
            SharedPreferences preferences = context.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            int value = preferences.getInt(
                    SettingsContract.KEY_GLOBAL_UNIT_CAP,
                    SettingsContract.DEFAULT_GLOBAL_UNIT_CAP);
            return Math.max(1, Math.min(SettingsContract.MAX_GLOBAL_UNIT_CAP, value));
        } catch (Throwable ignored) {
            return SettingsContract.DEFAULT_GLOBAL_UNIT_CAP;
        }
    }

    private void uninstallUnitCapHooksLocked() {
        unhookQuietly(gameStartHook);
        gameStartHook = null;
        unitCapHooksInstalled = false;
        restoreNativeUnitCapSettings();
    }

    private void uninstallTeamLimitHooksLocked() {
        unhookQuietly(spawnDropdownHook);
        unhookQuietly(teamDropdownHook);
        unhookQuietly(playerEditorApplyHook);
        spawnDropdownHook = null;
        teamDropdownHook = null;
        playerEditorApplyHook = null;
        teamLimitHooksInstalled = false;
    }

    private void restoreNativeUnitCapSettings() {
        if (!settingsOverrideSaved || overriddenSettings == null
                || settingsSinglePlayerCap == null || settingsHostedCap == null) return;
        try {
            settingsSinglePlayerCap.setInt(overriddenSettings, originalSinglePlayerCap);
            settingsHostedCap.setInt(overriddenSettings, originalHostedCap);
        } catch (Throwable t) {
            host.log(4, TAG, "恢复原生单位上限设置失败", t);
        } finally {
            overriddenSettings = null;
            settingsOverrideSaved = false;
        }
    }

    private void unhookQuietly(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try {
            handle.unhook();
        } catch (Throwable t) {
            host.log(5, TAG, "卸载游戏上限 Hook 失败", t);
        }
    }
}
