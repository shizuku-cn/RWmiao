package com.shizuku.rwmiao.module.script;

import com.shizuku.rwmiao.module.RWmiaoModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class GameAdapter {
    private final RWmiaoModule host;
    private final ClassLoader loader;
    private final Class<?> unitClass;
    private final Class<?> orderableClass;
    private final Class<?> customClass;
    private final Class<?> factoryClass;
    private final Field allUnits;
    private final Field localPlayer;
    private final Field tick;
    private final Field gameNetwork;
    private final Method deliverLocalMessage;
    private final Class<?> projectileClass;
    private final Field allProjectiles;
    private final Map<Long,Float> previousDurability = new HashMap<>();
    private final Map<Long,Integer> lastDamagedTick = new HashMap<>();
    private final Map<Long,Float> lastDamageAmount = new HashMap<>();
    private final Map<Long,Long> lastDamageSource = new HashMap<>();
    private Object snapshotMap;
    private Object snapshotEngine;
    private int snapshotTick=-1;
    private final Map<String,PathLayer> pathLayerCache = new HashMap<>();
    private final Set<String> capabilities = new LinkedHashSet<>();
    private final Map<Class<?>, Map<String, Field>> fieldCache = new HashMap<>();
    private final Map<Class<?>, Map<String, Method>> noArgMethodCache = new HashMap<>();
    private final Map<String,Method> argumentMethodCache = new HashMap<>();
    private final Map<String,ActionFacts> actionFactsCache = new HashMap<>();
    private final Map<Long,ObservedOrder> observedOrders = new HashMap<>();
    private final Map<Long,ArrayList<IssuedOrder>> pendingScriptOrders = new HashMap<>();
    private final Map<Long,Map<String,Object>> lastPlayerOrders = new HashMap<>();
    private final Map<Long,Long> orderRevisions = new HashMap<>();

    public GameAdapter(RWmiaoModule host, ClassLoader loader) throws Throwable {
        this.host = host;
        this.loader = loader;
        unitClass = loader.loadClass(host.target("game.units.ce"));
        orderableClass = loader.loadClass(host.target("game.units.bp"));
        customClass = optionalClass(host.target("game.units.custom.j"));
        factoryClass = optionalClass(host.target("game.units.d.s"));
        Class<?> engine = loader.loadClass(host.target("gameFramework.k"));
        localPlayer = host.findField(engine, "bp");
        tick = host.findField(engine, "bu");
        Field networkField=null;Method delivery=null;
        try{Class<?> networkClass=loader.loadClass(host.target("gameFramework.j.ae"));Class<?> connectionClass=loader.loadClass(host.target("gameFramework.j.c"));networkField=host.findField(engine,"bU");delivery=host.findCompatibleMethod(networkClass,"a",connectionClass,int.class,String.class,String.class);}catch(Throwable ignored){}
        gameNetwork=networkField;deliverLocalMessage=delivery;
        Field registry;
        try { registry = host.findField(unitClass, "bG"); }
        catch (Throwable ignored) {
            registry = host.findField(loader.loadClass(host.target("gameFramework.ah")), "et");
        }
        allUnits = registry;
        projectileClass = optionalClass(host.target("game.f"));
        Field projectileRegistry = null;
        if (projectileClass != null) try { projectileRegistry = host.findField(projectileClass, "a"); } catch(Throwable ignored) {}
        allProjectiles = projectileRegistry;
        Collections.addAll(capabilities, "units.read.all", "unit.health", "unit.shield",
                "unit.position", "unit.heading", "unit.target", "unit.orders",
                "unit.type", "unit.team", "unit.selection", "unit.build_progress",
                "unit.weapons", "unit.movement.physics", "unit.pathing", "unit.path_queue", "unit.order_intent",
                "unit.movement.trajectory", "map.dynamic_route", "map.threat_retreat", "map.info",
                "map.visibility", "team.resources", "unit.production", "unit.transport",
                "projectiles.read", "unit.damage_history", "unit.damage_source",
                "unit.abilities", "unit.ability_cooldown", "unit.production.details",
                "team.resources.all", "map.tile_metadata", "map.path_search",
                "map.path_clearance", "game.environment");
    }

    public GameSnapshot snapshot() throws Throwable {
        return snapshot(Collections.singleton("all"));
    }

    public GameSnapshot snapshot(Set<String> groups) throws Throwable {
        Object engine = host.findEngine(loader);
        if (engine == null) return new GameSnapshot(-1, false, -1, new ArrayList<>());
        Object me = localPlayer.get(engine);
        snapshotEngine=engine;snapshotMap=fieldValue(engine,"bI");pathLayerCache.clear();
        int localTeamId = integer(fieldValue(me, "l"), -1);
        int currentTick = integer(tick.get(engine), -1);
        snapshotTick=currentTick;
        ArrayList<UnitSnapshot> result = new ArrayList<>();
        Object registry = Modifier.isStatic(allUnits.getModifiers()) ? allUnits.get(null) : allUnits.get(engine);
        if (registry instanceof Iterable) {
            for (Object raw : (Iterable<?>) registry) {
                if (raw != null && unitClass.isInstance(raw)) {
                    UnitSnapshot unit = snapshotUnit(raw, me, groups, actionFactsCache);
                    if (unit != null) result.add(unit);
                }
            }
        }
        boolean all=groups==null||groups.contains("all");
        if(all||groups.contains("damage")){LinkedHashSet<Long> alive=new LinkedHashSet<>();for(UnitSnapshot u:result)alive.add(u.id);previousDurability.keySet().retainAll(alive);lastDamagedTick.keySet().retainAll(alive);lastDamageAmount.keySet().retainAll(alive);lastDamageSource.keySet().retainAll(alive);}
        if(all||groups.contains("orders")||groups.contains("pathing")){LinkedHashSet<Long> alive=new LinkedHashSet<>();for(UnitSnapshot u:result)alive.add(u.id);observedOrders.keySet().retainAll(alive);pendingScriptOrders.keySet().retainAll(alive);lastPlayerOrders.keySet().retainAll(alive);orderRevisions.keySet().retainAll(alive);}
        List<Map<String,Object>> projectiles=all||groups.contains("projectiles")?projectileFacts(engine):Collections.emptyList();
        return new GameSnapshot(currentTick, multiplayer(engine), localTeamId, result,
                all||groups.contains("map")?mapFacts(engine, me):Collections.emptyMap(),
                all||groups.contains("environment")?environmentFacts(engine,result):Collections.emptyMap(),
                all||groups.contains("resources")?teamFacts(me, result,engine):Collections.emptyMap(),
                projectiles,
                all||groups.contains("map")?(x,y)->tileFacts(me,x,y):null,
                all||groups.contains("map")?(x,y)->fogFacts(me,x,y):null,
                all||groups.contains("map")||groups.contains("pathing")?(x,y,movement)->pathFacts(x,y,movement):null);
    }

    public Object rawUnit(GameSnapshot snapshot, long id) {
        UnitSnapshot unit = snapshot == null ? null : snapshot.get(id);
        return unit == null ? null : unit.raw;
    }

    public boolean isOrderable(Object raw) { return raw != null && orderableClass.isInstance(raw); }
    public boolean isOwnedOrderable(Object raw) {
        try {
            if (!isOrderable(raw) || bool(fieldValue(raw,"bX")) || bool(fieldValue(raw,"el"))) return false;
            Object engine=host.findEngine(loader), me=engine==null?null:localPlayer.get(engine);
            return me!=null && host.relation(me,fieldValue(raw,"bZ"))==0;
        } catch(Throwable ignored) { return false; }
    }
    public Class<?> unitClass() { return unitClass; }
    public Class<?> orderableClass() { return orderableClass; }
    public Set<String> capabilities() { return Collections.unmodifiableSet(capabilities); }
    public void clearTransientState(){previousDurability.clear();lastDamagedTick.clear();lastDamageAmount.clear();lastDamageSource.clear();observedOrders.clear();pendingScriptOrders.clear();lastPlayerOrders.clear();orderRevisions.clear();snapshotMap=null;snapshotEngine=null;snapshotTick=-1;pathLayerCache.clear();}

    void recordScriptCommand(String scriptId,List<UnitSnapshot> units,String type,Long targetId,
                             Float x,Float y,long commandId){
        int issuedTick=currentTick();
        for(UnitSnapshot unit:units){
            ArrayList<IssuedOrder> pending=pendingScriptOrders.computeIfAbsent(unit.id,ignored->new ArrayList<>());
            pending.add(new IssuedOrder(scriptId,normalizeOrder(type),targetId,x,y,commandId,issuedTick));
            while(pending.size()>8)pending.remove(0);
        }
    }

    Map<String,Object> fireSolution(GameSnapshot snapshot,long attackerId,long targetId,int requestedWeapon){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        UnitSnapshot attacker=snapshot==null?null:snapshot.get(attackerId),target=snapshot==null?null:snapshot.get(targetId);
        boolean available=attacker!=null&&target!=null&&attacker.orderable&&!attacker.dead&&!attacker.deleted&&!target.dead&&!target.deleted;
        out.put("available",available);if(!available)return out;
        int weaponIndex=requestedWeapon<=0?1:requestedWeapon;
        float range=attacker.attackRange;Boolean ready=null;float remaining=0f;
        Object rawWeapons=attacker.extras.get("weapons");
        if(rawWeapons instanceof List){
            List<?> weapons=(List<?>)rawWeapons;
            if(requestedWeapon<=0){
                float best=-1f;int bestIndex=1;
                for(int i=0;i<weapons.size();i++){Object row=weapons.get(i);if(!(row instanceof Map))continue;Number value=number(((Map<?,?>)row).get("range"));if(value!=null&&value.floatValue()>best){best=value.floatValue();bestIndex=i+1;}}
                weaponIndex=bestIndex;
            }
            if(weaponIndex>=1&&weaponIndex<=weapons.size()&&weapons.get(weaponIndex-1) instanceof Map){
                Map<?,?> weapon=(Map<?,?>)weapons.get(weaponIndex-1);Number value=number(weapon.get("range"));if(value!=null)range=value.floatValue();
                Object readyValue=weapon.get("ready");if(readyValue instanceof Boolean)ready=(Boolean)readyValue;
                Number reload=number(weapon.get("reload_remaining")),warmup=number(weapon.get("warmup"));
                if(reload!=null)remaining=Math.max(remaining,reload.floatValue());if(warmup!=null)remaining=Math.max(remaining,warmup.floatValue());
            }
        }
        float dx=attacker.x-target.x,dy=attacker.y-target.y,distance=(float)Math.sqrt(dx*dx+dy*dy),margin=range-distance;
        Boolean targetable=null;Object nativeValue=invoke(attacker.raw,"a",new Class[]{unitClass,int.class,boolean.class},target.raw,weaponIndex-1,false);
        if(nativeValue instanceof Boolean)targetable=(Boolean)nativeValue;
        out.put("weapon_index",weaponIndex);out.put("range",range);out.put("distance",distance);out.put("margin",margin);
        out.put("reload_remaining",remaining);if(ready!=null)out.put("ready",ready);if(targetable!=null)out.put("targetable",targetable);
        out.put("can_fire",margin>=0f&&(targetable==null||targetable)&&(ready==null||ready)&&remaining<=0f);
        out.put("time_until_fire",Math.max(0f,remaining));return out;
    }

    public void localMessage(String message){
        if(message==null||message.trim().isEmpty()||gameNetwork==null||deliverLocalMessage==null)return;
        try{Object engine=host.findEngine(loader),me=engine==null?null:localPlayer.get(engine),network=engine==null?null:gameNetwork.get(engine);if(me==null||network==null)return;String text=message.replace('\n',' ').replace('\r',' ').trim();if(text.length()>240)text=text.substring(0,240);deliverLocalMessage.invoke(network,null,integer(fieldValue(me,"l"),-1),"RWmiao",text);}catch(Throwable ignored){}
    }

    public int currentTick() {
        try {
            Object engine = host.findEngine(loader);
            return engine == null ? -1 : integer(tick.get(engine), -1);
        } catch (Throwable ignored) { return -1; }
    }

    public boolean matchesType(Object raw, ScriptDefinition definition) {
        if (raw == null || !unitClass.isInstance(raw)) return false;
        Object type = invokeNoArg(raw, "q");
        String id = typeText(type, "i"), name = typeText(type, "e");
        if (id == null && type != null) id = String.valueOf(type);
        return definition.acceptsUnit(id, name == null ? id : name);
    }

    public UnitSnapshot snapshotOne(Object raw) {
        try {
            if (raw == null || !unitClass.isInstance(raw)) return null;
            Object engine = host.findEngine(loader);
            Object me = engine == null ? null : localPlayer.get(engine);
            return snapshotUnit(raw, me, Collections.singleton("all"), new HashMap<>());
        } catch (Throwable ignored) { return null; }
    }

    public List<UnitSnapshot> selectedOwnedSameType(Object raw) {
        ArrayList<UnitSnapshot> out=new ArrayList<>();
        try{
            if(!isOwnedOrderable(raw))return out;Object wanted=invokeNoArg(raw,"q");String wantedId=typeText(wanted,"i");
            Object engine=host.findEngine(loader),me=engine==null?null:localPlayer.get(engine);
            Object registry=Modifier.isStatic(allUnits.getModifiers())?allUnits.get(null):allUnits.get(engine);
            if(registry instanceof Iterable)for(Object candidate:(Iterable<?>)registry){
                if(!isOrderable(candidate)||bool(fieldValue(candidate,"bX"))||bool(fieldValue(candidate,"el"))
                        ||host.relation(me,fieldValue(candidate,"bZ"))!=0||!bool(fieldValue(candidate,"cI")))continue;
                String id=typeText(invokeNoArg(candidate,"q"),"i");if(wantedId==null||!wantedId.equals(id))continue;
                UnitSnapshot u=snapshotUnit(candidate,me,new LinkedHashSet<>(java.util.Arrays.asList("identity","selection")),new HashMap<>());
                if(u!=null&&!u.dead&&!u.deleted)out.add(u);
            }
            if(out.isEmpty()){UnitSnapshot one=snapshotOne(raw);if(one!=null)out.add(one);}
        }catch(Throwable ignored){}return out;
    }

    private UnitSnapshot snapshotUnit(Object raw, Object me, Set<String> groups,
                                      Map<String, ActionFacts> actionFactsCache) {
        try {
            boolean all=groups==null||groups.contains("all"), catalog=groups!=null&&groups.contains("catalog"), pathing=all||groups.contains("pathing"), position=all||catalog||groups.contains("position")||groups.contains("map"),
                    health=all||catalog||groups.contains("health")||groups.contains("damage"), selection=all||groups.contains("selection"),
                    combat=all||catalog||groups.contains("combat")||groups.contains("weapons"),
                    orders=all||groups.contains("orders")||groups.contains("pathing"), movement=all||groups.contains("movement")||groups.contains("pathing"),
                    build=all||groups.contains("build")||groups.contains("production"), actions=all||groups.contains("actions")||groups.contains("abilities")||build||groups.contains("production");
            movement=movement||catalog;actions=actions||catalog;
            Object team = fieldValue(raw, "bZ");
            int relation=host.relation(me,team);
            Object target = combat && orderableClass.isInstance(raw) ? fieldValue(raw, "T") : null;
            Object carrier = (all||groups.contains("transport")) ? fieldValue(raw, "cP") : null;
            Object type = invokeNoArg(raw, "q");
            String typeId = typeText(type, "i");
            String typeName = typeText(type, "e");
            if (typeId == null) typeId = type == null ? raw.getClass().getSimpleName() : type.toString();
            if (typeName == null) typeName = typeId;
            Object movementType = movement ? invokeNoArg(raw, "g") : null;
            float moveSpeed = movement && orderableClass.isInstance(raw) ? decimal(invokeNoArg(raw, "y"), 0f) : 0f;
            float range = combat && orderableClass.isInstance(raw) ? decimal(invokeNoArg(raw, "l"), 0f) : 0f;
            int weaponCount = combat && orderableClass.isInstance(raw) ? integer(invokeNoArg(raw, "aU"), 0) : 0;
            int waypointCount = orders && orderableClass.isInstance(raw) ? integer(fieldValue(raw, "O"), 0) : 0;
            Object order = orders && orderableClass.isInstance(raw) ? invokeNoArg(raw, "ap") : null;
            Object orderKind = fieldValue(order, "a");
            Object orderTarget = fieldValue(order, "h");
            boolean factory = build && factoryClass != null && factoryClass.isInstance(raw);
            boolean building=bool(invokeNoArg(raw,"bq"));
            int queueSize = factory ? integer(invokeNoArg(raw, "cW"), 0) : 0;
            ArrayList<String> actionIds = new ArrayList<>(), buildableTypes = new ArrayList<>();
            Map<String,Map<String,Object>> actionDetails = Collections.emptyMap();
            if(actions){ActionFacts facts=actionFactsCache.get(typeId);if(facts==null){facts=new ActionFacts();collectActions(raw,facts);actionFactsCache.put(typeId,facts);}actionIds.addAll(facts.actionIds);buildableTypes.addAll(facts.buildableTypes);actionDetails=facts.details;}
            LinkedHashMap<String,Object> extras=new LinkedHashMap<>();
            if(actions&&!actionDetails.isEmpty())extras.put("action_details",new ArrayList<>(actionDetails.values()));
            if(movement) collectMovement(raw,extras,moveSpeed,waypointCount,order);
            long unitId=longValue(fieldValue(raw,"ej"),-1L);
            Float orderX=order==null?null:decimalObject(fieldValue(order,"e"));
            Float orderY=order==null?null:decimalObject(fieldValue(order,"f"));
            Long orderTargetId=objectId(orderTarget);
            if(orders)collectOrderFacts(unitId,relation,orderKind==null?null:String.valueOf(orderKind),orderTargetId,orderX,orderY,
                    position?decimal(fieldValue(raw,"eq"),0f):0f,position?decimal(fieldValue(raw,"er"),0f):0f,extras);
            if(pathing)collectPathFacts(raw,waypointCount,order,orderX,orderY,extras);
            if(all||catalog||groups.contains("weapons")) collectWeapons(raw,weaponCount,range,extras);
            if(all||groups.contains("abilities")) collectAbilityDetails(raw,extras);
            if(all||groups.contains("transport")) collectTransport(raw,extras);
            if(build||all||groups.contains("production")) collectProduction(raw,extras,typeId,actionFactsCache);
            if(all||groups.contains("damage")){float durability=decimal(fieldValue(raw,"cw"),0f)+decimal(fieldValue(raw,"cz"),0f);
                Float old=previousDurability.put(unitId,durability);if(old!=null&&durability+0.001f<old){lastDamagedTick.put(unitId,integer(tickValue(),-1));lastDamageAmount.put(unitId,old-durability);Long source=findRecentDamageSource(unitId);if(source!=null)lastDamageSource.put(unitId,source);}
                if(lastDamagedTick.containsKey(unitId))extras.put("last_damaged_tick",lastDamagedTick.get(unitId));if(lastDamageAmount.containsKey(unitId))extras.put("last_damage_amount",lastDamageAmount.get(unitId));if(lastDamageSource.containsKey(unitId))extras.put("last_damage_source_id",lastDamageSource.get(unitId));}
            if(all||groups.contains("map")){Integer fog=fogValueAt(me,decimal(fieldValue(raw,"eq"),0f),decimal(fieldValue(raw,"er"),0f));if(fog!=null){extras.put("visible_to_local",fog<5);extras.put("fogged_to_local",fog>=5);extras.put("explored_to_local",fog<10);}}
            return new UnitSnapshot(
                    longValue(fieldValue(raw, "ej"), -1L), typeId, typeName,
                    integer(fieldValue(team, "l"), -1), string(fieldValue(team, "w")),
                    relation,
                    position?decimal(fieldValue(raw, "eq"), 0f):0f, position?decimal(fieldValue(raw, "er"), 0f):0f,
                    position?decimal(fieldValue(raw, "es"), 0f):0f, position?decimal(fieldValue(raw, "ci"), 0f):0f,
                    position?decimal(fieldValue(raw, "cl"), 0f):0f, health?decimal(fieldValue(raw, "cw"), 0f):0f,
                    health?decimal(fieldValue(raw, "cx"), 0f):0f, health?decimal(fieldValue(raw, "cz"), 0f):0f,
                    health?decimal(fieldValue(raw, "cC"), 0f):0f, build?decimal(fieldValue(raw, "co"), 1f):1f,
                    bool(fieldValue(raw, "bX")), bool(fieldValue(raw, "el")),
                    selection&&bool(fieldValue(raw, "cI")), carrier != null, orderableClass.isInstance(raw),
                    customClass != null && customClass.isInstance(raw), factory, building,
                    movementType == null ? null : String.valueOf(movementType), range, weaponCount,
                    waypointCount, queueSize, objectId(target), objectId(carrier),
                    orderKind == null ? null : String.valueOf(orderKind), orderTargetId,
                    orderX, orderY, moveSpeed,
                    actionIds, buildableTypes, extras, raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ActionFacts { final ArrayList<String> actionIds=new ArrayList<>(); final ArrayList<String> buildableTypes=new ArrayList<>(); final LinkedHashMap<String,Map<String,Object>> details=new LinkedHashMap<>(); }

    private void collectActions(Object raw, ActionFacts facts) {
        Object actions = invokeNoArg(raw, "N");
        if (!(actions instanceof Iterable)) return;
        for (Object action : (Iterable<?>) actions) {
            Object actionId = invokeNoArg(action, "z");
            Object value = fieldValue(actionId, "b");
            if (value != null) {
                String id=String.valueOf(value);facts.actionIds.add(id);
                LinkedHashMap<String,Object> detail=new LinkedHashMap<>();detail.put("id",id);
                Object name=invokeNoArg(action,"b"),description=invokeNoArg(action,"a");if(name!=null)detail.put("name",String.valueOf(name));if(description!=null)detail.put("description",String.valueOf(description));putNumber(detail,"cost",invokeNoArg(action,"c"));facts.details.put(id,detail);
            }
            Object buildType = invokeNoArg(action, "h");
            String type = typeText(buildType, "i");
            if (type != null && !facts.buildableTypes.contains(type)) facts.buildableTypes.add(type);
            if(value!=null&&type!=null){Map<String,Object> detail=facts.details.get(String.valueOf(value));if(detail!=null){detail.put("type_id",type);String name=typeText(buildType,"e");if(name!=null)detail.put("type_name",name);}}
        }
    }

    private void collectMovement(Object raw, Map<String,Object> out, float maxSpeed, int waypoints, Object order) {
        if (!orderableClass.isInstance(raw)) return;
        float vx=decimal(fieldValue(raw,"ce"),0f), vy=decimal(fieldValue(raw,"cf"),0f);
        float factor=decimal(fieldValue(raw,"ch"),0f);
        out.put("velocity_x",vx); out.put("velocity_y",vy);
        out.put("real_speed",Math.max((float)Math.sqrt(vx*vx+vy*vy),Math.abs(maxSpeed*factor)));
        out.put("throttle",factor);
        out.put("speed_ratio",maxSpeed>0f?Math.min(1f,Math.abs(factor)):0f);
        putNumber(out,"acceleration",invokeNoArg(raw,"A"));
        putNumber(out,"deceleration",invokeNoArg(raw,"B"));
        putNumber(out,"turn_speed",invokeNoArg(raw,"z"));
        out.put("moving",Math.abs(vx)+Math.abs(vy)+Math.abs(factor)>0.0001f);
        out.put("path_pending",waypoints>0||order!=null);
        out.put("path_state",order==null?"idle":waypoints>0?"following":"commanded");
    }

    private void collectPathFacts(Object raw,int waypointCount,Object current,Float targetX,Float targetY,
                                  Map<String,Object> out){
        ArrayList<Map<String,Object>> points=new ArrayList<>();
        Object queue=fieldValue(raw,"Q");
        if(queue!=null&&queue.getClass().isArray()){
            int count=Math.max(0,Math.min(16,Math.min(waypointCount,Array.getLength(queue))));
            for(int i=0;i<count;i++){
                Object order=Array.get(queue,i);Float x=decimalObject(fieldValue(order,"e")),y=decimalObject(fieldValue(order,"f"));
                if(x==null||y==null)continue;
                LinkedHashMap<String,Object> point=new LinkedHashMap<>();point.put("x",x);point.put("y",y);point.put("index",i+1);
                String kind=String.valueOf(fieldValue(order,"a"));if(order!=null)point.put("order",kind);
                Long id=objectId(fieldValue(order,"h"));if(id!=null)point.put("target_id",id);points.add(point);
            }
        }
        if(points.isEmpty()&&targetX!=null&&targetY!=null){LinkedHashMap<String,Object> point=new LinkedHashMap<>();point.put("x",targetX);point.put("y",targetY);point.put("index",1);points.add(point);}
        out.put("path_available",!points.isEmpty());out.put("path_points",points);
        if(!points.isEmpty()){
            Map<String,Object> next=points.get(0),last=points.get(points.size()-1);
            out.put("path_next_x",next.get("x"));out.put("path_next_y",next.get("y"));
            out.put("next_waypoint_x",next.get("x"));out.put("next_waypoint_y",next.get("y"));
            out.put("path_target_x",last.get("x"));out.put("path_target_y",last.get("y"));
        }
    }

    private void collectOrderFacts(long unitId,int relation,String kind,Long targetId,Float x,Float y,
                                   float unitX,float unitY,Map<String,Object> out){
        int now=snapshotTick;String normalized=normalizeOrder(kind);String signature=orderSignature(normalized,targetId,x,y);
        ObservedOrder previous=observedOrders.get(unitId);boolean changed=previous==null||!signature.equals(previous.signature);
        String source=previous==null?"game":previous.source,scriptId=previous==null?null:previous.scriptId;
        Long commandId=previous==null?null:previous.commandId;int issuedTick=previous==null?now:previous.issuedTick;
        if(changed){
            IssuedOrder issued=matchIssued(unitId,normalized,targetId,x,y,now);
            if(issued!=null){source="script";scriptId=issued.scriptId;commandId=issued.commandId;issuedTick=issued.tick;}
            else{boolean interruptedScript=kind==null&&previous!=null&&"script".equals(previous.source)
                    &&previous.x!=null&&previous.y!=null&&(previous.x-unitX)*(previous.x-unitX)+(previous.y-unitY)*(previous.y-unitY)>400f;
                source=relation==0&&(kind!=null||interruptedScript)?"player":"game";scriptId=null;commandId=null;issuedTick=now;}
            long revision=orderRevisions.getOrDefault(unitId,0L)+1L;orderRevisions.put(unitId,revision);
            if("player".equals(source)){
                LinkedHashMap<String,Object> player=new LinkedHashMap<>();player.put("type",kind);player.put("revision",revision);player.put("issued_tick",issuedTick);
                if(targetId!=null)player.put("target_id",targetId);if(x!=null)player.put("x",x);if(y!=null)player.put("y",y);lastPlayerOrders.put(unitId,player);
            }
        }
        long revision=orderRevisions.getOrDefault(unitId,0L);
        observedOrders.put(unitId,new ObservedOrder(signature,source,scriptId,commandId,issuedTick,x,y));
        out.put("order_revision",revision);out.put("order_changed",changed);out.put("order_source",source);out.put("order_issued_tick",issuedTick);
        if(commandId!=null)out.put("order_command_id",commandId);if(scriptId!=null)out.put("order_script_id",scriptId);
        Map<String,Object> lastPlayer=lastPlayerOrders.get(unitId);if(lastPlayer!=null)out.put("last_player_order",new LinkedHashMap<>(lastPlayer));
        if(x!=null&&y!=null){out.put("move_destination_x",x);out.put("move_destination_y",y);out.put("next_waypoint_x",x);out.put("next_waypoint_y",y);out.put("desired_heading",(float)Math.toDegrees(Math.atan2(y-unitY,x-unitX)));}
        if(targetId!=null&&normalized.contains("attack"))out.put("chasing_target_id",targetId);
        String intent=intentType(normalized);
        out.put("intent_type",intent);out.put("intent_target_id",targetId);out.put("intent_target_role",intentRole(intent));
        boolean chasing="chase".equals(intent),protecting="protect".equals(intent),building="build".equals(intent),reclaiming="reclaim".equals(intent);
        out.put("is_chasing",chasing&&targetId!=null);out.put("chase_target_id",chasing?targetId:null);
        out.put("is_protecting",protecting&&targetId!=null);out.put("protect_target_id",protecting?targetId:null);
        out.put("is_building_order",building);out.put("build_target_id",building?targetId:null);
        out.put("is_reclaiming",reclaiming&&targetId!=null);out.put("reclaim_target_id",reclaiming?targetId:null);
    }

    private static String intentType(String normalized){
        if(normalized==null||normalized.isEmpty())return "idle";
        if(normalized.contains("attack")||normalized.contains("chase")||normalized.contains("follow"))return "chase";
        if(normalized.contains("guard")||normalized.contains("protect")||normalized.contains("defend"))return "protect";
        if(normalized.contains("build")||normalized.contains("construct"))return "build";
        if(normalized.contains("reclaim")||normalized.contains("salvage"))return "reclaim";
        if(normalized.contains("repair"))return "repair";
        if(normalized.contains("patrol"))return "patrol";
        if(normalized.contains("move")||normalized.contains("waypoint"))return "move";
        if(normalized.contains("stop"))return "stop";
        return normalized;
    }
    private static String intentRole(String intent){
        if("chase".equals(intent))return "attack";if("protect".equals(intent))return "guard";
        if("build".equals(intent))return "construct";if("reclaim".equals(intent))return "reclaim";
        if("repair".equals(intent))return "repair";return intent;
    }

    private IssuedOrder matchIssued(long unitId,String kind,Long targetId,Float x,Float y,int now){
        ArrayList<IssuedOrder> pending=pendingScriptOrders.get(unitId);if(pending==null)return null;IssuedOrder matched=null;
        for(int i=pending.size()-1;i>=0;i--){IssuedOrder issued=pending.get(i);if(now-issued.tick>120){pending.remove(i);continue;}if(issued.matches(kind,targetId,x,y)){matched=issued;pending.remove(i);break;}}
        if(pending.isEmpty())pendingScriptOrders.remove(unitId);return matched;
    }

    private static String normalizeOrder(String value){if(value==null)return"";String lower=value.toLowerCase(java.util.Locale.ROOT);StringBuilder out=new StringBuilder(lower.length());for(int i=0;i<lower.length();i++){char c=lower.charAt(i);if(c>='a'&&c<='z')out.append(c);}return out.toString();}
    private static String orderSignature(String kind,Long targetId,Float x,Float y){return kind+'|'+String.valueOf(targetId)+'|'+quantize(x)+'|'+quantize(y);}
    private static int quantize(Float value){return value==null?Integer.MIN_VALUE:Math.round(value*2f);}

    private static final class IssuedOrder{
        final String scriptId,type;final Long targetId;final Float x,y;final long commandId;final int tick;
        IssuedOrder(String scriptId,String type,Long targetId,Float x,Float y,long commandId,int tick){this.scriptId=scriptId;this.type=type;this.targetId=targetId;this.x=x;this.y=y;this.commandId=commandId;this.tick=tick;}
        boolean matches(String kind,Long observedTarget,Float observedX,Float observedY){if(!type.equals(kind))return false;if(targetId!=null||observedTarget!=null)return targetId!=null&&targetId.equals(observedTarget);if(x==null||y==null||observedX==null||observedY==null)return true;float dx=x-observedX,dy=y-observedY;return dx*dx+dy*dy<=64f;}
    }
    private static final class ObservedOrder{
        final String signature,source,scriptId;final Long commandId;final int issuedTick;final Float x,y;
        ObservedOrder(String signature,String source,String scriptId,Long commandId,int issuedTick,Float x,Float y){this.signature=signature;this.source=source;this.scriptId=scriptId;this.commandId=commandId;this.issuedTick=issuedTick;this.x=x;this.y=y;}
    }

    private void collectWeapons(Object raw, int count, float defaultRange, Map<String,Object> out) {
        ArrayList<Map<String,Object>> slots=new ArrayList<>();
        Object array=fieldValue(raw,"cN");
        int n=array!=null&&array.getClass().isArray()?Array.getLength(array):count;
        for(int i=0;i<n;i++){
            Object slot=array!=null&&array.getClass().isArray()&&i<Array.getLength(array)?Array.get(array,i):null;
            LinkedHashMap<String,Object> w=new LinkedHashMap<>(); w.put("index",i+1);
            Number override=number(invoke(raw,"v",new Class[]{int.class},i));
            w.put("range",override!=null&&override.floatValue()>=0?override.floatValue():defaultRange);
            putNumber(w,"turret_heading",fieldValue(slot,"a"));
            Long target=objectId(fieldValue(slot,"j")); if(target!=null)w.put("target_id",target);
            putNumber(w,"reload_remaining",fieldValue(slot,"d"));
            putNumber(w,"warmup",fieldValue(slot,"e"));
            putNumber(w,"cooldown",invoke(raw,"b",new Class[]{int.class},i));
            Object ready=invokeNoArg(slot,"a"); if(ready instanceof Boolean)w.put("ready",ready);
            slots.add(w);
        }
        out.put("weapons",slots);
    }

    private void collectAbilityDetails(Object raw, Map<String,Object> out) {
        ArrayList<Map<String,Object>> details=new ArrayList<>(); Object actions=invokeNoArg(raw,"N");
        if(actions instanceof Iterable) for(Object action:(Iterable<?>)actions){
            LinkedHashMap<String,Object> a=new LinkedHashMap<>(); Object aid=invokeNoArg(action,"z");
            Object id=fieldValue(aid,"b"); if(id!=null)a.put("id",String.valueOf(id));
            Object name=invokeNoArg(action,"b"), desc=invokeNoArg(action,"a");
            if(name!=null)a.put("name",String.valueOf(name)); if(desc!=null)a.put("description",String.valueOf(desc));
            putNumber(a,"cost",invokeNoArg(action,"c"));
            Object available=invoke(action,"a",new Class[]{unitClass,boolean.class},raw,false);
            if(!(available instanceof Boolean))available=invoke(action,"a",new Class[]{unitClass},raw);
            if(available instanceof Boolean)a.put("executable",available);
            Object locked=invoke(action,"b",new Class[]{unitClass},raw); if(locked instanceof Boolean)a.put("locked",locked);
            putNumber(a,"cooldown",invoke(action,"b",new Class[]{unitClass,boolean.class},raw,false));
            putNumber(a,"cooldown_remaining",invoke(action,"b",new Class[]{unitClass,boolean.class},raw,true));
            details.add(a);
        }
        out.put("abilities",details);
    }

    private void collectTransport(Object raw, Map<String,Object> out) {
        Object slots=fieldValue(raw,"aL"); ArrayList<Long> ids=new ArrayList<>();
        if(slots!=null&&slots.getClass().isArray())for(int i=0;i<Array.getLength(slots);i++){Long id=objectId(Array.get(slots,i));if(id!=null)ids.add(id);}
        out.put("loaded_unit_ids",ids); out.put("transport_capacity",slots!=null&&slots.getClass().isArray()?Array.getLength(slots):0);
    }
    private void collectProduction(Object raw,Map<String,Object> out,String typeId,Map<String,ActionFacts> actionFactsCache){
        ArrayList<Map<String,Object>> items=new ArrayList<>();if(factoryClass!=null&&factoryClass.isInstance(raw)){
            Object queue=invokeNoArg(raw,"cY");if(queue instanceof Iterable)for(Object q:(Iterable<?>)queue){LinkedHashMap<String,Object> item=new LinkedHashMap<>();
                putNumber(item,"count",fieldValue(q,"a"));putNumber(item,"progress",fieldValue(q,"b"));putNumber(item,"rate",fieldValue(q,"m"));Number progress=number(fieldValue(q,"b")),rate=number(fieldValue(q,"m"));if(progress!=null&&rate!=null&&rate.doubleValue()>0)item.put("remaining_ticks",Math.max(0d,(1d-progress.doubleValue())/rate.doubleValue()));
                Object actionId=fieldValue(q,"j"),id=fieldValue(actionId,"b");if(id!=null)item.put("action_id",String.valueOf(id));
                Object type=fieldValue(q,"g");String builtTypeId=typeText(type,"i");if(builtTypeId!=null)item.put("type_id",builtTypeId);
                if(id!=null){ActionFacts facts=actionFactsCache.get(typeId);Map<String,Object> detail=facts==null?null:facts.details.get(String.valueOf(id));if(detail!=null){for(String key:new String[]{"name","description","cost","type_name"})if(detail.containsKey(key))item.put(key,detail.get(key));}}
                Long target=objectId(fieldValue(q,"i"));if(target!=null)item.put("target_id",target);items.add(item);}}
        out.put("production_items",items);
    }

    private Map<String,Object> mapFacts(Object engine,Object me){
        LinkedHashMap<String,Object> m=new LinkedHashMap<>(); Object map=fieldValue(engine,"bI"); if(map==null)return m;
        int w=integer(fieldValue(map,"D"),0),h=integer(fieldValue(map,"E"),0);
        float tw=decimal(fieldValue(map,"n"),0),th=decimal(fieldValue(map,"o"),0);
        m.put("width_tiles",w);m.put("height_tiles",h);m.put("tile_width",tw);m.put("tile_height",th);
        m.put("width",w*tw);m.put("height",h*th);m.put("visibility_grid_available",fieldValue(me,"Q")!=null);
        return m;
    }
    private Integer fogValueAt(Object team,float x,float y){
        Object fog=fieldValue(team,"Q");if(fog==null||!fog.getClass().isArray()||snapshotMap==null)return null;
        float tw=decimal(fieldValue(snapshotMap,"n"),0),th=decimal(fieldValue(snapshotMap,"o"),0);if(tw<=0||th<=0)return null;
        int tx=(int)(x/tw),ty=(int)(y/th);try{if(tx<0||tx>=Array.getLength(fog))return null;Object col=Array.get(fog,tx);if(col==null||ty<0||ty>=Array.getLength(col))return null;Object value=Array.get(col,ty);return value instanceof Number?((Number)value).intValue():null;}catch(Throwable ignored){return null;}
    }
    private Map<String,Object> fogFacts(Object team,float x,float y){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();if(snapshotMap==null)return out;
        float tw=decimal(fieldValue(snapshotMap,"n"),0),th=decimal(fieldValue(snapshotMap,"o"),0);
        int tx=tw>0?(int)(x/tw):-1,ty=th>0?(int)(y/th):-1,w=integer(fieldValue(snapshotMap,"D"),0),h=integer(fieldValue(snapshotMap,"E"),0);
        out.put("tile_x",tx);out.put("tile_y",ty);boolean inside=tx>=0&&ty>=0&&tx<w&&ty<h;out.put("in_bounds",inside);if(!inside)return out;
        Integer value=fogValueAt(team,x,y);out.put("available",value!=null);if(value!=null){out.put("value",value);out.put("visible",value<5);out.put("fogged",value>=5);out.put("explored",value<10);out.put("unexplored",value>=10);}return out;
    }
    private Map<String,Object> tileFacts(Object team,float x,float y){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();if(snapshotMap==null)return out;
        int tw=integer(fieldValue(snapshotMap,"n"),0),th=integer(fieldValue(snapshotMap,"o"),0);
        int tx=tw>0?(int)(x/tw):-1,ty=th>0?(int)(y/th):-1,w=integer(fieldValue(snapshotMap,"D"),0),h=integer(fieldValue(snapshotMap,"E"),0);
        out.put("tile_x",tx);out.put("tile_y",ty);boolean inside=tx>=0&&ty>=0&&tx<w&&ty<h;out.put("in_bounds",inside);if(!inside)return out;
        Object layer=fieldValue(snapshotMap,"u"),tile=invoke(layer,"a",new Class[]{int.class,int.class},tx,ty);
        if(tile!=null){
            Object tileId=firstMetadata(tile,"id","tileId","tile_id","index","typeId","type_id");Object tileName=firstMetadata(tile,"name","tileName","tile_name","displayName","display_name");if(tileId!=null)out.put("tile_id",tileId);if(tileName!=null)out.put("tile_name",String.valueOf(tileName));
            out.put("water",bool(fieldValue(tile,"e")));out.put("water_bridge",bool(fieldValue(tile,"f")));out.put("lava",bool(fieldValue(tile,"g")));
            out.put("cliff",bool(fieldValue(tile,"h")));out.put("resource_pool",bool(fieldValue(tile,"i")));out.put("large_cliff_or_trees",bool(fieldValue(tile,"k")));
            out.put("blocks_buildings",bool(fieldValue(tile,"l")));Number block=number(fieldValue(tile,"j"));out.put("land_blocked",block!=null&&block.intValue()<0);}
        out.putAll(fogFacts(team,x,y));return out;
    }

    private Object firstMetadata(Object object,String... names){for(String name:names){Object value=fieldValue(object,name);if(value==null)value=invokeNoArg(object,name);if(value instanceof String||value instanceof Number)return value;}return null;}
    private Map<String,Object> pathFacts(float x,float y,String movement){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();String kind=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);out.put("movement_type",kind);
        if(snapshotMap==null)return out;float scale=decimal(fieldValue(snapshotMap,"r"),0f);if(scale<=0f)return out;
        if("AIR".equals(kind)){int w=integer(fieldValue(snapshotMap,"D"),0),h=integer(fieldValue(snapshotMap,"E"),0),gx=(int)(x*scale),gy=(int)(y*scale);out.put("grid_x",gx);out.put("grid_y",gy);out.put("width",w);out.put("height",h);out.put("world_to_grid",scale);boolean inside=gx>=0&&gy>=0&&gx<w&&gy<h;out.put("in_bounds",inside);out.put("available",true);out.put("passable",inside);return out;}
        PathLayer layer=pathLayerCache.get(kind);if(layer==null){layer=resolvePathLayer(kind,scale);if(layer!=null)pathLayerCache.put(kind,layer);}if(layer==null){out.put("available",false);return out;}
        int gx=(int)(x*layer.scale),gy=(int)(y*layer.scale);out.put("grid_x",gx);out.put("grid_y",gy);out.put("width",layer.width);out.put("height",layer.height);out.put("world_to_grid",layer.scale);boolean inside=gx>=0&&gy>=0&&gx<layer.width&&gy<layer.height;out.put("in_bounds",inside);out.put("available",true);if(!inside){out.put("passable",false);return out;}
        int index=layer.height*gx+gy;int terrain=layer.terrain[index],buildings=layer.buildings[index],objects=layer.objects[index];out.put("terrain_cost",terrain);out.put("building_cost",buildings);out.put("object_cost",objects);out.put("terrain_blocked",terrain==-1);out.put("building_blocked",buildings==-1);out.put("object_blocked",objects==-1);out.put("passable",terrain!=-1&&buildings!=-1&&objects!=-1);return out;
    }
    private PathLayer resolvePathLayer(String kind,float scale){
        try{Object pathEngine=fieldValue(snapshotEngine,"bR");Class<?> movement=loader.loadClass(host.target("game.units.cg"));Object constant=null;Object[] values=movement.getEnumConstants();if(values!=null)for(Object value:values)if(kind.equals(String.valueOf(value))){constant=value;break;}if(pathEngine==null||constant==null)return null;Method getter=host.findCompatibleMethod(pathEngine.getClass(),"a",movement);if(getter==null)return null;Object raw=getter.invoke(pathEngine,constant);if(raw==null)return null;int width=integer(fieldValue(raw,"b"),0),height=integer(fieldValue(raw,"c"),0);Object terrain=fieldValue(raw,"d"),buildings=fieldValue(raw,"e"),objects=fieldValue(raw,"f");int size=width*height;if(width<=0||height<=0||size>250000||!(terrain instanceof byte[])||!(buildings instanceof byte[])||!(objects instanceof byte[])||((byte[])terrain).length<size||((byte[])buildings).length<size||((byte[])objects).length<size)return null;return new PathLayer(width,height,scale,(byte[])terrain,(byte[])buildings,(byte[])objects);}catch(Throwable ignored){return null;}
    }
    private static final class PathLayer{final int width,height;final float scale;final byte[] terrain,buildings,objects;PathLayer(int width,int height,float scale,byte[] terrain,byte[] buildings,byte[] objects){this.width=width;this.height=height;this.scale=scale;this.terrain=terrain;this.buildings=buildings;this.objects=objects;}}
    private Map<String,Object> environmentFacts(Object engine,List<UnitSnapshot> units){
        LinkedHashMap<String,Object> e=new LinkedHashMap<>();
        ArrayList<Map<String,Object>> zones=new ArrayList<>();for(UnitSnapshot u:units)if("damagingBorder".equals(u.typeId)||"zoneMarker".equals(u.typeId)){LinkedHashMap<String,Object> z=new LinkedHashMap<>();z.put("unit_id",u.id);z.put("type_id",u.typeId);z.put("x",u.x);z.put("y",u.y);z.put("radius",u.radius);z.put("team_id",u.teamId);zones.add(z);}e.put("danger_zones",zones);return e;
    }
    private Map<String,Object> teamFacts(Object me,List<UnitSnapshot> units,Object engine){
        LinkedHashMap<String,Object> t=new LinkedHashMap<>();if(me==null)return t;
        int localId=integer(fieldValue(me,"l"),-1);
        t.put("id",localId);t.put("name",string(fieldValue(me,"w")));
        putNumber(t,"funds",fieldValue(me,"p"));putNumber(t,"income_rate",invokeNoArg(me,"q"));putNumber(t,"income_multiplier",invokeNoArg(me,"w"));
        int count=0;for(UnitSnapshot u:units)if(u.relation==0&&!u.dead&&!u.deleted)count++;t.put("unit_count",count);
        putNumber(t,"unit_cap",fieldValue(engine,"bz"));
        LinkedHashMap<Integer,Object> rawTeams=new LinkedHashMap<>();LinkedHashMap<Integer,Integer> counts=new LinkedHashMap<>();
        rawTeams.put(localId,me);counts.put(localId,0);
        for(UnitSnapshot u:units){Object rawTeam=fieldValue(u.raw,"bZ");if(rawTeam==null)continue;int id=u.teamId;if(id<0)continue;rawTeams.putIfAbsent(id,rawTeam);counts.put(id,counts.getOrDefault(id,0)+(!u.dead&&!u.deleted?1:0));}
        ArrayList<Map<String,Object>> teams=new ArrayList<>();for(Map.Entry<Integer,Object> entry:rawTeams.entrySet())teams.add(teamRecord(entry.getKey(),entry.getValue(),counts.getOrDefault(entry.getKey(),0),engine,entry.getKey()==localId));t.put("teams",teams);
        return t;
    }
    private Map<String,Object> teamRecord(int id,Object team,int unitCount,Object engine,boolean local){
        LinkedHashMap<String,Object> t=new LinkedHashMap<>();t.put("id",id);t.put("name",string(fieldValue(team,"w")));t.put("unit_count",unitCount);t.put("local",local);
        putNumber(t,"funds",fieldValue(team,"p"));putNumber(t,"income_rate",invokeNoArg(team,"q"));putNumber(t,"income_multiplier",invokeNoArg(team,"w"));
        if(local)putNumber(t,"unit_cap",fieldValue(engine,"bz"));else{Object cap=firstMetadata(team,"unitCap","unit_cap","cap");if(cap instanceof Number)t.put("unit_cap",cap);}
        return t;
    }
    private List<Map<String,Object>> projectileFacts(Object engine){
        ArrayList<Map<String,Object>> out=new ArrayList<>();if(allProjectiles==null)return out;
        try{Object list=Modifier.isStatic(allProjectiles.getModifiers())?allProjectiles.get(null):allProjectiles.get(engine);
            if(list instanceof Iterable)for(Object p:(Iterable<?>)list){if(p==null||(projectileClass!=null&&!projectileClass.isInstance(p)))continue;
                LinkedHashMap<String,Object> x=new LinkedHashMap<>();Long id=objectId(p);if(id!=null)x.put("id",id);
                putNumber(x,"x",fieldValue(p,"eq"));putNumber(x,"y",fieldValue(p,"er"));putNumber(x,"height",fieldValue(p,"es"));
                Long source=objectId(fieldValue(p,"j")),target=objectId(fieldValue(p,"l"));if(source!=null)x.put("source_id",source);if(target!=null)x.put("target_id",target);out.add(x);
            }}catch(Throwable ignored){}return out;
    }

    private Long findRecentDamageSource(long targetId){
        if(allProjectiles==null||snapshotEngine==null)return null;
        try{
            Object list=Modifier.isStatic(allProjectiles.getModifiers())?allProjectiles.get(null):allProjectiles.get(snapshotEngine);
            if(list instanceof Iterable)for(Object projectile:(Iterable<?>)list){if(projectile==null)continue;Long target=objectId(fieldValue(projectile,"l"));if(target!=null&&target==targetId){Long source=objectId(fieldValue(projectile,"j"));if(source!=null)return source;}}
        }catch(Throwable ignored){}
        return null;
    }

    private boolean multiplayer(Object engine) {
        try {
            Object network = fieldValue(engine, "bY");
            Object value = invokeNoArg(network, "g");
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) { return false; }
    }

    private Object fieldValue(Object object, String name) {
        if (object == null) return null;
        try {
            Map<String, Field> fields = fieldCache.computeIfAbsent(object.getClass(), ignored -> new HashMap<>());
            Field field = fields.get(name);
            if (field == null) { field = host.findField(object.getClass(), name); fields.put(name, field); }
            return field.get(object);
        }
        catch (Throwable ignored) { return null; }
    }

    private Object invokeNoArg(Object object, String name) {
        if (object == null) return null;
        try {
            Map<String, Method> methods = noArgMethodCache.computeIfAbsent(object.getClass(), ignored -> new HashMap<>());
            Method method = methods.get(name);
            if (method == null) { method = host.findNoArgMethod(object.getClass(), name); if (method != null) methods.put(name, method); }
            return method == null ? null : method.invoke(object);
        } catch (Throwable ignored) { return null; }
    }

    private Object invoke(Object object,String name,Class<?>[] types,Object...args){
        if(object==null)return null;StringBuilder keyBuilder=new StringBuilder(object.getClass().getName()).append('#').append(name);for(Class<?> t:types)keyBuilder.append(':').append(t.getName());String key=keyBuilder.toString();
        try{Method cached=argumentMethodCache.get(key);if(cached!=null)return cached.invoke(object,args);Method m=host.findCompatibleMethod(object.getClass(),name,types);if(m!=null){argumentMethodCache.put(key,m);return m.invoke(object,args);}
        }catch(Throwable ignored){}return null;
    }
    private Object tickValue(){try{Object engine=host.findEngine(loader);return engine==null?null:tick.get(engine);}catch(Throwable ignored){return null;}}
    private static Number number(Object value){return value instanceof Number?(Number)value:null;}
    private static void putNumber(Map<String,Object> out,String key,Object value){if(value instanceof Number)out.put(key,value);}

    private String typeText(Object type, String methodName) {
        Object value = invokeNoArg(type, methodName);
        return value == null ? null : String.valueOf(value);
    }

    private Long objectId(Object object) {
        if (object == null) return null;
        long id = longValue(fieldValue(object, "ej"), -1L);
        return id < 0 ? null : id;
    }

    private Class<?> optionalClass(String name) {
        try { return loader.loadClass(name); } catch (Throwable ignored) { return null; }
    }
    private static boolean bool(Object value) { return value instanceof Boolean && (Boolean) value; }
    private static int integer(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }
    private static long longValue(Object value, long fallback) { return value instanceof Number ? ((Number) value).longValue() : fallback; }
    private static float decimal(Object value, float fallback) { return value instanceof Number ? ((Number) value).floatValue() : fallback; }
    private static Float decimalObject(Object value) { return value instanceof Number ? ((Number) value).floatValue() : null; }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
}
