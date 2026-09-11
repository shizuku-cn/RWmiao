package com.shizuku.rwmiao.module.script;

import android.graphics.PointF;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String,Map<Long,SteerState>> steering = new HashMap<>();
    private final Map<String,Map<Long,ConstructionIntent>> constructionIntents = new HashMap<>();
    private int issuedThisCallback;

    public static final class Result {
        public final boolean accepted;
        public final String reason;
        public final long commandId;
        public final boolean issued;
        private Result(boolean accepted, String reason, long commandId, boolean issued) {
            this.accepted = accepted; this.reason = reason; this.commandId = commandId; this.issued=issued;
        }
        static Result reject(String reason) { return new Result(false, reason, -1L, false); }
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
    public void clearTransientState(){steering.clear();constructionIntents.clear();issuedThisCallback=0;}
    public Set<String> capabilities() { return adapter.capabilities(); }
    public Map<String,Object> fireSolution(GameSnapshot snapshot,long attackerId,long targetId,int weaponIndex){return adapter.fireSolution(snapshot,attackerId,targetId,weaponIndex);}

    List<UnitSnapshot> repairTargets(GameSnapshot snapshot,UnitSnapshot repairer,String relation,int maxResults){
        ArrayList<UnitSnapshot> out=new ArrayList<>();
        for(UnitSnapshot target:snapshot.repairTargets(repairer,relation,maxResults)){
            Boolean compatible=adapter.nativeRepairCompatible(repairer,target);
            if(!Boolean.FALSE.equals(compatible))out.add(target);
        }
        return out;
    }

    Map<String,Object> constructionCheck(GameSnapshot snapshot,UnitSnapshot builder,UnitSnapshot target,
                                          int samples,float maxApproach,int maxNodes,boolean requireBuildableType,
                                          int candidateOffset,int maxPathChecks){
        Map<String,Object> base=snapshot.constructionCheck(builder,target,samples,maxApproach,maxNodes,
                requireBuildableType,candidateOffset,maxPathChecks);
        Boolean nativeCompatible=adapter.nativeConstructionCompatible(builder,target);
        if(nativeCompatible==null||target==null||builder==null)return base;

        /*
         * The native method is a compatibility check, not a replacement for
         * the route check.  Some game builds expose this overload but return
         * false for an otherwise valid repair target (for example while the
         * construction action is being refreshed).  Treating that transient
         * value as a permanent builder-target failure made the Lua script
         * stop issuing all repair commands after the framework update.
         */
        LinkedHashMap<String,Object> out=new LinkedHashMap<>(base);
        out.put("native_available",true);
        out.put("native_compatible",nativeCompatible);

        /*
         * If the path layer is unavailable, the native check is the only
         * safe capability signal available.  Permit the native command only
         * when that check explicitly accepts the pair; otherwise retain the
         * retryable path-data result instead of permanently blacklisting it.
         */
        if(!Boolean.TRUE.equals(base.get("valid"))
                && "path_data_unavailable".equals(base.get("reason"))
                && nativeCompatible){
            out.put("available",true);
            out.put("valid",true);
            out.put("reachable",true);
            out.put("reason","native_only");
            out.put("retryable",false);
            out.put("definitive",true);
            out.put("ignore_scope","none");
        }
        return out;
    }

    public Result move(String scriptId, GameSnapshot snapshot, List<Long> unitIds,
                       float x, float y, boolean append) {
        return pointCommand(scriptId, snapshot, unitIds, "move", "a", x, y, append);
    }

    public Result steer(String scriptId,GameSnapshot snapshot,List<Long> unitIds,float x,float y,
                        float deadband,int refreshTicks){
        List<UnitSnapshot> units=owned(snapshot,unitIds);
        if(units.isEmpty())return Result.reject("没有可控制的己方单位");
        float threshold=Math.max(0f,Math.min(200f,deadband)),limit=threshold*threshold;
        int refresh=Math.max(1,Math.min(600,refreshTicks));
        Map<Long,SteerState> script=steering.computeIfAbsent(scriptId,ignored->new HashMap<>());
        ArrayList<Long> due=new ArrayList<>();
        for(UnitSnapshot unit:units){
            SteerState old=script.get(unit.id);int age=old==null?Integer.MAX_VALUE:snapshot.tick-old.tick;
            float dx=old==null?Float.MAX_VALUE:x-old.x,dy=old==null?Float.MAX_VALUE:y-old.y;
            if(old==null||age<0||age>=refresh||dx*dx+dy*dy>limit)due.add(unit.id);
        }
        if(due.isEmpty())return new Result(true,null,-1L,false);
        Result result=pointCommand(scriptId,snapshot,due,"move","a",x,y,false);
        if(result.accepted)for(Long id:due)script.put(id,new SteerState(x,y,snapshot.tick));
        return result;
    }

    public Result moveKeepTarget(String scriptId,GameSnapshot snapshot,List<Long> unitIds,float x,float y,
                                 long targetId,float deadband,int refreshTicks){
        UnitSnapshot target=snapshot.get(targetId);
        if(target==null||target.dead||target.deleted)return Result.reject("目标不存在");
        return steer(scriptId,snapshot,unitIds,x,y,deadband,refreshTicks);
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

    public Result assistBuild(String scriptId,GameSnapshot snapshot,List<Long> unitIds,long targetId,
                              int refreshTicks,boolean respectPlayer){
        UnitSnapshot target=snapshot==null?null:snapshot.get(targetId);
        if(target==null||target.dead||target.deleted||!target.building)return Result.reject("目标不是有效建筑");
        if(target.buildProgress>=1.0f)return Result.reject("目标已经完成");
        if(target.relation!=0&&target.relation!=1)return Result.reject("只能协助己方或盟友建筑");
        List<UnitSnapshot> units=owned(snapshot,unitIds);if(units.isEmpty())return Result.reject("没有可控制的己方单位");
        int refresh=Math.max(1,Math.min(600,refreshTicks));Map<Long,ConstructionIntent> script=
                constructionIntents.computeIfAbsent(scriptId,ignored->new HashMap<>());
        ArrayList<Long> due=new ArrayList<>();boolean playerBlocked=false;
        for(UnitSnapshot unit:units){
            String source=text(unit.extras.get("order_source")),intent=text(unit.extras.get("intent_type"));
            if(respectPlayer&&"player".equals(source)&&intent!=null&&!"idle".equals(intent)){
                playerBlocked=true;continue;
            }
            if(sameConstructionIntent(unit,targetId)){script.put(unit.id,new ConstructionIntent(targetId,snapshot.tick));continue;}
            ConstructionIntent previous=script.get(unit.id);int age=previous==null?Integer.MAX_VALUE:snapshot.tick-previous.tick;
            if(previous!=null&&previous.targetId==targetId&&age>=0&&age<refresh)continue;
            due.add(unit.id);
        }
        if(due.isEmpty())return playerBlocked?Result.reject("玩家指令正在执行"):
                new Result(true,"same_intent",-1L,false);
        Result result=targetCommand(scriptId,snapshot,due,targetId,"repair","b",false);
        if(result.accepted)for(Long id:due)script.put(id,new ConstructionIntent(targetId,snapshot.tick));
        return result;
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
            Result result=accepted();adapter.recordScriptCommand(scriptId,units,"build",null,x,y,result.commandId);return result;
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
        return new Result(true, null, last, last>=0);
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
            Result result=accepted();adapter.recordScriptCommand(scriptId,units,"action",null,x,y,result.commandId);return result;
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
            Result result=accepted();adapter.recordScriptCommand(scriptId,units,type,null,x,y,result.commandId);return result;
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
            Result result=accepted();adapter.recordScriptCommand(scriptId,units,type,targetId,null,null,result.commandId);return result;
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
        return new Result(true, null, ids.incrementAndGet(),true);
    }

    private static boolean sameConstructionIntent(UnitSnapshot unit,long targetId){
        Long observed=unit.orderTargetId;Object raw=unit.extras.get("intent_target_id");
        if(raw instanceof Number)observed=((Number)raw).longValue();
        String intent=text(unit.extras.get("intent_type"));
        return observed!=null&&observed==targetId&&("repair".equals(intent)||"build".equals(intent)
                ||Boolean.TRUE.equals(unit.extras.get("is_building_order")));
    }

    private static String text(Object value){return value==null?null:String.valueOf(value);}

    private void setBoolean(Object object, String name, boolean value) throws Throwable {
        Field field = host.findField(object.getClass(), name);
        if (field.getType() == boolean.class) field.setBoolean(object, value);
    }

    private Object invokeNoArg(Object object, String name) throws Throwable {
        Method method = host.findNoArgMethod(object.getClass(), name);
        return method == null ? null : method.invoke(object);
    }

    private Method exact(Class<?> type, String name, Class<?>... parameters) {
        Method method = host.findExactCompatibleMethod(type, name, parameters);
        return method != null ? method : host.findCompatibleMethod(type, name, parameters);
    }
    private static final class SteerState{final float x,y;final int tick;SteerState(float x,float y,int tick){this.x=x;this.y=y;this.tick=tick;}}
    private static final class ConstructionIntent{final long targetId;final int tick;ConstructionIntent(long targetId,int tick){this.targetId=targetId;this.tick=tick;}}
}
