package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.view.KeyEvent;
import android.widget.PopupWindow;
import dalvik.system.InMemoryDexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;
import com.shizuku.rwmiao.module.script.ScriptManager;
import com.shizuku.rwmiao.module.freeselection.FreeSelection;
import com.shizuku.rwmiao.module.freebuild.FreeBuild;
import com.shizuku.rwmiao.module.selectall.SelectAll;

import static com.shizuku.rwmiao.config.SettingsContract.*;

final class SelectionActions {
    private static final String TAG = "RWmiao";
    private static final String ORIGINAL_PREFIX = "com.corrodinggames.rts";
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private ClassLoader actionLoader;
    private Method actionMaybeAdd;
    private Field actionRangeTitle;
    private Field actionLineTitle;
    private Field actionSegmentTitle;
    private Field actionSmartPathTitle;
    private Field actionScriptsTitle;
    private Field actionFreeSelectionTitle;
    private Field actionFreeBuildTitle;
    private Field actionSelectAllTitle;
    private Field actionCombatViewTitle;
    private Field actionMutePanelTitle;
    private Method actionMaybeAddCombatView;
    private int actionStateTick = Integer.MIN_VALUE;
    private boolean cachedShowRange;
    private boolean cachedShowLine;
    private boolean cachedShowPanel;
    private boolean cachedShowSegment;
    private boolean cachedShowSmartPath;
    private boolean cachedShowScripts;
    private boolean cachedShowMotherRally;
    private boolean cachedShowFreeSelection;
    private boolean cachedShowFreeBuild;
    private boolean cachedShowSelectAll;
    private boolean cachedShowCombatView;
    private boolean cachedShowMutePanel;
    private boolean cachedDrawable;
    private volatile int cachedActionScaleMask;
    private volatile int cachedVolumeAction;
    private int installedNativeScaleMask = Integer.MIN_VALUE;
    private volatile int installedVolumeAction = Integer.MIN_VALUE;
    private boolean volumeKeyHookUnavailable;
    private XposedInterface.HookHandle selectionHook;
    private XposedInterface.HookHandle volumeKeyHook;
    private XposedInterface.HookHandle nativeGuardScaleHook;
    private XposedInterface.HookHandle nativePatrolScaleHook;
    private XposedInterface.HookHandle nativeBaseScaleHook;
    private XposedInterface.HookHandle nativeDeselectScaleHook;
    private final ArrayList<XposedInterface.HookHandle> payloadScaleHooks = new ArrayList<>();
    private boolean payloadScaleHooksInstalled;
    private boolean payloadUnavailable;
    private boolean nativeProxyInstalled;
    private final Map<Object, NativeActionSpec> nativeActionSpecs =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private final java.util.HashMap<String, Object> nativeActions = new java.util.HashMap<>();
    private final ArrayList<XposedInterface.HookHandle> nativeProxyHooks = new ArrayList<>();
    private Object nativeMotherRally;
    private Object nativeInfoOnlyType;
    private Object nativeInfoOnlyCategory;

    SelectionActions(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    synchronized void refreshSettings() throws Throwable {
        actionStateTick = Integer.MIN_VALUE;
        cachedActionScaleMask = host.selectionActionScaleMask();
        cachedVolumeAction = host.volumeAction();
        refreshVolumeKeyHook(VOLUME_ACTION_NONE);
        refreshNativeScaleHooks();
        AutoReinforce reinforce = host.reinforceFeature();
        SegmentCommands segment = host.segmentCommands();
        SmartPathing smart = host.smartPathing();
        ScriptManager scripts = host.scriptManager();
        MotherRally motherRally = host.motherRallyFeature();
        FreeSelection freeSelection = host.freeSelectionFeature();
        FreeBuild freeBuild = host.freeBuildFeature();
        SelectAll selectAll = host.selectAllFeature();
        CombatView combatView = host.combatViewFeature();
        HostMutePanel mutePanel = host.hostMutePanelFeature();
        boolean needed = host.selectionActionEnabled(KEY_SHOW_RANGE_ACTION)
                || host.selectionActionEnabled(KEY_SHOW_LINE_ACTION)
                || reinforce != null && reinforce.automationEnabled() && reinforce.panelEnabled()
                || segment != null && segment.isEnabled()
                || smart != null && host.selectionActionEnabled(KEY_SHOW_SMART_PATH_ACTION)
                || scripts != null && scripts.masterEnabled() && scripts.hasEnabledScripts()
                || motherRally != null && motherRally.enabled()
                || freeSelection != null && freeSelection.enabled()
                || freeBuild != null && freeBuild.enabled()
                || selectAll != null && selectAll.enabled()
                || combatView != null && combatView.enabled()
                || mutePanel != null && mutePanel.enabled();
        if (!needed) {
            if (selectionHook != null) selectionHook.unhook();
            selectionHook = null;
        } else if (selectionHook == null) {
            hookSelectionActions(loader);
        }
    }

    private void refreshNativeScaleHooks() {
        int mask = cachedActionScaleMask;
        if (mask == installedNativeScaleMask) return;
        unhookNativeScaleHooks();
        installedNativeScaleMask = mask;
        if (mask == 0) return;
        try {
            if ((mask & SELECTION_ACTION_SCALE_GUARD) != 0) {
                nativeGuardScaleHook = hookNativeScaleMethod("game.units.a.f");
            }
            if ((mask & SELECTION_ACTION_SCALE_PATROL) != 0) {
                nativePatrolScaleHook = hookNativeScaleMethod("game.units.a.i");
            }
            if ((mask & (SELECTION_ACTION_SCALE_MOTHER_RALLY
                    | SELECTION_ACTION_SCALE_SCRIPTS)) != 0) {
                nativeBaseScaleHook = hookBaseScaleMethod();
            }
            if ((mask & SELECTION_ACTION_SCALE_DESELECT) != 0) {
                nativeDeselectScaleHook = hookNativeDeselectScaleMethod();
            }
        } catch (Throwable t) {
            unhookNativeScaleHooks();
            installedNativeScaleMask = Integer.MIN_VALUE;
            host.log(5, TAG, "Failed to install selected action height hooks", t);
        }
    }

    private XposedInterface.HookHandle hookNativeScaleMethod(String targetName)
            throws Throwable {
        Class<?> actionClass = loader.loadClass(host.target(targetName));
        Method height = host.findCompatibleMethod(actionClass, "l");
        if (height == null || height.getReturnType() != float.class) {
            throw new NoSuchMethodException("selected action height: " + targetName);
        }
        return host.hookExecutable(height, chain -> {
            Object result = chain.proceed();
            if ((cachedActionScaleMask != 0)
                    && result instanceof Number) {
                return ((Number) result).floatValue() * 2.0f;
            }
            return result;
        });
    }

    private XposedInterface.HookHandle hookBaseScaleMethod() throws Throwable {
        Class<?> actionBase = loader.loadClass(host.target("game.units.a.s"));
        Method height = host.findCompatibleMethod(actionBase, "l");
        if (height == null || height.getReturnType() != float.class) {
            throw new NoSuchMethodException("base selected action height");
        }
        final String nativeRallyName = host.target("game.units.a.o");
        final String payloadScriptsName =
                "com.shizuku.rwmiao.payload.RWMiaoScriptsAction";
        return host.hookExecutable(height, chain -> {
            Object result = chain.proceed();
            Object receiver = chain.getThisObject();
            if (!(result instanceof Number) || receiver == null) return result;
            String className = receiver.getClass().getName();
            boolean enlargeRally = (cachedActionScaleMask
                    & SELECTION_ACTION_SCALE_MOTHER_RALLY) != 0
                    && nativeRallyName.equals(className);
            boolean enlargeScripts = (cachedActionScaleMask
                    & SELECTION_ACTION_SCALE_SCRIPTS) != 0
                    && payloadScriptsName.equals(className);
            return enlargeRally || enlargeScripts
                    ? ((Number) result).floatValue() * 2.0f : result;
        });
    }

    private XposedInterface.HookHandle hookNativeDeselectScaleMethod()
            throws Throwable {
        Class<?> actionPanel = loader.loadClass(host.target("gameFramework.f.a"));
        Method height = host.findCompatibleMethod(actionPanel, "k");
        if (height == null || height.getReturnType() != float.class) {
            throw new NoSuchMethodException("native deselect button height");
        }
        return host.hookExecutable(height, chain -> {
            Object result = chain.proceed();
            return result instanceof Number
                    ? ((Number) result).floatValue() * 2.0f
                    : result;
        });
    }

    private void unhookNativeScaleHooks() {
        unhook(nativeGuardScaleHook);
        unhook(nativePatrolScaleHook);
        unhook(nativeBaseScaleHook);
        unhook(nativeDeselectScaleHook);
        nativeGuardScaleHook = null;
        nativePatrolScaleHook = null;
        nativeBaseScaleHook = null;
        nativeDeselectScaleHook = null;
    }

    private void unhook(XposedInterface.HookHandle handle) {
        if (handle == null) return;
        try {
            handle.unhook();
        } catch (Throwable ignored) {
        }
    }

private void hookSelectionActions(ClassLoader loader) throws Throwable {
    Class<?> actionPanel = loader.loadClass(host.target("gameFramework.f.a"));
    Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
     Method buildActions = host.findCompatibleMethod(actionPanel, "a", unitClass, ArrayList.class);
     if (buildActions == null) throw new NoSuchMethodException("selection action builder");
     selectionHook = host.hookExecutable(buildActions, chain -> {
        Object result = chain.proceed();
        if (!(result instanceof ArrayList)) {
            return result;
        }
        AutoReinforce reinforce = host.reinforceFeature();
        SegmentCommands segment = host.segmentCommands();
        SmartPathing smartPath = host.smartPathing();
        ScriptManager scripts = host.scriptManager();
         MotherRally motherRally = host.motherRallyFeature();
         FreeSelection freeSelection = host.freeSelectionFeature();
         FreeBuild freeBuild = host.freeBuildFeature();
         SelectAll selectAll = host.selectAllFeature();
         CombatView combatView = host.combatViewFeature();
         HostMutePanel mutePanel = host.hostMutePanelFeature();
         refreshActionState(reinforce);
        Object panelUnit = chain.getArg(0);
        boolean selectionPanel = panelUnit != null;
        boolean globalPanel = !selectionPanel && hasNoSelectedUnits(chain.getThisObject());
        boolean showRange = cachedShowRange && selectionPanel;
        boolean showLine = cachedShowLine && selectionPanel;
        boolean showReinforce = reinforce != null && cachedShowPanel
                && globalPanel;
        boolean showSegment = segment != null && cachedShowSegment
                && selectionPanel && segment.isUnitSelection(panelUnit);
        boolean showSmartPath = smartPath != null && cachedShowSmartPath
                && selectionPanel && smartPath.isUnitSelection(panelUnit);
        boolean showScripts = scripts != null && cachedShowScripts
                && selectionPanel && scripts.hasApplicableUnit(panelUnit);
        boolean showMotherRally = motherRally != null && cachedShowMotherRally
                && selectionPanel && motherRally.isApplicable(panelUnit);
        boolean showFreeSelection = freeSelection != null && cachedShowFreeSelection
                && globalPanel;
         boolean showFreeBuild = freeBuild != null && cachedShowFreeBuild
                 && selectionPanel && freeBuild.isApplicable(panelUnit);
         boolean showSelectAll = selectAll != null && cachedShowSelectAll
                 && globalPanel;
         boolean showCombatView = combatView != null && cachedShowCombatView
                 && globalPanel;
         boolean showMutePanel = mutePanel != null && cachedShowMutePanel
                 && globalPanel;
         boolean drawable = cachedDrawable;
         if ((!showRange && !showLine && !showReinforce && !showSegment && !showSmartPath
                 && !showScripts && !showMotherRally && !showFreeSelection && !showFreeBuild
                 && !showSelectAll && !showCombatView
                 && !showMutePanel)
                 || (showRange || showLine) && !drawable) {
             if (!showReinforce && !showSegment && !showSmartPath && !showScripts
                     && !showMotherRally && !showFreeSelection && !showFreeBuild
                     && !showSelectAll && !showCombatView
                     && !showMutePanel) {
                refreshVolumeKeyHook(VOLUME_ACTION_NONE);
                return result;
            }
        }
        try {
             int insertionIndex = host.findSelectionActionInsertIndex((ArrayList<?>) result);
            if (ensureActionPayload(loader)) {
                actionRangeTitle.set(null, RWmiaoModule.selectedRangeTitle());
                actionLineTitle.set(null, RWmiaoModule.selectedLineTitle());
                if (actionSegmentTitle != null && segment != null) {
                    actionSegmentTitle.set(null, segment.titleForSelection());
                }
                if (actionSmartPathTitle != null && smartPath != null) {
                    actionSmartPathTitle.set(null, smartPath.titleForSelection());
                }
                if (actionScriptsTitle != null) actionScriptsTitle.set(null, "脚本管理");
                if (actionFreeSelectionTitle != null && freeSelection != null) {
                    actionFreeSelectionTitle.set(null, freeSelection.titleForSelection());
                }
                if (actionFreeBuildTitle != null && freeBuild != null) {
                    actionFreeBuildTitle.set(null, freeBuild.titleForSelection());
                }
                if (actionSelectAllTitle != null) actionSelectAllTitle.set(null, "一键全选");
                if (actionCombatViewTitle != null && combatView != null) {
                    actionCombatViewTitle.set(null, combatView.titleForSelection());
                }
                if (actionMutePanelTitle != null) actionMutePanelTitle.set(null, "禁言面板");
                actionMaybeAdd.invoke(null, result, insertionIndex,
                        showRange && drawable, showLine && drawable, showReinforce,
                        showSegment, showSmartPath, showScripts, showMotherRally,
                        showFreeSelection, showFreeBuild, showSelectAll, showMutePanel);
                if (actionMaybeAddCombatView != null) {
                    actionMaybeAddCombatView.invoke(null, result, insertionIndex, showCombatView);
                }
            } else {
                appendNativeProxyActions((ArrayList<Object>) result, insertionIndex,
                        showRange && drawable, showLine && drawable, showReinforce,
                        showSegment, showSmartPath, showScripts, showMotherRally,
                        showFreeSelection, showFreeBuild, showSelectAll,
                        showCombatView, showMutePanel);
            }
            refreshVolumeKeyHook(volumeActionForVisibleButton(
                    showRange && drawable, showLine && drawable, showSegment,
                    showSmartPath, showFreeBuild));
        } catch (Throwable t) {
            refreshVolumeKeyHook(VOLUME_ACTION_NONE);
            host.log(6, TAG, "Failed to append selection actions", t);
        }
        return result;
    });
}

    /**
     * The builder is also called with a null unit while a selection panel is
     * active.  That invocation prepares the global/right-side action list and
     * must not receive module buttons until the native selection is empty.
     * Reading the controller's canonical selection count is independent of a
     * package name and is mapped by the compatibility resolver when renamed.
     */
    private boolean hasNoSelectedUnits(Object actionPanel) {
        try {
            Object input = host.findFieldValue(actionPanel, "a");
            Object count = input == null ? null : host.findFieldValue(input, "aX");
            if (count instanceof Number) return ((Number) count).intValue() == 0;
        } catch (Throwable ignored) {
        }
        return firstSelectedUnit() == null;
    }

    private int volumeActionForVisibleButton(boolean range, boolean line,
                                             boolean segment, boolean smartPath,
                                             boolean freeBuild) {
        switch (cachedVolumeAction) {
            case VOLUME_ACTION_SEGMENT:
                return segment ? VOLUME_ACTION_SEGMENT : VOLUME_ACTION_NONE;
            case VOLUME_ACTION_SMART_PATH:
                return smartPath ? VOLUME_ACTION_SMART_PATH : VOLUME_ACTION_NONE;
            case VOLUME_ACTION_FREE_BUILD:
                return freeBuild ? VOLUME_ACTION_FREE_BUILD : VOLUME_ACTION_NONE;
            case VOLUME_ACTION_RANGE:
                return range ? VOLUME_ACTION_RANGE : VOLUME_ACTION_NONE;
            case VOLUME_ACTION_LINE:
                return line ? VOLUME_ACTION_LINE : VOLUME_ACTION_NONE;
            default:
                return VOLUME_ACTION_NONE;
        }
    }

    private synchronized void refreshVolumeKeyHook(int action) {
        if (action == installedVolumeAction
                && (action == VOLUME_ACTION_NONE || volumeKeyHook != null
                || volumeKeyHookUnavailable)) return;
        unhookVolumeKeyHook();
        installedVolumeAction = action;
        volumeKeyHookUnavailable = false;
        if (action == VOLUME_ACTION_NONE) return;
        try {
            Class<?> activityClass = loader.loadClass(host.target("appFramework.InGameActivity"));
            Method onKeyDown = host.findCompatibleMethod(
                    activityClass, "onKeyDown", int.class, KeyEvent.class);
            if (onKeyDown == null) throw new NoSuchMethodException("volume key entrypoint");
            volumeKeyHook = host.hookExecutable(onKeyDown, chain -> {
                Object keyValue = chain.getArg(0);
                if (!(keyValue instanceof Integer)) return chain.proceed();
                int keyCode = (Integer) keyValue;
                if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                        && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return chain.proceed();
                }
                KeyEvent event = chain.getArg(1) instanceof KeyEvent
                        ? (KeyEvent) chain.getArg(1) : null;
                if (event != null && event.getRepeatCount() > 0) {
                    return isVisibleVolumeAction(installedVolumeAction) ? true : chain.proceed();
                }
                if (handleVolumeKey(keyCode)) return true;
                return chain.proceed();
            });
        } catch (Throwable t) {
            volumeKeyHookUnavailable = true;
            host.log(5, TAG, "Volume key hook unavailable", t);
        }
    }

    private void unhookVolumeKeyHook() {
        if (volumeKeyHook == null) return;
        try {
            volumeKeyHook.unhook();
        } catch (Throwable ignored) {
        }
        volumeKeyHook = null;
    }

    private boolean handleVolumeKey(int keyCode) {
        int action = installedVolumeAction;
        if (action == VOLUME_ACTION_NONE || !isVisibleVolumeAction(action)) return false;
        boolean enable = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        try {
            switch (action) {
                case VOLUME_ACTION_SEGMENT: {
                    SegmentCommands segment = host.segmentCommands();
                    if (segment == null) return false;
                    segment.setSelected(enable);
                    return true;
                }
                case VOLUME_ACTION_SMART_PATH: {
                    SmartPathing smartPath = host.smartPathing();
                    if (smartPath == null) return false;
                    smartPath.setSelected(enable);
                    return true;
                }
                case VOLUME_ACTION_FREE_BUILD: {
                    FreeBuild freeBuild = host.freeBuildFeature();
                    Object unit = firstSelectedUnit();
                    if (freeBuild == null || unit == null || !freeBuild.isApplicable(unit)) {
                        return false;
                    }
                    freeBuild.setMode(unit, enable);
                    return true;
                }
                case VOLUME_ACTION_RANGE:
                    RWmiaoModule.setSelectedRange(enable);
                    return true;
                case VOLUME_ACTION_LINE:
                    RWmiaoModule.setSelectedLine(enable);
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            host.log(5, TAG, "Volume key action failed", t);
            return false;
        }
    }

    private boolean isVisibleVolumeAction(int action) {
        switch (action) {
            case VOLUME_ACTION_SEGMENT: {
                SegmentCommands segment = host.segmentCommands();
                return segment != null && segment.isEnabled()
                        && segment.hasSelectedUnitSelection();
            }
            case VOLUME_ACTION_SMART_PATH: {
                SmartPathing smartPath = host.smartPathing();
                return smartPath != null
                        && host.selectionActionEnabled(KEY_SHOW_SMART_PATH_ACTION)
                        && smartPath.hasSelectedUnitSelection();
            }
            case VOLUME_ACTION_FREE_BUILD: {
                FreeBuild freeBuild = host.freeBuildFeature();
                Object unit = firstSelectedUnit();
                return freeBuild != null && freeBuild.enabled()
                        && unit != null && freeBuild.isApplicable(unit);
            }
            case VOLUME_ACTION_RANGE:
                return host.selectionActionEnabled(KEY_SHOW_RANGE_ACTION)
                        && host.hasDrawableSelection(loader);
            case VOLUME_ACTION_LINE:
                return host.selectionActionEnabled(KEY_SHOW_LINE_ACTION)
                        && host.hasDrawableSelection(loader);
            default:
                return false;
        }
    }

    private Object firstSelectedUnit() {
        try {
            Object engine = host.findEngine(loader);
            Object panel = host.findField(engine.getClass(), "bP").get(engine);
            Object selected = host.findField(panel.getClass(), "bZ").get(panel);
            if (selected instanceof Iterable) {
                for (Object unit : (Iterable<?>) selected) if (unit != null) return unit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

private void refreshActionState(AutoReinforce reinforce) {
    int tick = currentTick();
    if (tick >= 0 && tick == actionStateTick) return;
    cachedShowRange = host.selectionActionEnabled(KEY_SHOW_RANGE_ACTION);
    cachedShowLine = host.selectionActionEnabled(KEY_SHOW_LINE_ACTION);
    cachedShowPanel = reinforce != null && reinforce.panelEnabled();
    SegmentCommands segment = host.segmentCommands();
    cachedShowSegment = segment != null && segment.isEnabled();
    cachedShowSmartPath = host.selectionActionEnabled(KEY_SHOW_SMART_PATH_ACTION);
    ScriptManager scripts = host.scriptManager();
    cachedShowScripts = scripts != null && scripts.masterEnabled() && scripts.hasEnabledScripts();
    MotherRally motherRally = host.motherRallyFeature();
    cachedShowMotherRally = motherRally != null && motherRally.enabled();
    FreeSelection freeSelection = host.freeSelectionFeature();
    cachedShowFreeSelection = freeSelection != null && freeSelection.enabled();
    FreeBuild freeBuild = host.freeBuildFeature();
    cachedShowFreeBuild = freeBuild != null && freeBuild.enabled();
    SelectAll selectAll = host.selectAllFeature();
    cachedShowSelectAll = selectAll != null && selectAll.enabled();
    CombatView combatView = host.combatViewFeature();
    cachedShowCombatView = combatView != null && combatView.enabled();
    HostMutePanel mutePanel = host.hostMutePanelFeature();
    cachedShowMutePanel = mutePanel != null && mutePanel.enabled()
            && mutePanel.availableForAction();
    cachedDrawable = (cachedShowRange || cachedShowLine) && host.hasDrawableSelection(loader);
    actionStateTick = tick;
}

private int currentTick() {
    try {
        Object engine = host.findEngine(loader);
        return ((Number) host.findField(engine.getClass(), "bu").get(engine)).intValue();
    } catch (Throwable ignored) {
        return -1;
    }
}

private synchronized boolean ensureActionPayload(ClassLoader loader) {
    if (payloadUnavailable) return false;
    try {
        if (actionLoader != null && actionMaybeAdd != null) {
            return true;
        }
        Class<?> targetAction = loader.loadClass(host.target("game.units.a.s"));
        Method dialectProbe = host.findCompatibleMethod(targetAction, "q");
        if (dialectProbe == null || !"q".equals(dialectProbe.getName())) {
            // The precompiled payload overrides original short method names.
            // A source-remapped host needs identity-hooked native actions so
            // titles, clicks, visibility and height all use its actual names.
            payloadUnavailable = true;
            return false;
        }
        byte[] dex = readAsset("rwmiao_actions.dex");
        ClassLoader payloadParent = new TargetAliasClassLoader(
                loader, ORIGINAL_PREFIX, host);
        actionLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), payloadParent);
        Class<?> bridge = actionLoader.loadClass("com.shizuku.rwmiao.payload.RWMiaoBridge");
        bridge.getField("rangeToggle").set(null, (Runnable) RWmiaoModule::toggleSelectedRange);
        bridge.getField("lineToggle").set(null, (Runnable) RWmiaoModule::toggleSelectedLine);
        bridge.getField("reinforceOpen").set(null, (Runnable) () -> {
            AutoReinforce reinforce = host.reinforceFeature();
            if (reinforce != null) reinforce.openPanel();
        });
        bridge.getField("segmentToggle").set(null, (Runnable) () -> {
            SegmentCommands segment = host.segmentCommands();
            if (segment != null) segment.toggleSelected();
        });
        bridge.getField("smartPathToggle").set(null, (Runnable) () -> {
            SmartPathing smartPath = host.smartPathing();
            if (smartPath != null) smartPath.toggleSelected();
        });
        bridge.getField("scriptsOpen").set(null, (java.util.function.Consumer<Object>) unit -> {
            ScriptManager scripts = host.scriptManager();
            Activity activity = host.currentActivity();
            if (scripts != null && activity != null) activity.runOnUiThread(
                    () -> scripts.openUnitPanel(activity, unit));
        });
        bridge.getField("freeSelectionToggle").set(null, (Runnable) RWmiaoModule::toggleFreeSelection);
        bridge.getField("freeBuildToggle").set(null,
                (java.util.function.Consumer<Object>) RWmiaoModule::toggleFreeBuild);
        bridge.getField("selectAllToggle").set(null, (Runnable) RWmiaoModule::selectAllUnits);
         bridge.getField("combatViewOpen").set(null, (Runnable) () -> {
             RWmiaoModule.jumpToNextCombat();
             refreshCombatViewTitle();
         });
        bridge.getField("mutePanelOpen").set(null, (Runnable) () -> {
            HostMutePanel mutePanel = host.hostMutePanelFeature();
            if (mutePanel != null) mutePanel.openPanel();
        });
        actionRangeTitle = bridge.getField("rangeTitle");
        actionLineTitle = bridge.getField("lineTitle");
        actionSegmentTitle = bridge.getField("segmentTitle");
        actionSmartPathTitle = bridge.getField("smartPathTitle");
        actionScriptsTitle = bridge.getField("scriptsTitle");
        actionFreeSelectionTitle = bridge.getField("freeSelectionTitle");
        actionFreeBuildTitle = bridge.getField("freeBuildTitle");
        actionSelectAllTitle = bridge.getField("selectAllTitle");
        actionCombatViewTitle = bridge.getField("combatViewTitle");
        actionMutePanelTitle = bridge.getField("mutePanelTitle");
        actionMaybeAdd = bridge.getDeclaredMethod(
                "maybeAdd", ArrayList.class, int.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        actionMaybeAddCombatView = bridge.getDeclaredMethod(
                "maybeAddCombatView", ArrayList.class, int.class, boolean.class);
        Class<?> drawAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoDrawAction");
        Class<?> lineAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoLineAction");
        Class<?> segmentAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoSegmentAction");
        Class<?> smartPathAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoSmartPathAction");
        Class<?> scriptsAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoScriptsAction");
        Class<?> motherRallyAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoMotherRallyAction");
        Class<?> freeSelectionAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoFreeSelectionAction");
        Class<?> freeBuildAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoFreeBuildAction");
        Class<?> selectAllAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoSelectAllAction");
        Class<?> combatViewAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoCombatViewAction");
        Class<?> mutePanelAction = actionLoader.loadClass(
                "com.shizuku.rwmiao.payload.RWMiaoMutePanelAction");
        if (!targetAction.isAssignableFrom(drawAction)
                || !targetAction.isAssignableFrom(lineAction)
                || !targetAction.isAssignableFrom(segmentAction)
                || !targetAction.isAssignableFrom(smartPathAction)
                || !targetAction.isAssignableFrom(scriptsAction)
                || !targetAction.isAssignableFrom(motherRallyAction)
                || !targetAction.isAssignableFrom(freeSelectionAction)
                || !targetAction.isAssignableFrom(freeBuildAction)
                || !targetAction.isAssignableFrom(selectAllAction)
                || !targetAction.isAssignableFrom(combatViewAction)
                || !targetAction.isAssignableFrom(mutePanelAction)) {
            throw new LinkageError(
                    "selection payload action type mismatch for " + targetAction.getName());
        }
        ensurePayloadScaleHooks(drawAction, SELECTION_ACTION_SCALE_RANGE);
        ensurePayloadScaleHooks(lineAction, SELECTION_ACTION_SCALE_LINE);
        ensurePayloadScaleHooks(segmentAction, SELECTION_ACTION_SCALE_SEGMENT);
        ensurePayloadScaleHooks(smartPathAction, SELECTION_ACTION_SCALE_SMART_PATH);
        ensurePayloadScaleHooks(motherRallyAction, SELECTION_ACTION_SCALE_MOTHER_RALLY);
        ensurePayloadScaleHooks(freeBuildAction, SELECTION_ACTION_SCALE_FREE_BUILD);
        payloadScaleHooksInstalled = true;
        return true;
    } catch (Throwable t) {
        unhookPayloadScaleHooks();
        actionLoader = null;
        actionMaybeAdd = null;
        actionRangeTitle = null;
        actionLineTitle = null;
        actionSegmentTitle = null;
        actionSmartPathTitle = null;
        actionScriptsTitle = null;
        actionFreeSelectionTitle = null;
        actionFreeBuildTitle = null;
        actionSelectAllTitle = null;
        actionCombatViewTitle = null;
        actionMutePanelTitle = null;
        actionMaybeAddCombatView = null;
        payloadUnavailable = true;
        host.log(6, TAG, "Failed to load selection action payload", t);
        return false;
    }
}

    private synchronized void ensureNativeProxy() throws Throwable {
        if (nativeProxyInstalled) return;

        int hookStart = nativeProxyHooks.size();
        try {
            Class<?> proxyClass = loader.loadClass(host.target("game.units.a.o"));
            Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
            Class<?> proxyBase = loader.loadClass(host.target("game.units.a.s"));
            Method description = host.findCompatibleMethod(proxyClass, "a");
            Method title = host.findCompatibleMethod(proxyClass, "b");
            Method execute = host.findCompatibleMethod(
                    proxyClass, "c", unitClass, boolean.class);
            Method availability = host.findCompatibleMethod(proxyBase, "q");
            Method type = host.findCompatibleMethod(proxyClass, "d");
            Method category = host.findCompatibleMethod(proxyClass, "e");
            Method scale = host.findCompatibleMethod(proxyBase, "l");
            if (description == null || title == null || execute == null
                    || availability == null || type == null || category == null
                    || scale == null) {
                throw new NoSuchMethodException("native selection-action proxy contract");
            }

            nativeInfoOnlyType = host.enumValue(
                    loader.loadClass(host.target("game.units.a.u")), "infoOnly");
            nativeInfoOnlyCategory = host.enumValue(
                    loader.loadClass(host.target("game.units.a.t")), "infoOnly");

            trackNativeProxyHook(host.hookExecutable(description, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                return spec == null ? chain.proceed() : spec.description;
            }));
            trackNativeProxyHook(host.hookExecutable(title, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                return spec == null ? chain.proceed() : spec.title.get();
            }));
            trackNativeProxyHook(host.hookExecutable(execute, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                if (spec == null) return chain.proceed();
                spec.execute.accept(chain.getArg(0));
                return true;
            }));
            trackNativeProxyHook(host.hookExecutable(availability, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                if (spec != null || chain.getThisObject() == nativeMotherRally) return true;
                return chain.proceed();
            }));
            trackNativeProxyHook(host.hookExecutable(type, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                return spec == null || nativeInfoOnlyType == null
                        ? chain.proceed() : nativeInfoOnlyType;
            }));
            trackNativeProxyHook(host.hookExecutable(category, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                return spec == null || nativeInfoOnlyCategory == null
                        ? chain.proceed() : nativeInfoOnlyCategory;
            }));
            trackNativeProxyHook(host.hookExecutable(scale, chain -> {
                NativeActionSpec spec = nativeActionSpecs.get(chain.getThisObject());
                int scaleFlag;
                if (spec != null) {
                    scaleFlag = spec.scaleFlag;
                } else if (chain.getThisObject() == nativeMotherRally) {
                    scaleFlag = SELECTION_ACTION_SCALE_MOTHER_RALLY;
                } else {
                    return chain.proceed();
                }
                return scaleFlag == 0
                        ? chain.proceed()
                        : ((cachedActionScaleMask & scaleFlag) != 0 ? 1.0f : 0.5f);
            }));

            Class<?> actionPanel = loader.loadClass(host.target("gameFramework.f.a"));
            Method actionFilter = host.findCompatibleMethod(
                    actionPanel, "b", proxyBase, ArrayList.class);
            if (actionFilter != null) {
                trackNativeProxyHook(host.hookExecutable(actionFilter, chain -> {
                    Object action = chain.getArg(0);
                    return nativeActionSpecs.containsKey(action) || action == nativeMotherRally
                            ? true : chain.proceed();
                }));
            }
            nativeProxyInstalled = true;
        } catch (Throwable failure) {
            rollbackNativeProxyHooks(hookStart);
            throw failure;
        }
    }

    private void trackNativeProxyHook(XposedInterface.HookHandle hook) {
        nativeProxyHooks.add(hook);
    }

    private void rollbackNativeProxyHooks(int hookStart) {
        while (nativeProxyHooks.size() > hookStart) {
            XposedInterface.HookHandle hook =
                    nativeProxyHooks.remove(nativeProxyHooks.size() - 1);
            unhook(hook);
        }
    }

    private Object nativeAction(String key, String description, Supplier<String> title,
                                Consumer<Object> execute, int scaleFlag) throws Throwable {
        Object existing = nativeActions.get(key);
        if (existing != null) return existing;
        ensureNativeProxy();
        Class<?> proxyClass = loader.loadClass(host.target("game.units.a.o"));
        Object action = proxyClass.getDeclaredConstructor().newInstance();
        Method setId = host.findCompatibleMethod(proxyClass, "a", String.class);
        if (setId == null) throw new NoSuchMethodException("selection action id setter");
        setId.invoke(action, "c__cut_rwmiao_" + key);
        nativeActionSpecs.put(
                action, new NativeActionSpec(description, title, execute, scaleFlag));
        nativeActions.put(key, action);
        return action;
    }

    private void appendNativeProxyActions(ArrayList<Object> actions, int insertionIndex,
                                          boolean range, boolean line, boolean reinforce,
                                          boolean segment, boolean smartPath, boolean scripts,
                                          boolean motherRally, boolean freeSelection,
                                          boolean freeBuild, boolean selectAll,
                                          boolean combatView, boolean mutePanel) throws Throwable {
        int index = insertionIndex < 0 || insertionIndex > actions.size()
                ? actions.size() : insertionIndex;
        if (range) addIdentity(actions, index, nativeAction("draw",
                "Toggle attack range drawing", RWmiaoModule::selectedRangeTitle,
                unused -> RWmiaoModule.toggleSelectedRange(), SELECTION_ACTION_SCALE_RANGE));
        if (line) addIdentity(actions, index, nativeAction("line",
                "Toggle target indicator line", RWmiaoModule::selectedLineTitle,
                unused -> RWmiaoModule.toggleSelectedLine(), SELECTION_ACTION_SCALE_LINE));
        if (reinforce) addIdentity(actions, index, nativeAction("reinforce",
                "Automatic reinforcement list", () -> "自动补兵", unused -> {
                    AutoReinforce feature = host.reinforceFeature();
                    if (feature != null) feature.openPanel();
                }, 0));
        if (segment) addIdentity(actions, index, nativeAction("segment",
                "Toggle segmented commands", () -> {
                    SegmentCommands feature = host.segmentCommands();
                    return feature == null ? "分段指令" : feature.titleForSelection();
                }, unused -> {
                    SegmentCommands feature = host.segmentCommands();
                    if (feature != null) feature.toggleSelected();
                }, SELECTION_ACTION_SCALE_SEGMENT));
        if (smartPath) addIdentity(actions, index, nativeAction("smart_path",
                "Toggle smart pathing", () -> {
                    SmartPathing feature = host.smartPathing();
                    return feature == null ? "智寻" : feature.titleForSelection();
                }, unused -> {
                    SmartPathing feature = host.smartPathing();
                    if (feature != null) feature.toggleSelected();
                }, SELECTION_ACTION_SCALE_SMART_PATH));
        if (scripts) addIdentity(actions, index, nativeAction("scripts",
                "Choose automation scripts for selected unit types", () -> "脚本管理", unit -> {
                    ScriptManager feature = host.scriptManager();
                    Activity activity = host.currentActivity();
                    if (feature != null && activity != null) {
                        activity.runOnUiThread(() -> feature.openUnitPanel(activity, unit));
                    }
                }, SELECTION_ACTION_SCALE_SCRIPTS));
        if (motherRally) addNativeMotherRally(actions, index);
        if (freeSelection) addIdentity(actions, index, nativeAction("free_selection",
                "Toggle unrestricted drag selection", () -> {
                    FreeSelection feature = host.freeSelectionFeature();
                    return feature == null ? "自由框选" : feature.titleForSelection();
                }, unused -> RWmiaoModule.toggleFreeSelection(), 0));
        if (freeBuild) addIdentity(actions, index, nativeAction("free_build",
                "Toggle free build placement", () -> {
                    FreeBuild feature = host.freeBuildFeature();
                    return feature == null ? "自由建造" : feature.titleForSelection();
                }, RWmiaoModule::toggleFreeBuild, SELECTION_ACTION_SCALE_FREE_BUILD));
        if (selectAll) addIdentity(actions, index, nativeAction("select_all",
                "Select all controllable units", () -> "一键全选",
                unused -> RWmiaoModule.selectAllUnits(), 0));
        if (mutePanel) addIdentity(actions, index, nativeAction("mute_panel",
                "Open host mute panel", () -> "禁言面板", unused -> {
                    HostMutePanel feature = host.hostMutePanelFeature();
                    if (feature != null) feature.openPanel();
                }, 0));
        if (combatView) addIdentity(actions, index, nativeAction("combat_view",
                "Jump to the next combat", () -> {
                    CombatView feature = host.combatViewFeature();
                    return feature == null ? "交战视角" : feature.titleForSelection();
                }, unused -> RWmiaoModule.jumpToNextCombat(), 0));
    }

    private void addNativeMotherRally(ArrayList<Object> actions, int index) throws Throwable {
        ensureNativeProxy();
        Class<?> rallyClass = loader.loadClass(host.target("game.units.a.o"));
        for (Object action : actions) {
            if (rallyClass.isInstance(action) && !nativeActionSpecs.containsKey(action)) {
                return;
            }
        }
        if (nativeMotherRally == null) {
            nativeMotherRally = rallyClass.getDeclaredConstructor().newInstance();
        }
        addIdentity(actions, index, nativeMotherRally);
    }

    private static void addIdentity(ArrayList<Object> actions, int index, Object wanted) {
        for (Object action : actions) {
            if (action == wanted) return;
        }
        actions.add(Math.min(index, actions.size()), wanted);
    }

    private static final class NativeActionSpec {
        final String description;
        final Supplier<String> title;
        final Consumer<Object> execute;
        final int scaleFlag;

        NativeActionSpec(String description, Supplier<String> title,
                         Consumer<Object> execute, int scaleFlag) {
            this.description = description;
            this.title = title;
            this.execute = execute;
            this.scaleFlag = scaleFlag;
        }
    }

    private void refreshCombatViewTitle() {
        try {
            CombatView combatView = host.combatViewFeature();
            if (combatView != null && actionCombatViewTitle != null) {
                actionCombatViewTitle.set(null, combatView.titleForSelection());
            }
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to refresh combat-view title", t);
        }
    }

    private void ensurePayloadScaleHooks(Class<?> actionClass, int flag) {
        if (payloadScaleHooksInstalled) return;
        try {
            Method height = host.findCompatibleMethod(actionClass, "l");
            if (height == null || height.getReturnType() != float.class) return;
            payloadScaleHooks.add(host.hookExecutable(height, chain -> {
                Object result = chain.proceed();
                return (cachedActionScaleMask & flag) != 0 && result instanceof Number
                        ? ((Number) result).floatValue() * 2.0f : result;
            }));
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to install payload action height hook", t);
        }
    }

    private void unhookPayloadScaleHooks() {
        for (XposedInterface.HookHandle hook : payloadScaleHooks) {
            unhook(hook);
        }
        payloadScaleHooks.clear();
        payloadScaleHooksInstalled = false;
    }

    private byte[] readAsset(String name) throws Throwable {
        android.content.pm.ApplicationInfo info = host.getModuleApplicationInfo();
        try (ZipFile apk = new ZipFile(info.sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/" + name);
            if (entry == null) throw new java.io.FileNotFoundException(name);
            try (InputStream input = apk.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }
    }

    private static final class TargetAliasClassLoader extends ClassLoader {
        private final ClassLoader targetLoader;
        private final String originalPrefix;
        private final RWmiaoModule host;

        TargetAliasClassLoader(ClassLoader targetLoader, String originalPrefix,
                               RWmiaoModule host) {
            super(targetLoader);
            this.targetLoader = targetLoader;
            this.originalPrefix = originalPrefix;
            this.host = host;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith(originalPrefix + ".")) {
                String mapped = host.target(name.substring(originalPrefix.length() + 1));
                if (!mapped.equals(name)) {
                    try {
                        return targetLoader.loadClass(mapped);
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            return super.loadClass(name, resolve);
        }
    }

}
