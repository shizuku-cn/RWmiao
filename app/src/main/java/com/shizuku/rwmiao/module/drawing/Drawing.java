package com.shizuku.rwmiao.module.drawing;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.shizuku.rwmiao.module.RWmiaoModule;
import com.shizuku.rwmiao.module.SimulationLifecycle;
import com.shizuku.rwmiao.module.support.GameFrameDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import static com.shizuku.rwmiao.config.SettingsContract.*;

public final class Drawing {
    private static final int GAME_TIME_MILLIS_PER_SECOND = 1000;
    private static final int KIND_RANGE = 1;
    private static final int KIND_LINE = 1 << 1;
    private static final int KIND_FACTORY = 1 << 2;
    private static final int CANDIDATE_REFRESH_FRAMES = 20;
    private static final String TAG = "RWmiao";
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Set<Long> manualDrawSet = ConcurrentHashMap.newKeySet();
    private final Set<Long> manualHideSet = ConcurrentHashMap.newKeySet();
    private final Set<Long> manualLineSet = ConcurrentHashMap.newKeySet();
    private final Set<Long> manualLineHideSet = ConcurrentHashMap.newKeySet();
    private final Map<Object, Integer> movementBits = new WeakHashMap<>();
    private final Map<Object, Integer> teamRelations = new WeakHashMap<>();
    private final AttackRangeDrawing rangeDrawing;
    private final TargetLineDrawing targetLineDrawing;
    private final FactoryCountdownDrawing factoryDrawing = new FactoryCountdownDrawing();
    private final ArrayList<DrawCandidate> frameCandidates = new ArrayList<>();
    private final Paint gameDurationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Object[] gameDurationDrawArgs = new Object[4];
    private volatile DrawConfig cachedConfig;
    private volatile boolean candidatesDirty = true;
    private int framesUntilCandidateRefresh;
    private RuntimeAccess runtime;
    private RendererAccess rendererAccess;
    private Object relationPlayer;
    private final SimulationLifecycle simulationLifecycle = new SimulationLifecycle();
    private Method drawMethod;
    private GameFrameDispatcher.Registration drawRegistration;
    private int lastGameDurationSecond = -1;
    private String gameDurationText = "0:00";

    public Drawing(RWmiaoModule host, ClassLoader loader) {
        this.host = host;
        this.loader = loader;
        this.rangeDrawing = new AttackRangeDrawing(host);
        this.targetLineDrawing = new TargetLineDrawing();
        gameDurationPaint.setStyle(Paint.Style.FILL);
        gameDurationPaint.setColor(Color.WHITE);
        gameDurationPaint.setTextAlign(Paint.Align.LEFT);
        gameDurationPaint.setTextSize(15.0f);
        gameDurationDrawArgs[1] = 8.0f;
        gameDurationDrawArgs[2] = 16.0f;
        gameDurationDrawArgs[3] = gameDurationPaint;
    }

    public void install() throws Throwable {
        runtime = new RuntimeAccess();
        Class<?> uiRenderer = loader.loadClass(host.target("gameFramework.f.i"));
        drawMethod = host.findCompatibleMethod(uiRenderer, "b", float.class);
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
        factoryDrawing.clear();
        if (worldDrawingConfigured() || !manualDrawSet.isEmpty() || !manualLineSet.isEmpty()) {
            ensureHook();
        } else {
            disableHook();
        }
    }

    public void toggleSelected(boolean range) {
        boolean enable = !allSelectedEnabled(range);
        setSelected(range, enable);
    }

    public void setSelected(boolean range, boolean enable) {
        try {
            for (Object unit : selectedUnits()) {
                if (!hasAttackRange(unit)) continue;
                long id = unitId(unit);
                if (id < 0) continue;
                Set<Long> on = range ? manualDrawSet : manualLineSet;
                Set<Long> off = range ? manualHideSet : manualLineHideSet;
                if (enable) {
                    off.remove(id);
                    on.add(id);
                } else {
                    on.remove(id);
                    off.add(id);
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
                    || preferences.getBoolean(KEY_SHOW_FACTORY_COUNTDOWN, false)
                    || preferences.getBoolean(KEY_SHOW_GAME_DURATION, false);
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
        long id = unitId(unit);
        Set<Long> on = range ? manualDrawSet : manualLineSet;
        Set<Long> off = range ? manualHideSet : manualLineHideSet;
        if (on.contains(id)) return true;
        if (off.contains(id)) return false;
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

    private void drawFrame() throws Throwable {
        DrawConfig known = cachedConfig;
        if (known != null && !known.anyConfigured()
                && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
            disableHook();
            return;
        }
        Object engine = host.findEngine(loader);
        if (engine == null) return;
        SimulationLifecycle.Observation lifecycle = simulationLifecycle.observe(
                runtime.tick.getInt(engine), host.completedResyncGeneration());
        if (lifecycle == SimulationLifecycle.Observation.RESYNC) {
            invalidateWorldCaches();
        } else if (lifecycle == SimulationLifecycle.Observation.NEW_MATCH) {
            clearManualState();
            invalidateWorldCaches();
        }
        Object engineContext = runtime.context.get(engine);
        if (!(engineContext instanceof Context)) return;
        DrawConfig config = drawConfig((Context) engineContext);
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
        if (config.drawFactory && draw.text == null) {
            config.drawFactory = false;
        }
        if (!config.anyConfigured()
                && manualDrawSet.isEmpty() && manualLineSet.isEmpty()) {
            disableHook();
            return;
        }

        if (config.showGameDuration) {
            drawGameDuration(engine, renderer, draw);
        }

        boolean needsFrameCandidates = config.drawRanges || config.drawLines || config.drawFactory
                || !manualDrawSet.isEmpty() || !manualLineSet.isEmpty();
        if (!needsFrameCandidates) {
            if (!frameCandidates.isEmpty()) frameCandidates.clear();
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
        if (needsFrameCandidates && (candidatesDirty || framesUntilCandidateRefresh-- <= 0)) {
            Object allUnits = runtime.allUnits.invoke(null);
            if (!(allUnits instanceof Iterable)) return;
            refreshCandidates((Iterable<?>) allUnits, config, player);
            candidatesDirty = false;
            framesUntilCandidateRefresh = CANDIDATE_REFRESH_FRAMES;
        } else if (!needsFrameCandidates && !frameCandidates.isEmpty()) {
            frameCandidates.clear();
        }

        if (frameCandidates.isEmpty()) {
            return;
        }

        if (draw.save != null) draw.save.invoke(renderer);
        try {
            for (DrawCandidate candidate : frameCandidates) {
                Object unit = candidate.unit;
                if (runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) continue;
                long id = unitId(unit);
                boolean manualRange = manualDrawSet.contains(id);
                boolean manualLine = manualLineSet.contains(id);
                boolean mayRange = !manualHideSet.contains(id)
                        && (manualRange || config.drawRanges);
                boolean mayLine = !manualLineHideSet.contains(id)
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
        } finally {
            if (draw.restore != null) draw.restore.invoke(renderer);
        }
    }

    private void drawGameDuration(Object engine, Object renderer, RendererAccess draw)
            throws Throwable {
        if (!runtime.levelLoaded.getBoolean(engine) || draw.text == null) {
            lastGameDurationSecond = -1;
            return;
        }
        int millis = Math.max(0, runtime.gameTime.getInt(engine));
        int second = millis / GAME_TIME_MILLIS_PER_SECOND;
        if (second != lastGameDurationSecond) {
            lastGameDurationSecond = second;
            gameDurationText = formatGameDuration(second);
            gameDurationDrawArgs[0] = gameDurationText;
        }
        draw.text.invoke(renderer, gameDurationDrawArgs);
    }

    private String formatGameDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes < 60) {
            return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
        }
        int hours = minutes / 60;
        minutes %= 60;
        return hours + ":" + (minutes < 10 ? "0" : "") + minutes
                + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void refreshCandidates(Iterable<?> units, DrawConfig config, Object player)
            throws Throwable {
        frameCandidates.clear();
        for (Object unit : units) {
            if (unit == null || !runtime.battleUnit.isInstance(unit)
                    || runtime.dead.getBoolean(unit) || runtime.attached.get(unit) != null) continue;
            long id = unitId(unit);
            boolean manualRange = manualDrawSet.contains(id);
            boolean manualLine = manualLineSet.contains(id);
            boolean mayRange = !manualHideSet.contains(id)
                    && (manualRange || config.drawRanges);
            boolean mayLine = !manualLineHideSet.contains(id)
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
        created.drawFactory = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_FACTORY_COUNTDOWN, false);
        created.showGameDuration = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_GAME_DURATION, false);
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

    private long unitId(Object unit) {
        if (unit == null || runtime == null) return -1L;
        try { return runtime.id.getLong(unit); }
        catch (Throwable ignored) { return -1L; }
    }

    private void invalidateWorldCaches() {
        frameCandidates.clear();
        movementBits.clear();
        teamRelations.clear();
        relationPlayer = null;
        candidatesDirty = true;
        framesUntilCandidateRefresh = 0;
        factoryDrawing.clear();
    }

    private void clearManualState() {
        manualDrawSet.clear();
        manualHideSet.clear();
        manualLineSet.clear();
        manualLineHideSet.clear();
    }

    final class RuntimeAccess {
        final Class<?> battleUnit = loader.loadClass(host.target("game.units.bp"));
        final Class<?> unit = loader.loadClass(host.target("game.units.ce"));
        final Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        final Field context = host.findField(engine, "am");
        final Field renderer = host.findField(engine, "bL");
        final Field player = host.findField(engine, "bp");
        final Field levelLoaded = host.findField(engine, "bD");
        final Field gameTime = host.findField(engine, "bv");
        final Field tick = host.findField(engine, "bu");
        final Field cameraX = host.findField(engine, "ct");
        final Field cameraY = host.findField(engine, "cu");
        final Field scale = host.findField(engine, "cU");
        final Field screenWidth = host.findField(engine, "cC");
        final Field screenHeight = host.findField(engine, "cE");
        final Field dead = host.findField(unit, "bX");
        final Field id = host.findField(unit, "ej");
        final Field attached = host.findField(unit, "cP");
        final Field team = host.findField(unit, "bZ");
        final Field x = host.findField(unit, "eq");
        final Field y = host.findField(unit, "er");
        final Field target = host.findField(battleUnit, "T");
        final Method range = host.findNoArgMethod(battleUnit, "l");
        final Method movement = host.findNoArgMethod(unit, "g");
        final Class<?> factory = optionalClass("game.units.d.s");
        final Class<?> queue = optionalClass("game.units.d.q");
        final Class<?> actionId = optionalClass("game.units.a.c");
        final Class<?> actionBase = optionalClass("game.units.a.s");
        final Field queueProgress = optionalField(queue, "m");
        final Field queueRate = optionalField(queue, "b");
        final Field queueAction = optionalField(queue, "j");
        final Method currentQueue = optionalNoArg(factory, "cX");
        final Method factorySpeed = optionalNoArg(unit, "ca");
        final Method actionForQueue = actionId == null ? null
                : host.findCompatibleMethod(unit, "a", actionId);
        final Method productionAction = optionalNoArg(actionBase, "f");
        final Method allUnits = host.findCompatibleMethod(unit, "bn");

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
        boolean drawFactory;
        boolean showGameDuration;
        int factoryPlayers;
        Paint selfRange;
        Paint enemyRange;
        Paint allyRange;
        Paint selfLine;
        Paint enemyLine;
        Paint allyLine;
        Paint selfFactoryText;
        Paint enemyFactoryText;
        Paint allyFactoryText;
        boolean anyConfigured() {
            return drawRanges || drawLines || drawFactory || showGameDuration;
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
