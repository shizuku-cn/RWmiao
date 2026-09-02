package com.shizuku.rwmiao.module.script;

import android.graphics.PointF;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;

public final class CommandGateway {
    private static final int MAX_COMMANDS_PER_CALLBACK = 32;
    private static final int MAX_UNITS_PER_COMMAND = 256;
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final GameAdapter adapter;
    private final Method createCommand;
    private final Method resolveUnitType;
    private final Class<?> actionIdClass;
    private final Method resolveActionId;
    private final AtomicLong ids = new AtomicLong();
    private int issuedThisCallback;

    public static final class Result {
        public final boolean accepted;
        public final String reason;
        public final long commandId;
        private Result(boolean accepted, String reason, long commandId) {
            this.accepted = accepted; this.reason = reason; this.commandId = commandId;
        }
        static Result reject(String reason) { return new Result(false, reason, -1L); }
    }

    public CommandGateway(RWmiaoModule host, ClassLoader loader, GameAdapter adapter) throws Throwable {
        this.host = host; this.loader = loader; this.adapter = adapter;
        Class<?> input = loader.loadClass(host.target("gameFramework.f.i"));
        createCommand = exact(input, "g");
        if (createCommand == null || !Modifier.isStatic(createCommand.getModifiers())) {
            throw new NoSuchMethodException("native command factory i.g()");
        }
        Class<?> builtInTypes = loader.loadClass(host.target("game.units.cj"));
        resolveUnitType = exact(builtInTypes, "a", String.class);
        actionIdClass = loader.loadClass(host.target("game.units.a.c"));
        resolveActionId = exact(actionIdClass, "a", String.class);
    }

    public void beginCallback() { issuedThisCallback = 0; }
    public Set<String> capabilities() { return adapter.capabilities(); }

    public Result move(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                       float x, float y, boolean append) {
        return pointCommand(scriptId, snapshot, unitIds, "move", "a", x, y, append);
    }

    public Result attackMove(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                             float x, float y, boolean append) {
        return pointCommand(scriptId, snapshot, unitIds, "attack_move", "b", x, y, append);
    }

    public Result patrol(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                         float x, float y, boolean append) {
        return pointCommand(scriptId, snapshot, unitIds, "patrol", "c", x, y, append);
    }

    public Result attack(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                         long targetId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, targetId, "attack", "a", append);
    }

    public Result repair(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                         long targetId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, targetId, "repair", "b", append);
    }

    public Result guard(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                        long targetId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, targetId, "guard", "c", append);
    }

    public Result reclaim(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                          long targetId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, targetId, "reclaim", "d", append);
    }

    public Result loadInto(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                           long transportId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, transportId, "load_into", "e", append);
    }

    public Result load(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                       long targetId, boolean append) {
        return targetCommand(scriptId, snapshot, unitIds, targetId, "load", "f", append);
    }

    public Result build(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                        String typeId, float x, float y, int variant, boolean append) {
        Object buildType = null;
        try {
            if (resolveUnitType != null && Modifier.isStatic(resolveUnitType.getModifiers()))
                buildType = resolveUnitType.invoke(null, typeId);
        } catch (Throwable ignored) { }
        for (UnitSnapshot unit : snapshot.units) {
            if (buildType != null) break;
            if (typeId.equals(unit.typeId)) {
                try { buildType = invokeNoArg(unit.raw, "q"); } catch (Throwable ignored) { }
                if (buildType != null) break;
            }
        }
        if (buildType == null) return Result.reject("未知建造类型: " + typeId);
        List<UnitSnapshot> units = owned(snapshot, unitIds);
        if (units.isEmpty()) return Result.reject("没有可控制的己方单位");
        try {
            Object command = newCommand(append);
            Method setter = exact(command.getClass(), "a", float.class, float.class,
                    loader.loadClass(host.target("game.units.el")), int.class);
            if (setter == null) return Result.reject("当前版本不支持 build");
            setter.invoke(command, x, y, buildType, variant);
            attach(command, units);
            return accepted();
        } catch (Throwable t) { return Result.reject("build 失败: " + t.getClass().getSimpleName()); }
    }

    public Result stop(String scriptId, GameSnapshot snapshot, List<Long> unitIds) {
        List<UnitSnapshot> units = owned(snapshot, unitIds);
        if (units.isEmpty()) return Result.reject("没有可控制的己方单位");
        long last = -1L;
        for (UnitSnapshot unit : units) {
            Result result = move(scriptId, snapshot, java.util.Collections.singletonList(unit.id),
                    unit.x, unit.y, false);
            if (!result.accepted) return result;
            last = result.commandId;
        }
        return new Result(true, null, last);
    }

    public Result action(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                         String actionId, Float x, Float y, boolean append) {
        List<UnitSnapshot> units = owned(snapshot, unitIds);
        if (units.isEmpty()) return Result.reject("没有可控制的己方单位");
        boolean supported = false;
        for (UnitSnapshot unit : units) for (String available : unit.actionIds)
            if (available.equals(actionId)) { supported = true; break; }
        if (!supported) return Result.reject("单位不提供 Action ID: " + actionId);
        try {
            Object id = resolveActionId.invoke(null, actionId);
            Object command = newCommand(append);
            Method setter = exact(command.getClass(), "a", actionIdClass, PointF.class);
            if (setter == null) return Result.reject("当前版本不支持 action");
            PointF point = x == null || y == null ? null : new PointF(x, y);
            setter.invoke(command, id, point);
            attach(command, units);
            return accepted();
        } catch (Throwable t) { return Result.reject("action 失败: " + t.getClass().getSimpleName()); }
    }

    private Result pointCommand(String scriptId, GameSnapshot snapshot, List<Long> ids,
                                String type, String setterName, float x, float y, boolean append) {
        List<UnitSnapshot> units = owned(snapshot, ids);
        if (units.isEmpty()) return Result.reject("没有可控制的己方单位");
        if (!Float.isFinite(x) || !Float.isFinite(y)) return Result.reject("坐标无效");
        try {
            Object command = newCommand(append);
            Method setter = exact(command.getClass(), setterName, float.class, float.class);
            if (setter == null) return Result.reject("当前版本不支持 " + type);
            setter.invoke(command, x, y);
            attach(command, units);
            return accepted();
        } catch (Throwable t) { return Result.reject(type + " 失败: " + t.getClass().getSimpleName()); }
    }

    private Result targetCommand(String scriptId, GameSnapshot snapshot, List<Long> ids,
                                 long targetId, String type, String setterName, boolean append) {
        List<UnitSnapshot> units = owned(snapshot, ids);
        UnitSnapshot target = snapshot.get(targetId);
        if (units.isEmpty()) return Result.reject("没有可控制的己方单位");
        if (target == null || target.dead || target.deleted) return Result.reject("目标不存在");
        try {
            Object command = newCommand(append);
            Method setter = exact(command.getClass(), setterName, adapter.unitClass());
            if (setter == null) return Result.reject("当前版本不支持 " + type);
            setter.invoke(command, target.raw);
            attach(command, units);
            return accepted();
        } catch (Throwable t) { return Result.reject(type + " 失败: " + t.getClass().getSimpleName()); }
    }

    private Object newCommand(boolean append) throws Throwable {
        if (++issuedThisCallback > MAX_COMMANDS_PER_CALLBACK) throw new IllegalStateException("命令配额已用完");
        Object command = createCommand.invoke(null);
        if (command == null) throw new IllegalStateException("原生命令不可用");
        setBoolean(command, "e", append);
        setBoolean(command, "h", true);
        return command;
    }

    private void attach(Object command, List<UnitSnapshot> units) throws Throwable {
        Method add = exact(command.getClass(), "a", adapter.orderableClass());
        if (add == null) throw new NoSuchMethodException("command.addUnit");
        for (UnitSnapshot unit : units) add.invoke(command, unit.raw);
    }

    private List<UnitSnapshot> owned(GameSnapshot snapshot, List<Long> ids) {
        ArrayList<UnitSnapshot> result = new ArrayList<>();
        if (snapshot == null || ids == null || ids.isEmpty() || ids.size() > MAX_UNITS_PER_COMMAND) return result;
        for (Long id : ids) {
            UnitSnapshot unit = id == null ? null : snapshot.get(id);
            if (unit == null || unit.relation != 0 || !unit.orderable || unit.dead || unit.deleted) continue;
            result.add(unit);
        }
        return result;
    }

    private Result accepted() {
        return new Result(true, null, ids.incrementAndGet());
    }

    private void setBoolean(Object object, String name, boolean value) throws Throwable {
        Field field = host.findField(object.getClass(), name);
        if (field.getType() == boolean.class) field.setBoolean(object, value);
    }

    private Object invokeNoArg(Object object, String name) throws Throwable {
        Method method = host.findNoArgMethod(object.getClass(), name);
        return method == null ? null : method.invoke(object);
    }

    private static Method exact(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
}
