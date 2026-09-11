package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;

import com.shizuku.rwmiao.BuildConfig;
import com.shizuku.rwmiao.config.SettingsContract;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class RoomOptions {
    private static final String TAG = "RWmiao";
    private static final int MIN_ROOM_PLAYERS = 10;
    private static final int MAX_ROOM_PLAYERS = 100;
    private static final int MIN_UNIT_CAP = 1;
    private static final int MAX_UNIT_CAP = 10000;

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Map<Object, Set<String>> bannedByNetwork =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> banStartBroadcastGames =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final long BAN_MESSAGE_COOLDOWN_MS = 10_000L;
    private final Object banMessageCooldownLock = new Object();
    private final Map<String, Long> banMessageCooldownUntil = new LinkedHashMap<>();
    private volatile Object activeBannedNetwork;
    private volatile Set<String> activeBannedUnits = Collections.emptySet();
    private volatile int activeUnitCap = -1;

    private final Object runtimeHooksLock = new Object();
    private volatile boolean runtimeHooksInstalled;
    private volatile boolean banHooksInstalled;
    private final ThreadLocal<String> bannedCommandRejected = new ThreadLocal<>();
    private XposedInterface.HookHandle banValidateHook;
    private XposedInterface.HookHandle banExecuteHook;
    private XposedInterface.HookHandle banCommandIngressHook;
    private XposedInterface.HookHandle banGameStartHook;
    private XposedInterface.HookHandle productionQueueHook;
    private XposedInterface.HookHandle desyncMessageHook;
    private XposedInterface.HookHandle networkCapHook;
    private XposedInterface.HookHandle gameCapHook;
    private Method gameOptionsClickMethod;
    private XposedInterface.HookHandle gameOptionsClickHook;
    private BanReflection banReflection;

    public RoomOptions(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        loader.loadClass(host.target("appFramework.MultiplayerBattleroomActivity"));
        Class<?> clickListener = loader.loadClass(host.target("appFramework.ft"));
        gameOptionsClickMethod = host.findCompatibleMethod(
                clickListener, "onClick", View.class);
        if (gameOptionsClickMethod == null) {
            throw new NoSuchMethodException("找不到游戏选项按钮的 ft.onClick 方法");
        }
        refreshSettings();
    }

    void refreshSettings() throws Throwable {
        if (!extendedGameOptionsEnabled()) {
            unhookEntryHooks();
            disableRuntimeHooksIfNeeded();
            return;
        }
        ensureEntryHooks();
    }

    private synchronized void ensureEntryHooks() {
        if (gameOptionsClickHook == null && gameOptionsClickMethod != null) {
            gameOptionsClickHook = host.hookExecutable(gameOptionsClickMethod, chain -> {
                try {
                    boolean enabled = extendedGameOptionsEnabled();
                    if (!enabled) disableRuntimeHooksIfNeeded();
                    if (enabled) {
                        Activity activity = activityFromClickListener(
                                chain.getThisObject(), chain.getArg(0));
                        if (activity != null && !activity.isFinishing()) {
                            Object network = currentNetwork();
                            if (network != null && isHostNetwork(network)
                                    && showHostDialog(activity, network)) {
                                return null;
                            }
                        }
                    }
                } catch (Throwable t) {
                    host.log(4, TAG, "拦截游戏选项按钮失败，保留原版行为", t);
                }
                return chain.proceed();
            });
        }

    }

    private synchronized void unhookEntryHooks() {
        unhookQuietly(gameOptionsClickHook);
        gameOptionsClickHook = null;
    }

    private Activity activityFromClickListener(Object listener, Object clickedView) {
        if (clickedView instanceof View) {
            Activity activity = activityFromContext(((View) clickedView).getContext());
            if (activity != null) return activity;
        }
        try {
            Object activity = host.findField(listener.getClass(), "a").get(listener);
            return activity instanceof Activity ? (Activity) activity : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Activity activityFromContext(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private boolean extendedGameOptionsEnabled() {
        try {
            Context targetContext = host.preferenceContext();
            if (targetContext == null) return false;
            SharedPreferences targetPreferences = targetContext.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE);
            if (targetPreferences.contains(SettingsContract.KEY_EXTENDED_GAME_OPTIONS)) {
                return targetPreferences.getBoolean(
                        SettingsContract.KEY_EXTENDED_GAME_OPTIONS, false);
            }
            Context moduleContext = targetContext.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            return moduleContext.getSharedPreferences(
                    SettingsContract.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(
                    SettingsContract.KEY_EXTENDED_GAME_OPTIONS, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean showHostDialog(Activity activity, Object network) {
        try {
            try {
                ensureUnitCapHooksInstalled();
            } catch (Throwable t) {
                host.log(4, TAG, "单位上限 Hook 安装失败，仍显示扩展面板", t);
            }
            com.shizuku.rwmiao.ui.RoomOptions.show(activity, this, network);
            return true;
        } catch (Throwable t) {
            host.log(5, TAG, "打开房间游戏选项面板失败，已保留原版行为", t);
            return false;
        }
    }

    private void ensureUnitCapHooksInstalled() throws Throwable {
        if (!extendedGameOptionsEnabled()) return;
        synchronized (runtimeHooksLock) {
            if (runtimeHooksInstalled) return;
            try {
                installUnitCapHooks();
                runtimeHooksInstalled = true;
            } catch (Throwable t) {
                uninstallUnitCapHooksLocked();
                throw t;
            }
        }
    }

    private void ensureBanHooksInstalled() throws Throwable {
        if (!extendedGameOptionsEnabled()) {
            throw new IllegalStateException("扩展游戏选项面板已关闭");
        }
        synchronized (runtimeHooksLock) {
            if (banHooksInstalled) return;
            try {
                installBanHooks();
                banHooksInstalled = true;
            } catch (Throwable t) {
                uninstallBanHooksLocked();
                throw t;
            }
        }
    }

    private void installBanHooks() throws Throwable {
        Class<?> commandClass = loader.loadClass(host.target("gameFramework.e"));
        banReflection = createBanReflection(commandClass);

        Method validate = host.findCompatibleMethod(commandClass, "i");
        banValidateHook = host.hookExecutable(validate, chain -> {
            String bannedUnitName = bannedUnitNameInCommand(chain.getThisObject());
            if (bannedUnitName != null) {
                bannedCommandRejected.set(bannedUnitName);
                return false;
            }
            return chain.proceed();
        });

        Method execute = host.findCompatibleMethod(commandClass, "h");
        banExecuteHook = host.hookExecutable(execute, chain -> {
            if (bannedUnitNameInCommand(chain.getThisObject()) != null) return null;
            return chain.proceed();
        });

        Class<?> networkClass = loader.loadClass(host.target("gameFramework.j.ae"));
        Method commandIngress = host.findCompatibleMethod(networkClass, "a", commandClass);
        banCommandIngressHook = host.hookExecutable(commandIngress, chain -> {
            String bannedUnitName = bannedUnitNameInCommand(chain.getArg(0));
            if (bannedUnitName != null) {
                bannedCommandRejected.remove();
                sendBanRejectionMessage(bannedUnitName);
                return null;
            }
            return chain.proceed();
        });

        Method desyncMessage = host.findCompatibleMethod(networkClass,
                "a", String.class, boolean.class);
        desyncMessageHook = host.hookExecutable(desyncMessage, chain -> {
            Object message = chain.getArg(0);
            String bannedUnitName = bannedCommandRejected.get();
            boolean rejectedCommand = "Skipped command issued from server".equals(message)
                    || (message instanceof String
                    && ((String) message).startsWith("Ignored command from "));
            if (rejectedCommand
                    && bannedUnitName != null) {
                bannedCommandRejected.remove();
                sendBanRejectionMessage(bannedUnitName);
                return null;
            }
            return chain.proceed();
        });

        Class<?> gameClass = loader.loadClass(host.target("game.i"));
        Method startGame = host.findCompatibleMethod(gameClass,
                "a", boolean.class, boolean.class, int.class);
        banGameStartHook = host.hookExecutable(startGame, chain -> {
            Object result = chain.proceed();
            broadcastBannedUnitsAtGameStart(chain.getThisObject());
            return result;
        });

        try {
            Class<?> queueClass = loader.loadClass(host.target("game.units.d.r"));
            Class<?> actionClass = loader.loadClass(host.target("game.units.a.s"));
            Method enqueue = host.findCompatibleMethod(queueClass, "a", actionClass, boolean.class);
            productionQueueHook = host.hookExecutable(enqueue, chain -> {
                Object action = chain.getArg(0);
                Object cancel = chain.getArg(1);
                if (Boolean.FALSE.equals(cancel) && isBannedProductionAction(action)) {
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            host.log(4, TAG, "生产队列补强 Hook 不可用，继续使用命令拦截", t);
        }
    }

    private BanReflection createBanReflection(Class<?> commandClass) throws Throwable {
        Class<?> orderClass = loader.loadClass(host.target("game.units.en"));
        Class<?> orderKindClass = loader.loadClass(host.target("game.units.eo"));
        Class<?> unitTypeClass = loader.loadClass(host.target("game.units.el"));
        Class<?> orderableUnitClass = loader.loadClass(host.target("game.units.bp"));
        Class<?> actionIdClass = loader.loadClass(host.target("game.units.a.c"));
        Class<?> actionClass = loader.loadClass(host.target("game.units.a.s"));
        Class<?> gameObjectClass = loader.loadClass(host.target("gameFramework.ah"));

        Field commandOrder = host.findField(commandClass, "j");
        Field commandAction = host.findField(commandClass, "k");
        Field commandUnits = host.findField(commandClass, "w");
        Field commandSelectedIds = host.findField(commandClass, "A");

        Field orderType = findFieldByType(orderClass, orderKindClass);
        Field orderUnitType = findFieldByType(orderClass, unitTypeClass);
        Method unitId = host.findCompatibleMethod(unitTypeClass, "i");
        Method unitName = host.findCompatibleMethod(unitTypeClass, "e");
        Method resolveAction = host.findCompatibleMethod(
                orderableUnitClass, "a", actionIdClass);
        Method resolveUnitById = host.findCompatibleMethod(
                gameObjectClass, "b", long.class, boolean.class);
        Method actionUnitType = host.findCompatibleMethod(actionClass, "h");
        if (unitId == null || resolveAction == null || resolveUnitById == null
                || actionUnitType == null) {
            throw new NoSuchMethodException("Ban 命令反射结构不完整");
        }
        return new BanReflection(
                commandOrder, commandAction, commandUnits, commandSelectedIds,
                orderType, orderUnitType, unitId, unitName, resolveAction,
                resolveUnitById, actionUnitType);
    }

    private Field findFieldByType(Class<?> owner, Class<?> expectedType)
            throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() == expectedType) {
                    field.setAccessible(true);
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(expectedType.getName());
    }

    private void installUnitCapHooks() throws Throwable {
        Class<?> networkClass = loader.loadClass(host.target("gameFramework.j.ae"));
        Method resetNetwork = host.findCompatibleMethod(networkClass, "a", boolean.class);
        networkCapHook = host.hookExecutable(resetNetwork, chain -> {
            Object result = chain.proceed();
            forceNetworkUnitCap(chain.getThisObject());
            return result;
        });

        Class<?> gameClass = loader.loadClass(host.target("game.i"));
        Method startGame = host.findCompatibleMethod(gameClass,
                "a", boolean.class, boolean.class, int.class);
        gameCapHook = host.hookExecutable(startGame, chain -> {
            Object result = chain.proceed();
            forceGameUnitCap(chain.getThisObject());
            return result;
        });
    }

    private void disableRuntimeHooksIfNeeded() {
        if (extendedGameOptionsEnabled()) return;
        synchronized (runtimeHooksLock) {
            if (runtimeHooksInstalled || banHooksInstalled) uninstallRuntimeHooksLocked();
        }
    }

    private void uninstallRuntimeHooksLocked() {
        uninstallBanHooksLocked();
        uninstallUnitCapHooksLocked();
        activeBannedNetwork = null;
        activeBannedUnits = Collections.emptySet();
        clearBanMessageCooldown();
        activeUnitCap = -1;
    }

    private void uninstallBanHooksLocked() {
        unhookQuietly(banValidateHook);
        unhookQuietly(banExecuteHook);
        unhookQuietly(banCommandIngressHook);
        unhookQuietly(banGameStartHook);
        unhookQuietly(productionQueueHook);
        unhookQuietly(desyncMessageHook);
        banValidateHook = null;
        banExecuteHook = null;
        banCommandIngressHook = null;
        banGameStartHook = null;
        productionQueueHook = null;
        desyncMessageHook = null;
        banReflection = null;
        bannedCommandRejected.remove();
        banHooksInstalled = false;
    }

    private void uninstallUnitCapHooksLocked() {
        unhookQuietly(networkCapHook);
        unhookQuietly(gameCapHook);
        networkCapHook = null;
        gameCapHook = null;
        runtimeHooksInstalled = false;
    }

    private void unhookQuietly(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try {
            handle.unhook();
        } catch (Throwable t) {
            host.log(5, TAG, "卸载房间扩展 Hook 失败", t);
        }
    }

    private Object currentNetwork() throws Throwable {
        Object engine = host.findEngine(loader);
        return host.findField(engine.getClass(), "bU").get(engine);
    }

    private boolean isHostNetwork(Object network) throws Throwable {
        return host.findField(network.getClass(), "D").getBoolean(network);
    }

    private void broadcastBannedUnitsAtGameStart(Object game) {
        Set<String> banned = activeBannedUnits;
        if (game == null || banned.isEmpty()) return;
        try {
            Object network = currentNetwork();
            if (network == null || network != activeBannedNetwork || !isHostNetwork(network)) {
                return;
            }

            synchronized (banStartBroadcastGames) {
                if (!banStartBroadcastGames.add(game)) return;
            }

            String message = "此服务器已禁用以下单位：" + bannedUnitDisplayText(banned);
            Method send = host.findCompatibleMethod(network.getClass(), "k", String.class);
            if (send == null) throw new NoSuchMethodException("找不到网络聊天广播方法");
            send.invoke(network, message);
        } catch (Throwable t) {
            synchronized (banStartBroadcastGames) {
                banStartBroadcastGames.remove(game);
            }
            host.log(4, TAG, "广播开局 Ban 单位列表失败", t);
        }
    }

    private String bannedUnitDisplayText(Set<String> banned) {
        ArrayList<String> values = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (UnitOption option : loadAvailableUnits()) {
            labels.put(normalizeUnitId(option.getId()), option.getLabel());
        }
        for (String id : banned) {
            String label = labels.get(normalizeUnitId(id));
            values.add(label == null || label.trim().isEmpty() ? id : label.trim());
        }
        return join(values, ",");
    }

    private void sendBanRejectionMessage(String unitName) {
        try {
            Object network = currentNetwork();
            if (network == null || !isHostNetwork(network)) return;
            Method send = host.findCompatibleMethod(network.getClass(), "k", String.class);
            if (send == null) throw new NoSuchMethodException("找不到网络系统消息发送方法");
            String cooldownKey = normalizeUnitNameForCooldown(unitName);
            if (!tryAcquireBanMessageCooldown(cooldownKey)) return;
            try {
                send.invoke(network, "desync：此服务器已禁用" + unitName);
            } catch (Throwable t) {
                releaseBanMessageCooldown(cooldownKey);
                throw t;
            }
        } catch (Throwable t) {
            host.log(4, TAG, "发送 Ban 提示失败", t);
        }
    }

    private String normalizeUnitNameForCooldown(String unitName) {
        String normalized = unitName == null ? "" : unitName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "<unknown>" : normalized;
    }

    private boolean tryAcquireBanMessageCooldown(String key) {
        long now = SystemClock.elapsedRealtime();
        synchronized (banMessageCooldownLock) {
            Long cooldownUntil = banMessageCooldownUntil.get(key);
            if (cooldownUntil != null && cooldownUntil > now) return false;
            banMessageCooldownUntil.put(key, now + BAN_MESSAGE_COOLDOWN_MS);
            return true;
        }
    }

    private void releaseBanMessageCooldown(String key) {
        synchronized (banMessageCooldownLock) {
            banMessageCooldownUntil.remove(key);
        }
    }

    private void clearBanMessageCooldown() {
        synchronized (banMessageCooldownLock) {
            banMessageCooldownUntil.clear();
        }
    }

    public RoomOptionsState readState(Object network) throws Throwable {
        Object settings = host.findField(network.getClass(), "aA").get(network);
        RoomOptionsState state = new RoomOptionsState();
        state.creditsIndex = host.findField(settings.getClass(), "c").getInt(settings);
        state.fogMode = host.findField(settings.getClass(), "d").getInt(settings);
        state.aiDifficulty = host.findField(settings.getClass(), "f").getInt(settings);
        state.startingUnits = host.findField(settings.getClass(), "g").getInt(settings);
        state.incomeMultiplier = host.findField(settings.getClass(), "h").getFloat(settings);
        state.noNukes = host.findField(settings.getClass(), "i").getBoolean(settings);
        state.sharedControl = host.findField(settings.getClass(), "l").getBoolean(settings);

        Class<?> teams = loader.loadClass(host.target("game.p"));
        state.maxPlayers = host.findField(teams, "c").getInt(null);
        state.unitCap = host.findField(network.getClass(), "az").getInt(network);
        if (state.unitCap <= 0) {
            state.unitCap = host.findField(network.getClass(), "ay").getInt(network);
        }
        state.bannedUnits = bannedText(network);
        state.creditsOptions = loadResourceOptions(
                "credits_array", 0, 8,
                new String[]{"Default", "$0", "$1000", "$2000", "$5000", "$10000",
                        "$50000", "$100000", "$200000"});
        state.fogOptions = loadResourceOptions(
                "fog_array", 0, 2, new String[]{"No Fog", "Basic Fog", "LOS Fog"});
        state.incomeOptions = nativeStringArray("income_array");
        state.aiOptions = loadAiOptions();
        state.startingUnitOptions = loadStartingUnitOptions();
        state.teamLayoutOptions = loadResourceOptions(
                "setTeams_array", -1, 3,
                new String[]{"Keep current", "2 Teams (eg 5v5)", "3 Teams (eg 1v1v1)",
                        "No teams (FFA)", "All spectators"});
        state.availableUnits = loadAvailableUnits();
        return state;
    }

    public void apply(Object network,
                      int maxPlayers,
                      int unitCap,
                      float incomeMultiplier,
                      boolean noNukes,
                      boolean sharedControl,
                      int creditsIndex,
                      int fogMode,
                      int startingUnits,
                      int aiDifficulty,
                      int teamLayout,
                      String bannedText) throws Throwable {
        if (network == null || !isHostNetwork(network)) {
            throw new IllegalStateException("只有房主可以应用房间设置");
        }
        if (maxPlayers < MIN_ROOM_PLAYERS || maxPlayers > MAX_ROOM_PLAYERS) {
            throw new IllegalArgumentException("房间人数上限必须在 10～100 之间");
        }
        if (unitCap < MIN_UNIT_CAP || unitCap > MAX_UNIT_CAP) {
            throw new IllegalArgumentException("单位上限必须在 1～10000 之间");
        }
        if (Float.isNaN(incomeMultiplier) || Float.isInfinite(incomeMultiplier)) {
            throw new IllegalArgumentException("资金增长倍率必须是有限数字（允许负数和 0）");
        }
        if (creditsIndex < 0 || creditsIndex > 8) {
            throw new IllegalArgumentException("初始资金选项无效");
        }
        if (fogMode < 0 || fogMode > 2) {
            throw new IllegalArgumentException("战争迷雾选项无效");
        }
        if (!isKnownStartingUnit(startingUnits)) {
            throw new IllegalArgumentException("初始单位选项无效");
        }
        if (aiDifficulty < -2 || aiDifficulty > 3) {
            throw new IllegalArgumentException("AI 难度选项无效");
        }

        Class<?> teams = loader.loadClass(host.target("game.p"));
        Field maxField = host.findField(teams, "c");
        int oldMaxPlayers = maxField.getInt(null);
        if (maxPlayers < oldMaxPlayers) {
            Field slotsField = host.findField(teams, "j");
            Object slots = slotsField.get(null);
            int length = slots == null ? 0 : Array.getLength(slots);
            for (int i = maxPlayers; i < length; i++) {
                if (Array.get(slots, i) != null) {
                    throw new IllegalArgumentException("不能把人数上限调到已有玩家所在的房间槽位以下");
                }
            }
        }

        Set<String> parsedBannedUnits = Collections.unmodifiableSet(parseBannedUnits(bannedText));
        if (!parsedBannedUnits.isEmpty()) {
            ensureBanHooksInstalled();
        }

        Object settings = host.findField(network.getClass(), "aA").get(network);
        host.findField(settings.getClass(), "c").setInt(settings, creditsIndex);
        host.findField(settings.getClass(), "d").setInt(settings, fogMode);
        host.findField(settings.getClass(), "e").setBoolean(settings, true);
        host.findField(settings.getClass(), "f").setInt(settings, aiDifficulty);
        host.findField(settings.getClass(), "g").setInt(settings, startingUnits);
        host.findField(settings.getClass(), "h").setFloat(settings, incomeMultiplier);
        host.findField(settings.getClass(), "i").setBoolean(settings, noNukes);
        host.findField(settings.getClass(), "l").setBoolean(settings, sharedControl);

        Object engine = host.findEngine(loader);
        Object settingsEngine = host.findField(engine.getClass(), "bN").get(engine);
        host.findField(settingsEngine.getClass(), "teamUnitCapHostedGame")
                .setInt(settingsEngine, unitCap);
        host.findField(network.getClass(), "ay").setInt(network, unitCap);
        host.findField(network.getClass(), "az").setInt(network, unitCap);

        Method resize = host.findCompatibleMethod(teams, "b", int.class, boolean.class);
        if (resize == null) throw new NoSuchMethodException("找不到房间人数调整方法");
        resize.invoke(null, maxPlayers, true);

        if (teamLayout >= 0) {
            Class<?> layoutClass = loader.loadClass(host.target("gameFramework.j.ba"));
            Object[] values = layoutClass.getEnumConstants();
            if (values == null || teamLayout >= values.length) {
                throw new IllegalArgumentException("团队布局选项无效");
            }
            Method setLayout = host.findCompatibleMethod(network.getClass(), "a", layoutClass);
            if (setLayout == null) throw new NoSuchMethodException("找不到团队布局设置方法");
            setLayout.invoke(network, values[teamLayout]);
        }

        Method sendUpdate = host.findCompatibleMethod(network.getClass(), "b");
        if (sendUpdate != null) sendUpdate.invoke(network);
        Method updateTimer = host.findCompatibleMethod(network.getClass(), "p");
        if (updateTimer != null) updateTimer.invoke(network);
        Method refreshListing = host.findCompatibleMethod(network.getClass(), "n");
        if (refreshListing != null) refreshListing.invoke(network);

        if (parsedBannedUnits.isEmpty()) {
            synchronized (runtimeHooksLock) {
                uninstallBanHooksLocked();
            }
        }
        synchronized (bannedByNetwork) {
            bannedByNetwork.put(network, parsedBannedUnits);
            activeBannedNetwork = network;
            activeBannedUnits = parsedBannedUnits;
        }
        clearBanMessageCooldown();
        activeUnitCap = unitCap;
        forceNetworkUnitCap(network);
        refreshBattleroomUi();
    }

    private void forceNetworkUnitCap(Object network) {
        try {
            if (network == null || activeUnitCap <= 0 || !isHostNetwork(network)) return;
            host.findField(network.getClass(), "ay").setInt(network, activeUnitCap);
            host.findField(network.getClass(), "az").setInt(network, activeUnitCap);
            Object engine = host.findEngine(loader);
            Object settingsEngine = host.findField(engine.getClass(), "bN").get(engine);
            host.findField(settingsEngine.getClass(), "teamUnitCapHostedGame")
                    .setInt(settingsEngine, activeUnitCap);
        } catch (Throwable t) {
            host.log(4, TAG, "进入对局前重新写入单位上限失败", t);
        }
    }

    private void forceGameUnitCap(Object game) {
        try {
            if (game == null || activeUnitCap <= 0) return;
            Object network = currentNetwork();
            if (network == null || !isHostNetwork(network)) return;
            host.findField(game.getClass(), "by").setInt(game, activeUnitCap);
            host.findField(game.getClass(), "bz").setInt(game, activeUnitCap);
            Class<?> teams = loader.loadClass(host.target("game.p"));
            Method refreshTeams = host.findCompatibleMethod(teams, "M");
            if (refreshTeams != null) refreshTeams.invoke(null);
        } catch (Throwable t) {
            host.log(4, TAG, "进入对局后重新写入单位上限失败", t);
        }
    }

    private void refreshBattleroomUi() {
        try {
            Class<?> activityClass = loader.loadClass(
                    host.target("appFramework.MultiplayerBattleroomActivity"));
            Method update = host.findCompatibleMethod(activityClass, "updateUI");
            if (update != null) update.invoke(null);
        } catch (Throwable t) {
            host.log(5, TAG, "应用房间设置后刷新界面失败", t);
        }
    }

    private Set<String> parseBannedUnits(String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null) return result;
        String[] tokens = raw.split("[,;\\s\\n\\r]+");
        for (String token : tokens) {
            String normalized = normalizeUnitId(token);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private String normalizeUnitId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String bannedText(Object network) {
        ArrayList<String> values = new ArrayList<>();
        synchronized (bannedByNetwork) {
            Set<String> set = bannedByNetwork.get(network);
            if (set != null) values.addAll(set);
        }
        Collections.sort(values);
        return join(values, ", ");
    }

    private String join(ArrayList<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private ArrayList<UnitOption> loadAvailableUnits() {
        LinkedHashMap<String, UnitOption> byId = new LinkedHashMap<>();
        try {
            Class<?> registryClass = loader.loadClass(host.target("game.units.cj"));
            Class<?> typeClass = loader.loadClass(host.target("game.units.el"));
            Class<?> ceClass = loader.loadClass(host.target("game.units.ce"));
            Class<?> baseUnitClass = loader.loadClass(host.target("game.units.bp"));
            Class<?> customUnitClass = loader.loadClass(host.target("game.units.custom.l"));
            Object allTypes = host.findField(registryClass, "ae").get(null);
            Method resolveUnit = host.findCompatibleMethod(ceClass, "d", typeClass);
            if (!(allTypes instanceof Iterable) || resolveUnit == null) {
                throw new IllegalStateException("单位注册表不可用");
            }

            ArrayList<Object> editorTypes = new ArrayList<>();
            Object[] excluded = new Object[]{
                    staticField(registryClass, "I"), staticField(registryClass, "v"),
                    staticField(registryClass, "q"), staticField(registryClass, "R"),
                    staticField(registryClass, "H"), staticField(registryClass, "W"),
                    staticField(registryClass, "X"), staticField(registryClass, "Y"),
                    staticField(registryClass, "Z"), staticField(registryClass, "N")
            };
            for (Object type : (Iterable<?>) allTypes) {
                if (type == null || isSameAny(type, excluded)) continue;
                Object unit = resolveUnit.invoke(null, type);
                if (unit == null || !baseUnitClass.isInstance(unit)) continue;
                String id = readUnitId(type);
                if ("test_tank".equals(id) || "missing".equals(id)) continue;
                if (customUnitClass.isInstance(type)
                        && !host.findField(customUnitClass, "aF").getBoolean(type)) {
                    continue;
                }
                editorTypes.add(type);
            }

            try {
                Class<?> editorComparator = loader.loadClass(host.target("game.units.ab"));
                java.lang.reflect.Constructor<?> constructor =
                        editorComparator.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object comparator = constructor.newInstance();
                Collections.sort((ArrayList) editorTypes, (Comparator) comparator);
            } catch (Throwable sortError) {
                Collections.sort((ArrayList) editorTypes, (left, right) -> {
                    String leftName = readUnitName(left);
                    String rightName = readUnitName(right);
                    int result = leftName.compareToIgnoreCase(rightName);
                    return result != 0 ? result : readUnitId(left).compareTo(readUnitId(right));
                });
            }
            for (Object type : editorTypes) {
                addUnitOption(byId, type);
            }
        } catch (Throwable t) {
            host.log(4, TAG, "读取 All 单位列表失败", t);
        }
        ArrayList<UnitOption> result = new ArrayList<>(byId.values());
        return result;
    }

    private Object staticField(Class<?> type, String name) {
        try {
            return host.findField(type, name).get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isSameAny(Object value, Object[] values) {
        for (Object candidate : values) if (value == candidate) return true;
        return false;
    }

    private String readUnitId(Object unit) {
        try {
            Method method = host.findCompatibleMethod(unit.getClass(), "i");
            Object value = method == null ? null : method.invoke(unit);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String readUnitName(Object unit) {
        try {
            Method method = host.findCompatibleMethod(unit.getClass(), "e");
            Object value = method == null ? null : method.invoke(unit);
            return value == null ? readUnitId(unit) : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return readUnitId(unit);
        }
    }

    private void addUnitOption(Map<String, UnitOption> byId, Object unit) {
        try {
            if (unit == null) return;
            String id = readUnitId(unit);
            String key = normalizeUnitId(id);
            if (key.isEmpty() || byId.containsKey(key)) return;

            String label = readUnitName(unit);
            if (label.isEmpty()) label = id;
            byId.put(key, new UnitOption(id, label));
        } catch (Throwable ignored) {
        }
    }

    private boolean isKnownStartingUnit(int value) {
        for (IntOption option : loadStartingUnitOptions()) {
            if (option.getValue() == value) return true;
        }
        return false;
    }

    private ArrayList<IntOption> loadStartingUnitOptions() {
        ArrayList<IntOption> result = new ArrayList<>();
        try {
            Class<?> networkClass = loader.loadClass(host.target("gameFramework.j.ae"));
            Method values = host.findCompatibleMethod(networkClass, "d");
            Method label = host.findCompatibleMethod(networkClass, "c", int.class);
            Object raw = values == null ? null : values.invoke(null);
            if (raw instanceof Iterable && label != null) {
                for (Object value : (Iterable<?>) raw) {
                    if (!(value instanceof Number)) continue;
                    int id = ((Number) value).intValue();
                    Object text = label.invoke(null, id);
                    result.add(new IntOption(id, text == null ? String.valueOf(id) : String.valueOf(text)));
                }
            }
        } catch (Throwable t) {
            host.log(4, TAG, "读取原生初始单位选项失败", t);
        }
        if (result.isEmpty()) {
            result.add(new IntOption(1, "Normal (1 builder)"));
            result.add(new IntOption(2, "Small Army"));
            result.add(new IntOption(3, "3 Engineers"));
            result.add(new IntOption(4, "3 Engineers (No Command Center)"));
        }
        return result;
    }

    private ArrayList<IntOption> loadAiOptions() {
        ArrayList<IntOption> result = new ArrayList<>();
        for (int value = -2; value <= 3; value++) {
            result.add(new IntOption(value, nativeTranslate(
                    "menus.settings.option.ai." + value, nativeAiLabel(value))));
        }
        return result;
    }

    private String nativeAiLabel(int value) {
        switch (value) {
            case -2: return "Very Easy";
            case -1: return "Easy";
            case 0: return "Medium";
            case 1: return "Hard";
            case 2: return "Very Hard";
            case 3: return "Impossible";
            default: return "Unknown";
        }
    }

    private ArrayList<IntOption> loadResourceOptions(
            String resourceName, int firstValue, int lastValue, String[] fallback) {
        ArrayList<IntOption> result = new ArrayList<>();
        String[] labels = nativeStringArray(resourceName);
        for (int value = firstValue; value <= lastValue; value++) {
            int labelIndex = firstValue < 0 ? value - firstValue : value;
            String label = labels != null && labelIndex >= 0 && labelIndex < labels.length
                    ? labels[labelIndex] : null;
            if (label == null || label.trim().isEmpty()) {
                int fallbackIndex = labelIndex;
                label = fallbackIndex >= 0 && fallbackIndex < fallback.length
                        ? fallback[fallbackIndex] : String.valueOf(value);
            }
            result.add(new IntOption(value, label.trim()));
        }
        return result;
    }

    private String[] nativeStringArray(String resourceName) {
        try {
            Object engine = host.findEngine(loader);
            Context context = (Context) host.findField(engine.getClass(), "am").get(engine);
            Class<?> arrays = loader.loadClass(host.target("R$array"));
            int resourceId = host.findField(arrays, resourceName).getInt(null);
            return context.getResources().getStringArray(resourceId);
        } catch (Throwable t) {
            host.log(4, TAG, "读取原生选项资源失败: " + resourceName, t);
            return null;
        }
    }

    private String nativeTranslate(String key, String fallback) {
        try {
            Class<?> translation = loader.loadClass(host.target("gameFramework.h.a"));
            Method method = host.findCompatibleMethod(translation, "a", String.class, Object[].class);
            if (method != null) {
                Object[] args = new Object[]{key, new Object[0]};
                Object value = method.invoke(null, args);
                if (value != null && !String.valueOf(value).trim().isEmpty()) {
                    return String.valueOf(value).trim();
                }
            }
        } catch (Throwable t) {
            host.log(4, TAG, "调用游戏原生翻译失败: " + key, t);
        }
        return fallback;
    }

    private String bannedUnitNameInCommand(Object command) {
        Set<String> banned = activeBannedUnits;
        BanReflection reflection = banReflection;
        if (command == null || banned.isEmpty() || reflection == null) return null;
        try {
            Object order = reflection.commandOrder.get(command);
            if (order != null) {
                Object orderType = reflection.orderType.get(order);
                if (orderType instanceof Enum
                        && "build".equals(((Enum<?>) orderType).name())) {
                    Object unitType = reflection.orderUnitType.get(order);
                    String name = bannedUnitName(unitType, banned, reflection);
                    if (name != null) return name;
                }
            }

            Object actionId = reflection.commandAction.get(command);
            if (actionId == null) return null;
            Object selectedUnits = reflection.commandUnits.get(command);
            if (selectedUnits instanceof Iterable) {
                String name = bannedUnitNameInSelectedUnits(
                        (Iterable<?>) selectedUnits, actionId, banned, reflection);
                if (name != null) return name;
            }

            Object selectedIds = reflection.commandSelectedIds.get(command);
            if (selectedIds instanceof Iterable) {
                for (Object selectedId : (Iterable<?>) selectedIds) {
                    if (!(selectedId instanceof Number)) continue;
                    Object selectedUnit = reflection.resolveUnitById.invoke(
                            null, ((Number) selectedId).longValue(), true);
                    if (selectedUnit == null) continue;
                    String name = bannedUnitNameInSelectedUnit(
                            selectedUnit, actionId, banned, reflection);
                    if (name != null) return name;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String bannedUnitNameInSelectedUnits(Iterable<?> selectedUnits,
                                                 Object actionId,
                                                 Set<String> banned,
                                                 BanReflection reflection) throws Throwable {
        for (Object selectedUnit : selectedUnits) {
            if (selectedUnit == null) continue;
            String name = bannedUnitNameInSelectedUnit(
                    selectedUnit, actionId, banned, reflection);
            if (name != null) return name;
        }
        return null;
    }

    private String bannedUnitNameInSelectedUnit(Object selectedUnit,
                                                Object actionId,
                                                Set<String> banned,
                                                BanReflection reflection) throws Throwable {
        Object action = reflection.resolveAction.invoke(selectedUnit, actionId);
        if (action == null) return null;
        Object unitType = reflection.actionUnitType.invoke(action);
        return bannedUnitName(unitType, banned, reflection);
    }

    private String bannedUnitName(Object unitType,
                                  Set<String> banned,
                                  BanReflection reflection) throws Throwable {
        if (unitType == null) return null;
        Object rawId = reflection.unitId.invoke(unitType);
        if (rawId == null) return null;
        String id = String.valueOf(rawId).trim();
        if (id.isEmpty()) return null;
        if (!banned.contains(id) && !banned.contains(normalizeUnitId(id))) return null;
        if (reflection.unitName != null) {
            Object rawName = reflection.unitName.invoke(unitType);
            if (rawName != null) {
                String name = String.valueOf(rawName).trim();
                if (!name.isEmpty()) return name;
            }
        }
        return id;
    }

    private boolean isBannedProductionAction(Object action) {
        Set<String> banned = activeBannedUnits;
        BanReflection reflection = banReflection;
        if (action == null || banned.isEmpty() || reflection == null) return false;
        try {
            return bannedUnitName(
                    reflection.actionUnitType.invoke(action), banned, reflection) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class BanReflection {
        final Field commandOrder;
        final Field commandAction;
        final Field commandUnits;
        final Field commandSelectedIds;
        final Field orderType;
        final Field orderUnitType;
        final Method unitId;
        final Method unitName;
        final Method resolveAction;
        final Method resolveUnitById;
        final Method actionUnitType;

        BanReflection(Field commandOrder,
                      Field commandAction,
                      Field commandUnits,
                      Field commandSelectedIds,
                      Field orderType,
                      Field orderUnitType,
                      Method unitId,
                      Method unitName,
                      Method resolveAction,
                      Method resolveUnitById,
                      Method actionUnitType) {
            this.commandOrder = commandOrder;
            this.commandAction = commandAction;
            this.commandUnits = commandUnits;
            this.commandSelectedIds = commandSelectedIds;
            this.orderType = orderType;
            this.orderUnitType = orderUnitType;
            this.unitId = unitId;
            this.unitName = unitName;
            this.resolveAction = resolveAction;
            this.resolveUnitById = resolveUnitById;
            this.actionUnitType = actionUnitType;
        }
    }

    public static final class UnitOption {
        private final String id;
        private final String label;

        public UnitOption(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class IntOption {
        private final int value;
        private final String label;

        public IntOption(int value, String label) {
            this.value = value;
            this.label = label;
        }

        public int getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class RoomOptionsState {
        public int creditsIndex;
        public int fogMode;
        public int aiDifficulty;
        public int startingUnits;
        public float incomeMultiplier;
        public boolean noNukes;
        public boolean sharedControl;
        public int maxPlayers;
        public int unitCap;
        public String bannedUnits;
        public ArrayList<IntOption> creditsOptions;
        public ArrayList<IntOption> fogOptions;
        public ArrayList<IntOption> aiOptions;
        public ArrayList<IntOption> startingUnitOptions;
        public ArrayList<IntOption> teamLayoutOptions;
        public String[] incomeOptions;
        public ArrayList<UnitOption> availableUnits;
    }
}
