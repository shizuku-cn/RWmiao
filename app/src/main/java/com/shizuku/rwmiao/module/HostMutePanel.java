package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

import com.shizuku.rwmiao.ui.support.HostMutePanelUi;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_HOST_MUTE_PANEL;
import static com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME;

final class HostMutePanel {
    private static final String TAG = "RWmiao";
    private static final long UNMUTED = 0L;
    private static final long PERMANENT = -1L;
    private static final long TIMER_GRANULARITY_MS = 100L;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Map<Integer, MuteState> muteSnapshot = Collections.emptyMap();
    private volatile boolean allChatMuted;
    private volatile boolean enabled;
    private volatile Object activeNetwork;
    private Dialog activeDialog;
    private XposedInterface.HookHandle commandIngressHook;
    private final Runnable expiryRunnable = this::expireDueStates;

    private Class<?> networkClass;
    private Class<?> commandClass;
    private Class<?> connectionClass;
    private Class<?> teamClass;
    private Field networkServerMode;
    private Field networkConnected;
    private Field networkConnections;
    private Field connectionPlayer;
    private Field connectionActive;
    private Field connectionClosed;
    private Field playerIndex;
    private Field playerTeam;
    private Field playerName;
    private Method teamColorMethod;
    private Method spawnColorMethod;
    private Field commandTeam;
    private Field commandAction;
    private Method commandIngress;
    private Method systemMessage;
    private final Set<Object> mapPingActionIds = new HashSet<>();

    HostMutePanel(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        networkClass = loader.loadClass(host.target("gameFramework.j.ae"));
        commandClass = loader.loadClass(host.target("gameFramework.e"));
        connectionClass = loader.loadClass(host.target("gameFramework.j.c"));
        teamClass = loader.loadClass(host.target("game.p"));

        commandIngress = host.findCompatibleMethod(networkClass, "a", commandClass);
        commandIngress.setAccessible(true);
        networkServerMode = host.findField(networkClass, "D");
        networkConnected = host.findField(networkClass, "C");
        networkConnections = host.findField(networkClass, "aO");
        connectionPlayer = host.findField(connectionClass, "A");
        connectionActive = host.findField(connectionClass, "q");
        connectionClosed = host.findField(connectionClass, "b");
        playerIndex = host.findField(teamClass, "l");
        playerTeam = host.findField(teamClass, "s");
        playerName = host.findField(teamClass, "w");
        teamColorMethod = host.findCompatibleMethod(teamClass, "g", int.class);
        spawnColorMethod = host.findCompatibleMethod(teamClass, "f", int.class);
        commandTeam = host.findField(commandClass, "i");
        commandAction = host.findField(commandClass, "k");
        systemMessage = host.findCompatibleMethod(networkClass, "h", String.class);

        Class<?> pingActionClass = loader.loadClass(host.target("game.units.a.j"));
        Field pingActionList = host.findField(pingActionClass, "b");
        Field actionId = host.findField(pingActionClass, "j");
        Object actions = pingActionList.get(null);
        if (actions instanceof Iterable) {
            for (Object action : (Iterable<?>) actions) {
                if (action != null) mapPingActionIds.add(actionId.get(action));
            }
        }

        refreshSettings();
    }

    boolean enabled() {
        return enabled;
    }

    boolean availableForAction() {
        if (!enabled) return false;
        Object network = currentNetwork();
        if (!isHostNetwork(network)) return false;
        syncNetwork(network);
        return true;
    }

    synchronized void refreshSettings() throws Throwable {
        boolean shouldEnable = settingEnabled();
        if (shouldEnable) {
            enabled = true;
            ensureCommandIngressHook();
            Object network = currentNetwork();
            if (network != null) syncNetwork(network);
            scheduleExpiry();
            return;
        }

        enabled = false;
        XposedInterface.HookHandle handle = commandIngressHook;
        commandIngressHook = null;
        if (handle != null) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
        mainHandler.removeCallbacks(expiryRunnable);
        activeNetwork = null;
        clearState();
        dismissActiveDialog();
    }

    boolean shouldSuppressChat(Object network, Object sourceConnection, Object senderTeam) {
        if (!enabled || !isHostNetwork(network)) return false;
        if (sourceConnection == null) return false;
        if (allChatMuted) return true;
        try {
            Object player = connectionPlayer.get(sourceConnection);
            if (player == null) return false;
            int index = playerIndex.getInt(player);
            MuteState state = muteSnapshot.get(index);
            return isActive(state, SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldSuppressMapPing(Object network, Object command) {
        if (!enabled || !isHostNetwork(network) || mapPingActionIds.isEmpty()) return false;
        try {
            Object actionId = commandAction.get(command);
            if (!mapPingActionIds.contains(actionId)) return false;
            Object senderTeam = commandTeam.get(command);
            if (senderTeam == null) return false;
            MuteState state = muteSnapshot.get(playerIndex.getInt(senderTeam));
            return state != null && state.blockMapPing
                    && isActive(state, SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void ensureCommandIngressHook() {
        if (commandIngressHook != null) return;
        commandIngressHook = host.hookExecutable(commandIngress, chain -> {
            if (shouldSuppressMapPing(chain.getThisObject(), chain.getArg(0))) return null;
            return chain.proceed();
        });
    }

    private boolean settingEnabled() {
        if (host.selectionActionEnabled(KEY_HOST_MUTE_PANEL)) return true;
        Activity activity = host.currentActivity();
        return activity != null && activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HOST_MUTE_PANEL, false);
    }

    private Object currentNetwork() {
        try {
            Object game = host.findEngine(loader);
            if (game == null) return null;
            return host.findField(game.getClass(), "bU").get(game);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isHostNetwork(Object network) {
        if (network == null) return false;
        try {
            return networkServerMode.getBoolean(network) && networkConnected.getBoolean(network);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int invokeColor(Method method, int value) {
        try {
            if (method != null) {
                Object result = method.invoke(null, value);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (Throwable ignored) {
        }
        return 0xFFFFFFFF;
    }

    private void syncNetwork(Object network) {
        Object previous = activeNetwork;
        if (previous != null && previous != network) clearState();
        activeNetwork = network;
    }

    void openPanel() {
        if (!enabled) return;
        Activity activity = host.currentActivity();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (!enabled || activity.isFinishing()) return;
            Object network = currentNetwork();
            if (!isHostNetwork(network)) {
                Toast.makeText(activity, "仅多人房主可以使用禁言面板", Toast.LENGTH_SHORT).show();
                return;
            }
            syncNetwork(network);
            showDialog(activity, network);
        });
    }

    private ArrayList<PlayerSnapshot> snapshotPlayers(Object network) {
        ArrayList<PlayerSnapshot> result = new ArrayList<>();
        if (!isHostNetwork(network)) return result;
        try {
            Object connections = networkConnections.get(network);
            if (!(connections instanceof Iterable)) return result;
            for (Object connection : (Iterable<?>) connections) {
                if (connection == null) continue;
                if (!connectionActive.getBoolean(connection) || connectionClosed.getBoolean(connection)) {
                    continue;
                }
                Object player = connectionPlayer.get(connection);
                if (player == null) continue;
                int index = playerIndex.getInt(player);
                String name = String.valueOf(playerName.get(player));
                int team = playerTeam.getInt(player);
                MuteState state = muteSnapshot.get(index);
                result.add(new PlayerSnapshot(index, name,
                        invokeColor(teamColorMethod, team),
                        invokeColor(spawnColorMethod, index),
                        state == null ? UNMUTED : state.expiresAt,
                        state != null && state.blockMapPing));
            }
        } catch (Throwable t) {
            host.log(5, TAG, "读取房主禁言玩家列表失败", t);
        }
        result.sort(Comparator.comparingInt(value -> value.index));
        return result;
    }

    private void showDialog(Activity activity, Object network) {
        if (activeDialog != null && activeDialog.isShowing()) return;
        long now = SystemClock.elapsedRealtime();
        ArrayList<HostMutePanelUi.MutePanelPlayer> players = new ArrayList<>();
        for (PlayerSnapshot player : snapshotPlayers(network)) {
            MuteState state = muteSnapshot.get(player.index);
            long duration = player.expiresAt == PERMANENT
                    ? PERMANENT : state == null ? UNMUTED
                    : Math.max(UNMUTED, state.expiresAt - now);
            players.add(new HostMutePanelUi.MutePanelPlayer(
                    player.index, player.name, player.numberColor, player.nameColor,
                    player.expiresAt, player.blockMapPing, duration));
        }
        HostMutePanelUi.MutePanelData initial =
                new HostMutePanelUi.MutePanelData(allChatMuted, players);
        HostMutePanelUi.MutePanelCallback callback = new HostMutePanelUi.MutePanelCallback() {
            @Override
            public void onApply(HostMutePanelUi.MutePanelData data) {
                try {
                    Object current = currentNetwork();
                    if (current != network || !isHostNetwork(current)) {
                        Activity currentActivity = host.currentActivity();
                        if (currentActivity != null) {
                            Toast.makeText(currentActivity, "当前已不是原来的房主游戏",
                                    Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    ArrayList<Draft> drafts = new ArrayList<>();
                    for (HostMutePanelUi.MutePanelPlayer player : data.players) {
                        drafts.add(new Draft(player.index, player.name, player.expiresAt,
                                player.blockMapPing, player.durationMs));
                    }
                    applyDrafts(network, drafts, data.allChatMuted);
                    if (activeDialog != null) activeDialog.dismiss();
                } catch (Throwable t) {
                    host.log(5, TAG, "应用房主禁言状态失败", t);
                    Activity currentActivity = host.currentActivity();
                    if (currentActivity != null) {
                        Toast.makeText(currentActivity, "应用禁言状态失败", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };
        Dialog dialog = HostMutePanelUi.showHostMutePanel(activity, initial, callback);
        dialog.setOnDismissListener(value -> {
            if (activeDialog == dialog) activeDialog = null;
        });
        activeDialog = dialog;
    }

    private void applyDrafts(Object network, List<Draft> drafts, boolean nextAllChatMuted)
            throws Throwable {
        long now = SystemClock.elapsedRealtime();
        ArrayList<String> broadcasts = new ArrayList<>();
        synchronized (stateLock) {
            if (allChatMuted != nextAllChatMuted) {
                broadcasts.add(nextAllChatMuted ? "全体禁言已开启" : "全体禁言已关闭");
            }
            allChatMuted = nextAllChatMuted;
            Map<Integer, MuteState> next = new HashMap<>(muteSnapshot);
            for (Draft draft : drafts) {
                MuteState previous = next.get(draft.index);
                boolean wasMuted = previous != null && isActive(previous, now);
                boolean nowMuted = draft.expiresAt == PERMANENT || draft.expiresAt > now;
                if (nowMuted) {
                    long expiresAt = draft.expiresAt == PERMANENT
                            ? PERMANENT : draft.expiresAt;
                    MuteState updated = new MuteState(expiresAt, draft.blockMapPing, draft.name);
                    next.put(draft.index, updated);
                    if (!wasMuted || previous.expiresAt != updated.expiresAt
                            || previous.blockMapPing != updated.blockMapPing) {
                        long duration = draft.durationMs == PERMANENT ? PERMANENT
                                : draft.durationMs > 0L ? draft.durationMs
                                : Math.max(UNMUTED, expiresAt - now);
                        broadcasts.add(muteMessage(draft.name, draft.index, duration,
                                draft.blockMapPing));
                    }
                } else {
                    next.remove(draft.index);
                    if (wasMuted) broadcasts.add(unmuteMessage(draft.name, draft.index));
                }
            }
            muteSnapshot = next.isEmpty()
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(next);
        }
        activeNetwork = network;
        for (String message : broadcasts) broadcastSystemMessage(network, message);
        scheduleExpiry();
    }

    private void expireDueStates() {
        if (!enabled) return;
        Object network = currentNetwork();
        if (network == null) {
            scheduleExpiry();
            return;
        }
        if (activeNetwork != null && activeNetwork != network) {
            clearState();
            activeNetwork = network;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ArrayList<String> broadcasts = new ArrayList<>();
        synchronized (stateLock) {
            Map<Integer, MuteState> next = new HashMap<>(muteSnapshot);
            for (Map.Entry<Integer, MuteState> entry : muteSnapshot.entrySet()) {
                MuteState state = entry.getValue();
                if (state.expiresAt > 0L && state.expiresAt <= now) {
                    next.remove(entry.getKey());
                    broadcasts.add(unmuteMessage(state.name, entry.getKey()));
                }
            }
            if (next.size() != muteSnapshot.size()) {
                muteSnapshot = next.isEmpty()
                        ? Collections.emptyMap() : Collections.unmodifiableMap(next);
            }
        }
        if (isHostNetwork(network)) {
            for (String message : broadcasts) broadcastSystemMessage(network, message);
        }
        scheduleExpiry();
    }

    private void scheduleExpiry() {
        mainHandler.removeCallbacks(expiryRunnable);
        if (!enabled) return;
        long now = SystemClock.elapsedRealtime();
        long next = Long.MAX_VALUE;
        for (MuteState state : muteSnapshot.values()) {
            if (state.expiresAt > 0L && state.expiresAt < next) next = state.expiresAt;
        }
        if (next == Long.MAX_VALUE) return;
        mainHandler.postDelayed(expiryRunnable,
                Math.max(TIMER_GRANULARITY_MS, next - now));
    }

    private void broadcastSystemMessage(Object network, String message) {
        if (!isHostNetwork(network) || systemMessage == null) return;
        try {
            systemMessage.invoke(network, message);
        } catch (Throwable t) {
            host.log(5, TAG, "广播禁言状态消息失败", t);
        }
    }

    private String muteMessage(String name, int index, long durationMs, boolean blockMapPing) {
        String suffix = durationMs <= 0L ? "" : (durationMs / 60_000L) + "分"
                + ((durationMs % 60_000L) / 1_000L) + "秒";
        String scope = blockMapPing ? "聊天+标记" : "聊天";
        return name + "(" + index + ")已被禁言" + suffix + "(" + scope + ")";
    }

    private String unmuteMessage(String name, int index) {
        return name + "(" + index + ")已解除禁言";
    }

    private boolean isActive(MuteState state, long now) {
        return state != null && (state.expiresAt == PERMANENT || state.expiresAt > now);
    }

    private void clearState() {
        allChatMuted = false;
        synchronized (stateLock) {
            muteSnapshot = Collections.emptyMap();
        }
    }

    private void dismissActiveDialog() {
        Activity activity = host.currentActivity();
        if (activity != null) activity.runOnUiThread(() -> {
            if (activeDialog != null) activeDialog.dismiss();
        });
    }

    private static final class MuteState {
        final long expiresAt;
        final boolean blockMapPing;
        final String name;

        MuteState(long expiresAt, boolean blockMapPing, String name) {
            this.expiresAt = expiresAt;
            this.blockMapPing = blockMapPing;
            this.name = name;
        }
    }

    private static final class PlayerSnapshot {
        final int index;
        final String name;
        final int numberColor;
        final int nameColor;
        final long expiresAt;
        final boolean blockMapPing;

        PlayerSnapshot(int index, String name, int numberColor, int nameColor,
                       long expiresAt, boolean blockMapPing) {
            this.index = index;
            this.name = name;
            this.numberColor = numberColor;
            this.nameColor = nameColor;
            this.expiresAt = expiresAt;
            this.blockMapPing = blockMapPing;
        }
    }

    private static final class Draft {
        final int index;
        final String name;
        long expiresAt;
        boolean blockMapPing;
        long durationMs;

        Draft(int index, String name, long expiresAt, boolean blockMapPing, long durationMs) {
            this.index = index;
            this.name = name;
            this.expiresAt = expiresAt;
            this.blockMapPing = blockMapPing;
            this.durationMs = durationMs;
        }
    }
}
