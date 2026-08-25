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
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;
import com.shizuku.rwmiao.module.support.GameTickDispatcher;
import com.shizuku.rwmiao.module.script.ScriptManager;
import com.shizuku.rwmiao.module.lobby.MultiplayerLobby;

import static com.shizuku.rwmiao.config.SettingsContract.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.Enumeration;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class RWmiaoModule extends XposedModule {
    private static volatile RWmiaoModule instance;
    private static final String TAG = "RWmiao";
    private static final String ORIGINAL_PREFIX = "com.corrodinggames.rts";
    private String targetPrefix = ORIGINAL_PREFIX;
    private String loadedPackageName;
    // Keep clear of native and modified-build menu IDs (including the commonly used 23).
    private static final int MODULE_MENU_ID = 0x52574D;
    private static final String MAIN_MENU_BUTTON_TAG = "com.shizuku.rwmiao.main_menu_button";

    private final WeakHashMap<Activity, SettingsPage> modulePages = new WeakHashMap<>();
    private final java.util.Set<ClassLoader> installedLoaders =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private ClassLoader gameLoader;
    /** Target-process context captured before the game engine singleton exists. */
    private volatile Context targetContext;
    private volatile XposedInterface.HookHandle applicationAttachHook;
    private final ThreadLocal<Boolean> economicRefresh = new ThreadLocal<>();
    private volatile Boolean viewAllState;
    private volatile Boolean economicPanelState;
    private volatile Boolean factoryOptState;
    private volatile Activity lastActivity;
    private AutoReinforce reinforceFeature;
    private Drawing drawingFeature;
    private FreeSelection freeSelectionFeature;
    private NoFog noFogFeature;
    private ViewAll viewAllFeature;
    private EconomicPanel economicPanelFeature;
    private FactoryOptimization factoryOptimizationFeature;
    private FactoryExitThrough factoryExitThroughFeature;
    private MotherRally motherRallyFeature;
    private SelectionActions selectionActionsFeature;
    private SelectedUnitPanel selectedUnitPanelFeature;
    private FormationButtons formationButtonsFeature;
    private MultiplayerLobby multiplayerLobby;
    private SegmentCommands segmentCommands;
    private SmartPathing smartPathing;
    private ScriptManager scriptManager;
    private GameLimits gameLimitsFeature;
    private BatchPlacement batchPlacementFeature;
    private RoomOptions hostRoomOptionsFeature;
    private Peek peekFeature;
    private GameTickDispatcher tickDispatcher;
    private GameFrameDispatcher frameDispatcher;
    /** Reflection contracts are immutable after the target class loader is ready. */
    private final java.util.concurrent.ConcurrentHashMap<FieldKey, Field> fieldCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<FieldKey> missingFields =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<MethodKey, Method> methodCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<MethodKey> missingMethods =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
                 private volatile boolean selectionHookLogged;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        ClassLoader loader = param.getDefaultClassLoader();
        loadedPackageName = param.getPackageName();
        if (!isRustedWarfareVariant(loader, loadedPackageName)) {
            return;
        }
        synchronized (installedLoaders) {
            if (!installedLoaders.add(loader)) {
                return;
            }
        }
        try {
            instance = this;
            gameLoader = loader;
            installApplicationBootstrap();
            try {
                hookMenu(loader);
            } catch (Throwable t) {
                log(6, TAG, "Failed to install in-game menu hook", t);
            }
            try {
                hookMainMenu(loader);
            } catch (Throwable t) {
                log(6, TAG, "Failed to install main-menu button hook", t);
            }
            try {
                noFogFeature = new NoFog(this, loader);
                noFogFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install noFog setup hook", t);
            }
            try {
                Peek feature = new Peek(this, loader);
                feature.install();
                peekFeature = feature;
            } catch (Throwable t) {
                log(6, TAG, "安装其它队伍消息与地图标记钩子失败", t);
            }
            try {
                viewAllFeature = new ViewAll(this, loader);
                viewAllFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install view-all hook", t);
            }
            try {
                economicPanelFeature = new EconomicPanel(this, loader);
                economicPanelFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install economic panel hook", t);
            }
            try {
                factoryOptimizationFeature = new FactoryOptimization(this, loader);
                factoryOptimizationFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install factory optimization hook", t);
            }
            try {
                factoryExitThroughFeature = new FactoryExitThrough(this, loader);
                factoryExitThroughFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install factory exit-through hook", t);
            }
            try {
                motherRallyFeature = new MotherRally(this, loader);
                motherRallyFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to resolve mother-unit rally capability", t);
            }
            try {
                reinforceFeature = new AutoReinforce(this, loader);
                reinforceFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install automatic reinforcement feature", t);
            }
            try {
                drawingFeature = new Drawing(this, loader);
                drawingFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install drawing overlay hook", t);
            }
            try {
                freeSelectionFeature = new FreeSelection(this, loader);
                freeSelectionFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install free-selection hooks", t);
            }
            try {
                segmentCommands = new SegmentCommands(this, loader);
                segmentCommands.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install segmented command hook", t);
            }
            try {
                smartPathing = new SmartPathing(this, loader, segmentCommands);
                smartPathing.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install smart pathing hook", t);
            }
            try {
                scriptManager = new ScriptManager(this, loader);
                scriptManager.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install Lua automation framework", t);
            }
            try {
                selectionActionsFeature = new SelectionActions(this, loader);
                selectionActionsFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install selection actions", t);
            }
            try {
                selectedUnitPanelFeature = new SelectedUnitPanel(this, loader);
                selectedUnitPanelFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install selected-unit panel three-column feature", t);
            }
            try {
                formationButtonsFeature = new FormationButtons(this, loader);
                formationButtonsFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install custom formation button feature", t);
            }
            try {
                multiplayerLobby = new MultiplayerLobby(this, loader);
                multiplayerLobby.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install multiplayer M3 lobby feature", t);
            }
            try {
                gameLimitsFeature = new GameLimits(this, loader);
                gameLimitsFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install game limits feature", t);
            }
            try {
                batchPlacementFeature = new BatchPlacement(this, loader);
                batchPlacementFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install batch placement feature", t);
            }
            try {
                hostRoomOptionsFeature = new RoomOptions(this, loader);
                hostRoomOptionsFeature.install();
            } catch (Throwable t) {
                log(6, TAG, "Failed to install host room options feature", t);
            }
            // Usually PackageLoaded precedes Application.attach(). Cover ports
            // whose Application was initialized earlier as well.
            if (captureCurrentApplicationContext()) refreshFeatureHooks();
            log(4, TAG, "Rusted Warfare module hooks installed for " + loadedPackageName
                    + " using class prefix " + targetPrefix);
        } catch (Throwable t) {
            synchronized (installedLoaders) {
                installedLoaders.remove(loader);
            }
            log(6, TAG, "Failed to install Rusted Warfare hooks", t);
        }
    }

    /**
     * Application IDs differ between official builds and community ports, but
     * the game's stable class contract. Probe the
     * class loader instead of hard-coding one application package name.
     */
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
                loader.loadClass(prefix + ".game.i");
                loader.loadClass(prefix + ".gameFramework.f.a");
                targetPrefix = prefix;
                return true;
            } catch (Throwable ignored) {
                // Keep probing: repackaged APKs may preserve or rename the
                // Java namespace independently of the Android application ID.
                         }
                     }
                     // Some ports change both the application id and the Java
                     // namespace. In that case the namespace cannot be derived
                     // from PackageLoadedParam; inspect the loaded dex files for
                     // the three stable Rusted Warfare entry classes instead.
                     String scanned = findGamePrefixInDex(loader);
                     if (scanned != null) {
                         targetPrefix = scanned;
                         return true;
                     }
                     return false;
                 }

                 private String findGamePrefixInDex(ClassLoader loader) {
                     try {
                         Field pathListField = findField(loader.getClass(), "pathList");
                         Object pathList = pathListField.get(loader);
                         Field elementsField = findField(pathList.getClass(), "dexElements");
                         Object elements = elementsField.get(pathList);
                         if (!(elements instanceof Object[])) return null;
                         String activityPrefix = null;
                         String enginePrefix = null;
                         String actionPrefix = null;
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
                                 if (!(value instanceof String)) continue;
                                 String name = (String) value;
                                 if (name.endsWith(".appFramework.InGameActivity")) {
                                     activityPrefix = name.substring(0,
                                             name.length() - ".appFramework.InGameActivity".length());
                                 } else if (name.endsWith(".game.i")) {
                                     enginePrefix = name.substring(0,
                                             name.length() - ".game.i".length());
                                 } else if (name.endsWith(".gameFramework.f.a")) {
                                     actionPrefix = name.substring(0,
                                             name.length() - ".gameFramework.f.a".length());
                                 }
                             }
                          }
                          if (activityPrefix != null && activityPrefix.equals(enginePrefix)
                                  && activityPrefix.equals(actionPrefix)) {
                              return activityPrefix;
                          }
                     } catch (Throwable t) {
                         log(5, TAG, "Unable to scan variant dex namespace", t);
                     }
                     return null;
                 }

    public String target(String suffix) {
        return targetPrefix + "." + suffix;
    }

    String targetPrefix() {
        return targetPrefix;
    }

    AutoReinforce reinforceFeature() {
        return reinforceFeature;
    }

    FreeSelection freeSelectionFeature() {
        return freeSelectionFeature;
    }

    MotherRally motherRallyFeature() {
        return motherRallyFeature;
    }

    SegmentCommands segmentCommands() {
        return segmentCommands;
    }

    SmartPathing smartPathing() {
        return smartPathing;
    }

    public synchronized GameTickDispatcher tickDispatcher() {
        if (tickDispatcher == null) tickDispatcher = new GameTickDispatcher(this);
        return tickDispatcher;
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
                    refreshFeatureHooks();
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
            // The game's in-battle Menu implementation throws from findItem().
            // Inspect its populated items instead, then append without clearing/rebuilding.
            if (menu != null && !containsMenuItem(menu, MODULE_MENU_ID)) {
                menu.add(0, MODULE_MENU_ID, Menu.NONE, "模块页");
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

    /**
     * Add a permanent entry point to the game's main menu.  The target menu is
     * an Android XML layout, not the in-game options Menu, so it needs its own
     * Activity hook and a real Button inserted into the existing button group.
     */
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
    }

    Activity currentActivity() {
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

    /** Select the native statistics panel data source without changing game
     * simulation or network state. */
    private void refreshEconomicPanel(ClassLoader loader) {
        try {
            refreshFeatureHooks();
            Object engine = findEngine(loader);
            Class<?> mode = loader.loadClass(target("gameFramework.g.g"));
            Method setMode = findCompatibleMethod(engine.getClass(), "a", mode, int.class);
            if (setMode != null) {
                applyEconomicPanel(engine, setMode, mode, economicPanelEnabled(loader));
            }
        } catch (Throwable t) {
            log(5, TAG, "Failed to refresh economic panel", t);
        }
    }

    private void applyEconomicPanel(Object engine, Method setMode,
                                    Class<?> mode, boolean enabled) throws Throwable {
        Object selected = enumValue(mode, enabled ? "income" : "none");
        if (selected != null) {
            setMode.invoke(engine, selected, 3);
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

    boolean hasDrawableSelection(ClassLoader loader) {
        return drawingFeature != null && drawingFeature.hasDrawableSelection();
    }

    /**
     * Custom actions are inserted immediately before the UI's built-in m/n
     * actions. Those fields are package-private, so the target-loader payload
     * must not access them directly from its separate class loader.
     */
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
                () -> refreshEconomicPanel(loader),
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

    boolean economicPanelEnabled(ClassLoader loader) {
        Boolean cached = economicPanelState;
        if (cached != null) return cached;
        try {
            Context context = preferenceContext();
            if (context == null) return false;
            boolean enabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ECONOMIC_PANEL, false);
            economicPanelState = enabled;
            return enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void applyEconomicPanelIfEnabled(ClassLoader loader, Object engine,
                                     Method setMode, Class<?> mode) throws Throwable {
        if (!economicPanelEnabled(loader) || Boolean.TRUE.equals(economicRefresh.get())) return;
        economicRefresh.set(true);
        try {
            applyEconomicPanel(engine, setMode, mode, true);
        } finally {
            economicRefresh.remove();
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

    public Object findEngine(ClassLoader loader) throws Throwable {
        Class<?> engineClass = loader.loadClass(target("gameFramework.k"));
        Method getEngine = engineClass.getDeclaredMethod("t");
        getEngine.setAccessible(true);
        return getEngine.invoke(null);
    }

    /** Called by the settings UI to display the current target build default. */
    public static int defaultFormationButtonCount() {
        RWmiaoModule current = instance;
        if (current == null || current.formationButtonsFeature == null) return 3;
        try {
            return current.formationButtonsFeature.nativeDefaultCount();
        } catch (Throwable ignored) {
            return 3;
        }
    }

    /** Preferences remain available before the engine singleton is created. */
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
                current = current.getSuperclass();
            }
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

    /**
     * Formation count uses preference presence as its explicit enable flag.
     * This avoids installing the renderer hook while the target engine is
     * still being created, while still allowing a non-default choice to
     * install before the first render frame can expose the formation panel.
     */
    boolean preferenceContains(String key) {
        try {
            Context context = preferenceContext();
            return context != null && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .contains(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolve feature switches as soon as the target Application has a Context.
     * The game engine singleton is not available during PackageLoaded, so using
     * it as the only preference source delayed enabled hooks until the first
     * InGameActivity resume (after the first match had already started).
     *
     * This one-shot bootstrap removes itself before gameplay; disabled feature
     * hooks therefore remain absent from the hot path.
     */
    private void installApplicationBootstrap() {
        if (captureCurrentApplicationContext()) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            applicationAttachHook = hookExecutable(attach, chain -> {
                Object result = chain.proceed();
                Object argument = chain.getArg(0);
                if (argument instanceof Context) {
                    setTargetContext((Context) argument);
                    refreshFeatureHooks();
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
    }

    /** Synchronize hook presence with feature switches; disabled features leave no hot hook. */
    private void refreshFeatureHooks() {
        viewAllState = null;
        economicPanelState = null;
        factoryOptState = null;
        try { if (noFogFeature != null) noFogFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "noFog hook refresh failed", t); }
        try { if (viewAllFeature != null) viewAllFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "view-all hook refresh failed", t); }
        try { if (economicPanelFeature != null) economicPanelFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "economic panel hook refresh failed", t); }
        try { if (factoryOptimizationFeature != null) factoryOptimizationFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "factory hook refresh failed", t); }
        try { if (factoryExitThroughFeature != null) factoryExitThroughFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "factory exit-through hook refresh failed", t); }
        try { if (motherRallyFeature != null) motherRallyFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "mother-unit rally refresh failed", t); }
        try { if (reinforceFeature != null) reinforceFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "reinforcement hook refresh failed", t); }
        try { if (segmentCommands != null) segmentCommands.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "segment hook refresh failed", t); }
        try { if (smartPathing != null) smartPathing.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "smart-path hook refresh failed", t); }
        try { if (drawingFeature != null) drawingFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "drawing hook refresh failed", t); }
        try { if (freeSelectionFeature != null) freeSelectionFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "free-selection hook refresh failed", t); }
        try { if (peekFeature != null) peekFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "peek hook refresh failed", t); }
        try { if (scriptManager != null) scriptManager.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "script hook refresh failed", t); }
        try { if (hostRoomOptionsFeature != null) hostRoomOptionsFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "room-options hook refresh failed", t); }
        try { if (gameLimitsFeature != null) gameLimitsFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "game-limits hook refresh failed", t); }
        try { if (batchPlacementFeature != null) batchPlacementFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "batch-placement hook refresh failed", t); }
        try { if (selectionActionsFeature != null) selectionActionsFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "selection hook refresh failed", t); }
        try { if (selectedUnitPanelFeature != null) selectedUnitPanelFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "selected-unit panel hook refresh failed", t); }
        try { if (formationButtonsFeature != null) formationButtonsFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "formation button hook refresh failed", t); }
        try { if (multiplayerLobby != null) multiplayerLobby.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "multiplayer lobby hook refresh failed", t); }
    }

    /** Script inventory/preferences changed; keep the selection action hook in sync immediately. */
    public void refreshScriptSelectionAction() {
        try { if (selectionActionsFeature != null) selectionActionsFeature.refreshSettings(); }
        catch (Throwable t) { log(5, TAG, "script selection hook refresh failed", t); }
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
                if (!method.getName().equals(name) || method.getParameterCount() != parameters.length) {
                    continue;
                }
                Class<?>[] actual = method.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!actual[i].isAssignableFrom(parameters[i])
                            && !parameters[i].isAssignableFrom(actual[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    method.setAccessible(true);
                    methodCache.putIfAbsent(key, method);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        missingMethods.add(key);
        return null;
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
