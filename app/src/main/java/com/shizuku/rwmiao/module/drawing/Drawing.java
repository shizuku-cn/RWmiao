package com.shizuku.rwmiao.module.drawing;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


import static com.shizuku.rwmiao.config.SettingsContract.*;

/**
 * World drawing features with one shared overlay hook.
 *
 * The game already exposes the authoritative factory queue through d.s.cX().
 * The queue item stores progress in m and its normalized rate in b; production
 * advances with b * factory.ca() * delta, so the displayed total is
 * 1 / (b * factory.ca()). Nuclear and anti-nuclear ammunition are the native
 * unit fields y.c and c.d respectively. Launcher instances are resolved from
 * ce.bn() using both the concrete native class and q().i() unit type identity,
 * then drawn in the shared post-map pass at the unit center.
 */
public final class Drawing {
    private static final int KIND_RANGE = 1;
    private static final int KIND_LINE = 1 << 1;
    private static final int KIND_FACTORY = 1 << 2;
    private static final int CANDIDATE_REFRESH_FRAMES = 15;
    private static final String TAG = "RWmiao";
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Set<Object> manualDrawSet = weakSet();
    private final Set<Object> manualHideSet = weakSet();
    private final Set<Object> manualLineSet = weakSet();
    private final Set<Object> manualLineHideSet = weakSet();
    private final Map<Object, Integer> movementBits = new WeakHashMap<>();
    private final Map<Object, Integer> teamRelations = new WeakHashMap<>();
    private final AttackRangeDrawing rangeDrawing;
    private final TargetLineDrawing targetLineDrawing;
    private final AmmoDrawing ammoDrawing = new AmmoDrawing();
    private final FactoryCountdownDrawing factoryDrawing = new FactoryCountdownDrawing();
    private final ArrayList<DrawCandidate> frameCandidates = new ArrayList<>();
    private volatile DrawConfig cachedConfig;
    private volatile boolean candidatesDirty = true;
    private int framesUntilCandidateRefresh;
    private RuntimeAccess runtime;
    private RendererAccess rendererAccess;
    private Object relationPlayer;
    private Method drawMethod;
    private GameFrameDispatcher.Registration drawRegistration;

    public Drawing(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
        this.rangeDrawing = new AttackRangeDrawing(host);
        this.targetLineDrawing = new TargetLineDrawing();
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess();
        Class<?> uiRenderer = loader.loadClass(host.target("gameFramework.f.i"));
        drawMethod = uiRenderer.getDeclaredMethod("b", float.class);
        refreshSettings();
    }

    private synchronized void ensureHook() {
        if (drawRegistration != null || drawMethod == null) return;
        drawRegistration = host.frameDispatcher().register(drawMethod,
                (renderer, delta) -> {
                    try {
                        drawFrame();
                    } catch (Throwable t) {
                        host.log(6, TAG, "Native drawing failed", t);
                    }
                });
    }

    private synchronized void disableHook() {
        GameFrameDispatcher.Registration registration = drawRegistration;
        drawRegistration = null;
        if (registration != null) registration.close();
    }

    public void refreshSettings() {
        cachedConfig = null;
        candidatesDirty = true;
        ammoDrawing.clear();
        factoryDrawing.clear();
        if (worldDrawingConfigured() || !manualDrawSet.isEmpty() || !manualLineSet.isEmpty()) {
            ensureHook();
        } else {
            disableHook();
        }
    }

    public void toggleSelected(boolean range) {
        boolean enable = !allSelectedEnabled(range);
        try {
            for (Object unit : selectedUnits()) {
                if (!hasAttackRange(unit)) continue;
                Set<Object> on = range ? manualDrawSet : manualLineSet;
                Set<Object> off = range ? manualHideSet : manualLineHideSet;
                if (enable) {
                    off.remove(unit);
                    on.add(unit);
                } else {
                    on.remove(unit);
                    off.add(unit);
                }
            }
            if (enable) {
                ensureHook();
            } else if (!worldDrawingConfigured() && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
                disableHook();
            }
            candidatesDirty = true;
        } catch (Throwable t) {
            host.log(6, TAG, "Failed to toggle selected draw state", t);
        }
    }

    public boolean allSelectedEnabled(boolean range) {
        try {
            boolean found = false;
            for (Object unit : selectedUnits()) {
                found = true;
                if (!isDrawEnabled(unit, range)) return false;
            }
            return found;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean hasDrawableSelection() {
        try {
            for (Object unit : selectedUnits()) if (hasAttackRange(unit)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean worldDrawingConfigured() {
        try {
            Context context = host.preferenceContext();
            if (context == null) return false;
            android.content.SharedPreferences preferences =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return preferences.getBoolean(KEY_SHOW_ATTACK_RANGE, false)
                    || preferences.getBoolean(KEY_SHOW_TARGET_LINE, false)
                    || preferences.getBoolean(KEY_SHOW_AMMO_COUNT, false)
                    || preferences.getBoolean(KEY_SHOW_FACTORY_COUNTDOWN, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Iterable<?> selectedUnits() throws Throwable {
        Object engine = host.findEngine(loader);
        Object ui = host.findField(engine.getClass(), "bP").get(engine);
        Object selected = host.findField(ui.getClass(), "bZ").get(ui);
        return selected instanceof Iterable ? (Iterable<?>) selected : Collections.emptyList();
    }

    private boolean hasAttackRange(Object unit) {
        try {
            Method range = unit == null ? null : host.findNoArgMethod(unit.getClass(), "l");
            return range != null && host.number(range.invoke(unit)) > 0.0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDrawEnabled(Object unit, boolean range) throws Throwable {
        Set<Object> on = range ? manualDrawSet : manualLineSet;
        Set<Object> off = range ? manualHideSet : manualLineHideSet;
        if (on.contains(unit)) return true;
        if (off.contains(unit)) return false;
        Object engine = host.findEngine(loader);
        Context context = (Context) host.findField(engine.getClass(), "am").get(engine);
        if (!context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(range ? KEY_SHOW_ATTACK_RANGE : KEY_SHOW_TARGET_LINE, false)) {
            return false;
        }
        Object player = host.findField(engine.getClass(), "bp").get(engine);
        int relation = host.relation(player, host.findFieldValue(unit, "bZ"));
        int players = host.prefInt(context,
                range ? KEY_RANGE_PLAYER_FILTER : KEY_LINE_PLAYER_FILTER, 2);
        int types = host.prefInt(context,
                range ? KEY_RANGE_UNIT_TYPES : KEY_LINE_UNIT_TYPES, 31);
        return host.selected(relation, players) && host.typeSelected(unit, types);
    }

    /** Resolve reflection once; the frame loop uses direct Field reads and cached classifications. */
    private void drawFrame() throws Throwable {
        DrawConfig known = cachedConfig;
        if (known != null && !known.anyConfigured()
                && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
            disableHook();
            return;
        }
        Object engine = host.findEngine(loader);
        if (engine == null) return;
        DrawConfig config = drawConfig((Context) runtime.context.get(engine));
        if (!config.anyConfigured()
                && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
            disableHook();
            return;
        }

        Object renderer = runtime.renderer.get(engine);
        if (renderer == null) return;
        RendererAccess draw = rendererAccess;
        if (draw == null || draw.type != renderer.getClass()) {
            draw = new RendererAccess(renderer.getClass());
            rendererAccess = draw;
        }
        if (config.drawRanges && draw.circle == null) config.drawRanges = false;
        if (config.drawLines && draw.line == null) config.drawLines = false;
        if ((config.drawAmmo || config.drawFactory) && draw.text == null) {
            config.drawAmmo = false;
            config.drawFactory = false;
        }
        if (!config.anyConfigured()
                && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
            disableHook();
            return;
        }

        Object player = runtime.player.get(engine);
        if (relationPlayer != player) {
            relationPlayer = player;
            teamRelations.clear();
            candidatesDirty = true;
        }
        float cameraX = runtime.cameraX.getFloat(engine);
        float cameraY = runtime.cameraY.getFloat(engine);
        float scale = runtime.scale.getFloat(engine);
        float width = host.number(runtime.screenWidth.get(engine));
        float height = host.number(runtime.screenHeight.get(engine));
        boolean needsFrameCandidates = config.drawRanges || config.drawLines || config.drawFactory
                || !manualDrawSet.isEmpty() || !manualLineSet.isEmpty();
        boolean needsUnitRefresh = needsFrameCandidates || config.drawAmmo;
        if (needsUnitRefresh && (candidatesDirty || framesUntilCandidateRefresh-- <= 0)) {
            Object units = runtime.allUnits.invoke(null);
            if (!(units instanceof Iterable)) return;
            Iterable<?> iterable = (Iterable<?>) units;
            if (needsFrameCandidates) refreshCandidates(iterable, config, player);
            else frameCandidates.clear();
            if (config.drawAmmo) ammoDrawing.refresh(iterable, runtime);
            else ammoDrawing.clear();
            candidatesDirty = false;
            framesUntilCandidateRefresh = CANDIDATE_REFRESH_FRAMES;
        } else if (!needsUnitRefresh && (!frameCandidates.isEmpty() || ammoDrawing.hasTracked())) {
            frameCandidates.clear();
            ammoDrawing.clear();
        }
        if (frameCandidates.isEmpty() && !ammoDrawing.hasTracked()) {
            return;
        }

        if (draw.save != null) draw.save.invoke(renderer);
        try {
            for (DrawCandidate candidate : frameCandidates) {
                Object unit = candidate.unit;
                if (runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) continue;
                boolean manualRange = manualDrawSet.contains(unit);
                boolean manualLine = manualLineSet.contains(unit);
                boolean mayRange = !manualHideSet.contains(unit)
                        && (manualRange || config.drawRanges);
                boolean mayLine = !manualLineHideSet.contains(unit)
                        && (manualLine || config.drawLines);
                if (!mayRange && !mayLine
                        && (candidate.kinds & KIND_FACTORY) == 0) continue;

                int relation = candidate.relation;
                int type = candidate.type;
                boolean range = mayRange && (manualRange
                        || host.selected(relation, config.rangePlayers)
                        && (config.rangeTypes & type) != 0);
                boolean line = mayLine && (manualLine
                        || host.selected(relation, config.linePlayers)
                        && (config.lineTypes & type) != 0);
                if (!range && !line
                        && (candidate.kinds & KIND_FACTORY) == 0) continue;

                float worldX = runtime.x.getFloat(unit) - cameraX;
                float worldY = runtime.y.getFloat(unit) - cameraY;
                float x = worldX * scale;
                float y = worldY * scale;
                if (range) rangeDrawing.draw(renderer, draw, config, runtime, unit,
                        relation, x, y, scale, width, height);
                if (line) targetLineDrawing.draw(renderer, draw, config, runtime, unit,
                        relation, x, y, cameraX, cameraY, scale, width, height);
                if ((candidate.kinds & KIND_FACTORY) != 0 && config.drawFactory
                        && factorySelected(relation, config.factoryPlayers)) {
                    factoryDrawing.draw(renderer, draw, config, runtime, unit, relation,
                            worldX, worldY, x, y, scale, width, height);
                }
            }
            if (config.drawAmmo) {
                for (Map.Entry<Object, Integer> entry : ammoDrawing.tracked()) {
                    Object unit = entry.getKey();
                    if (unit == null || runtime.dead.getBoolean(unit)
                            || runtime.attached.get(unit) != null) continue;
                    float x = (runtime.x.getFloat(unit) - cameraX) * scale;
                    float y = (runtime.y.getFloat(unit) - cameraY) * scale;
                    int relation = relation(player, runtime.team.get(unit));
                    ammoDrawing.drawScreen(renderer, draw, config, runtime, unit,
                            entry.getValue(), relation, x, y, width, height);
                }
            }
        } finally {
            if (draw.restore != null) draw.restore.invoke(renderer);
        }
    }

    /** Refresh the expensive global-unit traversal only a few times per second. */
    private void refreshCandidates(Iterable<?> units, DrawConfig config, Object player)
            throws Throwable {
        frameCandidates.clear();
        for (Object unit : units) {
            if (unit == null || !runtime.battleUnit.isInstance(unit)
                    || runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) continue;
            boolean manualRange = manualDrawSet.contains(unit);
            boolean manualLine = manualLineSet.contains(unit);
            boolean mayRange = !manualHideSet.contains(unit)
                    && (manualRange || config.drawRanges);
            boolean mayLine = !manualLineHideSet.contains(unit)
                    && (manualLine || config.drawLines);
            int kinds = 0;
            if (mayRange) kinds |= KIND_RANGE;
            if (mayLine) kinds |= KIND_LINE;
            if (config.drawFactory && runtime.factory != null
                    && runtime.factory.isInstance(unit)) kinds |= KIND_FACTORY;
            if (kinds == 0) continue;
            int relation = relation(player, runtime.team.get(unit));
            int type = (kinds & (KIND_RANGE | KIND_LINE)) != 0 ? movementBit(unit) : 0;
            boolean range = mayRange && (manualRange
                    || host.selected(relation, config.rangePlayers)
                    && (config.rangeTypes & type) != 0);
            boolean line = mayLine && (manualLine
                    || host.selected(relation, config.linePlayers)
                    && (config.lineTypes & type) != 0);
            if (range || line || (kinds & KIND_FACTORY) != 0) {
                frameCandidates.add(new DrawCandidate(unit, relation, type, kinds));
            }
        }
        candidatesDirty = false;
    }

    private boolean factorySelected(int relation, int filter) {
        return filter == DEFAULT_FACTORY_PLAYER_FILTER || relation == filter;
    }

    private int relation(Object player, Object team) {
        if (team == player) return 0;
        Integer cached = teamRelations.get(team);
        if (cached != null) return cached;
        int value = host.relation(player, team);
        if (team != null) teamRelations.put(team, value);
        return value;
    }

    private int movementBit(Object unit) {
        Integer cached = movementBits.get(unit);
        if (cached != null) return cached;
        int type = 1;
        try {
            Object value = runtime.movement.invoke(unit);
            int ordinal = value instanceof Enum ? ((Enum<?>) value).ordinal() : -1;
            switch (ordinal) {
                case 1: case 6: type = 2; break;
                case 3: type = 8; break;
                case 4: case 7: type = 4; break;
                case 5: type = 16; break;
                default: type = 1; break;
            }
        } catch (Throwable ignored) {
        }
        movementBits.put(unit, type);
        return type;
    }

    private DrawConfig drawConfig(Context context) {
        DrawConfig cached = cachedConfig;
        if (cached != null) return cached;
        DrawConfig created = new DrawConfig();
        created.drawRanges = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_ATTACK_RANGE, false);
        created.drawLines = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_TARGET_LINE, false);
        created.rangePlayers = host.prefInt(context, KEY_RANGE_PLAYER_FILTER, 2);
        created.rangeTypes = host.prefInt(context, KEY_RANGE_UNIT_TYPES, 31);
        created.linePlayers = host.prefInt(context, KEY_LINE_PLAYER_FILTER, 2);
        created.lineTypes = host.prefInt(context, KEY_LINE_UNIT_TYPES, 31);
        created.drawAmmo = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_AMMO_COUNT, false);
        created.drawFactory = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_FACTORY_COUNTDOWN, false);
        created.factoryPlayers = host.prefInt(context,
                KEY_FACTORY_PLAYER_FILTER, DEFAULT_FACTORY_PLAYER_FILTER);
        created.selfRange = host.makePaint(host.prefInt(context,
                KEY_RANGE_COLOR_SELF, DEFAULT_RANGE_COLOR_SELF), 1.0f);
        created.enemyRange = host.makePaint(host.prefInt(context,
                KEY_RANGE_COLOR_ENEMY, DEFAULT_RANGE_COLOR_ENEMY), 1.0f);
        created.allyRange = host.makePaint(host.prefInt(context,
                KEY_RANGE_COLOR_ALLY, DEFAULT_RANGE_COLOR_ALLY), 0.5f);
        created.selfLine = host.makePaint(host.prefInt(context,
                KEY_LINE_COLOR_SELF, DEFAULT_LINE_COLOR_SELF), 1.0f);
        created.enemyLine = host.makePaint(host.prefInt(context,
                KEY_LINE_COLOR_ENEMY, DEFAULT_LINE_COLOR_ENEMY), 1.0f);
        created.allyLine = host.makePaint(host.prefInt(context,
                KEY_LINE_COLOR_ALLY, DEFAULT_LINE_COLOR_ALLY), 1.0f);
        created.selfText = textPaint(host.prefInt(context,
                KEY_AMMO_COLOR_SELF, DEFAULT_AMMO_COLOR_SELF));
        created.enemyText = textPaint(host.prefInt(context,
                KEY_AMMO_COLOR_ENEMY, DEFAULT_AMMO_COLOR_ENEMY));
        created.allyText = textPaint(host.prefInt(context,
                KEY_AMMO_COLOR_ALLY, DEFAULT_AMMO_COLOR_ALLY));
        created.selfFactoryText = factoryTextPaint(host.prefInt(context,
                KEY_FACTORY_COLOR_SELF, DEFAULT_FACTORY_COLOR_SELF));
        created.enemyFactoryText = factoryTextPaint(host.prefInt(context,
                KEY_FACTORY_COLOR_ENEMY, DEFAULT_FACTORY_COLOR_ENEMY));
        created.allyFactoryText = factoryTextPaint(host.prefInt(context,
                KEY_FACTORY_COLOR_ALLY, DEFAULT_FACTORY_COLOR_ALLY));
        cachedConfig = created;
        return created;
    }

    private Paint textPaint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(18.0f);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setShadowLayer(3.0f, 0.0f, 0.0f, 0xCC000000);
        return paint;
    }

    private Paint factoryTextPaint(int color) {
        Paint paint = textPaint(color);
        paint.setTextSize(12.0f);
        return paint;
    }

    private static Set<Object> weakSet() {
        return Collections.newSetFromMap(new WeakHashMap<>());
    }

    final class RuntimeAccess {
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        final Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        final Field context = host.findField(engine, "am");
        final Field renderer = host.findField(engine, "bL");
        final Field player = host.findField(engine, "bp");
        final Field cameraX = host.findField(engine, "ct");
        final Field cameraY = host.findField(engine, "cu");
        final Field scale = host.findField(engine, "cU");
        final Field screenWidth = host.findField(engine, "cC");
        final Field screenHeight = host.findField(engine, "cE");
        final Field dead = host.findField(unit, "bX");
        final Field attached = host.findField(unit, "cP");
        final Field team = host.findField(unit, "bZ");
        final Field x = host.findField(unit, "eq");
        final Field y = host.findField(unit, "er");
        final Field target = host.findField(battleUnit, "T");
        final Method range = host.findNoArgMethod(battleUnit, "l");
        final Method movement = host.findNoArgMethod(unit, "g");
        final Class<?> nukeClass = optionalClass("game.units.d.y");
        final Class<?> antiNukeClass = optionalClass("game.units.d.c");
        final Class<?> unitTypeClass = optionalClass("game.units.cj");
        final Method unitType = host.findNoArgMethod(unit, "q");
        final Object nukeType = optionalStaticField(unitTypeClass, "C");
        final Object antiNukeType = optionalStaticField(unitTypeClass, "D");
        final Class<?> factory = optionalClass("game.units.d.s");
        final Class<?> queue = optionalClass("game.units.d.q");
        final Class<?> actionId = optionalClass("game.units.a.c");
        final Class<?> actionBase = optionalClass("game.units.a.s");
        final Field nukeAmmo = optionalField(nukeClass, "c");
        final Field antiNukeAmmo = optionalField(antiNukeClass, "d");
        final Map<Class<?>, Method> unitTypeMethods = new WeakHashMap<>();
        final Map<Class<?>, Method> typeNameMethods = new WeakHashMap<>();
        final Map<Class<?>, Field> nukeAmmoFields = new WeakHashMap<>();
        final Map<Class<?>, Field> antiNukeAmmoFields = new WeakHashMap<>();
        final Field queueProgress = optionalField(queue, "m");
        final Field queueRate = optionalField(queue, "b");
        final Field queueAction = optionalField(queue, "j");
        final Method currentQueue = optionalNoArg(factory, "cX");
        final Method factorySpeed = optionalNoArg(unit, "ca");
        final Method actionForQueue = actionId == null ? null
                : host.findCompatibleMethod(unit, "a", actionId);
        final Method productionAction = optionalNoArg(actionBase, "f");
        final Method allUnits = unit.getDeclaredMethod("bn");

        RuntimeAccess() throws Throwable {
            allUnits.setAccessible(true);
            if (range == null || movement == null) {
                throw new NoSuchMethodException("unit drawing accessors");
            }
        }

        private Class<?> optionalClass(String suffix) {
            try {
                return loader.loadClass(host.target(suffix));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Field optionalField(Class<?> type, String name) {
            if (type == null) return null;
            try {
                return host.findField(type, name);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Method optionalNoArg(Class<?> type, String name) {
            return type == null ? null : host.findNoArgMethod(type, name);
        }

        private Object optionalStaticField(Class<?> type, String name) {
            if (type == null) return null;
            try {
                return host.findField(type, name).get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        int ammoKind(Object value) {
            if (value == null || !battleUnit.isInstance(value)) return 0;
            if (nukeClass != null && nukeClass.isInstance(value)) return 1;
            if (antiNukeClass != null && antiNukeClass.isInstance(value)) return 2;
            try {
                Class<?> valueClass = value.getClass();
                Method method = unitTypeMethods.get(valueClass);
                if (method == null) {
                    method = host.findNoArgMethod(valueClass, "q");
                    if (method != null) unitTypeMethods.put(valueClass, method);
                }
                Object type = method != null ? method.invoke(value)
                        : unitType != null ? unitType.invoke(value) : null;
                if (type == nukeType && nukeType != null) return 1;
                if (type == antiNukeType && antiNukeType != null) return 2;
                String name = nativeTypeName(type);
                if ("NukeLaucher".equalsIgnoreCase(name)
                        || "NukeLauncher".equalsIgnoreCase(name)) return 1;
                if ("AntiNukeLaucher".equalsIgnoreCase(name)
                        || "AntiNukeLauncher".equalsIgnoreCase(name)) return 2;
            } catch (Throwable ignored) {
            }
            return 0;
        }

        private String nativeTypeName(Object type) {
            if (type == null) return "";
            try {
                Class<?> typeClass = type.getClass();
                Method method = typeNameMethods.get(typeClass);
                if (method == null) {
                    method = host.findNoArgMethod(typeClass, "i");
                    if (method != null) typeNameMethods.put(typeClass, method);
                }
                Object result = method == null ? null : method.invoke(type);
                return result instanceof String ? (String) result : type.toString();
            } catch (Throwable ignored) {
                return type.toString();
            }
        }

        int ammoCount(Object value, int kind) throws IllegalAccessException {
            Field field = kind == 1 ? nukeAmmo : kind == 2 ? antiNukeAmmo : null;
            if (field == null || !field.getDeclaringClass().isInstance(value)) {
                Class<?> valueClass = value.getClass();
                Map<Class<?>, Field> fields = kind == 1 ? nukeAmmoFields : antiNukeAmmoFields;
                field = fields.get(valueClass);
                if (field == null) {
                    try {
                        field = host.findField(valueClass, kind == 1 ? "c" : "d");
                        fields.put(valueClass, field);
                    } catch (Throwable ignored) {
                        field = null;
                    }
                }
            }
            return field != null && field.getType() == int.class ? field.getInt(value) : 0;
        }

        float number(Object value) {
            return value instanceof Number ? ((Number) value).floatValue() : 0.0f;
        }
    }

    final class RendererAccess {
        final Class<?> type;
        final Method save;
        final Method restore;
        final Method scale;
        final Method line;
        final Method circle;
        final Method text;
        private final Map<Paint, float[]> multilineMetrics = new WeakHashMap<>();

        RendererAccess(Class<?> type) {
            this.type = type;
            save = host.findNoArgMethod(type, "i");
            restore = host.findNoArgMethod(type, "j");
            scale = host.findCompatibleMethod(type, "a", float.class, float.class);
            line = host.findCompatibleMethod(type, "a",
                    float.class, float.class, float.class, float.class, Paint.class);
            circle = host.findCompatibleMethod(type, "a",
                    float.class, float.class, float.class, Paint.class);
            text = host.findCompatibleMethod(type, "a",
                    String.class, float.class, float.class, Paint.class);
        }

        /** Draw one logical multiline label; the target Canvas backend has no
         * newline-aware drawText overload. */
        void multilineText(Object renderer, String[] lines, float centerX, float centerY,
                           Paint paint, float lineScale) throws Throwable {
            if (lines == null || lines.length == 0) return;
            float[] metrics = multilineMetrics.get(paint);
            if (metrics == null) {
                Paint.FontMetrics fontMetrics = paint.getFontMetrics();
                metrics = new float[]{
                        fontMetrics.descent - fontMetrics.ascent,
                        (fontMetrics.ascent + fontMetrics.descent) * 0.5f
                };
                multilineMetrics.put(paint, metrics);
            }
            float lineHeight = metrics[0] * lineScale;
            float midpoint = metrics[1] * lineScale;
            float firstBaseline = centerY - midpoint - lineHeight * (lines.length - 1) * 0.5f;
            for (int i = 0; i < lines.length; i++) {
                text.invoke(renderer, lines[i], centerX,
                        firstBaseline + lineHeight * i, paint);
            }
        }
    }

    static final class DrawConfig {
        boolean drawRanges;
        boolean drawLines;
        int rangePlayers;
        int rangeTypes;
        int linePlayers;
        int lineTypes;
        boolean drawAmmo;
        boolean drawFactory;
        int factoryPlayers;
        Paint selfRange;
        Paint enemyRange;
        Paint allyRange;
        Paint selfLine;
        Paint enemyLine;
        Paint allyLine;
        Paint selfText;
        Paint enemyText;
        Paint allyText;
        Paint selfFactoryText;
        Paint enemyFactoryText;
        Paint allyFactoryText;
        boolean anyConfigured() {
            return drawRanges || drawLines || drawAmmo || drawFactory;
        }
    }

    private static final class DrawCandidate {
        final Object unit;
        final int relation;
        final int type;
        final int kinds;

        DrawCandidate(Object unit, int relation, int type, int kinds) {
            this.unit = unit;
            this.relation = relation;
            this.type = type;
            this.kinds = kinds;
        }
    }

}
