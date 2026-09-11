package com.shizuku.rwmiao.module.script;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class GameSnapshot {
    public final int tick;
    public final boolean multiplayer;
    public final int localTeamId;
    public final List<UnitSnapshot> units;
    public final Map<String,Object> map;
    public final Map<String,Object> environment;
    public final Map<String,Object> localPlayer;
    public final List<Map<String,Object>> projectiles;
    interface TileReader { Map<String,Object> read(float x,float y); }
    interface FogReader { Map<String,Object> read(float x,float y); }
    interface PathReader { Map<String,Object> read(float x,float y,String movement); }
    final TileReader tileReader;
    final FogReader fogReader;
    final PathReader pathReader;
    private static final int MAX_QUERY_CACHE = 2048;
    private final Map<Long,Map<String,Object>> tileCache=new HashMap<>();
    private final Map<Long,Map<String,Object>> fogCache=new HashMap<>();
    private final Map<PathKey,Map<String,Object>> pathCache=new HashMap<>();
    private final Map<String,Map<String,Object>> trajectoryCache=new HashMap<>();
    private final Map<String,Map<String,Object>> routeCache=new HashMap<>();
    private final Map<String,Map<String,Object>> constructionCache=new HashMap<>();
    private final Map<Long, UnitSnapshot> byId;

    GameSnapshot(int tick, boolean multiplayer, int localTeamId, List<UnitSnapshot> units) {
        this(tick,multiplayer,localTeamId,units,Collections.emptyMap(),Collections.emptyMap(),Collections.emptyMap(),Collections.emptyList(),null,null,null);
    }

    GameSnapshot(int tick, boolean multiplayer, int localTeamId, List<UnitSnapshot> units,
                 Map<String,Object> map, Map<String,Object> environment, Map<String,Object> localPlayer,
                 List<Map<String,Object>> projectiles, TileReader tileReader, FogReader fogReader,
                 PathReader pathReader) {
        this.tick = tick;
        this.multiplayer = multiplayer;
        this.localTeamId = localTeamId;
        this.units = Collections.unmodifiableList(units);
        this.map=Collections.unmodifiableMap(map); this.environment=Collections.unmodifiableMap(environment);
        this.localPlayer=Collections.unmodifiableMap(localPlayer); this.projectiles=Collections.unmodifiableList(projectiles);
        this.tileReader=tileReader; this.fogReader=fogReader; this.pathReader=pathReader;
        LinkedHashMap<Long, UnitSnapshot> index = new LinkedHashMap<>();
        for (UnitSnapshot unit : units) index.put(unit.id, unit);
        byId = Collections.unmodifiableMap(index);
    }

    public UnitSnapshot get(long id) { return byId.get(id); }
    Map<String,Object> tileAt(float x,float y){if(tileReader==null)return Collections.emptyMap();long key=coordinateKey(x,y);Map<String,Object> value=tileCache.get(key);if(value==null){value=tileReader.read(x,y);if(tileCache.size()<MAX_QUERY_CACHE)tileCache.put(key,value);}return value;}
    Map<String,Object> fogAt(float x,float y){if(fogReader==null)return Collections.emptyMap();long key=coordinateKey(x,y);Map<String,Object> value=fogCache.get(key);if(value==null){value=fogReader.read(x,y);if(fogCache.size()<MAX_QUERY_CACHE)fogCache.put(key,value);}return value;}
    Map<String,Object> pathAt(float x,float y,String movement){return pathAt(x,y,movement,0f);}
    Map<String,Object> pathAt(float x,float y,String movement,float clearance){
        if(pathReader==null)return Collections.emptyMap();
        float radius=Math.max(0f,clearance);
        PathKey key=new PathKey(x,y,movement,radius);
        Map<String,Object> value=pathCache.get(key);
        if(value==null){
            Map<String,Object> base=pathReader.read(x,y,key.movement);
            value=withClearance(base,x,y,key.movement,radius);
            if(pathCache.size()<MAX_QUERY_CACHE)pathCache.put(key,value);
        }
        return value;
    }

    Map<String,Object> findPath(float sx,float sy,float gx,float gy,String movement,float clearance,int maxNodes){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        String kind=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);
        out.put("movement_type",kind);out.put("clearance_radius",Math.max(0f,clearance));
        Map<String,Object> start=pathAt(sx,sy,kind,clearance),goal=pathAt(gx,gy,kind,clearance);
        boolean available=Boolean.TRUE.equals(start.get("available"))&&Boolean.TRUE.equals(goal.get("available"));
        out.put("available",available);out.put("reachable",false);out.put("nodes_expanded",0);out.put("cost",-1d);
        ArrayList<Map<String,Object>> empty=new ArrayList<>();out.put("points",empty);
        if(!available||!Boolean.TRUE.equals(start.get("passable"))||!Boolean.TRUE.equals(goal.get("passable")))return out;
        Number sxGrid=number(start.get("grid_x")),syGrid=number(start.get("grid_y")),gxGrid=number(goal.get("grid_x")),gyGrid=number(goal.get("grid_y")),scaleNumber=number(start.get("world_to_grid"));
        Number widthNumber=number(start.get("width")),heightNumber=number(start.get("height"));
        if(sxGrid==null||syGrid==null||gxGrid==null||gyGrid==null||scaleNumber==null||scaleNumber.doubleValue()<=0||widthNumber==null||heightNumber==null)return out;
        int startX=sxGrid.intValue(),startY=syGrid.intValue(),goalX=gxGrid.intValue(),goalY=gyGrid.intValue(),width=widthNumber.intValue(),height=heightNumber.intValue();
        if(startX<0||startY<0||goalX<0||goalY<0||startX>=width||goalX>=width||startY>=height||goalY>=height)return out;
        int budget=Math.max(64,Math.min(16384,maxNodes<=0?4096:maxNodes));
        double scale=scaleNumber.doubleValue();
        PriorityQueue<PathNode> open=new PriorityQueue<>(Comparator.comparingDouble((PathNode n)->n.f).thenComparingDouble(n->n.g));
        Map<Long,PathNode> best=new HashMap<>();Set<Long> closed=new HashSet<>();
        PathNode first=new PathNode(startX,startY,0,heuristic(startX,startY,goalX,goalY),null);open.add(first);best.put(key(startX,startY),first);int expanded=0;PathNode found=null;
        int[] dx={-1,0,1,-1,1,-1,0,1},dy={-1,-1,-1,0,0,1,1,1};
        while(!open.isEmpty()&&expanded<budget){PathNode node=open.poll();long nodeKey=key(node.x,node.y);if(!closed.add(nodeKey))continue;expanded++;if(node.x==goalX&&node.y==goalY){found=node;break;}
            for(int i=0;i<8;i++){int nx=node.x+dx[i],ny=node.y+dy[i];if(nx<0||ny<0||nx>=width||ny>=height||closed.contains(key(nx,ny)))continue;float wx=(float)((nx+0.5)/scale),wy=(float)((ny+0.5)/scale);Map<String,Object> cell=pathAt(wx,wy,kind,clearance);if(!Boolean.TRUE.equals(cell.get("passable")))continue;if(dx[i]!=0&&dy[i]!=0){Map<String,Object> c1=pathAt((float)((node.x+dx[i]+0.5)/scale),(float)((node.y+0.5)/scale),kind,clearance),c2=pathAt((float)((node.x+0.5)/scale),(float)((node.y+dy[i]+0.5)/scale),kind,clearance);if(!Boolean.TRUE.equals(c1.get("passable"))||!Boolean.TRUE.equals(c2.get("passable")))continue;}double ng=node.g+(dx[i]==0||dy[i]==0?1.0:1.41421356237);long k=key(nx,ny);PathNode old=best.get(k);if(old==null||ng<old.g){PathNode next=new PathNode(nx,ny,ng,ng+heuristic(nx,ny,goalX,goalY),node);best.put(k,next);open.add(next);}}
        }
        out.put("nodes_expanded",expanded);out.put("reached_limit",found==null&&!open.isEmpty()&&expanded>=budget);
        if(found!=null){ArrayList<Map<String,Object>> points=new ArrayList<>();for(PathNode n=found;n!=null;n=n.parent){LinkedHashMap<String,Object> p=new LinkedHashMap<>();p.put("x",(double)((n.x+0.5)/scale));p.put("y",(double)((n.y+0.5)/scale));points.add(p);}Collections.reverse(points);out.put("reachable",true);out.put("cost",found.g);out.put("points",points);}return out;
    }

    Map<String,Object> pathClearance(float sx,float sy,float gx,float gy,String movement,float clearance,int maxSamples){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();String kind=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);
        int samples=Math.max(2,Math.min(64,maxSamples<=0?16:maxSamples));out.put("movement_type",kind);out.put("clearance_radius",Math.max(0f,clearance));out.put("samples",samples);
        boolean available=true,clear=true;Map<String,Object> blocked=null;
        for(int i=0;i<=samples;i++){float ratio=i/(float)samples,x=sx+(gx-sx)*ratio,y=sy+(gy-sy)*ratio;Map<String,Object> cell=pathAt(x,y,kind,clearance);if(!Boolean.TRUE.equals(cell.get("available"))){available=false;clear=false;break;}if(!Boolean.TRUE.equals(cell.get("passable"))){clear=false;blocked=new LinkedHashMap<>();blocked.put("x",x);blocked.put("y",y);blocked.put("sample",i);break;}}
        out.put("available",available);out.put("clear",clear);if(blocked!=null)out.put("first_blocked",blocked);return out;
    }

    Map<String,Object> reachableArea(float x,float y,String movement,float clearance,int maxNodes,float escapeDistance){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();String kind=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);
        int budget=Math.max(32,Math.min(4096,maxNodes<=0?256:maxNodes));float required=Math.max(1f,escapeDistance);
        Map<String,Object> start=pathAt(x,y,kind,clearance);boolean available=Boolean.TRUE.equals(start.get("available"));out.put("available",available);out.put("movement_type",kind);out.put("max_nodes",budget);
        if(!available||!Boolean.TRUE.equals(start.get("passable"))){out.put("nodes",0);out.put("dead_end",true);return out;}
        Number gxValue=number(start.get("grid_x")),gyValue=number(start.get("grid_y")),scaleValue=number(start.get("world_to_grid")),widthValue=number(start.get("width")),heightValue=number(start.get("height"));
        if(gxValue==null||gyValue==null||scaleValue==null||scaleValue.floatValue()<=0||widthValue==null||heightValue==null){out.put("available",false);out.put("nodes",0);out.put("dead_end",false);return out;}
        int startX=gxValue.intValue(),startY=gyValue.intValue(),width=widthValue.intValue(),height=heightValue.intValue();float scale=scaleValue.floatValue();
        ArrayList<int[]> queue=new ArrayList<>();Set<Long> visited=new HashSet<>();queue.add(new int[]{startX,startY});visited.add(key(startX,startY));int head=0,exits=0;float farthest=0f;
        int[] dx={-1,1,0,0},dy={0,0,-1,1};
        while(head<queue.size()&&visited.size()<budget){int[] node=queue.get(head++);float wx=(node[0]+0.5f)/scale,wy=(node[1]+0.5f)/scale;farthest=Math.max(farthest,(float)Math.hypot(wx-x,wy-y));if(farthest>=required)exits++;
            for(int i=0;i<4;i++){int nx=node[0]+dx[i],ny=node[1]+dy[i];long nextKey=key(nx,ny);if(nx<0||ny<0||nx>=width||ny>=height||visited.contains(nextKey))continue;float px=(nx+0.5f)/scale,py=(ny+0.5f)/scale;Map<String,Object> cell=pathAt(px,py,kind,clearance);if(Boolean.TRUE.equals(cell.get("passable"))){visited.add(nextKey);queue.add(new int[]{nx,ny});}}}
        boolean reachedLimit=visited.size()>=budget,escaped=farthest>=required;out.put("nodes",visited.size());out.put("reached_limit",reachedLimit);out.put("farthest_distance",farthest);out.put("escape_frontier",exits);out.put("dead_end",!escaped&&!reachedLimit);return out;
    }

    Map<String,Object> constructionCheck(UnitSnapshot builder,UnitSnapshot target,int requestedSamples,
                                         float requestedMaxApproach,int requestedMaxNodes,boolean requireBuildableType){
        return constructionCheck(builder,target,requestedSamples,requestedMaxApproach,requestedMaxNodes,
                requireBuildableType,0,3);
    }

    Map<String,Object> constructionCheck(UnitSnapshot builder,UnitSnapshot target,int requestedSamples,
                                         float requestedMaxApproach,int requestedMaxNodes,boolean requireBuildableType,
                                         int requestedCandidateOffset,int requestedPathChecks){
        int samples=Math.max(8,Math.min(24,requestedSamples<=0?12:requestedSamples));
        float maxApproach=Math.max(40f,Math.min(360f,requestedMaxApproach<=0f?240f:requestedMaxApproach));
        int maxNodes=Math.max(64,Math.min(2048,requestedMaxNodes<=0?512:requestedMaxNodes));
        int candidateOffset=Math.max(0,Math.min(4095,requestedCandidateOffset));
        int maxPathChecks=Math.max(1,Math.min(8,requestedPathChecks<=0?3:requestedPathChecks));
        String key=(builder==null?-1:builder.id)+":"+(target==null?-1:target.id)+":"+samples+":"
                +Float.floatToIntBits(maxApproach)+":"+maxNodes+":"+requireBuildableType+":"+candidateOffset+":"+maxPathChecks;
        Map<String,Object> cached=constructionCache.get(key);if(cached!=null)return cached;
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        if(builder!=null)out.put("builder_id",builder.id);
        if(target!=null){out.put("target_id",target.id);out.put("target_type_id",target.typeId);out.put("construction_key",constructionKey(target));}
        if(builder==null||builder.dead||builder.deleted||!builder.orderable||builder.relation!=0)
            return cacheConstruction(key,constructionFailure(out,true,"invalid_builder",false));
        if(target==null||target.dead||target.deleted||!target.building)
            return cacheConstruction(key,constructionFailure(out,true,"invalid_target",false));
        if(target.buildProgress>=1.0f)
            return cacheConstruction(key,constructionFailure(out,true,"target_complete",false));
        if(target.relation!=0&&target.relation!=1)
            return cacheConstruction(key,constructionFailure(out,true,"target_not_friendly",false));
        if(requireBuildableType&&!builder.buildableTypes.contains(target.typeId))
            return cacheConstruction(key,constructionFailure(out,true,"unsupported_type",false));
        String movement=builder.movementType==null?"LAND":builder.movementType;
        out.put("movement_type",movement);out.put("samples",samples);out.put("max_approach",maxApproach);out.put("max_nodes",maxNodes);
        out.put("candidate_offset",candidateOffset);out.put("max_path_checks",maxPathChecks);
        if(pathReader==null)return cacheConstruction(key,constructionFailure(out,false,"path_data_unavailable",true));

        float base=Math.max(8f,builder.radius+target.radius);
        float[] offsets={14f,60f,130f,maxApproach};
        float start=(float)Math.atan2(builder.y-target.y,builder.x-target.x);
        ArrayList<ConstructionCandidate> candidates=new ArrayList<>();boolean pathDataAvailable=false;int checked=0;
        for(float offset:offsets){
            float distance=base+Math.min(maxApproach,offset);
            for(int i=0;i<samples;i++){
                double angle=start+Math.PI*2d*i/samples;float x=target.x+(float)Math.cos(angle)*distance,y=target.y+(float)Math.sin(angle)*distance;
                Map<String,Object> cell=pathAt(x,y,movement,Math.max(0f,builder.radius));checked++;
                if(Boolean.TRUE.equals(cell.get("available")))pathDataAvailable=true;
                if(!Boolean.TRUE.equals(cell.get("passable")))continue;
                float dx=x-builder.x,dy=y-builder.y;candidates.add(new ConstructionCandidate(x,y,dx*dx+dy*dy));
            }
        }
        out.put("checked_candidates",checked);out.put("passable_candidates",candidates.size());
        candidates.sort(Comparator.comparingDouble(c->c.distanceSquared));
        if(candidates.isEmpty())return cacheConstruction(key,constructionFailure(out,pathDataAvailable,
                pathDataAvailable?"unreachable":"path_data_unavailable",!pathDataAvailable));
        int startIndex=candidateOffset%candidates.size(),pathChecks=0,visitedCandidates=0;boolean reachedLimit=false;
        for(int n=0;n<candidates.size();n++){
            ConstructionCandidate candidate=candidates.get((startIndex+n)%candidates.size());visitedCandidates++;
            Map<String,Object> clearance=pathClearance(builder.x,builder.y,candidate.x,candidate.y,movement,
                    Math.max(0f,builder.radius),12);
            if(Boolean.TRUE.equals(clearance.get("available"))&&Boolean.TRUE.equals(clearance.get("clear")))
                return cacheConstruction(key,constructionSuccess(out,candidate,pathChecks,Collections.emptyMap()));
            if(pathChecks>=maxPathChecks)break;
            pathChecks++;
            Map<String,Object> path=findPath(builder.x,builder.y,candidate.x,candidate.y,movement,
                    Math.max(0f,builder.radius),maxNodes);
            if(Boolean.TRUE.equals(path.get("reachable")))
                return cacheConstruction(key,constructionSuccess(out,candidate,pathChecks,path));
            if(Boolean.TRUE.equals(path.get("reached_limit")))reachedLimit=true;
        }
        out.put("path_checks",pathChecks);out.put("visited_candidates",visitedCandidates);
        out.put("next_candidate_offset",candidateOffset+Math.max(1,visitedCandidates));
        boolean complete=visitedCandidates>=candidates.size()&&!reachedLimit;
        return cacheConstruction(key,constructionFailure(out,pathDataAvailable,
                !pathDataAvailable?"path_data_unavailable":complete?"unreachable":reachedLimit?"search_limit":"search_incomplete",
                !pathDataAvailable||!complete));
    }

    private Map<String,Object> cacheConstruction(String key,Map<String,Object> value){
        if(constructionCache.size()<256)constructionCache.put(key,value);return value;
    }

    private static Map<String,Object> constructionFailure(LinkedHashMap<String,Object> out,boolean available,
                                                           String reason,boolean retryable){
        out.put("available",available);out.put("valid",false);out.put("reachable",false);
        out.put("reason",reason);out.put("retryable",retryable);out.put("definitive",!retryable);
        String scope="none";
        if("invalid_target".equals(reason)||"target_complete".equals(reason)||"target_not_friendly".equals(reason))scope="target";
        else if("invalid_builder".equals(reason)||"unsupported_type".equals(reason)||"unreachable".equals(reason))scope="builder_target";
        out.put("ignore_scope",scope);return out;
    }

    private static Map<String,Object> constructionSuccess(LinkedHashMap<String,Object> out,
                                                           ConstructionCandidate candidate,int pathChecks,
                                                           Map<String,Object> path){
        out.put("available",true);out.put("valid",true);out.put("reachable",true);out.put("reason","ok");
        out.put("retryable",false);out.put("definitive",true);out.put("ignore_scope","none");
        out.put("approach_x",candidate.x);out.put("approach_y",candidate.y);
        out.put("path_checks",pathChecks);out.put("path",path);return out;
    }

    Map<String,Object> constructionStatus(UnitSnapshot builder,UnitSnapshot requestedTarget){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        if(builder==null||builder.dead||builder.deleted||!builder.orderable){out.put("available",false);out.put("state","invalid_builder");return out;}
        out.put("available",true);out.put("builder_id",builder.id);
        String intent=text(builder.extras.get("intent_type"));Long intentTarget=longNumber(builder.extras.get("intent_target_id"));
        if(intentTarget==null)intentTarget=builder.orderTargetId;
        UnitSnapshot target=requestedTarget!=null?requestedTarget:intentTarget==null?null:get(intentTarget);
        if(target!=null){out.put("target_id",target.id);out.put("construction_key",constructionKey(target));out.put("progress",target.buildProgress);
            out.put("target_valid",!target.dead&&!target.deleted&&target.building);out.put("target_unfinished",!target.dead&&!target.deleted&&target.building&&target.buildProgress<1.0f);
            float dx=builder.x-target.x,dy=builder.y-target.y,distance=(float)Math.sqrt(dx*dx+dy*dy);out.put("distance",distance);out.put("edge_distance",Math.max(0f,distance-builder.radius-target.radius));}
        boolean intentConstruction="repair".equals(intent)||"build".equals(intent)||Boolean.TRUE.equals(builder.extras.get("is_building_order"));
        boolean matches=target!=null&&intentTarget!=null&&intentTarget==target.id;
        boolean active=intentConstruction&&matches;
        String source=text(builder.extras.get("order_source"));out.put("active",active);out.put("acknowledged",active);
        out.put("intent",intent==null?"idle":intent);if(intentTarget!=null)out.put("intent_target_id",intentTarget);
        if(source!=null)out.put("order_source",source);copy(builder.extras,out,"order_command_id");copy(builder.extras,out,"order_script_id");copy(builder.extras,out,"order_issued_tick");
        Number issued=number(builder.extras.get("order_issued_tick"));if(issued!=null)out.put("order_age",Math.max(0,tick-issued.intValue()));
        String state;
        if(target==null)state="no_target";else if(target.dead||target.deleted||!target.building)state="invalid_target";
        else if(target.buildProgress>=1.0f)state="target_complete";else if("player".equals(source)&&!active)state="player_order";
        else if(active)state=Boolean.TRUE.equals(builder.extras.get("moving"))?"approaching":"working";else state="idle";
        out.put("state",state);return out;
    }

    Map<String,Object> repairStatus(UnitSnapshot repairer){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        if(repairer==null||repairer.dead||repairer.deleted||!repairer.orderable){out.put("available",false);out.put("state","invalid_repairer");return out;}
        boolean available=repairer.extras.containsKey("is_repairing")||repairer.extras.containsKey("order_automatic");
        out.put("available",available);out.put("repairer_id",repairer.id);out.put("repair_range",Math.max(0f,repairer.attackRange));
        String intent=text(repairer.extras.get("intent_type"));Long targetId=longNumber(repairer.extras.get("repair_target_id"));
        if(targetId==null)targetId=longNumber(repairer.extras.get("intent_target_id"));if(targetId==null)targetId=repairer.orderTargetId;
        UnitSnapshot target=targetId==null?null:get(targetId);boolean valid=target!=null&&!target.dead&&!target.deleted
                &&(target.relation==0||target.relation==1)&&target.maxHealth>0f&&target.health<target.maxHealth-0.001f;
        boolean active="repair".equals(intent)&&valid;
        out.put("active",active);out.put("state",active?"repairing":targetId==null?"idle":"invalid_target");
        if(targetId!=null)out.put("target_id",targetId);
        if(target!=null){float dx=repairer.x-target.x,dy=repairer.y-target.y;float distance=(float)Math.sqrt(dx*dx+dy*dy);out.put("distance",distance);out.put("in_range",repairer.attackRange>0f&&distance<repairer.attackRange);}
        String source=text(repairer.extras.get("order_source"));if(source!=null)out.put("source",source);
        out.put("automatic",Boolean.TRUE.equals(repairer.extras.get("repair_automatic")));
        copy(repairer.extras,out,"order_revision");copy(repairer.extras,out,"order_issued_tick");copy(repairer.extras,out,"order_command_id");copy(repairer.extras,out,"order_script_id");
        return out;
    }

    List<UnitSnapshot> repairTargets(UnitSnapshot repairer,String requestedRelation,int requestedMaxResults){
        ArrayList<UnitSnapshot> out=new ArrayList<>();if(repairer==null||repairer.dead||repairer.deleted||repairer.attackRange<=0f)return out;
        String relation=requestedRelation==null?"both":requestedRelation.trim().toLowerCase(java.util.Locale.ROOT);
        int limit=Math.max(1,Math.min(256,requestedMaxResults<=0?128:requestedMaxResults));float rangeSquared=repairer.attackRange*repairer.attackRange;
        for(UnitSnapshot target:units){
            if(target==null||target.id==repairer.id||target.dead||target.deleted||target.attached||target.maxHealth<=0f||target.health>=target.maxHealth-0.001f)continue;
            boolean accepted="self".equals(relation)?target.relation==0:"ally".equals(relation)?target.relation==1:("both".equals(relation)||"friendly".equals(relation))&&(target.relation==0||target.relation==1);
            if(!accepted)continue;float dx=repairer.x-target.x,dy=repairer.y-target.y;if(dx*dx+dy*dy>=rangeSquared)continue;
            out.add(target);if(out.size()>=limit)break;
        }
        return out;
    }

    private static String text(Object value){return value==null?null:String.valueOf(value);}
    private static Long longNumber(Object value){return value instanceof Number?((Number)value).longValue():null;}
    private static void copy(Map<String,Object> from,Map<String,Object> to,String key){if(from.containsKey(key))to.put(key,from.get(key));}

    static String constructionKey(UnitSnapshot target){
        if(target==null)return null;
        return target.id+":"+target.typeId+":"+target.teamId+":"+Math.round(target.x)+":"+Math.round(target.y);
    }

    Map<String,Object> findEscape(UnitSnapshot unit,List<UnitSnapshot> threats,float step,int samples,
                                  float safety,int maxNodes){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();if(unit==null){out.put("available",false);return out;}
        String movement=unit.movementType==null?"LAND":unit.movementType;float radius=Math.max(0f,unit.radius);int count=Math.max(8,Math.min(32,samples<=0?16:samples));float travel=Math.max(40f,Math.min(600f,step));
        ArrayList<EscapeCandidate> candidates=new ArrayList<>();
        for(int i=0;i<count;i++)for(float scale:new float[]{1f,0.72f}){double angle=Math.PI*2*i/count;float px=unit.x+(float)Math.cos(angle)*travel*scale,py=unit.y+(float)Math.sin(angle)*travel*scale;Map<String,Object> cell=pathAt(px,py,movement,radius);if(!Boolean.TRUE.equals(cell.get("passable")))continue;int exits=0;float probe=Math.max(28f,radius*2f);for(int n=0;n<8;n++){double a=Math.PI*2*n/8;Map<String,Object> next=pathAt(px+(float)Math.cos(a)*probe,py+(float)Math.sin(a)*probe,movement,radius);if(Boolean.TRUE.equals(next.get("passable")))exits++;}if(exits==0)continue;float nearest=10000f;for(UnitSnapshot threat:threats){float dx=px-threat.x,dy=py-threat.y,d=(float)Math.sqrt(dx*dx+dy*dy),range=Math.max(0f,threat.attackRange);nearest=Math.min(nearest,d-range-safety);}float heading=(float)Math.toDegrees(angle),turn=Math.abs(normalizeDegrees(heading-unit.heading));float score=nearest*5f+exits*55f-turn*0.6f;candidates.add(new EscapeCandidate(px,py,score,exits));}
        candidates.sort((a,b)->Float.compare(b.score,a.score));int checked=0;for(EscapeCandidate candidate:candidates){if(checked++>=6)break;Map<String,Object> path=findPath(unit.x,unit.y,candidate.x,candidate.y,movement,radius,maxNodes);boolean pathAvailable=Boolean.TRUE.equals(path.get("available")),reachable=Boolean.TRUE.equals(path.get("reachable"));if(reachable||!pathAvailable&&Boolean.TRUE.equals(pathClearance(unit.x,unit.y,candidate.x,candidate.y,movement,radius,12).get("clear"))){out.put("available",true);out.put("reachable",true);out.put("x",candidate.x);out.put("y",candidate.y);out.put("score",candidate.score);out.put("exits",candidate.exits);out.put("path",path);return out;}}
        out.put("available",!candidates.isEmpty());out.put("reachable",false);return out;
    }

    Map<String,Object> predictTrajectory(UnitSnapshot unit,int requestedHorizon,int requestedStep){
        LinkedHashMap<String,Object> unavailable=new LinkedHashMap<>();if(unit==null){unavailable.put("available",false);return unavailable;}
        int horizon=Math.max(1,Math.min(600,requestedHorizon<=0?180:requestedHorizon));int step=Math.max(1,Math.min(120,requestedStep<=0?30:requestedStep));
        String cacheKey=unit.id+":"+horizon+":"+step;Map<String,Object> cached=trajectoryCache.get(cacheKey);if(cached!=null)return cached;
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("unit_id",unit.id);out.put("horizon",horizon);out.put("step",step);
        ArrayList<Map<String,Object>> points=new ArrayList<>();MotionState motion=motionState(unit);float elapsed=0f;
        for(int next=step;next<=horizon;next+=step){advance(motion,unit,next-(int)elapsed);elapsed=next;points.add(motionPoint(motion,next));}
        if(points.isEmpty())points.add(motionPoint(motion,0));out.put("points",points);out.put("moving",motion.speed>0.01f||motion.active);out.put("final",points.get(points.size()-1));
        if(trajectoryCache.size()<96)trajectoryCache.put(cacheKey,out);return out;
    }

    Map<String,Object> threatAssessment(UnitSnapshot unit,List<UnitSnapshot> threats,int requestedHorizon,int requestedStep,
                                        float safety,float surroundRadius,float surroundTriggerRadius){
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();if(unit==null){out.put("available",false);return out;}
        int horizon=Math.max(1,Math.min(600,requestedHorizon<=0?180:requestedHorizon));int step=Math.max(1,Math.min(120,requestedStep<=0?30:requestedStep));
        float safe=Math.max(0f,safety),scanRadius=Math.max(100f,surroundRadius<=0?3000f:surroundRadius),nearRadius=Math.max(100f,Math.min(scanRadius,surroundTriggerRadius<=0?1200f:surroundTriggerRadius));ArrayList<Map<String,Object>> rows=new ArrayList<>();
        float minimum=Float.POSITIVE_INFINITY;long nearestId=-1;float nearestDistance=Float.POSITIVE_INFINITY;int active=0,approaching=0;ArrayList<Float> angles=new ArrayList<>();
        for(UnitSnapshot threat:limitedThreats(threats,12)){
            if(threat==null||threat.dead||threat.deleted||threat.id==unit.id)continue;
            float dx=threat.x-unit.x,dy=threat.y-unit.y,distance=(float)Math.sqrt(dx*dx+dy*dy);if(distance<nearestDistance){nearestDistance=distance;nearestId=threat.id;}
            MotionState own=motionState(unit),enemy=motionState(threat);float currentMargin=distance-threat.attackRange-unit.radius-threat.radius-safe;
            boolean moving=enemy.active||enemy.speed>0.01f;if(moving)active++;
            float minMargin=currentMargin,enterAt=currentMargin<=0?0f:Float.POSITIVE_INFINITY;
            for(int t=step;t<=horizon;t+=step){Point ownPoint=predictedPoint(unit,t),enemyPoint=predictedPoint(threat,t);float separation=distance(ownPoint.x,ownPoint.y,enemyPoint.x,enemyPoint.y);float margin=separation-threat.attackRange-unit.radius-threat.radius-safe;if(margin<minMargin)minMargin=margin;if(enterAt==Float.POSITIVE_INFINITY&&margin<=0f)enterAt=t;}
            float rvx=own.vx-enemy.vx,rvy=own.vy-enemy.vy,closing=distance>0.01f?(rvx*dx+rvy*dy)/distance:0f;
            boolean isApproaching=moving&&(closing>0.005f||minMargin<currentMargin-4f);if(isApproaching)approaching++;
            if(minMargin<minimum){minimum=minMargin;}
            if(distance<=nearRadius&&(isApproaching||currentMargin<=0f))angles.add((float)Math.toDegrees(Math.atan2(dy,dx)));
            LinkedHashMap<String,Object> row=new LinkedHashMap<>();row.put("id",threat.id);row.put("distance",distance);row.put("margin",currentMargin);row.put("forecast_margin",minMargin);row.put("closing_speed",closing);row.put("moving",moving);row.put("approaching",isApproaching);row.put("will_enter_range",enterAt!=Float.POSITIVE_INFINITY);if(enterAt!=Float.POSITIVE_INFINITY)row.put("time_to_range",enterAt);rows.add(row);
        }
        boolean surrounded=isSurrounded(angles);float timeToDanger=Float.POSITIVE_INFINITY;
        for(Map<String,Object> row:rows){Number t=number(row.get("time_to_range"));if(t!=null)timeToDanger=Math.min(timeToDanger,t.floatValue());}
        boolean danger=minimum<=0f||timeToDanger<=horizon;
        out.put("available",true);out.put("unit_id",unit.id);out.put("threats",rows);out.put("threat_count",rows.size());out.put("active_count",active);out.put("approaching_count",approaching);out.put("nearest_id",nearestId);out.put("nearest_distance",nearestDistance==Float.POSITIVE_INFINITY?-1f:nearestDistance);out.put("minimum_margin",minimum==Float.POSITIVE_INFINITY?Float.POSITIVE_INFINITY:minimum);out.put("time_to_danger",timeToDanger==Float.POSITIVE_INFINITY?-1f:timeToDanger);out.put("surrounded",surrounded);out.put("danger",danger);out.put("retreat_required",danger||surrounded);return out;
    }

    Map<String,Object> findSafeRoute(UnitSnapshot unit,List<UnitSnapshot> threats,Long preserveTargetId,
                                     int requestedHorizon,int requestedForecastStep,float requestedStep,int requestedSamples,
                                     float safety,int maxNodes,boolean preserveRange){
        LinkedHashMap<String,Object> unavailable=new LinkedHashMap<>();if(unit==null){unavailable.put("available",false);return unavailable;}
        int horizon=Math.max(30,Math.min(600,requestedHorizon<=0?180:requestedHorizon));int forecastStep=Math.max(5,Math.min(120,requestedForecastStep<=0?30:requestedForecastStep));
        float travel=Math.max(30f,Math.min(600f,requestedStep<=0?180f:requestedStep));int samples=Math.max(8,Math.min(24,requestedSamples<=0?16:requestedSamples));float safe=Math.max(0f,safety);StringBuilder keyBuilder=new StringBuilder().append(unit.id).append(':').append(preserveTargetId).append(':').append(horizon).append(':').append(forecastStep).append(':').append(Math.round(travel)).append(':').append(samples).append(':').append(Math.round(safe)).append(':').append(maxNodes).append(':').append(preserveRange);
        for(UnitSnapshot threat:limitedThreats(threats,12))if(threat!=null)keyBuilder.append(':').append(threat.id);String cacheKey=keyBuilder.toString();Map<String,Object> cached=routeCache.get(cacheKey);if(cached!=null)return cached;
        ArrayList<UnitSnapshot> limited=limitedThreats(threats,12);Map<String,Object> assessment=threatAssessment(unit,limited,horizon,forecastStep,safe,3000f,1200f);boolean pathAvailable=pathReader!=null;String movement=unit.movementType==null?"LAND":unit.movementType;float radius=Math.max(0f,unit.radius),escapeX=0f,escapeY=0f;
        for(UnitSnapshot threat:limited){if(threat==null)continue;float dx=unit.x-threat.x,dy=unit.y-threat.y,d=(float)Math.hypot(dx,dy);if(d<0.01f)continue;float weight=(float)(1d/Math.max(80d,d*d))*((motionState(threat).active||motionState(threat).speed>0.01f)?2f:1f);escapeX+=dx*weight;escapeY+=dy*weight;}
        if(Math.abs(escapeX)+Math.abs(escapeY)<0.01f){escapeX=(float)Math.cos(Math.toRadians(unit.heading));escapeY=(float)Math.sin(Math.toRadians(unit.heading));}
        float escapeLength=(float)Math.hypot(escapeX,escapeY);escapeX/=escapeLength;escapeY/=escapeLength;ArrayList<RouteCandidate> candidates=new ArrayList<>();
        for(int i=0;i<samples;i++){float angle=(float)(Math.PI*2d*i/samples);for(float scale:new float[]{1f,0.62f}){float px=unit.x+(float)Math.cos(angle)*travel*scale,py=unit.y+(float)Math.sin(angle)*travel*scale;Map<String,Object> tile=pathAt(px,py,movement,radius);if(pathAvailable&&(!Boolean.TRUE.equals(tile.get("available"))||!Boolean.TRUE.equals(tile.get("passable"))))continue;float direction=(float)Math.cos(angle)*escapeX+(float)Math.sin(angle)*escapeY;float turn=Math.abs(normalizeDegrees((float)Math.toDegrees(angle)-unit.heading));candidates.add(new RouteCandidate(px,py,angle,direction,turn));}}
        candidates.sort((a,b)->Float.compare(b.direction*900f-b.turn*0.3f,a.direction*900f-a.turn*0.3f));RouteCandidate best=null;Map<String,Object> bestPath=null;float bestScore=-Float.MAX_VALUE;int checked=0;
        for(RouteCandidate candidate:candidates){if(checked++>=6)break;Map<String,Object> path=pathAvailable?findPath(unit.x,unit.y,candidate.x,candidate.y,movement,radius,Math.max(64,Math.min(2048,maxNodes))):Collections.emptyMap();boolean reachable=!pathAvailable||Boolean.TRUE.equals(path.get("reachable"));if(pathAvailable&&!reachable)continue;float score=evaluateRoute(unit,limited,preserveTargetId,candidate,path,horizon,forecastStep,safe,preserveRange);if(score>bestScore){bestScore=score;best=candidate;bestPath=path;}}
        LinkedHashMap<String,Object> out=new LinkedHashMap<>();out.put("available",pathAvailable);out.put("reachable",best!=null);out.put("route_required",Boolean.TRUE.equals(assessment.get("retreat_required")));out.put("nodes_checked",checked);out.put("score",best==null?null:bestScore);out.put("path",bestPath==null?Collections.emptyMap():bestPath);
        if(best!=null){out.put("x",best.x);out.put("y",best.y);out.put("heading",Math.toDegrees(best.angle));out.put("minimum_margin",routeMinimumMargin(unit,limited,best,bestPath,horizon,forecastStep,safe));UnitSnapshot target=preserveTargetId==null?null:get(preserveTargetId);Point end=routePoint(bestPath,unit.x,unit.y,best.x,best.y,Math.max(1f,unit.maxMoveSpeed),horizon);boolean keep=target!=null&&unit.attackRange>0f&&distance(end.x,end.y,target.x,target.y)<=unit.attackRange+4f;out.put("can_keep_firing",keep);out.put("preserve_target_id",preserveTargetId);}
        if(routeCache.size()<64)routeCache.put(cacheKey,out);return out;
    }

    Map<String,Object> threatRetreat(UnitSnapshot unit,List<UnitSnapshot> threats,Long preserveTargetId,int horizon,int forecastStep,
                                     float step,int samples,float safety,int maxNodes,float surroundRadius,float surroundTriggerRadius,boolean preserveRange){
        Map<String,Object> assessment=threatAssessment(unit,threats,horizon,forecastStep,safety,surroundRadius,surroundTriggerRadius);LinkedHashMap<String,Object> out=new LinkedHashMap<>(assessment);boolean required=Boolean.TRUE.equals(assessment.get("retreat_required"));out.put("retreat_required",required);if(required){Map<String,Object> route=findSafeRoute(unit,threats,preserveTargetId,horizon,forecastStep,step,samples,safety,maxNodes,preserveRange);out.put("route",route);if(Boolean.TRUE.equals(route.get("reachable"))){out.put("x",route.get("x"));out.put("y",route.get("y"));out.put("reachable",true);}}else{out.put("reachable",false);out.put("route",Collections.emptyMap());}return out;
    }

    private float evaluateRoute(UnitSnapshot unit,List<UnitSnapshot> threats,Long preserveTargetId,RouteCandidate candidate,
                                Map<String,Object> path,int horizon,int step,float safety,boolean preserveRange){
        float minimum=routeMinimumMargin(unit,threats,candidate,path,horizon,step,safety);float score=minimum*6f+candidate.direction*140f-candidate.turn*0.4f;
        UnitSnapshot target=preserveTargetId==null?null:get(preserveTargetId);if(target!=null&&unit.attackRange>0f){Point end=routePoint(path,unit.x,unit.y,candidate.x,candidate.y,Math.max(1f,unit.maxMoveSpeed),horizon);float targetMargin=unit.attackRange-distance(end.x,end.y,target.x,target.y);score+=targetMargin*2f;if(preserveRange&&targetMargin<-8f)score-=3000f;}
        return score;
    }

    private float routeMinimumMargin(UnitSnapshot unit,List<UnitSnapshot> threats,RouteCandidate candidate,Map<String,Object> path,
                                     int horizon,int step,float safety){
        float minimum=Float.POSITIVE_INFINITY;for(int t=0;t<=horizon;t+=step){Point own=routePoint(path,unit.x,unit.y,candidate.x,candidate.y,Math.max(1f,unit.maxMoveSpeed),t);for(UnitSnapshot threat:threats){if(threat==null)continue;Point enemy=predictedPoint(threat,t);float margin=distance(own.x,own.y,enemy.x,enemy.y)-threat.attackRange-unit.radius-threat.radius-safety;if(margin<minimum)minimum=margin;}}return minimum==Float.POSITIVE_INFINITY?10000f:minimum;
    }

    private static Point routePoint(Map<String,Object> path,float sx,float sy,float gx,float gy,float speed,int ticks){
        Object raw=path==null?null:path.get("points");if(!(raw instanceof List)||((List<?>)raw).isEmpty())return linePoint(sx,sy,gx,gy,speed,ticks);
        ArrayList<Point> points=new ArrayList<>();points.add(new Point(sx,sy));for(Object value:(List<?>)raw)if(value instanceof Map){Number x=number(((Map<?,?>)value).get("x")),y=number(((Map<?,?>)value).get("y"));if(x!=null&&y!=null)points.add(new Point(x.floatValue(),y.floatValue()));}if(points.size()<2)return linePoint(sx,sy,gx,gy,speed,ticks);
        float remaining=Math.max(0f,speed*ticks);for(int i=1;i<points.size();i++){Point from=points.get(i-1),to=points.get(i);float length=distance(from.x,from.y,to.x,to.y);if(remaining<=length){float ratio=length<0.01f?1f:remaining/length;return new Point(from.x+(to.x-from.x)*ratio,from.y+(to.y-from.y)*ratio);}remaining-=length;}return points.get(points.size()-1);
    }
    private static float distanceAlongRoute(Map<String,Object> path,float sx,float sy,float gx,float gy,float speed,float ticks){Point p=routePoint(path,sx,sy,gx,gy,speed,(int)ticks);return distance(sx,sy,p.x,p.y);}
    private static Point linePoint(float sx,float sy,float gx,float gy,float speed,int ticks){float total=distance(sx,sy,gx,gy),ratio=total<0.01f?1f:Math.min(1f,speed*ticks/total);return new Point(sx+(gx-sx)*ratio,sy+(gy-sy)*ratio);}
    private Point predictedPoint(UnitSnapshot unit,int ticks){if(ticks<=0)return new Point(unit.x,unit.y);Map<String,Object> trajectory=predictTrajectory(unit,Math.max(ticks,60),30);Object raw=trajectory.get("points");if(raw instanceof List&&!((List<?>)raw).isEmpty()){Map<?,?> closest=null;int best=Integer.MAX_VALUE;for(Object value:(List<?>)raw)if(value instanceof Map){Number offset=number(((Map<?,?>)value).get("ticks"));if(offset!=null&&Math.abs(offset.intValue()-ticks)<best){best=Math.abs(offset.intValue()-ticks);closest=(Map<?,?>)value;}}if(closest!=null){Number x=number(closest.get("x")),y=number(closest.get("y"));if(x!=null&&y!=null)return new Point(x.floatValue(),y.floatValue());}}return new Point(unit.x,unit.y);}
    private static ArrayList<UnitSnapshot> limitedThreats(List<UnitSnapshot> threats,int limit){ArrayList<UnitSnapshot> out=new ArrayList<>();if(threats==null)return out;for(UnitSnapshot threat:threats){if(threat!=null&&!threat.dead&&!threat.deleted){out.add(threat);if(out.size()>=limit)break;}}return out;}
    private static boolean isSurrounded(List<Float> angles){if(angles==null||angles.size()<3)return false;ArrayList<Float> sorted=new ArrayList<>(angles);Collections.sort(sorted);float largest=0f;for(int i=1;i<sorted.size();i++)largest=Math.max(largest,sorted.get(i)-sorted.get(i-1));largest=Math.max(largest,sorted.get(0)+360f-sorted.get(sorted.size()-1));return largest<205f;}
    private static MotionState motionState(UnitSnapshot unit){float vx=numberValue(unit.extras.get("velocity_x")),vy=numberValue(unit.extras.get("velocity_y")),speed=(float)Math.hypot(vx,vy);boolean moving=Boolean.TRUE.equals(unit.extras.get("moving"));float throttle=Math.abs(numberValue(unit.extras.get("throttle")));boolean active=moving||speed>0.01f||throttle>0.01f;float heading=speed>0.01f?(float)Math.toDegrees(Math.atan2(vy,vx)):unit.heading;Object desiredRaw=unit.extras.get("desired_heading");float desired=desiredRaw instanceof Number?((Number)desiredRaw).floatValue():heading;float max=Math.max(unit.maxMoveSpeed,numberValue(unit.extras.get("real_speed")));if(max<=0f)max=speed;float acceleration=numberValue(unit.extras.get("acceleration"));if(acceleration<=0f)acceleration=Math.max(0.01f,max/30f);float turn=numberValue(unit.extras.get("turn_speed"));if(turn<=0f)turn=360f;return new MotionState(unit.x,unit.y,vx,vy,speed,heading,desired,max,acceleration,turn,active);}
    private static void advance(MotionState motion,UnitSnapshot unit,int ticks){if(ticks<=0)return;for(int i=0;i<ticks;i++){if(!motion.active){motion.vx=0f;motion.vy=0f;motion.speed=0f;continue;}float delta=normalizeDegrees(motion.desired-motion.heading),turn=Math.max(-motion.turn,Math.min(motion.turn,delta));motion.heading+=turn;float target=motion.maxSpeed;motion.speed=Math.min(target,motion.speed+motion.acceleration);motion.vx=(float)Math.cos(Math.toRadians(motion.heading))*motion.speed;motion.vy=(float)Math.sin(Math.toRadians(motion.heading))*motion.speed;motion.x+=motion.vx;motion.y+=motion.vy;}}
    private static Map<String,Object> motionPoint(MotionState motion,int ticks){LinkedHashMap<String,Object> point=new LinkedHashMap<>();point.put("ticks",ticks);point.put("x",motion.x);point.put("y",motion.y);point.put("velocity_x",motion.vx);point.put("velocity_y",motion.vy);point.put("speed",motion.speed);point.put("heading",motion.heading);point.put("moving",motion.active&&motion.speed>0.01f);return point;}
    private static float distance(float ax,float ay,float bx,float by){return(float)Math.hypot(ax-bx,ay-by);}
    private static float numberValue(Object value){return value instanceof Number?((Number)value).floatValue():0f;}
    private static final class Point{final float x,y;Point(float x,float y){this.x=x;this.y=y;}}
    private static final class RouteCandidate{final float x,y,angle,direction,turn;RouteCandidate(float x,float y,float angle,float direction,float turn){this.x=x;this.y=y;this.angle=angle;this.direction=direction;this.turn=turn;}}
    private static final class MotionState{float x,y,vx,vy,speed,heading,desired,maxSpeed,acceleration,turn;final boolean active;MotionState(float x,float y,float vx,float vy,float speed,float heading,float desired,float maxSpeed,float acceleration,float turn,boolean active){this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.speed=speed;this.heading=heading;this.desired=desired;this.maxSpeed=maxSpeed;this.acceleration=acceleration;this.turn=turn;this.active=active;}}

    private Map<String,Object> withClearance(Map<String,Object> base,float x,float y,String movement,float radius){
        if(radius<=0f||base==null||base.isEmpty()||!Boolean.TRUE.equals(base.get("passable")))return base==null?Collections.emptyMap():base;
        Number scale=number(base.get("world_to_grid"));float cell=scale!=null&&scale.floatValue()>0f?1f/scale.floatValue():1f;boolean clear=true;
        for(int i=0;i<8;i++){double angle=Math.PI*2*i/8.0;Map<String,Object> sample=pathAt(x+(float)Math.cos(angle)*(radius+cell*0.45f),y+(float)Math.sin(angle)*(radius+cell*0.45f),movement,0f);if(!Boolean.TRUE.equals(sample.get("passable"))){clear=false;break;}}
        LinkedHashMap<String,Object> copy=new LinkedHashMap<>(base);copy.put("clearance_radius",radius);copy.put("clearance_passable",clear);copy.put("passable",clear);return copy;
    }
    private static double heuristic(int x,int y,int gx,int gy){int dx=Math.abs(x-gx),dy=Math.abs(y-gy);return Math.max(dx,dy)+(Math.sqrt(2)-1)*Math.min(dx,dy);}
    private static float normalizeDegrees(float value){value%=360f;if(value>180f)value-=360f;if(value<-180f)value+=360f;return value;}
    private static long key(int x,int y){return(((long)x)<<32)^(y&0xffffffffL);}
    private static Number number(Object value){return value instanceof Number?(Number)value:null;}
    private static final class PathNode{final int x,y;final double g,f;final PathNode parent;PathNode(int x,int y,double g,double f,PathNode parent){this.x=x;this.y=y;this.g=g;this.f=f;this.parent=parent;}}
    private static final class EscapeCandidate{final float x,y,score;final int exits;EscapeCandidate(float x,float y,float score,int exits){this.x=x;this.y=y;this.score=score;this.exits=exits;}}
    private static final class ConstructionCandidate{final float x,y,distanceSquared;ConstructionCandidate(float x,float y,float distanceSquared){this.x=x;this.y=y;this.distanceSquared=distanceSquared;}}
    private static long coordinateKey(float x,float y){return((long)Float.floatToIntBits(x)<<32)^(Float.floatToIntBits(y)&0xffffffffL);}
    private static final class PathKey{final long point;final String movement;final int clearance;PathKey(float x,float y,String movement,float clearance){point=coordinateKey(x,y);this.movement=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);this.clearance=Float.floatToIntBits(Math.max(0f,clearance));}@Override public int hashCode(){int h=31*Long.hashCode(point)+movement.hashCode();return 31*h+clearance;}@Override public boolean equals(Object other){if(this==other)return true;if(!(other instanceof PathKey))return false;PathKey key=(PathKey)other;return point==key.point&&clearance==key.clearance&&movement.equals(key.movement);}}
}
