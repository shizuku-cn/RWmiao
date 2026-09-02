package com.shizuku.rwmiao.module.playerinfo;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.shizuku.rwmiao.config.SettingsContract;
import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class PlayerInfoPanel {
    private static final String TAG = "PlayerInfoPanel";
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final int MAX_ROWS = 64;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshScheduled = false;
            if (overlay != null && overlay.isPanelExpanded()) {
                refreshData();
                scheduleRefresh();
            }
        }
    };
    private final Runnable attachRetryRunnable = new Runnable() {
        @Override
        public void run() {
            attachRetryScheduled = false;
            refreshSettings();
        }
    };

    private RuntimeAccess runtime;
    private PlayerInfoOverlay overlay;
    private Activity activeActivity;
    private Activity pausedActivity;
    private boolean refreshScheduled;
    private boolean attachRetryScheduled;

    public PlayerInfoPanel(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess(loader, host);
        refreshSettings();
    }

    public void onResume(Activity activity) {
        if (activity != null) pausedActivity = null;
        refreshSettings();
    }

    public void onPause(Object value) {
        if (value instanceof Activity && value == activeActivity) {
            pausedActivity = (Activity) value;
            detach();
        }
    }

    public void onDestroy(Object value) {
        if (value instanceof Activity && value == activeActivity) {
            pausedActivity = (Activity) value;
            detach();
        }
    }

    public void refreshSettings() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::refreshSettings);
            return;
        }

        if (!host.selectionActionEnabled(SettingsContract.KEY_PLAYER_INFO_PANEL)) {
            cancelAttachRetry();
            detach();
            return;
        }

        Activity activity = host.currentActivity();
        if (activity == null || activity.isFinishing()
                || runtime == null || !runtime.gameActivityClass.isInstance(activity)) {
            detach();
            return;
        }
        if (activity == pausedActivity) {
            return;
        }
        if (host.isModulePageOpen(activity)) {
            detach();
            scheduleAttachRetry();
            return;
        }
        cancelAttachRetry();
        if (overlay != null && activeActivity == activity && overlay.getParent() != null) {
            overlay.updatePalette(PlayerInfoOverlay.ThemePalette.from(activity, host));
            return;
        }
        attach(activity);
    }

    private void attach(Activity activity) {
        if (overlay != null && activeActivity == activity && overlay.getParent() != null) {
            return;
        }
        detach();
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        PlayerInfoOverlay newOverlay = new PlayerInfoOverlay(
                activity,
                PlayerInfoOverlay.ThemePalette.from(activity, host),
                new PlayerInfoOverlay.Callback() {
                    @Override
                    public void onPanelExpandedChanged(boolean expanded) {
                        if (expanded) {
                            refreshData();
                            scheduleRefresh();
                        } else {
                            cancelRefresh();
                        }
                    }

                    @Override
                    public void onPageChanged(int page) {
                        refreshData();
                    }
                });
        activeActivity = activity;
        overlay = newOverlay;
        ((ViewGroup) content).addView(newOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        newOverlay.post(newOverlay::placeDefault);
    }

    private void detach() {
        cancelRefresh();
        PlayerInfoOverlay old = overlay;
        overlay = null;
        activeActivity = null;
        if (old != null) {
            if (old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }
            old.release();
        }
    }

    private void scheduleRefresh() {
        if (refreshScheduled || overlay == null || !overlay.isPanelExpanded()) return;
        refreshScheduled = true;
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void cancelRefresh() {
        refreshScheduled = false;
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private void scheduleAttachRetry() {
        if (attachRetryScheduled) return;
        attachRetryScheduled = true;
        mainHandler.postDelayed(attachRetryRunnable, 350L);
    }

    private void cancelAttachRetry() {
        attachRetryScheduled = false;
        mainHandler.removeCallbacks(attachRetryRunnable);
    }

    private void refreshData() {
        PlayerInfoOverlay current = overlay;
        RuntimeAccess access = runtime;
        if (current == null || access == null || !current.isPanelExpanded()) return;

        try {
            Object result = access.playersMethod.invoke(null);
            if (!(result instanceof Iterable)) return;

            ArrayList<PlayerInfoOverlay.PlayerRow> rows = new ArrayList<>();
            for (Object player : (Iterable<?>) result) {
                if (player == null || !access.playerClass.isInstance(player)) continue;
                if (rows.size() >= MAX_ROWS) break;

                int slot = access.slotField.getInt(player);
                int team = access.teamField.getInt(player);
                String name = (String) access.nameField.get(player);
                if (name == null || name.length() == 0) name = "玩家";

                int credits = (int) Math.round(access.creditsField.getDouble(player));
                int growth = 0;
                try {
                    growth = ((Number) access.incomeMethod.invoke(player)).intValue();
                } catch (Throwable ignored) {
                }
                int teamColor = ((Number) access.teamColorMethod.invoke(null, team)).intValue();
                int spawnColor = ((Number) access.spawnColorMethod.invoke(null, slot)).intValue();
                String information;
                information = current.getPageIndex() == 1
                        ? formatUnitCount(access, player)
                        : formatEconomy(credits, growth);
                rows.add(new PlayerInfoOverlay.PlayerRow(
                        slot,
                        name,
                        information,
                        teamColor,
                        spawnColor));
            }
            Collections.sort(rows, Comparator.comparingInt(row -> row.slot));
            current.updatePlayers(rows);
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to read native player information", t);
        }
    }

    private static String formatEconomy(int credits, int growth) {
        return credits + "(" + (growth >= 0 ? "+" : "") + growth + ")";
    }

    private static String formatUnitCount(RuntimeAccess access, Object player) {
        int count = 0;
        try {
            count = ((Number) access.unitCountMethod.invoke(player)).intValue();
        } catch (Throwable ignored) {
        }
        return String.valueOf(count);
    }

    private static final class RuntimeAccess {
        final Class<?> gameActivityClass;
        final Class<?> playerClass;
        final Method playersMethod;
        final Method incomeMethod;
        final Method teamColorMethod;
        final Method spawnColorMethod;
        final Method unitCountMethod;
        final Field slotField;
        final Field teamField;
        final Field nameField;
        final Field creditsField;

        RuntimeAccess(ClassLoader loader, RWmiaoModule host) throws Throwable {
            gameActivityClass = loader.loadClass(host.target("appFramework.InGameActivity"));
            playerClass = loader.loadClass(host.target("game.p"));

            playersMethod = playerClass.getDeclaredMethod("d");
            playersMethod.setAccessible(true);
            incomeMethod = host.findCompatibleMethod(playerClass, "q");
            teamColorMethod = host.findCompatibleMethod(playerClass, "g", int.class);
            spawnColorMethod = host.findCompatibleMethod(playerClass, "f", int.class);
            unitCountMethod = host.findCompatibleMethod(playerClass, "r");
            if (incomeMethod == null || teamColorMethod == null || spawnColorMethod == null
                    || unitCountMethod == null) {
                throw new NoSuchMethodException("game.p player information methods");
            }

            slotField = host.findField(playerClass, "l");
            teamField = host.findField(playerClass, "s");
            nameField = host.findField(playerClass, "w");
            creditsField = host.findField(playerClass, "p");
            slotField.setAccessible(true);
            teamField.setAccessible(true);
            nameField.setAccessible(true);
            creditsField.setAccessible(true);
        }
    }
}
