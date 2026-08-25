package com.shizuku.rwmiao.module;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import com.shizuku.rwmiao.module.support.GameTickDispatcher;
import com.shizuku.rwmiao.ui.support.RuntimePanels;

import static com.shizuku.rwmiao.config.SettingsContract.*;

/**
 * 自动补兵主协调器：负责动作捕获、序列状态、面板和原生同步指令。
 * Main reinforcement coordinator: captures actions, owns queue state/UI, and sends native synchronized orders.
 */
final class AutoReinforce {
    private static final String TAG = "RWmiao";
    private static final String BLOCKED_POPUP_KEY = "__rwmiao_blocked__";

    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Map<String, ReinforceEntry> entries = new LinkedHashMap<>();
    private final Map<String, QueuePlan> plans = new LinkedHashMap<>();
    private final Map<String, Integer> targets = new LinkedHashMap<>();
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    private final Map<String, Integer> produced = new LinkedHashMap<>();
    private final Map<String, Bitmap> previews = new LinkedHashMap<>();
    private final Map<String, Integer> previewRetryTicks = new LinkedHashMap<>();

    private volatile Boolean enabledState;
    private volatile Boolean panelState;
    private volatile Boolean weightModeState;
    private volatile PopupWindow popup;
    private volatile String popupBuildPendingKey;
    private volatile Dialog listDialog;
    private volatile RuntimePanels.ReinforcePanelCallback listPanelCallback;
    private volatile String popupKey;
    private volatile String dismissedKey;
    private volatile Object popupAction;
    private volatile Set<Long> popupGroup;
    private volatile EditText countLabel;
    private volatile EditText weightLabel;
    private volatile boolean expanded;
    private volatile boolean updatingLabel;
    private volatile int popupSeenTick = -1;
    private volatile int lastTick;
    private volatile int lastMatchTick = -1;
    private volatile int lastPreviewTick = Integer.MIN_VALUE;
    private volatile long blockedMessageUntilMs;
    private volatile long blockedPopupUntilMs;
    private volatile int selectedCacheTick = Integer.MIN_VALUE;
    private final Set<Long> selectedFactoryIds = new HashSet<>();
    private final ArrayList<Object> selectedFactoryObjects = new ArrayList<>();
    private int nextGroupId;
    private int nextEntryId;
    private int roundRobinPlanIndex;
    private Method productionActionMethod;
    private final ArrayList<Method> tickMethods = new ArrayList<>();
    private final ArrayList<GameTickDispatcher.Registration> tickRegistrations = new ArrayList<>();
    private XposedInterface.HookHandle productionHook;

    AutoReinforce(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
    }

    void install() throws Throwable {
        resolveTickMethods();
        resolveProductionAction();
        refreshSettings();
    }

    boolean panelEnabled() {
        return readBoolean(KEY_SHOW_REINFORCE_PANEL, true, 1);
    }

    void openPanel() {
        Activity activity = host.currentActivity();
        if (activity != null) activity.runOnUiThread(() -> buildListUi(activity));
    }

    synchronized void refreshSettings() {
        enabledState = null;
        panelState = null;
        weightModeState = null;
        if (enabled()) ensureHooks();
        else disableHooks();
    }

    void collectReinforceActions(ClassLoader ignored, ArrayList<?> actions) {
        Set<Long> group = snapshotSelectedGroup();
        if (group.isEmpty() || selectedGroupCanBuild(group)) return;
        synchronized (entries) {
            for (Object action : actions) {
                try {
                    if (action == null || action.getClass().getName().contains("RWMiao")) continue;
                    Object type = host.findField(action.getClass(), "j").get(action);
                    if (type == null || isRallyOrBuildAction(action)
                            || !isProductionAction(type, group)) continue;
                    rememberEntry(type, group, actionTitle(action), actionSpec(action));
                } catch (Throwable ignoredAction) {
                    // Ignore non-production actions.
                }
            }
        }
    }

    private void resolveProductionAction() throws Throwable {
        Class<?> renderer = loader.loadClass(host.target("gameFramework.f.i"));
        Class<?> action = loader.loadClass(host.target("game.units.a.s"));
        Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        productionActionMethod = host.findCompatibleMethod(renderer, "a", action, boolean.class, unit,
                boolean.class, boolean.class, float.class, boolean.class);
        if (productionActionMethod == null) throw new NoSuchMethodException("production action executor");
    }

    private void ensureHooks() {
        if (productionHook == null) productionHook = host.hookExecutable(productionActionMethod, chain -> {
            try {
                Object selected = chain.getArg(0);
                if (selected != null && !selected.getClass().getName().contains("RWMiao")) {
                    showPopupForAction(selected);
                }
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to capture reinforcement sequence", t);
            }
            return chain.proceed();
        });
        ensureTickHooks();
    }

    private void disableHooks() {
        XposedInterface.HookHandle hook = productionHook;
        productionHook = null;
        if (hook != null) {
            try { hook.unhook(); } catch (Throwable ignored) { }
        }
        disableTickHooks();
        dismissPopup(false);
    }

    private synchronized void ensureTickHooks() {
        if (!enabled() || !hasSchedulerWork() || !tickRegistrations.isEmpty()) return;
        for (Method tick : tickMethods) {
            tickRegistrations.add(host.tickDispatcher().register(tick, queue -> {
                try {
                    checkNewMatch();
                    checkDismiss();
                    renderPendingPreviews();
                    runAutomaticReinforcement();
                    if (!hasSchedulerWork()) disableTickHooks();
                } catch (Throwable t) {
                    host.log(5, TAG, "Automatic reinforcement skipped", t);
                }
            }));
        }
    }

    private synchronized void disableTickHooks() {
        for (GameTickDispatcher.Registration registration : tickRegistrations) {
            try { registration.close(); } catch (Throwable ignored) { }
        }
        tickRegistrations.clear();
    }

    private boolean hasSchedulerWork() {
        if (!enabled()) return false;
        return popup != null || hasPendingPreviews() || hasActivePlans();
    }

    private boolean hasActivePlans() {
        for (QueuePlan plan : plans.values()) if (plan.active()) return true;
        return false;
    }

    private boolean hasPendingPreviews() {
        if (listDialog == null) return false;
        for (ReinforceEntry entry : entries.values()) {
            if (entry.spec != null && !previews.containsKey(entry.typeId)) return true;
        }
        return false;
    }

    private void showPopupForAction(Object action) throws Throwable {
        if (!enabled()) {
            dismissPopup(false);
            return;
        }
        if (isRallyOrBuildAction(action)) return;
        Object type = host.findField(action.getClass(), "j").get(action);
        if (type == null) return;
        // Action execution can occur in the same tick as a selection change.
        selectedCacheTick = Integer.MIN_VALUE;
        Set<Long> group = snapshotSelectedGroup();
        if (group.isEmpty() || selectedGroupCanBuild(group)
                || !isProductionAction(type, group)) {
            dismissPopup(false);
            return;
        }
        String key;
        synchronized (entries) {
            key = rememberEntry(type, group, actionTitle(action), actionSpec(action));
        }
        if (key == null) {
            dismissPopup(false);
            showBlockedMessage();
            return;
        }
        ensureTickHooks();
        if (key.equals(dismissedKey)) return;
        dismissedKey = null;
        popupAction = action;
        popupGroup = new HashSet<>(group);
        popupSeenTick = currentTick();
        PopupWindow current = popup;
        if (current != null && current.isShowing() && key.equals(popupKey)) return;
        if (key.equals(popupBuildPendingKey)) return;
        Activity activity = host.currentActivity();
        if (activity != null) {
            popupBuildPendingKey = key;
            activity.runOnUiThread(() -> {
                try {
                    if (key.equals(popupBuildPendingKey)) buildPopupUi(activity, key);
                } finally {
                    if (key.equals(popupBuildPendingKey)) popupBuildPendingKey = null;
                }
            });
        }
    }

    private String rememberEntry(Object type, Set<Long> group, String title, Object spec) {
        String typeId = typeId(type);
        QueuePlan plan = findPlan(group);
        if (plan == null) {
            for (QueuePlan existing : plans.values()) {
                if (existing.active() && intersects(existing.groupIds, group)) {
                    return null;
                }
            }
            String planId = "g" + nextGroupId;
            plan = new QueuePlan(planId, nextGroupId + 1, group);
            nextGroupId++;
            plans.put(planId, plan);
        }
        for (String existingKey : new ArrayList<>(plan.sequence)) {
            ReinforceEntry entry = entries.get(existingKey);
            if (entry != null && typeId.equals(entry.typeId)) {
                entry.actionType = type;
                if (!TextUtils.isEmpty(title)) entry.title = title;
                if (spec != null) entry.spec = spec;
                return entry.key;
            }
        }
        String key = typeId + "#" + nextEntryId++;
        ReinforceEntry entry = new ReinforceEntry(key, plan.id, typeId,
                TextUtils.isEmpty(title) ? typeId : title, type, group, spec);
        entries.put(key, entry);
        int insertAt = plan.sequence.size();
        for (int i = 0; i < plan.sequence.size(); i++) {
            if (target(plan.sequence.get(i)) == -1) {
                insertAt = i;
                break;
            }
        }
        plan.sequence.add(insertAt, key);
        return key;
    }

    private QueuePlan findPlan(Set<Long> group) {
        for (QueuePlan plan : plans.values()) {
            if (plan.groupIds.equals(group)) return plan;
        }
        return null;
    }

    private boolean intersects(Set<Long> left, Set<Long> right) {
        for (Long id : left) if (right.contains(id)) return true;
        return false;
    }

    /** Shows the conflict inside the same editor PopupWindow, not as a toast. */
    private void showBlockedMessage() {
        long now = System.currentTimeMillis();
        if (now < blockedMessageUntilMs) return;
        blockedMessageUntilMs = now + 180L;
        blockedPopupUntilMs = now + 2500L;
        Activity activity = host.currentActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            try {
                PopupWindow old = popup;
                if (old != null) old.dismiss();
                float density = activity.getResources().getDisplayMetrics().density;
                TextView message = label(activity, "此工厂已存在独立序列", 14, Color.WHITE);
                message.setGravity(Gravity.CENTER);
                message.setPadding(dp(density, 14), dp(density, 8),
                        dp(density, 14), dp(density, 8));
                LinearLayout root = new LinearLayout(activity);
                root.setGravity(Gravity.CENTER);
                root.setPadding(dp(density, 10), dp(density, 8),
                        dp(density, 10), dp(density, 8));
                root.setBackground(roundBackground(-14275534, 24 * density));
                root.addView(message);
                PopupWindow created = new PopupWindow(root, -2, -2);
                popup = created;
                popupKey = BLOCKED_POPUP_KEY;
                popupSeenTick = -1;
                created.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                created.setFocusable(true);
                created.setOutsideTouchable(false);
                created.setTouchModal(false);
                created.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
                created.setOnDismissListener(() -> {
                    if (popup == created) {
                        popup = null;
                        popupKey = null;
                    }
                });
                created.showAtLocation(activity.getWindow().getDecorView(),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(density, 12));
            } catch (Throwable t) {
                host.log(5, TAG, "Failed to show sequence conflict message", t);
            }
        });
    }

    private Set<Long> snapshotSelectedGroup() {
        refreshSelectedFactoryCache();
        synchronized (selectedFactoryIds) {
            return new HashSet<>(selectedFactoryIds);
        }
    }

    private void refreshSelectedFactoryCache() {
        int tick = currentTick();
        if (tick >= 0 && selectedCacheTick == tick) return;
        Set<Long> ids = new HashSet<>();
        ArrayList<Object> factories = new ArrayList<>();
        try {
            Object all = allUnits();
            Class<?> building = loader.loadClass(host.target("game.units.d.s"));
            if (all instanceof Iterable) {
                for (Object unit : (Iterable<?>) all) {
                    if (unit != null && building.isInstance(unit)
                            && host.boolField(unit, "cI")) {
                        ids.add(unitId(unit));
                        factories.add(unit);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (selectedFactoryIds) {
            selectedFactoryIds.clear();
            selectedFactoryIds.addAll(ids);
            selectedFactoryObjects.clear();
            selectedFactoryObjects.addAll(factories);
            selectedCacheTick = tick;
        }
    }

    private boolean isProductionAction(Object actionType, Set<Long> group) throws Throwable {
        Object engine = host.findEngine(loader);
        Object me = host.findField(engine.getClass(), "bp").get(engine);
        refreshSelectedFactoryCache();
        ArrayList<Object> factories;
        synchronized (selectedFactoryIds) {
            factories = new ArrayList<>(selectedFactoryObjects);
        }
        for (Object candidate : factories) {
            if (candidate == null || !group.contains(unitId(candidate))
                    || host.findFieldValue(candidate, "bZ") != me) continue;
            Method method = host.findCompatibleMethod(candidate.getClass(), "a", actionType.getClass());
            if (method != null && method.invoke(candidate, actionType) != null) return true;
        }
        return false;
    }

    /**
     * Producers may be mobile (carrier/flying fortress), so movement or building class is not
     * a valid discriminator. Builders expose place/reclaim actions through their unit type;
     * pure producers expose queue actions but no builder action.
     */
    private boolean selectedGroupCanBuild(Set<Long> group) {
        refreshSelectedFactoryCache();
        ArrayList<Object> selected;
        synchronized (selectedFactoryIds) {
            selected = new ArrayList<>(selectedFactoryObjects);
        }
        for (Object unit : selected) {
            try {
                if (unit == null || !group.contains(unitId(unit))) continue;
                Method unitTypeMethod = host.findNoArgMethod(unit.getClass(), "q");
                Object unitType = unitTypeMethod == null ? null : unitTypeMethod.invoke(unit);
                if (unitType == null) continue;
                Method actionsAtLevel = host.findCompatibleMethod(
                        unitType.getClass(), "a", int.class);
                if (actionsAtLevel == null) continue;
                for (int level = 1; level <= 3; level++) {
                    Object value = actionsAtLevel.invoke(unitType, level);
                    if (!(value instanceof Iterable)) continue;
                    for (Object candidate : (Iterable<?>) value) {
                        String kind = actionKind(candidate);
                        if ("placeBuilding".equals(kind)
                                || "reclaimTarget".equals(kind)) return true;
                    }
                }
            } catch (Throwable ignored) {
                // Unknown unit implementations remain eligible only when their clicked action
                // itself passes the production-action check below.
            }
        }
        return false;
    }

    private Object allUnits() throws Throwable {
        Class<?> units = loader.loadClass(host.target("gameFramework.ah"));
        return host.findField(units, "et").get(null);
    }

    private long unitId(Object unit) throws Throwable {
        Object value = host.findField(unit.getClass(), "ej").get(unit);
        return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
    }

    private String typeId(Object type) {
        try {
            Object value = host.findField(type.getClass(), "b").get(type);
            if (value != null && !String.valueOf(value).isEmpty()) return String.valueOf(value);
        } catch (Throwable ignored) {
        }
        return type == null ? "unknown" : type.getClass().getName();
    }

    private Object actionSpec(Object action) {
        try {
            Method method = host.findNoArgMethod(action.getClass(), "h");
            return method == null ? null : method.invoke(action);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String actionTitle(Object action) {
        try {
            Method method = host.findNoArgMethod(action.getClass(), "b");
            Object value = method == null ? null : method.invoke(action);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isRallyOrBuildAction(Object action) {
        String name = actionKind(action);
        return "setRally".equals(name) || "placeBuilding".equals(name)
                || "reclaimTarget".equals(name) || "repairTarget".equals(name);
    }

    private String actionKind(Object action) {
        if (action == null) return "";
        try {
            Method kind = host.findNoArgMethod(action.getClass(), "d");
            Object value = kind == null ? null : kind.invoke(action);
            return value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void buildPopupUi(Activity activity, String key) {
        try {
            ReinforceEntry entry;
            synchronized (entries) { entry = entries.get(key); }
            if (entry == null) return;
            PopupWindow old = popup;
            if (old != null) old.dismiss();
            popupKey = key;

            float density = activity.getResources().getDisplayMetrics().density;
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(activity);
            name.setText(entry.title);
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(density, 8), 0, dp(density, 8), 0);
            line.addView(name, new LinearLayout.LayoutParams(dp(density, 80), -2));
            int groupFactoryCount = factoryCount(popupGroup);
            line.addView(makePopupButton(activity, density, "−",
                    () -> changePopupTarget(-1),
                    () -> changePopupTargetBy(-groupFactoryCount)));
            EditText count = numberEditor(activity, countText(target(key)), 50, density);
            countLabel = count;
            count.addTextChangedListener(targetWatcher(key));
            enablePopupEditor(count);
            line.addView(count);
            line.addView(makePopupButton(activity, density, "+",
                    () -> changePopupTarget(1),
                    () -> changePopupTargetBy(groupFactoryCount)));
            line.addView(makePopupButton(activity, density, "∞", () -> setPopupTarget(-1)));
            line.addView(makePopupButton(activity, density, "×", () -> {
                dismissedKey = key;
                dismissPopupKeyboard();
                dismissPopup(true);
            }));
            if (weightMode()) {
                line.addView(makePopupButton(activity, density, expanded ? "▲" : "▼", () -> {
                    expanded = !expanded;
                    buildPopupUi(activity, key);
                }));
            }

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(density, 8), dp(density, 8), dp(density, 8), dp(density, 8));
            root.setBackground(roundBackground(-14275534, 24 * density));
            root.addView(line);
            if (weightMode() && expanded) root.addView(buildWeightRow(activity, density, key));
            root.setFocusableInTouchMode(true);

            PopupWindow created = new PopupWindow(root, -2, -2);
            popup = created;
            created.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            created.setFocusable(true);
            created.setOutsideTouchable(false);
            // Keep the editor focusable for IME, but do not make the whole
            // game window modal while this small popup is visible.
            created.setTouchModal(false);
            created.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
            created.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            created.setOnDismissListener(() -> {
                if (popup == created) {
                    popup = null;
                    popupKey = null;
                    countLabel = null;
                    weightLabel = null;
                }
            });
            created.showAtLocation(activity.getWindow().getDecorView(),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(density, 12));
            root.requestFocus();
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to show reinforcement sequence editor", t);
        }
    }

    private LinearLayout buildWeightRow(Activity activity, float density, String key) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(density, 6), 0, 0);
        TextView title = label(activity, "权重 ", 14, Color.WHITE);
        row.addView(title);
        row.addView(makePopupButton(activity, density, "−", () -> setPopupWeight(weight(key) - 1)));
        EditText editor = numberEditor(activity, String.valueOf(weight(key)), 50, density);
        weightLabel = editor;
        editor.addTextChangedListener(weightWatcher(key));
        enablePopupEditor(editor);
        row.addView(editor);
        row.addView(makePopupButton(activity, density, "+", () -> setPopupWeight(weight(key) + 1)));
        return row;
    }

    private void buildListUi(Activity activity) {
        try {
            Dialog old = listDialog;
            if (old != null) old.dismiss();
            RuntimePanels.ReinforcePanelData initial = buildPanelData();
            RuntimePanels.ReinforcePanelCallback panelCallback =
                    new RuntimePanels.ReinforcePanelCallback() {
                        @Override
                        public RuntimePanels.ReinforcePanelData onTargetChanged(String key, int value) {
                            setTarget(key, value);
                            return buildPanelData();
                        }

                        @Override
                        public RuntimePanels.ReinforcePanelData onWeightChanged(String key, int value) {
                            setWeight(key, value);
                            return buildPanelData();
                        }

                        @Override
                        public RuntimePanels.ReinforcePanelData onRemove(String key) {
                            removeConfig(key);
                            return buildPanelData();
                        }

                        @Override
                        public RuntimePanels.ReinforcePanelData onRefresh() {
                            return buildPanelData();
                        }
                    };
            Dialog dialog = RuntimePanels.showReinforcePanel(
                    activity,
                    initial,
                    panelCallback);
            listDialog = dialog;
            listPanelCallback = panelCallback;
            ensureTickHooks();
            dialog.setOnDismissListener(v -> {
                if (listDialog == dialog) {
                    listDialog = null;
                    listPanelCallback = null;
                    if (hasSchedulerWork()) ensureTickHooks();
                    else disableTickHooks();
                }
            });
        } catch (Throwable t) {
            host.log(5, TAG, "Failed to show reinforcement panel", t);
        }
    }

    private RuntimePanels.ReinforcePanelData buildPanelData() {
        ArrayList<RuntimePanels.ReinforceRow> rows = new ArrayList<>();
        ArrayList<RuntimePanels.ReinforceGroup> groups = new ArrayList<>();
        synchronized (entries) {
            for (QueuePlan plan : plans.values()) {
                for (String key : new ArrayList<>(plan.sequence)) {
                    ReinforceEntry entry = entries.get(key);
                    if (entry == null || target(key) == 0 || entry.groupIds.isEmpty()) continue;
                    String subtitle = (entry.groupIds.size() <= 1 ? "" : "x" + entry.groupIds.size() + " · ")
                            + "组" + plan.displayNumber;
                    rows.add(new RuntimePanels.ReinforceRow(
                            entry.key,
                            entry.title,
                            subtitle,
                            target(key),
                            countText(target(key)),
                            weight(entry.key),
                            weightMode(),
                            isCurrentEntry(entry.key),
                            previews.get(entry.typeId)
                    ));
                }
                if (activeEntries(plan).isEmpty()) continue;
                ArrayList<PreviewToken> tokens = buildPlanPreviewTokens(plan);
                String currentKey = previewCurrentKey(plan);
                String nextKey = currentKey == null ? null : nextPreviewKey(tokens, currentKey);
                ArrayList<RuntimePanels.ReinforceToken> panelTokens = new ArrayList<>();
                for (PreviewToken token : tokens) {
                    int state = token.key.equals(currentKey) ? 1
                            : token.key.equals(nextKey) ? 2 : 0;
                    panelTokens.add(new RuntimePanels.ReinforceToken(previewText(token), state));
                }
                groups.add(new RuntimePanels.ReinforceGroup(
                        "组" + plan.displayNumber,
                        panelTokens
                ));
            }
        }
        return new RuntimePanels.ReinforcePanelData(rows, groups, weightMode());
    }

    private TextWatcher targetWatcher(String key) {
        return new SimpleWatcher() {
            @Override public void afterTextChanged(Editable editable) {
                if (updatingLabel) return;
                String value = editable == null ? "" : editable.toString();
                if ("∞".equals(value.trim())) setTarget(key, -1);
                else setTarget(key, digits(value, 0));
            }
        };
    }

    private TextWatcher weightWatcher(String key) {
        return new SimpleWatcher() {
            @Override public void afterTextChanged(Editable editable) {
                if (!updatingLabel) setWeight(key, Math.max(0, digits(String.valueOf(editable), 0)));
            }
        };
    }

    private void changePopupTarget(int delta) {
        changePopupTargetBy(delta);
    }

    private void changePopupTargetBy(int delta) {
        String key = popupKey;
        if (key == null) return;
        int current = target(key);
        if (current == -1) {
            setPopupTarget(delta > 0 ? Math.max(1, delta) : 0);
        } else {
            setPopupTarget(Math.max(0, current + delta));
        }
    }

    private int factoryCount(Set<Long> group) {
        return group == null || group.isEmpty() ? 1 : Math.max(1, group.size());
    }

    private void setPopupTarget(int value) {
        String key = popupKey;
        if (key == null) return;
        setTarget(key, value);
        EditText editor = countLabel;
        if (editor != null) updateEditor(editor, countText(value));
    }

    private void setPopupWeight(int value) {
        String key = popupKey;
        if (key == null) return;
        int normalized = Math.max(0, value);
        setWeight(key, normalized);
        EditText editor = weightLabel;
        if (editor != null) updateEditor(editor, String.valueOf(normalized));
    }

    private void updateEditor(EditText editor, String text) {
        updatingLabel = true;
        editor.setText(text);
        editor.setSelection(editor.length());
        updatingLabel = false;
    }

    private int target(String key) {
        Integer value = targets.get(key);
        return value == null ? 0 : value;
    }

    private void setTarget(String key, int value) {
        if (key == null) return;
        if (value == 0) {
            targets.remove(key);
            weights.remove(key);
            produced.remove(key);
        } else {
            int normalized = value < -1 ? 0 : value;
            if (normalized != target(key)) produced.put(key, 0);
            targets.put(key, normalized);
            if (normalized == -1 && !weightMode()) replaceUnweightedInfinite(key);
        }
    }

    private void replaceUnweightedInfinite(String keepKey) {
        ReinforceEntry keep = entries.get(keepKey);
        if (keep == null) return;
        QueuePlan plan = plans.get(keep.planId);
        if (plan == null) return;
        for (String key : new ArrayList<>(plan.sequence)) {
            if (key.equals(keepKey) || target(key) != -1) continue;
            targets.remove(key);
            weights.remove(key);
            produced.remove(key);
            plan.sequence.remove(key);
            entries.remove(key);
        }
    }

    private void normalizeUnweightedInfinite() {
        for (QueuePlan plan : plans.values()) {
            String newest = null;
            for (String key : plan.sequence) if (target(key) == -1) newest = key;
            if (newest != null) replaceUnweightedInfinite(newest);
        }
    }

    private int weight(String key) {
        Integer value = weights.get(key);
        return value == null ? 1 : Math.max(0, value);
    }

    private void setWeight(String key, int value) {
        if (key != null) {
            weights.put(key, Math.max(0, value));
            produced.put(key, 0);
        }
    }

    private String previewText(PreviewToken token) {
        StringBuilder text = new StringBuilder(token.title);
        if (!token.infinite && token.count > 1) text.append('x').append(token.count);
        return text.toString();
    }

    private String nextPreviewKey(ArrayList<PreviewToken> tokens, String currentKey) {
        boolean afterCurrent = currentKey == null;
        for (PreviewToken token : tokens) {
            if (afterCurrent && (currentKey == null || !token.key.equals(currentKey))) {
                return token.key;
            }
            if (currentKey != null && currentKey.equals(token.key)) afterCurrent = true;
        }
        return null;
    }

    private ArrayList<PreviewToken> buildPlanPreviewTokens(QueuePlan plan) {
        ArrayList<PreviewToken> result = new ArrayList<>();
        if (plan == null) return result;
        if (!weightMode()) {
            for (String key : plan.sequence) {
                ReinforceEntry entry = entries.get(key);
                int value = target(key);
                if (entry != null && value != 0) {
                    result.add(new PreviewToken(entry, value, value == -1));
                }
            }
            return result;
        }
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) != 0 && weight(key) == 0) {
                result.add(new PreviewToken(entry, target(key), target(key) == -1));
            }
        }
        LinkedHashMap<String, Integer> remaining = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> cycle = new LinkedHashMap<>();
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) != 0 && weight(key) > 0) {
                remaining.put(key, target(key));
                cycle.put(key, 0);
            }
        }
        for (int step = 0; step < 18 && !remaining.isEmpty(); step++) {
            boolean complete = true;
            for (String key : remaining.keySet()) {
                if (cycle.get(key) < weight(key)) {
                    complete = false;
                    break;
                }
            }
            if (complete) for (String key : new ArrayList<>(remaining.keySet())) cycle.put(key, 0);
            String selected = null;
            for (String key : remaining.keySet()) {
                if (cycle.get(key) < weight(key)) {
                    selected = key;
                    break;
                }
            }
            if (selected == null) break;
            ReinforceEntry entry = entries.get(selected);
            int value = remaining.get(selected);
            appendPreviewToken(result, entry, 1, value == -1);
            cycle.put(selected, cycle.get(selected) + 1);
            if (value > 0) {
                value--;
                if (value == 0) {
                    remaining.remove(selected);
                    cycle.remove(selected);
                } else {
                    remaining.put(selected, value);
                }
            }
        }
        return result;
    }

    /**
     * 相邻的同一队列合并为一个 chip，尤其避免无限队列把整行渲染成重复的绿色按钮。
     * Merge adjacent emissions from one queue into a chip, preventing an infinite queue
     * from rendering an entire row as repeated green buttons.
     */
    private void appendPreviewToken(ArrayList<PreviewToken> result, ReinforceEntry entry,
                                     int count, boolean infinite) {
        if (entry == null) return;
        if (!result.isEmpty()) {
            PreviewToken last = result.get(result.size() - 1);
            if (last.key.equals(entry.key)) {
                int mergedCount = last.infinite || infinite ? -1 : last.count + count;
                result.set(result.size() - 1,
                        new PreviewToken(entry, mergedCount, last.infinite || infinite));
                return;
            }
        }
        result.add(new PreviewToken(entry, infinite ? -1 : count, infinite));
    }

    private String previewCurrentKey(QueuePlan plan) {
        if (plan == null) return null;
        if (!weightMode()) {
            ReinforceEntry entry = firstSequentialEntry(plan);
            return entry == null ? null : entry.key;
        }
        ReinforceEntry zero = firstWeightZeroEntry(plan);
        if (zero != null) return zero.key;
        ReinforceEntry global = peekGlobalWeightedEntry();
        if (global != null && plan.id.equals(global.planId)) return global.key;
        return null;
    }

    private ArrayList<ReinforceEntry> activeEntries(QueuePlan plan) {
        ArrayList<ReinforceEntry> active = new ArrayList<>();
        if (plan == null) return active;
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) != 0) active.add(entry);
        }
        return active;
    }

    private boolean isCurrentEntry(String key) {
        ReinforceEntry entry = entries.get(key);
        if (entry == null || target(key) == 0) return false;
        QueuePlan plan = plans.get(entry.planId);
        if (plan == null) return false;
        ReinforceEntry current;
        if (!weightMode()) {
            current = firstSequentialEntry(plan);
        } else {
            current = null;
            for (QueuePlan candidate : plans.values()) {
                current = firstWeightZeroEntry(candidate);
                if (current != null) break;
            }
            if (current == null) current = peekGlobalWeightedEntry();
        }
        return current != null && key.equals(current.key);
    }

    private void removeConfig(String key) {
        ReinforceEntry removed = entries.get(key);
        targets.remove(key);
        weights.remove(key);
        produced.remove(key);
        entries.remove(key);
        previewRetryTicks.remove(key);
        if (removed != null) {
            QueuePlan plan = plans.get(removed.planId);
            if (plan != null) {
                plan.sequence.remove(key);
                if (plan.sequence.isEmpty()) plans.remove(plan.id);
            }
        }
        if (key != null && key.equals(dismissedKey)) dismissedKey = null;
    }

    private void resolveTickMethods() throws Throwable {
        Class<?> queue = loader.loadClass(host.target("gameFramework.c"));
        Method singlePlayerTick = host.findCompatibleMethod(queue, "c");
        Method multiplayerTick = host.findCompatibleMethod(queue, "d");
        if (singlePlayerTick == null && multiplayerTick == null) {
            throw new NoSuchMethodException("command queue tick c/d");
        }
        if (singlePlayerTick != null) tickMethods.add(singlePlayerTick);
        if (multiplayerTick != null && multiplayerTick != singlePlayerTick) {
            tickMethods.add(multiplayerTick);
        }
    }

    private void runAutomaticReinforcement() throws Throwable {
        if (!hasActivePlans()) return;
        int tick = currentTick();
        if (weightMode()) {
            if (tick - lastTick < 6) return;
            lastTick = tick;
            runWeighted();
        } else {
            if (tick - lastTick < 30) return;
            lastTick = tick;
            normalizeUnweightedInfinite();
            runUnweighted();
        }
    }

    /** Render unit previews through the game's off-screen renderer. */
    private void renderPendingPreviews() {
        if (listDialog == null) return;
        int tick = currentTick();
        if (tick >= 0 && lastPreviewTick >= 0 && tick - lastPreviewTick < 10) return;
        for (ReinforceEntry entry : new ArrayList<>(entries.values())) {
            if (entry.spec == null || previews.containsKey(entry.typeId)) continue;
            Integer retry = previewRetryTicks.get(entry.typeId);
            if (retry != null && tick >= 0 && tick < retry) continue;
            if (tick >= 0) lastPreviewTick = tick;
            Bitmap bitmap = renderPreview(entry.spec);
            if (bitmap != null) {
                previews.put(entry.typeId, bitmap);
                RuntimePanels.ReinforcePanelCallback callback = listPanelCallback;
                Dialog dialog = listDialog;
                RuntimePanels.ReinforcePanelData updated = callback == null
                        ? null : callback.onRefresh();
                Activity activity = host.currentActivity();
                if (updated != null && dialog != null && activity != null) {
                    activity.runOnUiThread(() ->
                            RuntimePanels.refreshReinforcePanel(dialog, updated));
                }
            } else if (tick >= 0) {
                previewRetryTicks.put(entry.typeId, tick + 30);
            }
            // Render at most one missing icon per game tick so the panel does
            // not block the game thread while rows are being populated.
            break;
        }
    }

    private Bitmap renderPreview(Object spec) {
        Object engine = null;
        Field rendererField = null;
        Object savedRenderer = null;
        try {
            Activity activity = host.currentActivity();
            if (activity == null) return null;
            engine = host.findEngine(loader);
            Class<?> softwareRenderer = loader.loadClass(host.target("gameFramework.m.fh"));
            Object renderer = softwareRenderer.getDeclaredConstructor().newInstance();
            Method initialize = host.findCompatibleMethod(softwareRenderer, "a", Activity.class);
            if (initialize == null) return null;
            initialize.invoke(renderer, activity);
            Method makeImage = host.findCompatibleMethod(softwareRenderer, "b",
                    int.class, int.class, boolean.class);
            if (makeImage == null) return null;
            Object image = makeImage.invoke(renderer, 96, 96, true);
            if (image == null) return null;
            Method bind = host.findCompatibleMethod(softwareRenderer, "b", image.getClass());
            if (bind == null) return null;
            Object canvas = bind.invoke(renderer, image);
            rendererField = host.findField(engine.getClass(), "bL");
            savedRenderer = rendererField.get(engine);
            rendererField.set(engine, canvas);

            Class<?> team = loader.loadClass(host.target("game.p"));
            Method colorMethod = host.findCompatibleMethod(team, "i", int.class);
            Object previewTeam = colorMethod == null ? null : colorMethod.invoke(null, 0);
            if (previewTeam == null) return null;
            Class<?> drawer = loader.loadClass(host.target("game.units.cj"));
            Method draw = findPreviewDrawMethod(drawer, spec.getClass(), previewTeam.getClass());
            if (draw == null) return null;
            float width = ((Number) host.findField(image.getClass(), "r").get(image)).floatValue();
            float height = ((Number) host.findField(image.getClass(), "s").get(image)).floatValue();
            draw.invoke(null, spec, width, height, 0f, previewTeam, 20f, 100f,
                    false, false, 1, true, null);
            rendererField.set(engine, savedRenderer);
            savedRenderer = null;
            Method finish = host.findNoArgMethod(canvas.getClass(), "n");
            if (finish != null) finish.invoke(canvas);
            Method bitmap = host.findNoArgMethod(image.getClass(), "b");
            Object result = bitmap == null ? null : bitmap.invoke(image);
            return result instanceof Bitmap ? (Bitmap) result : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (engine != null && rendererField != null && savedRenderer != null) {
                try { rendererField.set(engine, savedRenderer); } catch (Throwable ignored) { }
            }
        }
    }

    private Method findPreviewDrawMethod(Class<?> drawer, Class<?> specType, Class<?> teamType) {
        for (Method method : drawer.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (!"a".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                    || p.length != 12 || !p[0].isAssignableFrom(specType)
                    || p[1] != float.class || p[2] != float.class || p[3] != float.class
                    || !p[4].isAssignableFrom(teamType) || p[5] != float.class || p[6] != float.class
                    || p[7] != boolean.class || p[8] != boolean.class || p[9] != int.class
                    || p[10] != boolean.class || p[11].isPrimitive()) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    /**
     * Unweighted mode keeps each factory group as an independent sequence,
     * while the groups themselves get a round-robin turn.  A finite item is
     * always handled before the group's infinite tail.
     */
    private void runUnweighted() throws Throwable {
        ArrayList<QueuePlan> activePlans = new ArrayList<>();
        for (QueuePlan plan : plans.values()) {
            if (!activeEntries(plan).isEmpty()) activePlans.add(plan);
        }
        if (activePlans.isEmpty()) return;
        int size = activePlans.size();
        int start = Math.floorMod(roundRobinPlanIndex, size);
        for (int offset = 0; offset < size; offset++) {
            int index = (start + offset) % size;
            QueuePlan plan = activePlans.get(index);
            ReinforceEntry entry = firstSequentialEntry(plan);
            if (entry == null || !hasFreeFactory(entry)) continue;
            if (issueProduce(entry, 1) <= 0) continue;
            consumeOne(entry.key);
            roundRobinPlanIndex = (index + 1) % size;
            return;
        }
    }

    /**
     * 正权重队列共享一个跨工厂组的权重周期；权重 0 独立运行，不暂停也不稀释正权重队列。
     * Positive queues share one weighted cycle across factory groups; weight zero stays
     * independent and never pauses or dilutes that positive-weight cycle.
     */
    private void runWeighted() throws Throwable {
        ArrayList<QueuePlan> allPlans = new ArrayList<>(plans.values());
        if (allPlans.isEmpty()) return;

        // Weight-zero queues are independent from the positive-weight cycle.
        // Let each eligible group submit one native order this tick.
        for (QueuePlan plan : allPlans) {
            ReinforceEntry zero = firstWeightZeroEntry(plan);
            if (zero == null || !hasFreeFactory(zero)) continue;
            if (issueProduce(zero, 1) > 0) consumeOne(zero.key);
        }

        ReinforceEntry entry = chooseGlobalWeightedEntry();
        if (entry == null || !hasFreeFactory(entry)) return;
        if (issueProduce(entry, 1) <= 0) return;
        consumeOne(entry.key);
        produced.put(entry.key, producedValue(entry.key) + 1);
    }

    private ReinforceEntry firstSequentialEntry(QueuePlan plan) {
        if (plan == null) return null;
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) > 0) return entry;
        }
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) == -1) return entry;
        }
        return null;
    }

    private ReinforceEntry firstWeightZeroEntry(QueuePlan plan) {
        if (plan == null) return null;
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) > 0 && weight(key) == 0) return entry;
        }
        for (String key : plan.sequence) {
            ReinforceEntry entry = entries.get(key);
            if (entry != null && target(key) == -1 && weight(key) == 0) return entry;
        }
        return null;
    }

    /** Selects the next positive queue across all groups in sequence order. */
    private ReinforceEntry chooseGlobalWeightedEntry() {
        ArrayList<ReinforceEntry> candidates = positiveEntries();
        if (candidates.isEmpty()) return null;
        boolean cycleComplete = true;
        for (ReinforceEntry entry : candidates) {
            if (producedValue(entry.key) < weight(entry.key)) {
                cycleComplete = false;
                break;
            }
        }
        if (cycleComplete) {
            for (ReinforceEntry entry : candidates) produced.put(entry.key, 0);
        }
        for (ReinforceEntry entry : candidates) {
            if (producedValue(entry.key) < weight(entry.key)) return entry;
        }
        return null;
    }

    /** Same selection as the scheduler, without changing the cycle cursor. */
    private ReinforceEntry peekGlobalWeightedEntry() {
        ArrayList<ReinforceEntry> candidates = positiveEntries();
        if (candidates.isEmpty()) return null;
        boolean complete = true;
        for (ReinforceEntry entry : candidates) {
            if (producedValue(entry.key) < weight(entry.key)) {
                complete = false;
                break;
            }
        }
        for (ReinforceEntry entry : candidates) {
            int current = complete ? 0 : producedValue(entry.key);
            if (current < weight(entry.key)) return entry;
        }
        return null;
    }

    private ArrayList<ReinforceEntry> positiveEntries() {
        ArrayList<ReinforceEntry> result = new ArrayList<>();
        for (ReinforceEntry entry : entries.values()) {
            if (target(entry.key) != 0 && weight(entry.key) > 0) result.add(entry);
        }
        return result;
    }

    private void consumeOne(String key) {
        int current = target(key);
        if (current > 0) {
            targets.put(key, Math.max(0, current - 1));
            if (target(key) == 0) produced.remove(key);
        }
    }

    private int producedValue(String key) {
        Integer value = produced.get(key);
        return value == null ? 0 : value;
    }

    private boolean hasFreeFactory(ReinforceEntry entry) throws Throwable {
        return freeSlots(entry) > 0;
    }

    private int freeSlots(ReinforceEntry entry) throws Throwable {
        Object engine = host.findEngine(loader);
        Object me = host.findField(engine.getClass(), "bp").get(engine);
        Class<?> factory = loader.loadClass(host.target("game.units.d.s"));
        Object all = allUnits();
        int free = 0;
        if (!(all instanceof Iterable)) return 0;
        for (Object unit : (Iterable<?>) all) {
            if (unit == null || !factory.isInstance(unit) || !entry.groupIds.contains(unitId(unit))
                    || host.findFieldValue(unit, "bZ") != me) continue;
            Method queueCount = host.findNoArgMethod(unit.getClass(), "cW");
            int queued = queueCount == null ? 0 : ((Number) queueCount.invoke(unit)).intValue();
            free += Math.max(1 - queued, 0);
        }
        return free;
    }

    /**
     * 生产指令始终走游戏原生 command 对象，避免直接改本地队列破坏多人同步。
     * Production orders always use the game's native command object instead of mutating
     * a local queue, preserving multiplayer synchronization.
     */
    private int issueProduce(ReinforceEntry entry, int amount) throws Throwable {
        if (amount <= 0 || entry.actionType == null) return 0;
        Object engine = host.findEngine(loader);
        Object me = host.findField(engine.getClass(), "bp").get(engine);
        if (me == null) return 0;
        Object queue = host.findField(engine.getClass(), "cc").get(engine);
        Class<?> team = loader.loadClass(host.target("game.p"));
        Method create = host.findCompatibleMethod(queue.getClass(), "a", team);
        if (create == null) return 0;
        Object command = create.invoke(queue, me);
        if (command == null) return 0;
        Class<?> factory = loader.loadClass(host.target("game.units.d.s"));
        Class<?> bp = loader.loadClass(host.target("game.units.bp"));
        ArrayList<Object> factories = new ArrayList<>();
        Object all = allUnits();
        if (all instanceof Iterable) {
            for (Object unit : (Iterable<?>) all) {
                if (unit != null && factory.isInstance(unit)
                        && entry.groupIds.contains(unitId(unit))) factories.add(unit);
            }
        }
        if (factories.isEmpty()) return 0;
        Method addFactory = host.findCompatibleMethod(command.getClass(), "a", bp);
        Method send = findCommandActionMethod(command.getClass(), entry.actionType.getClass());
        if (addFactory == null || send == null) return 0;
        int[] queued = new int[factories.size()];
        int[] assigned = new int[factories.size()];
        for (int i = 0; i < factories.size(); i++) {
            Method count = host.findNoArgMethod(factories.get(i).getClass(), "cW");
            queued[i] = count == null ? 0 : ((Number) count.invoke(factories.get(i))).intValue();
        }
        for (int order = 0; order < amount; order++) {
            int selected = 0;
            int lowest = Integer.MAX_VALUE;
            for (int i = 0; i < factories.size(); i++) {
                int score = queued[i] + assigned[i];
                if (score < lowest) {
                    lowest = score;
                    selected = i;
                }
            }
            addFactory.invoke(command, factories.get(selected));
            assigned[selected]++;
        }
        send.invoke(command, entry.actionType, null);
        return amount;
    }

    private Method findCommandActionMethod(Class<?> type, Class<?> actionType) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("a".equals(method.getName()) && parameters.length == 2
                        && parameters[0].isAssignableFrom(actionType) && !parameters[1].isPrimitive()) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private void dismissPopup(boolean keepDismissedKey) {
        popupBuildPendingKey = null;
        PopupWindow current = popup;
        Activity activity = host.currentActivity();
        if (current != null && activity != null) {
            activity.runOnUiThread(current::dismiss);
        } else {
            popupKey = null;
            countLabel = null;
            weightLabel = null;
        }
        if (!keepDismissedKey) dismissedKey = null;
    }

    private void dismissPopupKeyboard() {
        EditText editor = countLabel;
        if (editor == null) editor = weightLabel;
        if (editor == null) return;
        InputMethodManager manager = (InputMethodManager)
                editor.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(editor.getWindowToken(), 0);
    }

    private void checkDismiss() {
        PopupWindow current = popup;
        if (current == null) return;
        refreshSelectedFactoryCache();
        boolean noFactory;
        synchronized (selectedFactoryIds) { noFactory = selectedFactoryIds.isEmpty(); }
        if (BLOCKED_POPUP_KEY.equals(popupKey)) {
            if (System.currentTimeMillis() < blockedPopupUntilMs) return;
            Activity activity = host.currentActivity();
            if (activity != null) activity.runOnUiThread(current::dismiss);
            return;
        }
        if (!noFactory && (popupSeenTick < 0 || currentTick() - popupSeenTick <= 3)) return;
        Activity activity = host.currentActivity();
        if (activity != null) activity.runOnUiThread(current::dismiss);
    }

    private void checkNewMatch() {
        int tick = currentTick();
        if (tick < 0) return;
        if (lastMatchTick >= 0 && tick < lastMatchTick) clearConfig();
        lastMatchTick = tick;
    }

    private void clearConfig() {
        targets.clear();
        weights.clear();
        produced.clear();
        entries.clear();
        plans.clear();
        popupGroup = null;
        popupAction = null;
        popupBuildPendingKey = null;
        dismissedKey = null;
        popupSeenTick = -1;
        lastPreviewTick = Integer.MIN_VALUE;
        lastTick = 0;
        nextGroupId = 0;
        nextEntryId = 0;
        roundRobinPlanIndex = 0;
        blockedMessageUntilMs = 0L;
        blockedPopupUntilMs = 0L;
        selectedCacheTick = Integer.MIN_VALUE;
        synchronized (selectedFactoryIds) {
            selectedFactoryIds.clear();
            selectedFactoryObjects.clear();
        }
        Dialog currentList = listDialog;
        listDialog = null;
        listPanelCallback = null;
        PopupWindow current = popup;
        Activity activity = host.currentActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (currentList != null) currentList.dismiss();
                if (current != null) current.dismiss();
            });
        }
    }

    private int currentTick() {
        try {
            Object engine = host.findEngine(loader);
            return ((Number) host.findField(engine.getClass(), "bu").get(engine)).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private boolean enabled() { return readBoolean(KEY_REINFORCE_ON, false, 0); }

    boolean automationEnabled() { return enabled(); }

    private boolean weightMode() {
        return enabled() && readBoolean(KEY_REINFORCE_WEIGHT_MODE, false, 2);
    }

    private boolean readBoolean(String key, boolean fallback, int slot) {
        Boolean cached = slot == 0 ? enabledState : slot == 1 ? panelState : weightModeState;
        if (cached != null) return cached;
        try {
            Context context = host.preferenceContext();
            if (context == null) return fallback;
            boolean value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(key, fallback);
            if (slot == 0) enabledState = value;
            else if (slot == 1) panelState = value;
            else weightModeState = value;
            return value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private TextView makePopupButton(Activity activity, float density, String text, Runnable action) {
        TextView button = label(activity, text, 16, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(density, 32), dp(density, 32));
        params.setMargins(dp(density, 4), 0, dp(density, 4), 0);
        button.setLayoutParams(params);
        button.setBackground(roundBackground(-12301228, 8 * density));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView makePopupButton(Activity activity, float density, String text,
                                     Runnable action, Runnable longAction) {
        TextView button = makePopupButton(activity, density, text, action);
        button.setOnLongClickListener(v -> {
            longAction.run();
            return true;
        });
        return button;
    }

    private EditText numberEditor(Activity activity, String text, int width, float density) {
        EditText editor = new EditText(activity);
        editor.setText(text);
        editor.setTextColor(Color.WHITE);
        editor.setTextSize(15);
        editor.setGravity(Gravity.CENTER);
        editor.setInputType(2);
        editor.setSingleLine(true);
        editor.setPadding(0, 0, 0, 0);
        editor.setLayoutParams(new LinearLayout.LayoutParams(dp(density, width), -2));
        return editor;
    }

    private void enablePopupEditor(EditText editor) {
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        editor.setOnClickListener(v -> showPopupKeyboard(editor));
        editor.setOnFocusChangeListener((v, focused) -> {
            if (focused) editor.postDelayed(() -> showPopupKeyboard(editor), 60L);
        });
    }

    private void showPopupKeyboard(EditText editor) {
        editor.requestFocus();
        InputMethodManager manager = (InputMethodManager)
                editor.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
    }

    private TextView label(Context context, String text, float size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable roundBackground(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(float density, int value) { return (int) (value * density + 0.5f); }

    private int digits(String value, int fallback) {
        try {
            int result = 0;
            boolean found = false;
            for (int i = 0; value != null && i < value.length(); i++) {
                char c = value.charAt(i);
                if (c >= '0' && c <= '9') {
                    result = result * 10 + (c - '0');
                    found = true;
                }
            }
            return found ? result : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String countText(int value) {
        return value == -1 ? "∞" : value == 0 ? "关" : String.valueOf(value);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }

    private final class QueuePlan {
        final String id;
        final int displayNumber;
        final Set<Long> groupIds;
        final ArrayList<String> sequence = new ArrayList<>();

        QueuePlan(String id, int displayNumber, Set<Long> groupIds) {
            this.id = id;
            this.displayNumber = displayNumber;
            this.groupIds = new HashSet<>(groupIds);
        }

        boolean active() {
            for (String key : sequence) {
                if (target(key) != 0) return true;
            }
            return false;
        }
    }

    private static final class PreviewToken {
        final String key;
        final String planId;
        final String title;
        final int count;
        final boolean infinite;

        PreviewToken(ReinforceEntry entry, int count, boolean infinite) {
            this.key = entry.key;
            this.planId = entry.planId;
            this.title = entry.title;
            this.count = count;
            this.infinite = infinite;
        }
    }

    private static final class ReinforceEntry {
        final String key;
        final String planId;
        final String typeId;
        final Set<Long> groupIds;
        String title;
        Object actionType;
        Object spec;

        ReinforceEntry(String key, String planId, String typeId, String title, Object actionType,
                       Set<Long> groupIds, Object spec) {
            this.key = key;
            this.planId = planId;
            this.typeId = typeId;
            this.title = title;
            this.actionType = actionType;
            this.groupIds = new HashSet<>(groupIds);
            this.spec = spec;
        }
    }
}
