package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.Menu;
import android.view.ViewGroup;
import android.widget.Button;

import com.shizuku.rwmiao.ui.main.SettingsPage;
import com.shizuku.rwmiao.module.drawing.Drawing;
import com.shizuku.rwmiao.module.freeselection.FreeSelection;
import com.shizuku.rwmiao.module.freebuild.FreeBuild;
import com.shizuku.rwmiao.module.selectall.SelectAll;
import com.shizuku.rwmiao.module.playerinfo.PlayerInfoPanel;
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;
import com.shizuku.rwmiao.module.script.ScriptManager;
import com.shizuku.rwmiao.module.lobby.MultiplayerLobby;
import com.shizuku.rwmiao.module.network.NetworkInfo;
import com.shizuku.rwmiao.module.proxy.ProxyService;

import static com.shizuku.rwmiao.config.SettingsContract.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class RWmiaoModule extends XposedModule {
    private static volatile RWmiaoModule instance;
    private static final String TAG = "RWmiao";
    private static final String ORIGINAL_PREFIX = "com.corrodinggames.rts";
    private String targetPrefix = ORIGINAL_PREFIX;
    private String loadedPackageName;
    private volatile Set<String> targetDexClasses = Collections.emptySet();
    private volatile CompatibilityResolver compatibilityResolver;
    private static final int MODULE_MENU_ID = 0x52574D;
    private static final String MAIN_MENU_BUTTON_TAG = "com.shizuku.rwmiao.main_menu_button";

    private final WeakHashMap<Activity, SettingsPage> modulePages = new WeakHashMap<>();
    private final java.util.Set<ClassLoader> installedLoaders =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> gameResyncListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private ClassLoader gameLoader;
    private volatile Context targetContext;
    private volatile java.util.concurrent.ScheduledExecutorService activationHeartbeat;
    private volatile XposedInterface.HookHandle applicationAttachHook;
    private volatile Boolean viewAllState;
    private volatile Boolean factoryOptState;
    private volatile Activity lastActivity;
    private AutoReinforce reinforceFeature;
    private Drawing drawingFeature;
    private FreeSelection freeSelectionFeature;
    private FreeBuild freeBuildFeature;
    private SelectAll selectAllFeature;
    private CombatView combatViewFeature;
    private NoFog noFogFeature;
    private ViewAll viewAllFeature;
    private PlayerInfoPanel playerInfoPanelFeature;
    private FactoryOptimization factoryOptimizationFeature;
    private FactoryExitThrough factoryExitThroughFeature;
    private MotherRally motherRallyFeature;
    private SelectionActions selectionActionsFeature;
    private SelectedUnitPanel selectedUnitPanelFeature;
    private FormationButtons formationButtonsFeature;
    private MultiplayerLobby multiplayerLobby;
    private NetworkInfo networkInfoFeature;
    private ProxyService proxyServiceFeature;
    private SegmentCommands segmentCommands;
    private SmartPathing smartPathing;
    private ScriptManager scriptManager;
    private GameLimits gameLimitsFeature;
    private BatchPlacement batchPlacementFeature;
    private RoomOptions hostRoomOptionsFeature;
    private HostMutePanel hostMutePanelFeature;
    private Peek peekFeature;
    private GameSyncTracker gameSyncTracker;
    private GameTickDispatcher tickDispatcher;
    private GameFrameDispatcher frameDispatcher;
    private final java.util.concurrent.ConcurrentHashMap<FieldKey, Field> fieldCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<FieldKey> missingFields =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<MethodKey, Method> methodCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<MethodKey> missingMethods =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader loader = param.getDefaultClassLoader();
        loadedPackageName = param.getPackageName();
        if (!isRustedWarfareVariant(loader, loadedPackageName)) return;
        compatibilityResolver = CompatibilityResolver.load(
                this, targetPrefix, targetDexClasses);
        synchronized (installedLoaders) {
            if (!installedLoaders.add(loader)) {
                return;
            }
        }
        try {
            instance = this;
            gameLoader = loader;
            startActivationHeartbeat();
            installApplicationBootstrap();
            installFeatures(loader);
            if (captureCurrentApplicationContext()) refreshFeatureHooks();
            startModuleStartupUpdate();
        } catch (Throwable t) {
            synchronized (installedLoaders) {
                installedLoaders.remove(loader);
            }
            log(6, TAG, "Failed to install Rusted Warfare hooks", t);
        }
    }

    private void installFeatures(ClassLoader loader) {
        gameSyncTracker = installFeature("multiplayer resync lifecycle tracker",
                () -> new GameSyncTracker(this, loader), GameSyncTracker::install);
        installHook("in-game menu", () -> hookMenu(loader));
        installHook("main-menu button", () -> hookMainMenu(loader));
        noFogFeature = installFeature("no-fog",
                () -> new NoFog(this, loader), NoFog::install);
        hostMutePanelFeature = installFeature("host mute panel",
                () -> new HostMutePanel(this, loader), HostMutePanel::install);
        peekFeature = installFeature("enemy-team messages and map pings",
                () -> new Peek(this, loader), Peek::install);
        viewAllFeature = installFeature("view-all",
                () -> new ViewAll(this, loader), ViewAll::install);
        playerInfoPanelFeature = installFeature("player info panel",
                () -> new PlayerInfoPanel(this, loader), PlayerInfoPanel::install);
        factoryOptimizationFeature = installFeature("factory optimization",
                () -> new FactoryOptimization(this, loader), FactoryOptimization::install);
        factoryExitThroughFeature = installFeature("factory exit-through",
                () -> new FactoryExitThrough(this, loader), FactoryExitThrough::install);
        motherRallyFeature = installFeature("mother-unit rally",
                () -> new MotherRally(this, loader), MotherRally::install);
        reinforceFeature = installFeature("automatic reinforcement",
                () -> new AutoReinforce(this, loader), AutoReinforce::install);
        drawingFeature = installFeature("drawing overlay",
                () -> new Drawing(this, loader), Drawing::install);
        freeSelectionFeature = installFeature("free selection",
                () -> new FreeSelection(this, loader), FreeSelection::install);
        freeBuildFeature = installFeature("free build",
                () -> new FreeBuild(this, loader), FreeBuild::install);
        selectAllFeature = installFeature("native select-all",
                () -> new SelectAll(this, loader), SelectAll::install);
        combatViewFeature = installFeature("combat view",
                () -> new CombatView(this, loader), CombatView::install);
        segmentCommands = installFeature("segmented commands",
                () -> new SegmentCommands(this, loader), SegmentCommands::install);
        smartPathing = installFeature("smart pathing",
                () -> new SmartPathing(this, loader, segmentCommands), SmartPathing::install);
        scriptManager = installFeature("Lua automation",
                () -> new ScriptManager(this, loader), ScriptManager::install);
        selectionActionsFeature = installFeature("selection actions",
                () -> new SelectionActions(this, loader), SelectionActions::install);
        selectedUnitPanelFeature = installFeature("selected-unit panel",
                () -> new SelectedUnitPanel(this, loader), SelectedUnitPanel::install);
        formationButtonsFeature = installFeature("formation buttons",
                () -> new FormationButtons(this, loader), FormationButtons::install);
        multiplayerLobby = installFeature("multiplayer lobby",
                () -> new MultiplayerLobby(this, loader), MultiplayerLobby::install);
        networkInfoFeature = installFeature("network information",
                () -> new NetworkInfo(this, loader), NetworkInfo::install);
        proxyServiceFeature = installFeature("proxy service",
                () -> new ProxyService(this), ProxyService::install);
        gameLimitsFeature = installFeature("game limits",
                () -> new GameLimits(this, loader), GameLimits::install);
        batchPlacementFeature = installFeature("batch placement",
                () -> new BatchPlacement(this, loader), BatchPlacement::install);
        hostRoomOptionsFeature = installFeature("host room options",
                () -> new RoomOptions(this, loader), RoomOptions::install);
    }

    private <T> T installFeature(
            String name, ThrowingSupplier<T> factory, ThrowingConsumer<T> installer) {
        try {
            T feature = factory.get();
            installer.accept(feature);
            return feature;
        } catch (Throwable t) {
            log(6, TAG, "Failed to install " + name, t);
            return null;
        }
    }

    private void installHook(String name, ThrowingRunnable installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            log(6, TAG, "Failed to install " + name + " hook", t);
        }
    }

    private boolean isRustedWarfareVariant(ClassLoader loader, String packageName) {
        if (loader == null) {
            return false;
        }
        java.util.LinkedHashSet<String> prefixes = new java.util.LinkedHashSet<>();
        prefixes.add(ORIGINAL_PREFIX);
        if (packageName != null && !packageName.isEmpty()) {
            prefixes.add(packageName);
            String cursor = packageName;
            while (cursor.contains(".")) {
                cursor = cursor.substring(0, cursor.lastIndexOf('.'));
                if (!cursor.isEmpty()) prefixes.add(cursor);
            }
            for (String suffix : new String[]{".rts", ".game", ".android", ".app"}) {
                if (packageName.endsWith(suffix)) {
                    prefixes.add(packageName.substring(0, packageName.length() - suffix.length()));
                }
            }
        }
        for (String prefix : prefixes) {
            try {
                loader.loadClass(prefix + ".appFramework.InGameActivity");
                targetPrefix = prefix;
                targetDexClasses = collectDexClassNames(loader);
                return true;
            } catch (Throwable ignored) {
            }
        }
        Set<String> dexClasses = collectDexClassNames(loader);
        String scanned = findGamePrefixInDex(dexClasses);
        if (scanned != null) {
            targetPrefix = scanned;
            targetDexClasses = dexClasses;
            return true;
        }
        return false;
    }

    private Set<String> collectDexClassNames(ClassLoader loader) {
        HashSet<String> result = new HashSet<>();
        try {
            Field pathListField = findField(loader.getClass(), "pathList");
            Object pathList = pathListField.get(loader);
            Field elementsField = findField(pathList.getClass(), "dexElements");
            Object elements = elementsField.get(pathList);
            if (!(elements instanceof Object[])) return result;
            for (Object element : (Object[]) elements) {
                if (element == null) continue;
                Field dexFileField;
                try {
                    dexFileField = findField(element.getClass(), "dexFile");
                } catch (Throwable ignored) {
                    continue;
                }
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) continue;
                Method entriesMethod = findNoArgMethod(dexFile.getClass(), "entries");
                if (entriesMethod == null) continue;
                Object entriesObject = entriesMethod.invoke(dexFile);
                if (!(entriesObject instanceof Enumeration)) continue;
                Enumeration<?> entries = (Enumeration<?>) entriesObject;
                while (entries.hasMoreElements()) {
                    Object value = entries.nextElement();
                    if (value instanceof String) result.add((String) value);
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "Unable to scan variant dex namespace", t);
        }
        return result;
    }

    private String findGamePrefixInDex(Set<String> dexClasses) {
        final String suffix = ".appFramework.InGameActivity";
        for (String name : dexClasses) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return null;
    }

    public String target(String suffix) {
        CompatibilityResolver resolver = compatibilityResolver;
        return resolver == null ? targetPrefix + "." + suffix : resolver.className(suffix);
    }

    InputStream openModuleAsset(String name) throws IOException {
        android.content.pm.ApplicationInfo info = getModuleApplicationInfo();
        if (info == null || info.sourceDir == null) {
            throw new IOException("Module APK path is unavailable");
        }
        try (ZipFile apk = new ZipFile(info.sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/" + name);
            if (entry == null) throw new IOException("Missing module asset: " + name);
            try (InputStream input = apk.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return new ByteArrayInputStream(output.toByteArray());
            }
        }
    }

    AutoReinforce reinforceFeature() {
        return reinforceFeature;
    }

    FreeSelection freeSelectionFeature() {
        return freeSelectionFeature;
    }

    FreeBuild freeBuildFeature() {
        return freeBuildFeature;
    }

    SelectAll selectAllFeature() {
        return selectAllFeature;
    }

    CombatView combatViewFeature() {
        return combatViewFeature;
    }

    MotherRally motherRallyFeature() {
        return motherRallyFeature;
    }

    FactoryExitThrough factoryExitThroughFeature() {
        return factoryExitThroughFeature;
    }

    HostMutePanel hostMutePanelFeature() {
        return hostMutePanelFeature;
    }

    public SegmentCommands segmentCommands() {
        return segmentCommands;
    }

    public void setFreeBuildQueueExpansion(boolean enabled) {
        BatchPlacement feature = batchPlacementFeature;
        if (feature == null) return;
        try {
            feature.setFreeBuildQueueExpansion(enabled);
        } catch (Throwable t) {
            log(5, TAG, "free-build queue expansion refresh failed", t);
        }
    }

    SmartPathing smartPathing() {
        return smartPathing;
    }

    public synchronized GameTickDispatcher tickDispatcher() {
        if (tickDispatcher == null) tickDispatcher = new GameTickDispatcher(this);
        return tickDispatcher;
    }

    public long completedResyncGeneration() {
        GameSyncTracker tracker = gameSyncTracker;
        return tracker == null ? 0L : tracker.completedGeneration();
    }

    public void addGameResyncListener(Runnable listener) {
        if (listener != null) gameResyncListeners.addIfAbsent(listener);
    }

    void notifyGameResyncCompleted() {
        for (Runnable listener : gameResyncListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                log(5, TAG, "Module resync listener failed", t);
            }
        }
    }

    public synchronized GameFrameDispatcher frameDispatcher() {
        if (frameDispatcher == null) frameDispatcher = new GameFrameDispatcher(this);
        return frameDispatcher;
    }

    ScriptManager scriptManager() {
        return scriptManager;
    }

    private void hookMenu(ClassLoader loader) throws Throwable {
        Class<?> activityClass = loader.loadClass(target("appFramework.InGameActivity"));
        Method resume = findCompatibleMethod(activityClass, "onResume");
        if (resume != null) {
            hook(resume).intercept(chain -> {
                if (chain.getThisObject() instanceof Activity) {
                    lastActivity = (Activity) chain.getThisObject();
                    if (playerInfoPanelFeature != null) {
                        playerInfoPanelFeature.onResume((Activity) chain.getThisObject());
                    }
                    ModuleActivationReporter.report(
                            (Activity) chain.getThisObject(), loadedPackageName);
                    refreshFeatureHooks();
                }
                return chain.proceed();
            });
        }
        Method pause = findCompatibleMethod(activityClass, "onPause");
        if (pause != null) {
            hook(pause).intercept(chain -> {
                if (playerInfoPanelFeature != null) {
                    playerInfoPanelFeature.onPause(chain.getThisObject());
                }
                return chain.proceed();
            });
        }
        Method destroy = findCompatibleMethod(activityClass, "onDestroy");
        if (destroy != null) {
            hook(destroy).intercept(chain -> {
                if (playerInfoPanelFeature != null) {
                    playerInfoPanelFeature.onDestroy(chain.getThisObject());
                }
                return chain.proceed();
            });
        }
        Method prepareMenu = activityClass.getDeclaredMethod("onPrepareOptionsMenu", Menu.class);
        Method selectMenu = activityClass.getDeclaredMethod("selectMenuOptionInternal", int.class);
        hook(prepareMenu).intercept(chain -> {
            Object result = chain.proceed();
            if (chain.getThisObject() instanceof Activity) {
                lastActivity = (Activity) chain.getThisObject();
            }
            Menu menu = (Menu) chain.getArg(0);
            if (menu != null && !containsMenuItem(menu, MODULE_MENU_ID)) {
                menu.add(0, MODULE_MENU_ID, Menu.NONE, "RW miao");
            }
            return result;
        });
        hook(selectMenu).intercept(chain -> {
            int id = (Integer) chain.getArg(0);
            if (id == MODULE_MENU_ID) {
                showModulePage((Activity) chain.getThisObject(), loader);
                return null;
            }
            return chain.proceed();
        });
    }

    private void hookMainMenu(ClassLoader loader) throws Throwable {
        Class<?> activityClass = loader.loadClass(target("appFramework.MainMenuActivity"));
        Method create = findCompatibleMethod(activityClass, "onCreate", Bundle.class);
        if (create == null) {
            log(5, TAG, "MainMenuActivity.onCreate was not found");
            return;
        }
        hook(create).intercept(chain -> {
            Object result = chain.proceed();
            Object activity = chain.getThisObject();
            if (activity instanceof Activity) {
                installMainMenuButton((Activity) activity, loader);
            }
            return result;
        });
    }

    private void installMainMenuButton(Activity activity, ClassLoader loader) {
        try {
            int settingsId = resolveTargetResourceId(activity, loader, "settingsButton");
            View settingsButton = settingsId == 0 ? null : activity.findViewById(settingsId);
            if (!(settingsButton instanceof Button)
                    || !(settingsButton.getParent() instanceof ViewGroup)) {
                log(5, TAG, "Main menu settings button was not found");
                return;
            }

            ViewGroup buttonGroup = (ViewGroup) settingsButton.getParent();
            for (int index = 0; index < buttonGroup.getChildCount(); index++) {
                if (MAIN_MENU_BUTTON_TAG.equals(buttonGroup.getChildAt(index).getTag())) {
                    return;
                }
            }

            Button moduleButton = new Button(activity);
            moduleButton.setTag(MAIN_MENU_BUTTON_TAG);
            moduleButton.setText("RW miao");
            moduleButton.setContentDescription("RW miao");
            copyButtonAppearance((Button) settingsButton, moduleButton);
            moduleButton.setOnClickListener(view -> showModulePage(activity, loader));

            ViewGroup.LayoutParams layoutParams = copyLayoutParams(settingsButton, activity);
            buttonGroup.addView(moduleButton, layoutParams);
            log(4, TAG, "Added RW miao button to the main menu");
        } catch (Throwable t) {
            log(5, TAG, "Failed to add RW miao button to the main menu", t);
        }
    }

    private int resolveTargetResourceId(Activity activity, ClassLoader loader, String name) {
        try {
            int id = activity.getResources().getIdentifier(
                    name, "id", activity.getPackageName());
            if (id != 0) return id;
        } catch (Throwable ignored) {
        }
        for (String className : new String[]{target("R$id"), activity.getPackageName() + ".R$id"}) {
            try {
                Class<?> resourceClass = loader.loadClass(className);
                return findField(resourceClass, name).getInt(null);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private void copyButtonAppearance(Button source, Button target) {
        target.setTextColor(source.getTextColors());
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        target.setTypeface(source.getTypeface());
        target.setGravity(source.getGravity());
        target.setAllCaps(source.isAllCaps());
        target.setMinHeight(source.getMinHeight());
        target.setMinWidth(source.getMinWidth());
        target.setPadding(
                source.getPaddingLeft(), source.getPaddingTop(),
                source.getPaddingRight(), source.getPaddingBottom());
        Drawable background = source.getBackground();
        if (background != null && background.getConstantState() != null) {
            target.setBackground(background.getConstantState().newDrawable());
        }
    }

    private ViewGroup.LayoutParams copyLayoutParams(View source, Activity activity) {
        ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
        if (sourceParams instanceof android.widget.LinearLayout.LayoutParams) {
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(sourceParams);
            if (params.topMargin == 0) {
                params.topMargin = dp(activity, 4);
            }
            return params;
        }
        return new ViewGroup.LayoutParams(sourceParams);
    }

    private int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private boolean containsMenuItem(Menu menu, int itemId) {
        try {
            for (int index = 0; index < menu.size(); index++) {
                android.view.MenuItem item = menu.getItem(index);
                if (item != null && item.getItemId() == itemId) return true;
            }
        } catch (Throwable t) {
            log(5, TAG, "Failed to inspect in-game menu items", t);
        }
        return false;
    }

    private void removeModulePage(Activity activity) {
        SettingsPage page = modulePages.remove(activity);
        if (page != null && page.getParent() instanceof ViewGroup) {
            ((ViewGroup) page.getParent()).removeView(page);
        }
        refreshFeatureHooks();
    }

    public boolean isModulePageOpen(Activity activity) {
        return activity != null && modulePages.containsKey(activity);
    }

    public Activity currentActivity() {
        if (lastActivity != null && !lastActivity.isFinishing()) return lastActivity;
        synchronized (modulePages) {
            for (Activity activity : modulePages.keySet()) {
                if (activity != null && !activity.isFinishing()) return activity;
            }
        }
        return null;
    }

    public XposedInterface.HookHandle hookExecutable(Method method, XposedInterface.Hooker hooker) {
        return hook(method).intercept(hooker);
    }

    public static void toggleSelectedRange() {
        RWmiaoModule current = instance;
        if (current != null && current.drawingFeature != null) {
            current.drawingFeature.toggleSelected(true);
        }
    }

    public static void toggleSelectedLine() {
        RWmiaoModule current = instance;
        if (current != null && current.drawingFeature != null) {
            current.drawingFeature.toggleSelected(false);
        }
    }

    static void setSelectedRange(boolean enabled) {
        RWmiaoModule current = instance;
        if (current != null && current.drawingFeature != null) {
            current.drawingFeature.setSelected(true, enabled);
        }
    }

    static void setSelectedLine(boolean enabled) {
        RWmiaoModule current = instance;
        if (current != null && current.drawingFeature != null) {
            current.drawingFeature.setSelected(false, enabled);
        }
    }

    public static String selectedRangeTitle() {
        RWmiaoModule current = instance;
        return current != null && current.drawingFeature != null
                && current.drawingFeature.allSelectedEnabled(true) ? "取消绘制" : "绘制范围";
    }

    public static String selectedLineTitle() {
        RWmiaoModule current = instance;
        return current != null && current.drawingFeature != null
                && current.drawingFeature.allSelectedEnabled(false) ? "取消指示" : "指示索敌";
    }

    public static void toggleFreeSelection() {
        RWmiaoModule current = instance;
        if (current != null && current.freeSelectionFeature != null) {
            current.freeSelectionFeature.toggleMode();
        }
    }

    public static String freeSelectionTitle() {
        RWmiaoModule current = instance;
        return current == null || current.freeSelectionFeature == null
                ? "自由框选" : current.freeSelectionFeature.titleForSelection();
    }

    public static void toggleFreeBuild(Object unit) {
        RWmiaoModule current = instance;
        if (current != null && current.freeBuildFeature != null) {
            current.freeBuildFeature.toggleMode(unit);
        }
    }

    public static String freeBuildTitle() {
        RWmiaoModule current = instance;
        return current == null || current.freeBuildFeature == null
                ? "自由建造" : current.freeBuildFeature.titleForSelection();
    }

    public static void selectAllUnits() {
        RWmiaoModule current = instance;
        if (current != null && current.selectAllFeature != null) {
            current.selectAllFeature.selectAll();
        }
    }

    public static void jumpToNextCombat() {
        RWmiaoModule current = instance;
        if (current != null && current.combatViewFeature != null) {
            current.combatViewFeature.jumpToNextCombat();
        }
    }

    public static NetworkInfo.Snapshot networkInfoSnapshot() {
        RWmiaoModule current = instance;
        if (current == null || current.networkInfoFeature == null) {
            return NetworkInfo.Snapshot.emptyForUi();
        }
        try {
            return current.networkInfoFeature.snapshot();
        } catch (Throwable ignored) {
            return NetworkInfo.Snapshot.emptyForUi();
        }
    }

    public static String networkInfoText() {
        return networkInfoSnapshot().toDisplayTextForUi("（未查询）");
    }

    public static String networkPublicIp() {
        RWmiaoModule current = instance;
        if (current == null || current.networkInfoFeature == null) return "（查询失败）";
        try {
            if (current.proxyServiceFeature != null
                    && current.proxyServiceFeature.isRequested()) {
                String proxyIp = current.proxyServiceFeature.queryPublicIp();
                return proxyIp == null || proxyIp.isEmpty() ? "（代理不可用）" : proxyIp;
            }
            return current.networkInfoFeature.queryPublicIp();
        } catch (Throwable ignored) {
            return "（查询失败）";
        }
    }

    private void refreshPlayerInfoPanel(ClassLoader loader) {
        try {
            refreshFeatureHooks();
        } catch (Throwable t) {
            log(5, TAG, "Failed to refresh player info panel", t);
        }
    }

    public boolean selectionActionEnabled(String key) {
        try {
            Context context = preferenceContext();
            if (context == null) return false;
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(key, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int uiThemeMode() {
        return preferenceInt(KEY_UI_THEME_MODE, UI_THEME_SYSTEM);
    }

    public int uiColorMode() {
        return preferenceInt(KEY_UI_COLOR_MODE, UI_COLOR_DEFAULT);
    }

    int selectionActionScaleMask() {
        return preferenceInt(KEY_SELECTION_ACTION_SCALE_MASK, 0) & SELECTION_ACTION_SCALE_ALL;
    }

    int volumeAction() {
        return Math.max(VOLUME_ACTION_NONE,
                Math.min(VOLUME_ACTION_LINE,
                        preferenceInt(KEY_VOLUME_ACTION, VOLUME_ACTION_NONE)));
    }

    boolean hasDrawableSelection(ClassLoader loader) {
        return drawingFeature != null && drawingFeature.hasDrawableSelection();
    }

    int findSelectionActionInsertIndex(ArrayList<?> actions) {
        try {
            Object engine = findEngine(gameLoader);
            Object panel = findField(engine.getClass(), "bP").get(engine);
            Object firstAnchor = findField(panel.getClass(), "m").get(panel);
            Object secondAnchor = findField(panel.getClass(), "n").get(panel);
            int first = actions.indexOf(firstAnchor);
            int second = actions.indexOf(secondAnchor);
            int index = first >= 0 ? first : -1;
            if (second >= 0 && (index < 0 || second < index)) {
                index = second;
            }
            return index;
        } catch (Throwable t) {
            log(5, TAG, "Selection action anchors unavailable; appending custom actions", t);
            return -1;
        }
    }

    private void showModulePage(Activity activity, ClassLoader loader) {
        SettingsPage old = modulePages.get(activity);
        if (old != null) {
            old.bringToFront();
            return;
        }
        SettingsPage page = new SettingsPage(
                activity,
                () -> removeModulePage(activity),
                () -> refreshPlayerInfoPanel(loader),
                scriptManager);
        modulePages.put(activity, page);
        activity.addContentView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.bringToFront();
    }

    boolean isNoFogEnabled(Object gameObject) {
        try {
            Context context;
            if (gameObject instanceof Context) {
                context = (Context) gameObject;
            } else {
                Field contextField = findField(gameObject.getClass(), "am");
                context = (Context) contextField.get(gameObject);
            }
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NO_FOG, false);
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean viewAllEnabled(ClassLoader loader) {
        Boolean cached = viewAllState;
        if (cached != null) return cached;
        try {
            Context context = preferenceContext();
            if (context == null) return false;
            boolean enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VIEW_ALL, false);
            viewAllState = enabled;
            return enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean factoryOptimizationEnabled(ClassLoader loader) {
        Boolean cached = factoryOptState;
        if (cached != null) return cached;
        try {
            Context context = preferenceContext();
            if (context == null) return false;
            boolean enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_FACTORY_OPT, false);
            factoryOptState = enabled;
            return enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String preferenceString(String key, String defaultValue) {
        try {
            Context context = preferenceContext();
            if (context == null) return defaultValue;
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(key, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public boolean preferenceBoolean(String key, boolean defaultValue) {
        try {
            Context context = preferenceContext();
            if (context == null) return defaultValue;
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(key, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public Object findEngine(ClassLoader loader) throws Throwable {
        Class<?> engineClass = loader.loadClass(target("gameFramework.k"));
        Method getEngine = findCompatibleMethod(engineClass, "t");
        if (getEngine == null) throw new NoSuchMethodException(engineClass.getName() + ".t()");
        return getEngine.invoke(null);
    }

    public static int defaultFormationButtonCount() {
        RWmiaoModule current = instance;
        if (current == null || current.formationButtonsFeature == null) return 3;
        try {
            return current.formationButtonsFeature.nativeDefaultCount();
        } catch (Throwable ignored) {
            return 3;
        }
    }

    public Context preferenceContext() {
        Context context = targetContext;
        if (context != null) return context;
        if (captureCurrentApplicationContext()) return targetContext;
        try {
            Object engine = findEngine(gameLoader);
            if (engine == null) return null;
            context = (Context) findField(engine.getClass(), "am").get(engine);
            if (context != null) setTargetContext(context);
            return targetContext;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Field findField(Class<?> type, String name) throws NoSuchFieldException {
        FieldKey key = new FieldKey(type, name);
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;
        if (missingFields.contains(key)) throw new NoSuchFieldException(name);
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                fieldCache.putIfAbsent(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
            CompatibilityResolver resolver = compatibilityResolver;
            Field mapped = resolver == null ? null : resolver.field(current, name);
            if (mapped != null) {
                fieldCache.putIfAbsent(key, mapped);
                return mapped;
            }
            current = current.getSuperclass();
        }
        missingFields.add(key);
        throw new NoSuchFieldException(name);
    }

    public Paint makePaint(int color, float width) {
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        return paint;
    }

    public int relation(Object playerTeam, Object unitTeam) {
        if (unitTeam == playerTeam) {
            return 0;
        }
        if (playerTeam != null && unitTeam != null) {
            try {
                Method enemy = findCompatibleMethod(playerTeam.getClass(), "b", unitTeam.getClass());
                if (enemy != null && Boolean.TRUE.equals(enemy.invoke(playerTeam, unitTeam))) {
                    return 1;
                }
            } catch (Throwable ignored) {
            }
        }
        return 2;
    }

    public boolean selected(int relation, int filter) {
        return filter == 2 || (filter == 0 && relation == 0) || (filter == 1 && relation == 1);
    }

    public boolean typeSelected(Object unit, int mask) {
        return typeSelected(unit, mask, findNoArgMethod(unit.getClass(), "g"));
    }

    int preferenceInt(String key, int defaultValue) {
        try {
            Context context = preferenceContext();
            if (context == null) return defaultValue;
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(key, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    boolean preferenceContains(String key) {
        try {
            Context context = preferenceContext();
            return context != null && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .contains(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void installApplicationBootstrap() {
        if (captureCurrentApplicationContext()) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            applicationAttachHook = hookExecutable(attach, chain -> {
                Object argument = chain.getArg(0);
                if (argument instanceof Context) {
                    ModuleActivationReporter.report((Context) argument, loadedPackageName);
                }
                Object result = chain.proceed();
                if (argument instanceof Context) {
                    setTargetContext((Context) argument);
                    refreshFeatureHooks();
                    startModuleStartupUpdate();
                }
                XposedInterface.HookHandle handle = applicationAttachHook;
                applicationAttachHook = null;
                if (handle != null) handle.unhook();
                return result;
            });
        } catch (Throwable t) {
            log(6, TAG, "Failed to install target-context bootstrap hook", t);
        }
    }

    private boolean captureCurrentApplicationContext() {
        if (targetContext != null) return true;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                setTargetContext((Context) application);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void setTargetContext(Context context) {
        Context application = context == null ? null : context.getApplicationContext();
        targetContext = application != null ? application : context;
        ModuleActivationReporter.report(targetContext, loadedPackageName);
    }

    private void startModuleStartupUpdate() {
        Context context = targetContext;
        if (context == null) return;
        try {
            com.shizuku.rwmiao.ui.main.ModuleStartupUpdate.start(context);
        } catch (Throwable t) {
            log(5, TAG, "Automatic update check failed to start", t);
        }
    }

    private void startActivationHeartbeat() {
        if (activationHeartbeat != null) return;
        synchronized (this) {
            if (activationHeartbeat != null) return;
            java.util.concurrent.ScheduledExecutorService scheduler =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "RWmiao-activation-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });
            activationHeartbeat = scheduler;
            scheduler.scheduleAtFixedRate(() -> {
                Context context = targetContext;
                if (context != null) {
                    ModuleActivationReporter.report(context, loadedPackageName);
                }
            }, 0L, 20L, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void refreshFeatureHooks() {
        viewAllState = null;
        factoryOptState = null;
        refreshFeature("no-fog", noFogFeature, NoFog::refreshSettings);
        refreshFeature("view-all", viewAllFeature, ViewAll::refreshSettings);
        refreshFeature("player info panel", playerInfoPanelFeature,
                PlayerInfoPanel::refreshSettings);
        refreshFeature("factory optimization", factoryOptimizationFeature,
                FactoryOptimization::refreshSettings);
        refreshFeature("factory exit-through", factoryExitThroughFeature,
                FactoryExitThrough::refreshSettings);
        refreshFeature("mother-unit rally", motherRallyFeature, MotherRally::refreshSettings);
        refreshFeature("automatic reinforcement", reinforceFeature,
                AutoReinforce::refreshSettings);
        refreshFeature("segmented commands", segmentCommands, SegmentCommands::refreshSettings);
        refreshFeature("smart pathing", smartPathing, SmartPathing::refreshSettings);
        refreshFeature("drawing overlay", drawingFeature, Drawing::refreshSettings);
        refreshFeature("free selection", freeSelectionFeature, FreeSelection::refreshSettings);
        refreshFeature("free build", freeBuildFeature, FreeBuild::refreshSettings);
        refreshFeature("combat view", combatViewFeature, CombatView::refreshSettings);
        refreshFeature("host mute panel", hostMutePanelFeature, HostMutePanel::refreshSettings);
        refreshFeature("enemy-team messages and map pings", peekFeature, Peek::refreshSettings);
        refreshFeature("Lua automation", scriptManager, ScriptManager::refreshSettings);
        refreshFeature("host room options", hostRoomOptionsFeature, RoomOptions::refreshSettings);
        refreshFeature("game limits", gameLimitsFeature, GameLimits::refreshSettings);
        refreshFeature("batch placement", batchPlacementFeature, BatchPlacement::refreshSettings);
        refreshFeature("selection actions", selectionActionsFeature,
                SelectionActions::refreshSettings);
        refreshFeature("selected-unit panel", selectedUnitPanelFeature,
                SelectedUnitPanel::refreshSettings);
        refreshFeature("formation buttons", formationButtonsFeature,
                FormationButtons::refreshSettings);
        refreshFeature("multiplayer lobby", multiplayerLobby, MultiplayerLobby::refreshSettings);
        refreshFeature("network information", networkInfoFeature, NetworkInfo::refreshSettings);
        refreshFeature("proxy service", proxyServiceFeature, ProxyService::refreshSettings);
    }

    private <T> void refreshFeature(String name, T feature, ThrowingConsumer<T> refresher) {
        if (feature == null) return;
        try {
            refresher.accept(feature);
        } catch (Throwable t) {
            log(5, TAG, "Failed to refresh " + name, t);
        }
    }

    public void refreshScriptSelectionAction() {
        refreshFeature("script selection action", selectionActionsFeature,
                SelectionActions::refreshSettings);
    }

    boolean typeSelected(Object unit, int mask, Method movementType) {
        try {
            Object value = movementType == null ? null : movementType.invoke(unit);
            int ordinal = value instanceof Enum ? ((Enum<?>) value).ordinal() : -1;
            int type;
            switch (ordinal) {
                case 1: type = 2; break;
                case 2: type = 1; break;
                case 3: type = 8; break;
                case 4: type = 4; break;
                case 5: type = 16; break;
                case 6: type = 2; break;
                case 7: type = 4; break;
                default: type = 1; break;
            }
            return (mask & type) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int prefInt(Context context, String key, int defaultValue) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(key, defaultValue);
    }

    public Object findFieldValue(Object object, String name) throws Throwable {
        return object == null ? null : findField(object.getClass(), name).get(object);
    }

    boolean boolField(Object object, String name) {
        try {
            return findField(object.getClass(), name).getBoolean(object);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public float number(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
    }

    public Method findNoArgMethod(Class<?> type, String name) {
        if (type == null) return null;
        return findCompatibleMethod(type, name);
    }

    Object enumValue(Class<?> enumType, String name) {
        if (enumType == null || !enumType.isEnum()) return null;
        for (Object value : enumType.getEnumConstants()) {
            if (value instanceof Enum && name.equals(((Enum<?>) value).name())) {
                return value;
            }
        }
        return null;
    }

    public Method findCompatibleMethod(Class<?> type, String name, Class<?>... parameters) {
        MethodKey key = new MethodKey(type, name, parameters);
        Method cached = methodCache.get(key);
        if (cached != null) return cached;
        if (missingMethods.contains(key)) return null;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !parametersMatch(method, parameters)) {
                    continue;
                }
                method.setAccessible(true);
                methodCache.putIfAbsent(key, method);
                return method;
            }
            CompatibilityResolver resolver = compatibilityResolver;
            if (resolver != null) {
                for (Method method : resolver.methods(current, name)) {
                    if (parametersMatch(method, parameters)) {
                        methodCache.putIfAbsent(key, method);
                        return method;
                    }
                }
            }
            current = current.getSuperclass();
        }
        missingMethods.add(key);
        return null;
    }

    /**
     * Resolves a canonical method name while requiring the runtime parameter
     * classes to match exactly.  This is used for command emitters where a
     * nearby assignable overload would change game state in a different way.
     */
    public Method findExactCompatibleMethod(Class<?> type, String name,
                                            Class<?>... parameters) {
        if (type == null) return null;
        Class<?>[] wanted = parameters == null ? new Class<?>[0] : parameters;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && java.util.Arrays.equals(method.getParameterTypes(), wanted)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            CompatibilityResolver resolver = compatibilityResolver;
            if (resolver == null) continue;
            for (Method method : resolver.methods(current, name)) {
                if (java.util.Arrays.equals(method.getParameterTypes(), wanted)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean parametersMatch(Method method, Class<?>[] parameters) {
        if (method.getParameterCount() != parameters.length) return false;
        Class<?>[] actual = method.getParameterTypes();
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].isAssignableFrom(parameters[i])
                    && !parameters[i].isAssignableFrom(actual[i])) return false;
        }
        return true;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Throwable;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class FieldKey {
        final Class<?> type;
        final String name;

        FieldKey(Class<?> type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof FieldKey)) return false;
            FieldKey key = (FieldKey) other;
            return type == key.type && java.util.Objects.equals(name, key.name);
        }

        @Override public int hashCode() {
            return 31 * System.identityHashCode(type) + java.util.Objects.hashCode(name);
        }
    }

    private static final class MethodKey {
        final Class<?> type;
        final String name;
        final Class<?>[] parameters;
        final int hash;

        MethodKey(Class<?> type, String name, Class<?>[] parameters) {
            this.type = type;
            this.name = name;
            this.parameters = parameters == null ? new Class<?>[0] : parameters.clone();
            int value = 31 * System.identityHashCode(type) + java.util.Objects.hashCode(name);
            this.hash = 31 * value + java.util.Arrays.hashCode(this.parameters);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof MethodKey)) return false;
            MethodKey key = (MethodKey) other;
            return type == key.type && java.util.Objects.equals(name, key.name)
                    && java.util.Arrays.equals(parameters, key.parameters);
        }

        @Override public int hashCode() {
            return hash;
        }
    }

}
