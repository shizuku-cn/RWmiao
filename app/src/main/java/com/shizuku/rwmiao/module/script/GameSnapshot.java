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
        out.put("nodes_expanded",expanded);
        if(found!=null){ArrayList<Map<String,Object>> points=new ArrayList<>();for(PathNode n=found;n!=null;n=n.parent){LinkedHashMap<String,Object> p=new LinkedHashMap<>();p.put("x",(double)((n.x+0.5)/scale));p.put("y",(double)((n.y+0.5)/scale));points.add(p);}Collections.reverse(points);out.put("reachable",true);out.put("cost",found.g);out.put("points",points);}return out;
    }

    private Map<String,Object> withClearance(Map<String,Object> base,float x,float y,String movement,float radius){
        if(radius<=0f||base==null||base.isEmpty()||!Boolean.TRUE.equals(base.get("passable")))return base==null?Collections.emptyMap():base;
        Number scale=number(base.get("world_to_grid"));float cell=scale!=null&&scale.floatValue()>0f?1f/scale.floatValue():1f;boolean clear=true;
        for(int i=0;i<8;i++){double angle=Math.PI*2*i/8.0;Map<String,Object> sample=pathAt(x+(float)Math.cos(angle)*(radius+cell*0.45f),y+(float)Math.sin(angle)*(radius+cell*0.45f),movement,0f);if(!Boolean.TRUE.equals(sample.get("passable"))){clear=false;break;}}
        LinkedHashMap<String,Object> copy=new LinkedHashMap<>(base);copy.put("clearance_radius",radius);copy.put("clearance_passable",clear);copy.put("passable",clear);return copy;
    }
    private static double heuristic(int x,int y,int gx,int gy){int dx=Math.abs(x-gx),dy=Math.abs(y-gy);return Math.max(dx,dy)+(Math.sqrt(2)-1)*Math.min(dx,dy);}
    private static long key(int x,int y){return(((long)x)<<32)^(y&0xffffffffL);}
    private static Number number(Object value){return value instanceof Number?(Number)value:null;}
    private static final class PathNode{final int x,y;final double g,f;final PathNode parent;PathNode(int x,int y,double g,double f,PathNode parent){this.x=x;this.y=y;this.g=g;this.f=f;this.parent=parent;}}
    private static long coordinateKey(float x,float y){return((long)Float.floatToIntBits(x)<<32)^(Float.floatToIntBits(y)&0xffffffffL);}
    private static final class PathKey{final long point;final String movement;final int clearance;PathKey(float x,float y,String movement,float clearance){point=coordinateKey(x,y);this.movement=movement==null?"LAND":movement.trim().toUpperCase(java.util.Locale.ROOT);this.clearance=Float.floatToIntBits(Math.max(0f,clearance));}@Override public int hashCode(){int h=31*Long.hashCode(point)+movement.hashCode();return 31*h+clearance;}@Override public boolean equals(Object other){if(this==other)return true;if(!(other instanceof PathKey))return false;PathKey key=(PathKey)other;return point==key.point&&clearance==key.clearance&&movement.equals(key.movement);}}
}
