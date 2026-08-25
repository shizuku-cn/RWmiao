package com.shizuku.rwmiao.module.script;

import android.util.Log;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** One isolated Lua VM. Lua owns all decisions; Java only publishes snapshots and commands. */
final class LuaProgram {
    private static final int MAX_CALLBACKS = 32;
    private static final int MAX_INSTRUCTIONS = 250_000;
    private static final String[] DATA_GROUPS = {"identity","team","position","health","movement","combat","weapons","orders","pathing","transport","build","production","actions","abilities","damage","selection","map","resources","projectiles","environment","catalog","all"};
    private static final String[] UNIT_FIELDS = {"id","type_id","type_name","building","orderable","team_id","team_name","relation","relation_name","x","y","height","position","heading","radius","health","max_health","health_ratio","health_missing","shield","max_shield","shield_ratio","shield_missing","combined_health_ratio","max_move_speed","real_speed","velocity_x","velocity_y","acceleration","deceleration","turn_speed","moving","movement_type","path_state","path_pending","attack_range","weapon_count","weapons","target_id","current_order","order_target_id","order_x","order_y","waypoint_count","carrier_id","attached","transport_capacity","loaded_unit_ids","build_progress","factory","queue_size","production_items","actions","abilities","buildable_types","last_damaged_tick","visible_to_local","fogged_to_local","explored_to_local","selected","dead","deleted","idle","group_id"};
    private static final String[] COMMANDS = {"move","attack_move","attack","patrol","guard","repair","reclaim","enter","load","build","action","stop"};
    private static final String[] LIFECYCLE = {"finish","exit"};
    final Globals globals;
    final ScriptDefinition definition;
    final BudgetDebugLib budget;
    private final ArrayList<TickHandler> ticks;
    private int consecutiveErrors;

    private static final class TickHandler {
        final int interval;
        final LuaValue function;
        int lastTick = Integer.MIN_VALUE;
        TickHandler(int interval, LuaValue function) { this.interval = interval; this.function = function; }
    }

    private LuaProgram(Globals globals, ScriptDefinition definition, ArrayList<TickHandler> ticks,
                       BudgetDebugLib budget) {
        this.globals = globals; this.definition = definition; this.ticks = ticks; this.budget = budget;
    }

    static LuaProgram compile(String source, String sourceName) throws IOException {
        if (source == null || source.trim().isEmpty()) throw new IOException("空脚本");
        Globals globals = JsePlatform.standardGlobals();
        BudgetDebugLib budget = new BudgetDebugLib();
        globals.load(budget);
        // Remove all host escape surfaces. Scripts retain normal Lua tables, strings and math.
        for (String name : new String[]{"luajava", "io", "os", "package", "debug", "require",
                "dofile", "loadfile", "load"}) globals.set(name, LuaValue.NIL);
        final ScriptDefinition[] metadata = new ScriptDefinition[1];
        ArrayList<TickHandler> handlers = new ArrayList<>();
        LuaTable rw = new LuaTable();
        rw.set("script", new OneArgFunction() {
            @Override public LuaValue call(LuaValue arg) {
                if (!arg.istable()) throw new LuaError("rw.script 需要 table");
                if (metadata[0] != null) throw new LuaError("rw.script 只能声明一次");
                try {
                    LuaValue unitsValue = first(arg, "units", "unit", "self");
                    metadata[0] = new ScriptDefinition(arg.get("api").optint(1),
                            arg.get("id").optjstring(strip(sourceName)),
                            arg.get("name").optjstring(null), strings(unitsValue),
                            strings(arg.get("data")), settings(arg.get("settings")), sourceName);
                } catch (IOException e) { throw new LuaError(e.getMessage()); }
                return LuaValue.NONE;
            }
        });
        rw.set("on_tick", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue interval, LuaValue function) {
                if (!function.isfunction()) throw new LuaError("rw.on_tick(interval, function) 缺少函数");
                if (handlers.size() >= MAX_CALLBACKS) throw new LuaError("回调数量超过 32");
                handlers.add(new TickHandler(Math.max(1, Math.min(600, interval.checkint())), function));
                return LuaValue.NONE;
            }
        });
        rw.set("distance", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue a, LuaValue b) {
                double dx = coord(a, "x") - coord(b, "x"), dy = coord(a, "y") - coord(b, "y");
                return LuaValue.valueOf(Math.sqrt(dx * dx + dy * dy));
            }
        });
        rw.set("away", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue from = args.arg1(), target = args.arg(2); double step = args.checkdouble(3);
                double fx = coord(from, "x"), fy = coord(from, "y");
                double dx = fx - coord(target, "x"), dy = fy - coord(target, "y");
                double length = Math.sqrt(dx * dx + dy * dy);
                if (length < 0.001) { dx = 0; dy = -1; length = 1; }
                return point(fx + dx / length * step, fy + dy / length * step);
            }
        });
        rw.set("around", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue center = args.arg1(); double radius = args.checkdouble(2);
                double angle = Math.toRadians(args.checkdouble(3));
                return point(coord(center, "x") + Math.cos(angle) * radius,
                        coord(center, "y") + Math.sin(angle) * radius);
            }
        });
        rw.set("is_building",new OneArgFunction(){@Override public LuaValue call(LuaValue unit){return LuaValue.valueOf(unit.istable()&&unit.get("building").toboolean());}});
        rw.set("centroid",new OneArgFunction(){@Override public LuaValue call(LuaValue units){double x=0,y=0;int n=0;if(units.istable())for(int i=1;!units.get(i).isnil();i++){x+=coord(units.get(i),"x");y+=coord(units.get(i),"y");n++;}return n==0?LuaValue.NIL:point(x/n,y/n);}});
        rw.set("data_groups", stringTable(DATA_GROUPS));
        rw.set("unit_fields", stringTable(UNIT_FIELDS));
        rw.set("commands", stringTable(COMMANDS));
        rw.set("lifecycle", stringTable(LIFECYCLE));
        LuaTable signatures=new LuaTable();
        signatures.set("move","ctx:move(unit_or_list,x,y,append)");signatures.set("attack_move","ctx:attack_move(unit_or_list,x,y,append)");
        signatures.set("attack","ctx:attack(unit_or_list,target,append)");signatures.set("patrol","ctx:patrol(unit_or_list,x,y,append)");
        signatures.set("guard","ctx:guard(unit_or_list,target,append)");signatures.set("repair","ctx:repair(unit_or_list,target,append)");
        signatures.set("reclaim","ctx:reclaim(unit_or_list,target,append)");signatures.set("enter","ctx:enter(unit_or_list,transport,append)");
        signatures.set("load","ctx:load(transport_or_list,target,append)");signatures.set("build","ctx:build(builder_or_list,type_id,x,y,variant,append)");
        signatures.set("action","ctx:action(unit_or_list,action_id[,x,y,append])");signatures.set("stop","ctx:stop(unit_or_list)");
        signatures.set("finish","ctx:finish([unit_or_list])");
        signatures.set("exit","ctx:exit([local_message])");
        rw.set("command_signatures",signatures);
        LuaTable fieldGroups=new LuaTable();
        putGroup(fieldGroups,"identity","id","type_id","type_name","building","orderable","dead","deleted");
        putGroup(fieldGroups,"team","team_id","team_name","relation","relation_name");
        putGroup(fieldGroups,"position","x","y","height","position","heading","radius");
        putGroup(fieldGroups,"health","health","max_health","health_ratio","health_missing","shield","max_shield","shield_ratio","shield_missing","combined_health_ratio");
        putGroup(fieldGroups,"movement","movement_type","max_move_speed");putGroup(fieldGroups,"combat","attack_range","weapon_count","target_id");
        putGroup(fieldGroups,"movement","real_speed","velocity_x","velocity_y","acceleration","deceleration","turn_speed","moving");
        putGroup(fieldGroups,"weapons","weapons");putGroup(fieldGroups,"pathing","path_state","path_pending");
        putGroup(fieldGroups,"orders","current_order","order_target_id","order_x","order_y","waypoint_count","idle");
        putGroup(fieldGroups,"transport","carrier_id","attached","transport_capacity","loaded_unit_ids");putGroup(fieldGroups,"build","build_progress","factory","queue_size","buildable_types");
        putGroup(fieldGroups,"production","production_items");putGroup(fieldGroups,"actions","actions");putGroup(fieldGroups,"abilities","abilities");
        putGroup(fieldGroups,"damage","last_damaged_tick");putGroup(fieldGroups,"selection","selected");putGroup(fieldGroups,"map","visible_to_local","fogged_to_local","explored_to_local");rw.set("field_groups",fieldGroups);
        rw.set("unit_types", new LuaTable());
        rw.set("catalog", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf("data="+join(DATA_GROUPS)+"\nfields="+join(UNIT_FIELDS)+"\ncommands="+join(COMMANDS)+"\nlifecycle="+join(LIFECYCLE));
            }
        });
        rw.set("log", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                StringBuilder out=new StringBuilder();for(int i=1;i<=args.narg();i++){if(i>1)out.append(' ');out.append(args.arg(i).tojstring());}
                Log.i("RWmiaoLua", out.toString()); return LuaValue.NONE;
            }
        });
        globals.set("rw", rw);
        try { budget.begin(MAX_INSTRUCTIONS); globals.load(source, sourceName == null ? "script.lua" : sourceName).call(); }
        catch (Throwable t) { throw new IOException("Lua 编译/初始化失败: " + message(t), t); }
        finally { budget.end(); }
        if (metadata[0] == null) throw new IOException("缺少 rw.script{api,id,name,units}");
        if (handlers.isEmpty()) throw new IOException("至少注册一个 rw.on_tick(interval, function)");
        return new LuaProgram(globals, metadata[0], handlers, budget);
    }

    void tick(GameSnapshot snapshot, CommandGateway gateway, UnitPolicy policy, Map<String,Object> settings) throws Throwable {
        for (TickHandler handler : ticks) {
            if (handler.lastTick != Integer.MIN_VALUE && snapshot.tick - handler.lastTick < handler.interval) continue;
            handler.lastTick = snapshot.tick;
            gateway.beginCallback();
            LuaTable ctx = context(snapshot, gateway, policy, settings);
            try {
                budget.begin(MAX_INSTRUCTIONS);
                handler.function.call(ctx);
                consecutiveErrors = 0;
            } catch (Throwable t) {
                if (++consecutiveErrors >= 3) throw new IOException("连续 3 次 Lua 异常: " + message(t), t);
                throw t;
            } finally { budget.end(); }
        }
    }

    void resetTickState() { for (TickHandler h : ticks) h.lastTick = Integer.MIN_VALUE; consecutiveErrors = 0; }

    boolean due(int tick) {
        for (TickHandler h : ticks)
            if (h.lastTick == Integer.MIN_VALUE || tick - h.lastTick >= h.interval) return true;
        return false;
    }

    interface UnitPolicy { boolean enabled(UnitSnapshot unit); void finish(long unitId); String group(long unitId); void exit(String localMessage); }

    private LuaTable context(GameSnapshot snapshot, CommandGateway gateway, UnitPolicy policy, Map<String,Object> settings) {
        globals.get("rw").set("unit_types", wants("catalog") ? typeCatalog(snapshot) : new LuaTable());
        java.util.HashMap<Long,LuaTable> unitTables=new java.util.HashMap<>();
        LuaTable ctx = new LuaTable();
        ctx.set("tick", snapshot.tick); ctx.set("multiplayer", LuaValue.valueOf(snapshot.multiplayer));
        ctx.set("local_team", snapshot.localTeamId);
        ctx.set("settings",value(settings));
        ctx.set("map",value(snapshot.map));ctx.set("environment",value(snapshot.environment));
        ctx.set("local_player",value(snapshot.localPlayer));ctx.set("projectiles",value(snapshot.projectiles));
        ctx.set("tile_at",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);return value(snapshot.tileAt((float)coord(p,"x"),(float)coord(p,"y")));}});
        ctx.set("fog_at",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);return value(snapshot.fogAt((float)coord(p,"x"),(float)coord(p,"y")));}});
        ctx.set("is_fogged",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);Object fog=snapshot.fogAt((float)coord(p,"x"),(float)coord(p,"y")).get("fogged");return fog instanceof Boolean?LuaValue.valueOf((Boolean)fog):LuaValue.NIL;}});
        ctx.set("is_visible",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);Object visible=snapshot.fogAt((float)coord(p,"x"),(float)coord(p,"y")).get("visible");return visible instanceof Boolean?LuaValue.valueOf((Boolean)visible):LuaValue.NIL;}});
        ctx.set("path_tile_at",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);String movement=a.arg(b+1).optjstring("LAND");return value(snapshot.pathAt((float)coord(p,"x"),(float)coord(p,"y"),movement));}});
        ctx.set("is_path_passable",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);String movement=a.arg(b+1).optjstring("LAND");Object passable=snapshot.pathAt((float)coord(p,"x"),(float)coord(p,"y"),movement).get("passable");return passable instanceof Boolean?LuaValue.valueOf((Boolean)passable):LuaValue.NIL;}});
        ctx.set("is_passable",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int b=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;LuaValue p=a.arg(b);String movement=a.arg(b+1).optjstring("LAND").toUpperCase();float x=(float)coord(p,"x"),y=(float)coord(p,"y");Object nativePassable=snapshot.pathAt(x,y,movement).get("passable");if(nativePassable instanceof Boolean)return LuaValue.valueOf((Boolean)nativePassable);Map<String,Object> tile=snapshot.tileAt(x,y);if(tile.isEmpty()||Boolean.FALSE.equals(tile.get("in_bounds")))return LuaValue.FALSE;boolean water=Boolean.TRUE.equals(tile.get("water")),bridge=Boolean.TRUE.equals(tile.get("water_bridge")),cliff=Boolean.TRUE.equals(tile.get("cliff")),large=Boolean.TRUE.equals(tile.get("large_cliff_or_trees")),blocked=Boolean.TRUE.equals(tile.get("land_blocked"));boolean ok=movement.contains("AIR")?true:movement.contains("WATER")?water&&!bridge:movement.contains("HOVER")?!large:(!water||bridge)&&!cliff&&!large&&!blocked;return LuaValue.valueOf(ok);}});
        ctx.set("units", queryFunction(snapshot, policy, -1,unitTables));
        ctx.set("self_units", queryFunction(snapshot, policy, 0,unitTables));
        ctx.set("enemies", queryFunction(snapshot, policy, 1,unitTables));
        ctx.set("allies", queryFunction(snapshot, policy, 2,unitTables));
        ctx.set("selected", new VarArgFunction() {
            @Override public Varargs invoke(Varargs ignored) {
                LuaTable result = new LuaTable(); int i = 1;
                for (UnitSnapshot u : snapshot.units) if (u.selected && !u.dead && !u.deleted)
                    result.set(i++, exposedUnit(u,unitTables,policy));
                return result;
            }
        });
        ctx.set("get", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                UnitSnapshot unit = snapshot.get(id(a.arg(a.narg())));
                return unit == null ? LuaValue.NIL : exposedUnit(unit,unitTables,policy);
            }
        });
        ctx.set("nearest_enemy", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int base = a.arg1().istable() && a.arg1().get("tick").isnumber() ? 2 : 1;
                LuaValue from = a.arg(base), filter = a.arg(base + 1);
                double range = filter.istable() ? filter.get("range").optdouble(Double.MAX_VALUE) : Double.MAX_VALUE;
                UnitSnapshot best = null; double bestD = range * range;
                for (UnitSnapshot u : snapshot.units) {
                    if (u.relation != 1 || u.dead || u.deleted || !matchesFilter(u, filter)) continue;
                    double dx = coord(from, "x") - u.x, dy = coord(from, "y") - u.y, d = dx * dx + dy * dy;
                    if (d < bestD || d == bestD && (best == null || u.id < best.id)) { bestD = d; best = u; }
                }
                return best == null ? LuaValue.NIL : exposedUnit(best,unitTables,policy);
            }
        });
        ctx.set("within", new VarArgFunction() {
            @Override public Varargs invoke(Varargs a) {
                int base = a.arg1().istable() && a.arg1().get("tick").isnumber() ? 2 : 1;
                LuaValue center = a.arg(base), filter = a.arg(base + 2);
                double radius = a.checkdouble(base + 1), limit = radius * radius;
                LuaTable result = new LuaTable(); int index = 1;
                for (UnitSnapshot u : snapshot.units) {
                    if (u.dead || u.deleted || !matchesFilter(u, filter)) continue;
                    double dx = coord(center, "x") - u.x, dy = coord(center, "y") - u.y;
                    if (dx * dx + dy * dy <= limit) result.set(index++, exposedUnit(u,unitTables,policy));
                }
                return result;
            }
        });
        ctx.set("groups",new VarArgFunction(){@Override public Varargs invoke(Varargs ignored){
            LinkedHashMap<String,ArrayList<UnitSnapshot>> groups=new LinkedHashMap<>();
            for(UnitSnapshot u:snapshot.units)if(u.relation==0&&!u.dead&&!u.deleted&&definition.acceptsUnit(u.typeId,u.typeName)&&policy.enabled(u)){
                String id=policy.group(u.id);if(id==null)id="single:"+u.id;groups.computeIfAbsent(id,k->new ArrayList<>()).add(u);
            }
            LuaTable out=new LuaTable();int gi=1;for(Map.Entry<String,ArrayList<UnitSnapshot>> entry:groups.entrySet()){
                LuaTable g=new LuaTable(),units=new LuaTable();double x=0,y=0;int ui=1;
                for(UnitSnapshot u:entry.getValue()){units.set(ui++,exposedUnit(u,unitTables,policy));x+=u.x;y+=u.y;}
                g.set("id",entry.getKey());g.set("units",units);g.set("count",entry.getValue().size());g.set("center",point(x/entry.getValue().size(),y/entry.getValue().size()));out.set(gi++,g);
            }return out;
        }});
        ctx.set("group_of",new VarArgFunction(){@Override public Varargs invoke(Varargs a){long unitId=id(a.arg(a.narg()));String group=policy.group(unitId);return group==null?LuaValue.NIL:LuaValue.valueOf(group);}});        command(ctx, "move", a -> gateway.move(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                (float) a.checkdouble(3), (float) a.checkdouble(4), a.optboolean(5, false)));
        command(ctx, "attack_move", a -> gateway.attackMove(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                (float) a.checkdouble(3), (float) a.checkdouble(4), a.optboolean(5, false)));
        command(ctx, "patrol", a -> gateway.patrol(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                (float) a.checkdouble(3), (float) a.checkdouble(4), a.optboolean(5, false)));
        command(ctx, "attack", a -> gateway.attack(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "guard", a -> gateway.guard(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "repair", a -> gateway.repair(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "reclaim", a -> gateway.reclaim(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "enter", a -> gateway.loadInto(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "load", a -> gateway.load(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                id(a.arg(3)), a.optboolean(4, false)));
        command(ctx, "build", a -> gateway.build(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy),
                a.checkjstring(3), (float) a.checkdouble(4), (float) a.checkdouble(5),
                a.optint(6, 1), a.optboolean(7, false)));
        command(ctx, "stop", a -> gateway.stop(definition.id, snapshot, controlledIds(a.arg(2), snapshot, policy)));
        command(ctx, "action", a -> gateway.action(definition.id, snapshot,
                controlledIds(a.arg(2), snapshot, policy), a.checkjstring(3),
                a.arg(4).isnumber() ? (float)a.checkdouble(4) : null,
                a.arg(5).isnumber() ? (float)a.checkdouble(5) : null,
                a.optboolean(6, false)));
        ctx.set("finish",new VarArgFunction(){@Override public Varargs invoke(Varargs a){ArrayList<Long> done=new ArrayList<>();if(a.arg(2).isnil()){for(UnitSnapshot u:snapshot.units)if(u.relation==0&&definition.acceptsUnit(u.typeId,u.typeName)&&policy.enabled(u))done.add(u.id);}else done.addAll(controlledIds(a.arg(2),snapshot,policy));for(Long id:done)policy.finish(id);LuaTable r=new LuaTable();r.set("accepted",LuaValue.TRUE);r.set("finished",done.size());return r;}});
        ctx.set("exit",new VarArgFunction(){@Override public Varargs invoke(Varargs a){int base=a.arg1().istable()&&a.arg1().get("tick").isnumber()?2:1;policy.exit(a.arg(base).optjstring(null));LuaTable r=new LuaTable();r.set("accepted",LuaValue.TRUE);return r;}});
        return ctx;
    }

    private interface CommandCall { CommandGateway.Result call(Varargs args); }
    private static void command(LuaTable ctx, String name, CommandCall call) {
        ctx.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return result(call.call(args)); }
                catch (Throwable t) { return result(CommandGateway.Result.reject(message(t))); }
            }
        });
    }

    private LuaValue queryFunction(GameSnapshot snapshot, UnitPolicy policy, int relation,Map<Long,LuaTable> cache) {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs ignored) {
                LuaTable result = new LuaTable(); int i = 1;
                for (UnitSnapshot u : snapshot.units) {
                    if (u.dead || u.deleted || relation >= 0 && u.relation != relation) continue;
                    if (relation == 0 && (!definition.acceptsUnit(u.typeId, u.typeName) || !policy.enabled(u))) continue;
                    result.set(i++, exposedUnit(u,cache,policy));
                }
                return result;
            }
        };
    }

    private boolean wants(String group){return definition.dataGroups.contains("all")||definition.dataGroups.contains(group);}
    private LuaTable unit(UnitSnapshot u) {
        LuaTable t=new LuaTable();
        set(t,"id",u.id);set(t,"type_id",u.typeId);set(t,"type_name",u.typeName);set(t,"building",u.building);set(t,"orderable",u.orderable);
        set(t,"dead",u.dead);set(t,"deleted",u.deleted);
        if(wants("team")){set(t,"team_id",u.teamId);set(t,"team_name",u.teamName);set(t,"relation",u.relation);set(t,"relation_name",u.relation==0?"self":u.relation==1?"enemy":"ally");}
        if(wants("position")){set(t,"x",u.x);set(t,"y",u.y);set(t,"height",u.height);set(t,"heading",u.heading);set(t,"radius",u.radius);LuaTable p=point(u.x,u.y);p.set("height",u.height);t.set("position",p);}
        if(wants("health")){set(t,"health",u.health);set(t,"max_health",u.maxHealth);set(t,"health_ratio",u.healthRatio());set(t,"shield",u.shield);set(t,"max_shield",u.maxShield);set(t,"shield_ratio",u.shieldRatio());set(t,"combined_health_ratio",u.combinedHealthRatio());set(t,"health_missing",Math.max(0f,u.maxHealth-u.health));set(t,"shield_missing",Math.max(0f,u.maxShield-u.shield));}
        if(wants("selection"))set(t,"selected",u.selected);
        if(wants("transport")){set(t,"attached",u.attached);set(t,"carrier_id",u.carrierId);}
        if(wants("build")||wants("production")){set(t,"factory",u.factory);set(t,"build_progress",u.buildProgress);set(t,"queue_size",u.queueSize);}
        if(wants("movement")||wants("pathing")){set(t,"movement_type",u.movementType);set(t,"max_move_speed",u.maxMoveSpeed);}
        if(wants("combat")||wants("weapons")){set(t,"attack_range",u.attackRange);set(t,"weapon_count",u.weaponCount);set(t,"target_id",u.targetId);}
        if(wants("orders")||wants("pathing")){set(t,"waypoint_count",u.waypointCount);set(t,"current_order",u.currentOrder);set(t,"order_target_id",u.orderTargetId);set(t,"order_x",u.orderX);set(t,"order_y",u.orderY);set(t,"idle",u.currentOrder==null);}
        for(Map.Entry<String,Object> e:u.extras.entrySet())t.set(e.getKey(),value(e.getValue()));
        if(wants("actions")){LuaTable actions=new LuaTable();int ai=1;for(String id:u.actionIds)actions.set(ai++,id);t.set("actions",actions);}
        if(wants("build")){LuaTable builds=new LuaTable();int bi=1;for(String id:u.buildableTypes)builds.set(bi++,id);t.set("buildable_types",builds);}return t;
    }
    private LuaTable cachedUnit(UnitSnapshot u,Map<Long,LuaTable> cache){LuaTable t=cache.get(u.id);if(t==null){t=unit(u);cache.put(u.id,t);}return t;}
    private LuaTable exposedUnit(UnitSnapshot u,Map<Long,LuaTable> cache,UnitPolicy policy){LuaTable t=cachedUnit(u,cache);String group=policy.group(u.id);if(group!=null)t.set("group_id",group);return t;}
    private static boolean matchesFilter(UnitSnapshot u, LuaValue filter) {
        if (!filter.istable()) return true;
        LuaValue relation = filter.get("relation");
        if (!relation.isnil()) {
            String wanted = relation.tojstring();
            if (!("all".equalsIgnoreCase(wanted)
                    || "self".equalsIgnoreCase(wanted) && u.relation == 0
                    || "enemy".equalsIgnoreCase(wanted) && u.relation == 1
                    || "ally".equalsIgnoreCase(wanted) && u.relation == 2)) return false;
        }
        if (!filter.get("orderable").isnil() && filter.get("orderable").toboolean()!=u.orderable) return false;
        if (!filter.get("selected").isnil() && filter.get("selected").toboolean()!=u.selected) return false;
        if (!filter.get("building").isnil() && filter.get("building").toboolean()!=u.building) return false;
        LuaValue types = first(filter, "types", "type", "unit");
        if (types.isnil()) return true;
        for (String type : strings(types)) if ("All".equals(type) || "*".equals(type)
                || type.equals(u.typeId)) return true;
        return false;
    }

    private List<Long> controlledIds(LuaValue value, GameSnapshot snapshot, UnitPolicy policy) {
        ArrayList<Long> accepted = new ArrayList<>();
        for (Long candidate : ids(value)) {
            UnitSnapshot unit = snapshot.get(candidate);
            if (unit != null && unit.relation == 0 && unit.orderable && !unit.dead && !unit.deleted
                    && definition.acceptsUnit(unit.typeId, unit.typeName) && policy.enabled(unit)) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }
    private static List<Long> ids(LuaValue value) {
        if (value.isnumber() || value.istable() && !value.get("id").isnil()) return Collections.singletonList(id(value));
        ArrayList<Long> result = new ArrayList<>();
        if (value.istable()) for (int i=1; !value.get(i).isnil(); i++) result.add(id(value.get(i)));
        return result;
    }
    private static long id(LuaValue value) { return value.istable() ? value.get("id").checklong() : value.checklong(); }
    private static double coord(LuaValue value, String name) {
        LuaValue direct = value.get(name); if (!direct.isnil()) return direct.checkdouble();
        return value.get("position").get(name).checkdouble();
    }
    private static LuaTable point(double x,double y) { LuaTable t=new LuaTable(); t.set("x",x);t.set("y",y);return t; }
    private static LuaValue first(LuaValue table, String... names) { for(String n:names){LuaValue v=table.get(n);if(!v.isnil())return v;}return LuaValue.NIL; }
    private static List<String> strings(LuaValue value) {
        ArrayList<String> out=new ArrayList<>();
        if(value.isstring()) out.add(value.tojstring());
        else if(value.istable()) for(int i=1;!value.get(i).isnil();i++) out.add(value.get(i).checkjstring());
        return out;
    }
    private static List<ScriptSetting> settings(LuaValue value)throws IOException{
        ArrayList<ScriptSetting> out=new ArrayList<>();if(!value.istable())return out;
        for(int i=1;!value.get(i).isnil();i++){
            LuaValue row=value.get(i);if(!row.istable())throw new IOException("settings["+i+"] 必须是 table");
            String declared=row.get("type").optjstring("text").toLowerCase(),component=row.get("component").optjstring(null),type=declared;
            if("switch".equals(declared)){type=ScriptSetting.BOOLEAN;component=ScriptSetting.SWITCH;}
            else if("slider".equals(declared)){type=ScriptSetting.NUMBER;component=ScriptSetting.SLIDER;}
            else if("input".equals(declared)){type=row.get("value_type").optjstring("text").toLowerCase();component=ScriptSetting.INPUT;}
            String def=scalar(row.get("default"));
            Double min=numberOrNull(row.get("min")),max=numberOrNull(row.get("max")),step=numberOrNull(row.get("step"));
            ArrayList<ScriptSetting.Option> options=new ArrayList<>();LuaValue opts=row.get("options");
            if(opts.istable())for(int oi=1;!opts.get(oi).isnil();oi++){
                LuaValue option=opts.get(oi);String ov,ol;if(option.istable()){ov=scalar(option.get("value"));ol=option.get("label").optjstring(ov);}else{ov=scalar(option);ol=ov;}
                if(ov==null)throw new IOException("choice option 缺少 value");options.add(new ScriptSetting.Option(ov,ol));
            }
            out.add(new ScriptSetting(row.get("key").checkjstring(),row.get("name").optjstring(null),row.get("description").optjstring(""),type,component,def,min,max,step,options));
        }return out;
    }
    private static String scalar(LuaValue v){if(v.isnil())return null;if(v.isboolean())return Boolean.toString(v.toboolean());if(v.isnumber())return Double.toString(v.todouble());return v.tojstring();}
    private static Double numberOrNull(LuaValue v){return v.isnumber()?v.todouble():null;}
    private static LuaTable result(CommandGateway.Result r) { LuaTable t=new LuaTable();t.set("accepted",LuaValue.valueOf(r.accepted));if(r.reason!=null)t.set("reason",r.reason);if(r.commandId>=0)t.set("command_id",r.commandId);return t; }
    private static LuaTable stringTable(String[] values){LuaTable t=new LuaTable();for(int i=0;i<values.length;i++)t.set(i+1,values[i]);return t;}
    private static void putGroup(LuaTable table,String group,String...fields){for(String field:fields)table.set(field,group);}
    private static String join(String[] values){StringBuilder b=new StringBuilder();for(String v:values){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
    private static LuaTable typeCatalog(GameSnapshot snapshot){LuaTable out=new LuaTable();for(UnitSnapshot u:snapshot.units){if(out.get(u.typeId).isnil()){LuaTable t=new LuaTable();set(t,"id",u.typeId);set(t,"name",u.typeName);set(t,"custom",u.custom);set(t,"movement_type",u.movementType);set(t,"attack_range",u.attackRange);set(t,"max_move_speed",u.maxMoveSpeed);set(t,"radius",u.radius);set(t,"max_health",u.maxHealth);set(t,"max_shield",u.maxShield);set(t,"weapon_count",u.weaponCount);for(String k:new String[]{"acceleration","deceleration","turn_speed","weapons","abilities","transport_capacity"})if(u.extras.containsKey(k))t.set(k,value(u.extras.get(k)));LuaTable a=new LuaTable();int i=1;for(String v:u.actionIds)a.set(i++,v);t.set("actions",a);LuaTable b=new LuaTable();i=1;for(String v:u.buildableTypes)b.set(i++,v);t.set("buildable_types",b);out.set(u.typeId,t);}}return out;}
    private static void set(LuaTable t,String k,Object v){ if(v==null)return;if(v instanceof String)t.set(k,(String)v);else if(v instanceof Boolean)t.set(k,LuaValue.valueOf((Boolean)v));else if(v instanceof Number)t.set(k,((Number)v).doubleValue()); }
    private static LuaValue value(Object v){
        if(v==null)return LuaValue.NIL;if(v instanceof LuaValue)return (LuaValue)v;
        if(v instanceof String)return LuaValue.valueOf((String)v);if(v instanceof Boolean)return LuaValue.valueOf((Boolean)v);
        if(v instanceof Number)return LuaValue.valueOf(((Number)v).doubleValue());LuaTable t=new LuaTable();
        if(v instanceof Map){for(Object e0:((Map<?,?>)v).entrySet()){Map.Entry<?,?> e=(Map.Entry<?,?>)e0;t.set(String.valueOf(e.getKey()),value(e.getValue()));}return t;}
        if(v instanceof Iterable){int i=1;for(Object x:(Iterable<?>)v)t.set(i++,value(x));return t;}return LuaValue.valueOf(String.valueOf(v));
    }
    private static String strip(String name){if(name==null)return"script";int dot=name.lastIndexOf('.');return dot>0?name.substring(0,dot):name;}
    private static String message(Throwable t){String m=t.getMessage();return m==null?t.getClass().getSimpleName():m;}
}




