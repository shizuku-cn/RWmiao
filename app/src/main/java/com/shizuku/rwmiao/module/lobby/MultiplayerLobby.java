package com.shizuku.rwmiao.module.lobby;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TableLayout;

import com.shizuku.rwmiao.ui.lobby.LobbyPanelHost;
import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

import static com.shizuku.rwmiao.config.SettingsContract.DEFAULT_LOBBY_AD_KEYWORDS;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_AD_KEYWORDS;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_AD_KEYWORDS_VERSION;
import static com.shizuku.rwmiao.config.SettingsContract.KEY_LOBBY_SEARCHER;
import static com.shizuku.rwmiao.config.SettingsContract.LOBBY_AD_KEYWORDS_VERSION;
import static com.shizuku.rwmiao.config.SettingsContract.PREFS_NAME;

/**
 * Replaces only the native lobby table generation. Network discovery,
 * native ordering and the native join protocol remain intact.
 */
public final class MultiplayerLobby {
    private static final String TAG = "RWmiao";
    private static final int MAX_ADDRESS_DISPLAY_LENGTH = 22;
    private static final int MAX_PRIMARY_DISPLAY_LENGTH = 48;
    private static final int MAX_MOD_DISPLAY_LENGTH = 36;
    private final RWmiaoModule host;
    private final ClassLoader loader;

    private XposedInterface.HookHandle createHook;
    private XposedInterface.HookHandle listRunnableHook;
    private Method getSortedServers;
    private Method refreshServerList;
    private Method joinServerFromList;
    private Method mapDisplayMethod;
    private Field runnableActivityField;
    private RecordFields recordFields;

    private Activity lobbyActivity;
    private LobbyPanelHost panel;
    private TableLayout nativeTable;
    private ViewGroup nativeParent;
    private ViewGroup.LayoutParams nativeTableParams;
    private int nativeTableIndex = -1;
    private View nativeRefreshButton;
    private int nativeRefreshVisibility = View.VISIBLE;
    private boolean nativeRefreshEnabled = true;
    private String adKeywordsRaw;
    private String[] adKeywords = new String[0];
    private int refreshGeneration;

    public MultiplayerLobby(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    public void install() throws Throwable {
        refreshSettings();
    }

    public synchronized void refreshSettings() throws Throwable {
        if (!host.selectionActionEnabled(KEY_LOBBY_SEARCHER)) {
            unhookAll();
            restoreNativeTable();
            return;
        }

        try {
            if (createHook == null || listRunnableHook == null) {
                installHooks();
            }
        } catch (Throwable t) {
            unhookAll();
            restoreNativeTable();
            throw t;
        }
    }

    private void installHooks() throws Throwable {
        Class<?> activityClass = loader.loadClass(host.target("appFramework.MultiplayerLobbyActivity"));
        Class<?> recordClass = loader.loadClass(host.target("gameFramework.j.f"));
        Class<?> runnableClass = loader.loadClass(host.target("appFramework.gi"));

        Method create = host.findCompatibleMethod(activityClass, "onCreate", Bundle.class);
        Method listRun = host.findCompatibleMethod(runnableClass, "run");
        getSortedServers = host.findCompatibleMethod(activityClass, "getSortedDiscoveredServers");
        refreshServerList = host.findCompatibleMethod(activityClass, "refreshServerList");
        joinServerFromList = host.findCompatibleMethod(
                activityClass, "joinServerFromList", recordClass, String.class);
        if (create == null || listRun == null || getSortedServers == null
                || refreshServerList == null || joinServerFromList == null) {
            throw new NoSuchMethodException("multiplayer lobby contract");
        }
        if (!Modifier.isStatic(getSortedServers.getModifiers())
                || !Modifier.isStatic(refreshServerList.getModifiers())) {
            throw new NoSuchMethodException("multiplayer lobby static refresh contract");
        }

        // JADX prints synthetic field aliases such as f195a, but the DEX
        // member name is the original short name a.
        runnableActivityField = host.findField(runnableClass, "a");
        recordFields = new RecordFields(recordClass);
        try {
            Class<?> levelSelect = loader.loadClass(host.target("appFramework.LevelSelectActivity"));
            mapDisplayMethod = host.findCompatibleMethod(
                    levelSelect, "convertLevelFileNameForDisplay", String.class);
        } catch (Throwable ignored) {
            mapDisplayMethod = null;
        }

        createHook = host.hookExecutable(create, chain -> {
            Object result = chain.proceed();
            Object activity = chain.getThisObject();
            if (activity instanceof Activity) {
                Activity lobby = (Activity) activity;
                if (installPanel(lobby)) publishRooms(lobby);
            }
            return result;
        });

        // gi.run() is the native TableRow builder. Return before the original
        // body so no native rows/TextViews are generated when M3 is active.
        listRunnableHook = host.hookExecutable(listRun, chain -> {
            Object activity = runnableActivityField.get(chain.getThisObject());
            if (activity instanceof Activity && installPanel((Activity) activity)) {
                publishRooms((Activity) activity);
                return null;
            }
            // Safe fallback if the target layout changed and replacement was
            // not installed: do not break the original lobby.
            return chain.proceed();
        });
        host.log(4, TAG, "Multiplayer M3 lobby hooks installed");
    }

    private boolean installPanel(Activity activity) {
        if (panel != null && panel.getParent() != null && lobbyActivity == activity) {
            hideNativeRefreshButton(activity);
            return true;
        }
        View tableView;
        try {
            int tableId = activity.getResources().getIdentifier(
                    "gameListTable", "id", activity.getPackageName());
            tableView = tableId == 0 ? null : activity.findViewById(tableId);
        } catch (Throwable t) {
            host.log(5, TAG, "Unable to resolve multiplayer gameListTable", t);
            return false;
        }
        if (!(tableView instanceof TableLayout)
                || !(tableView.getParent() instanceof ViewGroup)) {
            host.log(5, TAG, "Native multiplayer gameListTable was not found");
            return false;
        }

        TableLayout table = (TableLayout) tableView;
        ViewGroup parent = (ViewGroup) table.getParent();
        lobbyActivity = activity;
        nativeTable = table;
        nativeParent = parent;
        nativeTableIndex = parent.indexOfChild(table);
        nativeTableParams = table.getLayoutParams();
        panel = new LobbyPanelHost(activity, new LobbyPanelHost.Callbacks() {
            @Override
            public void onRoomClicked(LobbyRoom room) {
                handleRoomClick(activity, room);
            }

            @Override
            public void onRefreshClicked() {
                requestRefresh(activity);
            }

            @Override
            public void onKeywordsChanged() {
                publishRooms(activity);
            }
        });

        parent.removeView(table);
        ViewGroup.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(panel, Math.max(0, nativeTableIndex), panelParams);
        hideNativeRefreshButton(activity);
        host.log(4, TAG, "Replaced native multiplayer TableLayout with M3 room list");
        return true;
    }

    private void publishRooms(Activity activity) {
        LobbyPanelHost currentPanel = panel;
        if (currentPanel == null || currentPanel.getParent() == null) return;
        try {
            Object value = getSortedServers.invoke(null);
            if (!(value instanceof List)) return;
            refreshAdKeywords();
            ArrayList<LobbyRoom> rooms = new ArrayList<>(((List<?>) value).size());
            for (Object record : (List<?>) value) {
                LobbyRoom room = toRoom(record);
                if (room != null) rooms.add(room);
            }
            currentPanel.submitRooms(rooms);
            hideNativeRefreshButton(activity);
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to publish multiplayer room snapshots", t);
            hideNativeRefreshButton(activity);
        }
    }

    private void hideNativeRefreshButton(Activity activity) {
        try {
            int refreshId = activity.getResources().getIdentifier(
                    "refreshServersButton", "id", activity.getPackageName());
            View refreshButton = refreshId == 0 ? null : activity.findViewById(refreshId);
            if (refreshButton != null) {
                if (nativeRefreshButton != refreshButton) {
                    nativeRefreshButton = refreshButton;
                    nativeRefreshVisibility = refreshButton.getVisibility();
                    nativeRefreshEnabled = refreshButton.isEnabled();
                }
                refreshButton.setVisibility(View.GONE);
                refreshButton.setEnabled(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private void requestRefresh(Activity activity) {
        final int generation = ++refreshGeneration;
        try {
            if (refreshServerList != null) refreshServerList.invoke(null);
        } catch (Throwable t) {
            host.log(5, TAG, "Unable to refresh multiplayer room list", t);
        }
        // refreshServerList() posts the native gi runnable. Publish once now
        // and once after that queued redraw/network handoff so the M3 panel
        // cannot remain on a stale snapshot if the native runnable is skipped.
        publishRooms(activity);
        try {
            View decor = activity.getWindow().getDecorView();
            decor.postDelayed(() -> {
                if (generation == refreshGeneration) publishRooms(activity);
            }, 500L);
        } catch (Throwable ignored) {
        }
    }

    private void restoreNativeRefreshButton() {
        if (nativeRefreshButton == null) return;
        try {
            nativeRefreshButton.setVisibility(nativeRefreshVisibility);
            nativeRefreshButton.setEnabled(nativeRefreshEnabled);
        } catch (Throwable ignored) {
        }
        nativeRefreshButton = null;
        nativeRefreshVisibility = View.VISIBLE;
        nativeRefreshEnabled = true;
    }

    private LobbyRoom toRoom(Object record) throws Throwable {
        if (record == null) return null;
        String roomId = string(recordFields.id, record);
        String address = string(recordFields.address, record);
        String lanAddress = string(recordFields.lanAddress, record);
        String externalUrl = string(recordFields.externalUrl, record);
        String description = string(recordFields.description, record);
        String rawHostName = string(recordFields.hostName, record);
        String rawServerName = string(recordFields.serverName, record);

        String hostName = firstNonBlank(rawHostName, rawServerName);
        String serverNameKey = normalizeKey(hostName);
        boolean official = isOfficialServer(rawHostName, rawServerName);
        String rawMap = string(recordFields.map, record);
        String mapName = rawMap;
        if (mapDisplayMethod != null && rawMap.length() > 0) {
            try {
                Object displayed = mapDisplayMethod.invoke(null, rawMap);
                if (displayed != null) mapName = firstNonBlank(displayed.toString(), rawMap);
            } catch (Throwable ignored) {
            }
        }
        String modName = normalizeModName(string(recordFields.mods, record));
        boolean modded = !modName.isEmpty();
        String version = string(recordFields.version, record);
        String state = string(recordFields.state, record);
        int port = integer(recordFields.port, record, 0);
        int relayId = integer(recordFields.relayId, record, 0);
        String playersText = string(recordFields.playersText, record);
        String maxPlayersText = string(recordFields.maxPlayersText, record);
        // v/w are the native parsed counters. t/u are their wire-text
        // counterparts and can remain stale after a room update, so use the
        // same authoritative fields as the original lobby for both display
        // and hasSlots/joinable decisions. Only fall back to t/u when v/w are
        // unavailable on a compatible game build.
        int players = integer(recordFields.players, record, -1);
        if (players < 0) players = playerCount(playersText, -1);
        int maxPlayers = integer(recordFields.maxPlayers, record, -1);
        if (maxPlayers < 0) maxPlayers = playerCount(maxPlayersText, -1);
        boolean open = bool(recordFields.open, record);
        boolean password = bool(recordFields.password, record);
        boolean lan = bool(recordFields.lan, record);
        boolean compatible = compatible(record);
        boolean hasSlots = players >= 0 && maxPlayers > 0 && players < maxPlayers;
        boolean external = externalUrl.length() > 0;
        String rawAddress = lan
                ? withPort(lanAddress, port)
                : (address.length() > 0 ? withPort(address, port) : roomId);
        String addressKey = normalizeKey(firstNonBlank(rawAddress, roomId));
        String joinAddress;
        if (external) {
            joinAddress = "";
        } else if (lan) {
            joinAddress = withPort(lanAddress, port);
        } else if (relayId != 0) {
            joinAddress = "get|" + roomId.replace('|', '.') + "|"
                    + relayId + "|" + password + "|" + port;
        } else {
            joinAddress = withPort(address, port);
        }
        boolean joinable = !external && compatible && hasSlots
                && ("battleroom".equalsIgnoreCase(state)
                || "chat".equalsIgnoreCase(state))
                && (lan || open || relayId != 0);
        boolean officialLinkAd = isOfficialLinkAd(
                rawServerName, rawMap, mapName);
        // The second M3 column is hostName. Match custom ad keywords against
        // that exact displayed field only; do not use the hidden raw server
        // metadata field, map, address, description, or version.
        boolean adLike = !official && (external || containsAdKeyword(
                hostName));
        String visibility = lan ? "L" : (open ? "Y" : "N");
        String versionName = version.length() == 0 ? "" : (
                "ANY".equalsIgnoreCase(version) ? "ANY" : "V" + version);
        String status = lobbyStatus(state);
        String playersLabel = displayPlayerCount(players, maxPlayers);
        String details = "房主：" + hostName
                + "\n人数：" + playersLabel
                + "\n地图：" + mapName
                + "\n模组：" + modName
                + "\n版本：" + versionName
                + "\n地址：" + rawAddress;
        String searchText = lower(
                roomId + " " + rawAddress + " " + address + " " + lanAddress + " "
                        + rawHostName + " " + rawServerName + " " + hostName + " "
                        + mapName + " " + rawMap + " " + modName
                        + " " + version + " " + description);
        return new LobbyRoom(
                record,
                roomId,
                shorten(firstNonBlank(rawAddress, roomId), MAX_ADDRESS_DISPLAY_LENGTH),
                addressKey,
                status,
                shorten(hostName, MAX_PRIMARY_DISPLAY_LENGTH),
                serverNameKey,
                shorten(mapName, MAX_PRIMARY_DISPLAY_LENGTH),
                shorten(modName, MAX_MOD_DISPLAY_LENGTH),
                versionName,
                visibility,
                playersLabel,
                externalUrl,
                joinAddress,
                details,
                searchText,
                players,
                maxPlayers,
                password,
                compatible,
                hasSlots,
                joinable,
                adLike,
                modded,
                official,
                officialLinkAd
        );
    }

    private boolean compatible(Object record) {
        try {
            Object value = recordFields.compatible.invoke(record);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void handleRoomClick(Activity activity, LobbyRoom room) {
        if (room.getExternalUrl() != null && !room.getExternalUrl().isEmpty()) {
            try {
                activity.startActivity(new Intent(
                        Intent.ACTION_VIEW, Uri.parse(room.getExternalUrl())));
            } catch (Throwable t) {
                host.log(5, TAG, "Unable to open multiplayer external link", t);
            }
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("加入房间")
                .setMessage(room.getDetails())
                .setPositiveButton("加入", (dialog, which) -> {
                    try {
                        joinServerFromList.invoke(
                                activity, room.getNativeRecord(), room.getJoinAddress());
                        LobbyPanelHost currentPanel = panel;
                        if (currentPanel != null) currentPanel.markRoomJoined(room);
                    } catch (Throwable t) {
                        host.log(5, TAG, "Unable to join multiplayer room", t);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreNativeTable() {
        Runnable restore = () -> {
            if (panel != null && panel.getParent() instanceof ViewGroup) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
            restoreNativeRefreshButton();
            if (nativeTable != null && nativeParent != null && nativeTable.getParent() == null) {
                int index = Math.max(0, Math.min(nativeTableIndex, nativeParent.getChildCount()));
                nativeParent.addView(nativeTable, index, nativeTableParams);
                if (refreshServerList != null) {
                    try {
                        refreshServerList.invoke(null);
                    } catch (Throwable t) {
                        host.log(5, TAG, "Unable to refresh restored native lobby", t);
                    }
                }
            }
            panel = null;
            lobbyActivity = null;
            nativeTable = null;
            nativeParent = null;
            nativeTableParams = null;
            nativeTableIndex = -1;
            refreshServerList = null;
        };
        Activity activity = lobbyActivity;
        if (activity != null && Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(restore);
        } else {
            restore.run();
        }
    }

    private void unhookAll() {
        unhook(createHook);
        unhook(listRunnableHook);
        createHook = null;
        listRunnableHook = null;
        getSortedServers = null;
        joinServerFromList = null;
        mapDisplayMethod = null;
        runnableActivityField = null;
        recordFields = null;
    }

    private void unhook(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try {
            handle.unhook();
        } catch (Throwable ignored) {
        }
    }

    private final class RecordFields {
        final Field id;
        final Field address;
        final Field lanAddress;
        final Field externalUrl;
        final Field description;
        final Field port;
        final Field open;
        final Field password;
        final Field hostName;
        final Field serverName;
        final Field map;
        final Field mods;
        final Field state;
        final Field version;
        final Field players;
        final Field maxPlayers;
        final Field playersText;
        final Field maxPlayersText;
        final Field lan;
        final Field relayId;
        final Method compatible;

        RecordFields(Class<?> type) throws Throwable {
            id = host.findField(type, "b");
            address = host.findField(type, "c");
            lanAddress = host.findField(type, "d");
            externalUrl = host.findField(type, "e");
            description = host.findField(type, "f");
            port = host.findField(type, "g");
            open = host.findField(type, "h");
            password = host.findField(type, "m");
            hostName = host.findField(type, "n");
            serverName = host.findField(type, "r");
            map = host.findField(type, "q");
            mods = host.findField(type, "z");
            state = host.findField(type, "s");
            version = host.findField(type, "k");
            players = host.findField(type, "v");
            maxPlayers = host.findField(type, "w");
            playersText = host.findField(type, "t");
            maxPlayersText = host.findField(type, "u");
            // The decompiler alias f738a maps back to the real field a.
            lan = host.findField(type, "a");
            relayId = host.findField(type, "A");
            compatible = host.findCompatibleMethod(type, "b");
            if (compatible == null) throw new NoSuchMethodException("server compatibility method");
        }
    }

    private static String string(Field field, Object object) throws IllegalAccessException {
        Object value = field.get(object);
        return value == null ? "" : value.toString().trim();
    }

    private static boolean bool(Field field, Object object) throws IllegalAccessException {
        Object value = field.get(object);
        return value instanceof Boolean && (Boolean) value;
    }

    private static int integer(Field field, Object object, int fallback)
            throws IllegalAccessException {
        Object value = field.get(object);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int playerCount(String text, int fallback) {
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String displayPlayerCount(int players, int maxPlayers) {
        String current = players < 0 ? "?" : Integer.toString(players);
        String maximum = maxPlayers < 0 ? "?" : Integer.toString(maxPlayers);
        return current + "/" + maximum;
    }

    private static String withPort(String address, int port) {
        if (address == null || address.isEmpty()) return "";
        return port > 0 ? address + ":" + port : address;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first.trim()
                : (second == null ? "" : second.trim());
    }

    private static boolean isOfficialServer(String hostName, String serverName) {
        String hostKey = normalizeKey(hostName);
        String serverKey = normalizeKey(serverName);
        return ("server".equals(hostKey) && "auto server".equals(serverKey))
                || ("auto server".equals(hostKey) && "server".equals(serverKey));
    }

    private static String lobbyStatus(String state) {
        return "battleroom".equalsIgnoreCase(state)
                || "chat".equalsIgnoreCase(state)
                ? "战役室" : "游戏中";
    }

    private static boolean isOfficialLinkAd(
            String serverName,
            String rawMap,
            String displayedMap
    ) {
        if (!"link".equals(normalizeKey(serverName))) return false;
        return lower(rawMap).contains("discord")
                || lower(displayedMap).contains("discord");
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) return "";
        value = value.trim();
        if (value.length() <= maxLength) return value;
        int left = Math.max(1, (maxLength - 1) / 2);
        int right = Math.max(1, maxLength - left - 1);
        return value.substring(0, left) + "…" + value.substring(value.length() - right);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String value) {
        return lower(value == null ? "" : value.trim().replaceAll("\\s+", " "));
    }

    private static String normalizeModName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return "";
        String key = normalizeKey(normalized);
        if ("none".equals(key) || "no mod".equals(key) || "no mods".equals(key)
                || "vanilla".equals(key) || "原版".equals(key)
                || "无".equals(key) || "无模组".equals(key)) {
            return "";
        }
        return normalized;
    }

    private boolean containsAdKeyword(String value) {
        String normalized = lower(value);
        for (String keyword : adKeywords) {
            if (!keyword.isEmpty() && normalized.contains(keyword)) return true;
        }
        return false;
    }

    private void refreshAdKeywords() {
        String raw = DEFAULT_LOBBY_AD_KEYWORDS;
        Context context = host.preferenceContext();
        if (context != null) {
            SharedPreferences preferences = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            String version = preferences.getString(KEY_LOBBY_AD_KEYWORDS_VERSION, null);
            if (!LOBBY_AD_KEYWORDS_VERSION.equals(version)) {
                preferences.edit()
                        .remove(KEY_LOBBY_AD_KEYWORDS)
                        .putString(KEY_LOBBY_AD_KEYWORDS_VERSION, LOBBY_AD_KEYWORDS_VERSION)
                        .apply();
            } else {
                raw = preferences.getString(KEY_LOBBY_AD_KEYWORDS,
                        DEFAULT_LOBBY_AD_KEYWORDS);
            }
        } else {
            raw = host.preferenceString(KEY_LOBBY_AD_KEYWORDS, DEFAULT_LOBBY_AD_KEYWORDS);
        }
        if (raw == null) raw = "";
        if (raw.equals(adKeywordsRaw)) return;
        adKeywordsRaw = raw;
        Set<String> parsed = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String keyword = lower(line == null ? "" : line.trim());
            if (!keyword.isEmpty()) parsed.add(keyword);
        }
        adKeywords = parsed.toArray(new String[0]);
    }
}
