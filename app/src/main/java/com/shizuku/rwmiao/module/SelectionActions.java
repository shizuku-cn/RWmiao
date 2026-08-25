package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.widget.PopupWindow;
import dalvik.system.InMemoryDexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;
import com.shizuku.rwmiao.module.script.ScriptManager;

import static com.shizuku.rwmiao.config.SettingsContract.*;

/** Selection menu actions and target-loader payload resolution. */
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
    private boolean selectionHookLogged;
    private int actionStateTick = Integer.MIN_VALUE;
    private boolean cachedShowRange;
    private boolean cachedShowLine;
    private boolean cachedShowPanel;
    private boolean cachedShowSegment;
    private boolean cachedShowSmartPath;
    private boolean cachedShowScripts;
    private boolean cachedShowMotherRally;
    private boolean cachedDrawable;
    private XposedInterface.HookHandle selectionHook;

    SelectionActions(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        refreshSettings();
    }

    synchronized void refreshSettings() throws Throwable {
        actionStateTick = Integer.MIN_VALUE;
        AutoReinforce reinforce = host.reinforceFeature();
        SegmentCommands segment = host.segmentCommands();
        SmartPathing smart = host.smartPathing();
        ScriptManager scripts = host.scriptManager();
        MotherRally motherRally = host.motherRallyFeature();
        boolean needed = host.selectionActionEnabled(KEY_SHOW_RANGE_ACTION)
                || host.selectionActionEnabled(KEY_SHOW_LINE_ACTION)
                || reinforce != null && reinforce.automationEnabled() && reinforce.panelEnabled()
                || segment != null && segment.isEnabled()
                || smart != null && host.selectionActionEnabled(KEY_SHOW_SMART_PATH_ACTION)
                || scripts != null && scripts.masterEnabled() && scripts.hasEnabledScripts()
                || motherRally != null && motherRally.enabled();
        if (!needed) {
            if (selectionHook != null) selectionHook.unhook();
            selectionHook = null;
        } else if (selectionHook == null) {
            hookSelectionActions(loader);
        }
    }

private void hookSelectionActions(ClassLoader loader) throws Throwable {
    Class<?> actionPanel = loader.loadClass(host.target("gameFramework.f.a"));
    Class<?> unitClass = loader.loadClass(host.target("game.units.ce"));
     Method buildActions = host.findCompatibleMethod(actionPanel, "a", unitClass, ArrayList.class);
     if (buildActions == null) throw new NoSuchMethodException("selection action builder");
     host.log(4, TAG, "Selection action builder installed: " + buildActions
             + ", prefix=" + host.targetPrefix());
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
        refreshActionState(reinforce);
        boolean showRange = cachedShowRange;
        boolean showLine = cachedShowLine;
        boolean showReinforce = reinforce != null && cachedShowPanel
                && chain.getArg(0) == null;
        boolean showSegment = segment != null && cachedShowSegment
                && (segment.hasSelectedUnitSelection()
                || segment.isUnitSelection(chain.getArg(0)));
        boolean showSmartPath = smartPath != null && cachedShowSmartPath
                && (smartPath.hasSelectedUnitSelection()
                || smartPath.isUnitSelection(chain.getArg(0)));
        boolean showScripts = scripts != null && cachedShowScripts
                && scripts.hasApplicableUnit(chain.getArg(0));
        boolean showMotherRally = motherRally != null && cachedShowMotherRally
                && motherRally.isApplicable(chain.getArg(0));
        boolean drawable = cachedDrawable;
        if ((!showRange && !showLine && !showReinforce && !showSegment && !showSmartPath
                && !showScripts && !showMotherRally)
                || (showRange || showLine) && !drawable) {
            if (!showReinforce && !showSegment && !showSmartPath && !showScripts
                    && !showMotherRally) return result;
            // The reinforcement entry belongs to the same no-selection
            // action row as Team Chat and Map Ping, including single-player.
        }
        try {
            if (!ensureActionPayload(loader)) return result;
            actionRangeTitle.set(null, RWmiaoModule.selectedRangeTitle());
            actionLineTitle.set(null, RWmiaoModule.selectedLineTitle());
            if (actionSegmentTitle != null && segment != null) {
                actionSegmentTitle.set(null, segment.titleForSelection());
            }
            if (actionSmartPathTitle != null && smartPath != null) {
                actionSmartPathTitle.set(null, smartPath.titleForSelection());
            }
            if (actionScriptsTitle != null) actionScriptsTitle.set(null, "脚本管理");
            actionMaybeAdd.invoke(null, result,
                    host.findSelectionActionInsertIndex((ArrayList<?>) result),
                    showRange && drawable, showLine && drawable, showReinforce,
                    showSegment, showSmartPath, showScripts, showMotherRally);
            if (!selectionHookLogged) {
                selectionHookLogged = true;
                host.log(4, TAG, "Selection actions appended: size=" + ((ArrayList<?>) result).size()
                        + ", range=" + (showRange && drawable)
                        + ", line=" + (showLine && drawable)
                        + ", reinforce=" + showReinforce
                        + ", segment=" + showSegment
                        + ", motherRally=" + showMotherRally);
            }
        } catch (Throwable t) {
            host.log(6, TAG, "Failed to append selection actions", t);
        }
        return result;
    });
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
    try {
        if (actionLoader != null && this.loader == loader && actionMaybeAdd != null) {
            return true;
        }
        byte[] dex = readAsset("rwmiao_actions.dex");
        ClassLoader payloadParent = new TargetAliasClassLoader(
                loader, ORIGINAL_PREFIX, host.targetPrefix());
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
        actionRangeTitle = bridge.getField("rangeTitle");
        actionLineTitle = bridge.getField("lineTitle");
        actionSegmentTitle = bridge.getField("segmentTitle");
        actionSmartPathTitle = bridge.getField("smartPathTitle");
        actionScriptsTitle = bridge.getField("scriptsTitle");
        actionMaybeAdd = bridge.getDeclaredMethod(
                "maybeAdd", ArrayList.class, int.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
        Class<?> targetAction = loader.loadClass(host.target("game.units.a.s"));
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
        if (!targetAction.isAssignableFrom(drawAction)
                || !targetAction.isAssignableFrom(lineAction)
                || !targetAction.isAssignableFrom(segmentAction)
                || !targetAction.isAssignableFrom(smartPathAction)
                || !targetAction.isAssignableFrom(scriptsAction)
                || !targetAction.isAssignableFrom(motherRallyAction)) {
            throw new LinkageError("selection payload action type mismatch for " + host.targetPrefix());
        }
        return true;
    } catch (Throwable t) {
        actionLoader = null;
        actionMaybeAdd = null;
        actionRangeTitle = null;
        actionLineTitle = null;
        actionSegmentTitle = null;
        actionSmartPathTitle = null;
        actionScriptsTitle = null;
        host.log(6, TAG, "Failed to load selection action payload", t);
        return false;
    }
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

/** Lets the prebuilt action DEX keep its original descriptors while
 * resolving the game's renamed Java namespace at runtime. */
private static final class TargetAliasClassLoader extends ClassLoader {
    private final ClassLoader targetLoader;
    private final String originalPrefix;
    private final String targetPrefix;

    TargetAliasClassLoader(ClassLoader targetLoader, String originalPrefix, String targetPrefix) {
        super(targetLoader);
        this.targetLoader = targetLoader;
        this.originalPrefix = originalPrefix;
        this.targetPrefix = targetPrefix;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        if (name.startsWith(originalPrefix + ".")
                && !originalPrefix.equals(targetPrefix)) {
            String mapped = targetPrefix + name.substring(originalPrefix.length());
            try {
                return targetLoader.loadClass(mapped);
            } catch (ClassNotFoundException ignored) {
                // Fall through so the normal loader can report the
                // original symbol if this is not a game class.
            }
        }
        return super.loadClass(name, resolve);
    }
}

}
