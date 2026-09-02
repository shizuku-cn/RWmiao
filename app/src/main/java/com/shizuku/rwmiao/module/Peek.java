package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.KEY_ENEMY_MAP_PINGS;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_ENEMY_TEAM_CHAT;
import static com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME;

final class Peek {
    private static final String TAG = "RWmiao";
    private static final long PING_LABEL_DURATION_MS = 8_000L;
    private static final int MAX_PING_LABELS = 16;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Map<Object, Object> pingActions = new IdentityHashMap<>();
    private final ArrayList<PingLabel> pingLabels = new ArrayList<>();
    private volatile PingLabel[] pingLabelSnapshot = new PingLabel[0];
    private volatile long nextPingExpiryAt = Long.MAX_VALUE;
    private final Paint pingLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Class<?> connectionClass;
    private Class<?> teamClass;
    private Method receiveChat;
    private Method deliverMessage;
    private Method sameTeam;
    private Method teamName;
    private Method executeCommand;
    private Method showMapPing;
    private Method pingText;
    private Method drawFrame;
    private Field serverMode;
    private Field hostTeam;
    private Field playerIndex;
    private Field teamPlayerName;
    private Field teamGroup;
    private Field commandTeam;
    private Field commandAction;
    private Field commandPoint;
    private Field gameNetwork;
    private Field gameUi;
    private Field gameRenderer;
    private Field cameraX;
    private Field cameraY;
    private Field renderScale;
    private Field screenWidth;
    private Field screenHeight;

    private XposedInterface.HookHandle chatHook;
    private XposedInterface.HookHandle mapPingHook;
    private XposedInterface.HookHandle pingLabelHook;
    private volatile boolean chatEnabled;
    private volatile boolean mapPingEnabled;
    private Class<?> rendererType;
    private Method drawText;

    Peek(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        Class<?> networkClass = loader.loadClass(host.target("gameFramework.j.ae"));
        Class<?> commandClass = loader.loadClass(host.target("gameFramework.e"));
        Class<?> gameClass = loader.loadClass(host.target("gameFramework.k"));
        Class<?> pingActionClass = loader.loadClass(host.target("game.units.a.j"));
        Class<?> uiClass = loader.loadClass(host.target("gameFramework.f.i"));
        connectionClass = loader.loadClass(host.target("gameFramework.j.c"));
        teamClass = loader.loadClass(host.target("game.p"));

        receiveChat = networkClass.getDeclaredMethod(
                "a", connectionClass, teamClass, String.class, String.class, connectionClass);
        receiveChat.setAccessible(true);
        deliverMessage = networkClass.getDeclaredMethod(
                "a", connectionClass, int.class, String.class, String.class);
        deliverMessage.setAccessible(true);
        sameTeam = teamClass.getDeclaredMethod("c", teamClass);
        sameTeam.setAccessible(true);
        teamName = teamClass.getDeclaredMethod("a", int.class);
        teamName.setAccessible(true);

        serverMode = host.findField(networkClass, "D");
        hostTeam = host.findField(networkClass, "A");
        playerIndex = host.findField(teamClass, "l");
        teamPlayerName = host.findField(teamClass, "w");
        teamGroup = host.findField(teamClass, "s");

        executeCommand = commandClass.getDeclaredMethod("h");
        executeCommand.setAccessible(true);
        commandTeam = host.findField(commandClass, "i");
        commandAction = host.findField(commandClass, "k");
        commandPoint = host.findField(commandClass, "l");

        showMapPing = uiClass.getDeclaredMethod(
                "a", float.class, float.class, teamClass, pingActionClass);
        showMapPing.setAccessible(true);
        pingText = pingActionClass.getDeclaredMethod("b");
        pingText.setAccessible(true);
        drawFrame = uiClass.getDeclaredMethod("b", float.class);
        drawFrame.setAccessible(true);

        gameNetwork = host.findField(gameClass, "bU");
        gameUi = host.findField(gameClass, "bP");
        gameRenderer = host.findField(gameClass, "bL");
        cameraX = host.findField(gameClass, "ct");
        cameraY = host.findField(gameClass, "cu");
        renderScale = host.findField(gameClass, "cU");
        screenWidth = host.findField(gameClass, "cC");
        screenHeight = host.findField(gameClass, "cE");

        Field pingActionList = host.findField(pingActionClass, "b");
        Field actionId = host.findField(pingActionClass, "j");
        Object actions = pingActionList.get(null);
        if (actions instanceof Iterable) {
            for (Object action : (Iterable<?>) actions) {
                if (action != null) pingActions.put(actionId.get(action), action);
            }
        }
        if (pingActions.isEmpty()) {
            throw new IllegalStateException("未找到地图标记动作");
        }

        pingLabelPaint.setColor(Color.WHITE);
        pingLabelPaint.setTextAlign(Paint.Align.CENTER);
        pingLabelPaint.setTextSize(18.0f);
        pingLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        pingLabelPaint.setShadowLayer(3.0f, 0.0f, 0.0f, Color.BLACK);

        refreshSettings();
    }

    synchronized void refreshSettings() throws Throwable {
        boolean shouldEnableChat = settingEnabled(KEY_ENEMY_TEAM_CHAT);
        HostMutePanel mutePanel = host.hostMutePanelFeature();
        boolean shouldHookChat = shouldEnableChat
                || (mutePanel != null && mutePanel.enabled());
        boolean shouldEnableMapPings = settingEnabled(KEY_ENEMY_MAP_PINGS);

        if (shouldHookChat) {
            ensureChatHook();
            chatEnabled = shouldEnableChat;
        } else {
            chatEnabled = false;
            XposedInterface.HookHandle handle = chatHook;
            chatHook = null;
            if (handle != null) handle.unhook();
        }

        if (shouldEnableMapPings) {
            ensureMapPingHook();
            mapPingEnabled = true;
        } else {
            mapPingEnabled = false;
            XposedInterface.HookHandle handle = mapPingHook;
            mapPingHook = null;
            if (handle != null) handle.unhook();
            clearPingLabels();
            disablePingLabelHook();
        }
    }

    private boolean settingEnabled(String key) {
        if (host.selectionActionEnabled(key)) return true;
        Activity activity = host.currentActivity();
        return activity != null && activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, false);
    }

    private void ensureChatHook() {
        if (chatHook != null) return;
        chatHook = host.hookExecutable(receiveChat, chain -> {
            Object network = chain.getThisObject();
            Object sourceConnection = chain.getArg(0);
            Object senderTeam = chain.getArg(1);
            HostMutePanel mutePanel = host.hostMutePanelFeature();
            if (mutePanel != null
                    && mutePanel.shouldSuppressChat(network, sourceConnection, senderTeam)) {
                return null;
            }
            try {
                if (chatEnabled) {
                    maybeShowEnemyMessage(network, sourceConnection, senderTeam,
                            (String) chain.getArg(2), (String) chain.getArg(3));
                }
            } catch (Throwable t) {
                host.log(5, TAG, "显示其它队伍消息失败", t);
            }
            return chain.proceed();
        });
    }

    private void ensureMapPingHook() {
        if (mapPingHook != null) return;
        mapPingHook = host.hookExecutable(executeCommand, chain -> {
            Object result = chain.proceed();
            try {
                if (mapPingEnabled) maybeShowEnemyMapPing(chain.getThisObject());
            } catch (Throwable t) {
                host.log(5, TAG, "显示其它队伍地图标记失败", t);
            }
            return result;
        });
    }

    private void maybeShowEnemyMessage(Object network, Object sourceConnection,
                                       Object senderTeam, String sender, String rawText)
            throws Throwable {
        if (!serverMode.getBoolean(network) || senderTeam == null || rawText == null
                || isSameTeam(senderTeam, hostTeam.get(network))) {
            return;
        }

        String markedText = toMarkedTeamText(rawText, teamMarker(senderTeam));
        if (markedText == null) return;
        deliverMessage.invoke(network, sourceConnection, playerIndex.getInt(senderTeam),
                sender, markedText);
    }

    private void maybeShowEnemyMapPing(Object command) throws Throwable {
        Object pingAction = pingActions.get(commandAction.get(command));
        if (pingAction == null) return;

        Object senderTeam = commandTeam.get(command);
        Object pointObject = commandPoint.get(command);
        if (senderTeam == null || !(pointObject instanceof PointF)) return;

        Object game = host.findEngine(loader);
        if (game == null) return;
        Object network = gameNetwork.get(game);
        if (network == null) return;
        Object localTeam = hostTeam.get(network);
        if (serverMode.getBoolean(network)) {
            if (isSameTeam(senderTeam, localTeam)) return;
        } else if (localTeam == null || isSameTeam(senderTeam, localTeam)) {
            return;
        }

        PointF point = (PointF) pointObject;
        Object ui = gameUi.get(game);
        if (ui == null) return;
        showMapPing.invoke(ui, point.x, point.y, senderTeam, pingAction);

        String actionText = String.valueOf(pingText.invoke(pingAction));
        addPingLabel(point.x, point.y, teamMarker(senderTeam) + actionText);
        showEnemyPingInChat(network, senderTeam, actionText);
    }

    private void showEnemyPingInChat(Object network, Object senderTeam, String actionText)
            throws Throwable {
        String sender = String.valueOf(teamPlayerName.get(senderTeam));
        String message = teamMarker(senderTeam) + actionText;
        deliverMessage.invoke(network, null, playerIndex.getInt(senderTeam), sender, message);
    }

    private boolean isSameTeam(Object senderTeam, Object localTeam) throws Throwable {
        if (senderTeam == null || localTeam == null) return false;
        return Boolean.TRUE.equals(sameTeam.invoke(senderTeam, localTeam));
    }

    private String teamMarker(Object team) throws Throwable {
        String name = String.valueOf(teamName.invoke(null, teamGroup.getInt(team)));
        return "[队伍 " + name + "] ";
    }

    private String toMarkedTeamText(String rawText, String marker) {
        String command = commandName(rawText);
        if ("t".equals(command)) return marker + rawText.substring(2);
        if ("surrender".equals(command)) return marker + rawText;
        return null;
    }

    private String commandName(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2) return null;
        char marker = trimmed.charAt(0);
        if (marker != '-' && marker != '.' && marker != '_') return null;
        String command = trimmed.substring(1).trim();
        int space = command.indexOf(' ');
        if (space < 0) space = command.length();
        return command.substring(0, space).toLowerCase(java.util.Locale.ENGLISH);
    }

    private void addPingLabel(float x, float y, String text) {
        if (!mapPingEnabled) return;
        long expiresAt = SystemClock.uptimeMillis() + PING_LABEL_DURATION_MS;
        synchronized (pingLabels) {
            while (pingLabels.size() >= MAX_PING_LABELS) pingLabels.remove(0);
            pingLabels.add(new PingLabel(x, y, text, expiresAt));
            pingLabelSnapshot = pingLabels.toArray(new PingLabel[0]);
            refreshNextPingExpiryLocked();
        }
        ensurePingLabelHook();
    }

    private synchronized void ensurePingLabelHook() {
        if (pingLabelHook != null || drawFrame == null) return;
        pingLabelHook = host.hookExecutable(drawFrame, chain -> {
            Object result = chain.proceed();
            try {
                drawPingLabels();
            } catch (Throwable t) {
                host.log(5, TAG, "绘制地图标记队伍文本失败", t);
                clearPingLabels();
                disablePingLabelHook();
            }
            return result;
        });
    }

    private synchronized void disablePingLabelHook() {
        XposedInterface.HookHandle hook = pingLabelHook;
        pingLabelHook = null;
        if (hook != null) {
            try { hook.unhook(); } catch (Throwable ignored) { }
        }
    }

    private void clearPingLabels() {
        synchronized (pingLabels) {
            pingLabels.clear();
            pingLabelSnapshot = new PingLabel[0];
            nextPingExpiryAt = Long.MAX_VALUE;
        }
    }

    private void drawPingLabels() throws Throwable {
        if (!mapPingEnabled) {
            clearPingLabels();
            disablePingLabelHook();
            return;
        }

        long now = SystemClock.uptimeMillis();
        PingLabel[] active = pingLabelSnapshot;
        if (active.length == 0) {
            disablePingLabelHook();
            return;
        }
        if (now >= nextPingExpiryAt) {
            synchronized (pingLabels) {
                for (int i = pingLabels.size() - 1; i >= 0; i--) {
                    if (pingLabels.get(i).expiresAt <= now) pingLabels.remove(i);
                }
                pingLabelSnapshot = pingLabels.toArray(new PingLabel[0]);
                refreshNextPingExpiryLocked();
                active = pingLabelSnapshot;
            }
            if (active.length == 0) {
                disablePingLabelHook();
                return;
            }
        }

        Object game = host.findEngine(loader);
        if (game == null) return;
        Object renderer = gameRenderer.get(game);
        if (renderer == null) return;
        if (rendererType != renderer.getClass()) {
            rendererType = renderer.getClass();
            drawText = host.findCompatibleMethod(rendererType, "a",
                    String.class, float.class, float.class, Paint.class);
        }
        if (drawText == null) throw new NoSuchMethodException("地图标记文字绘制方法");

        float viewX = cameraX.getFloat(game);
        float viewY = cameraY.getFloat(game);
        float scale = renderScale.getFloat(game);
        float width = host.number(screenWidth.get(game));
        float height = host.number(screenHeight.get(game));
        for (PingLabel label : active) {
            float x = (label.x - viewX) * scale;
            float y = (label.y - viewY) * scale - 28.0f;
            if (x < -120.0f || y < -40.0f || x > width + 120.0f || y > height + 40.0f) {
                continue;
            }
            long remaining = label.expiresAt - now;
            int alpha = remaining < 1_500L ? (int) (255L * remaining / 1_500L) : 255;
            pingLabelPaint.setAlpha(Math.max(0, Math.min(255, alpha)));
            drawText.invoke(renderer, label.text, x, y, pingLabelPaint);
        }
        pingLabelPaint.setAlpha(255);
    }

    private void refreshNextPingExpiryLocked() {
        long next = Long.MAX_VALUE;
        for (PingLabel label : pingLabels) {
            if (label.expiresAt < next) next = label.expiresAt;
        }
        nextPingExpiryAt = next;
    }

    private static final class PingLabel {
        final float x;
        final float y;
        final String text;
        final long expiresAt;

        PingLabel(float x, float y, String text, long expiresAt) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.expiresAt = expiresAt;
        }
    }
}
