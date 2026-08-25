package com.shizuku.rwmiao.module.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One simulation-tick view. Enemy entries intentionally ignore fog. */
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
    Map<String,Object> tileAt(float x,float y){return tileReader==null?Collections.emptyMap():tileReader.read(x,y);}
    Map<String,Object> fogAt(float x,float y){return fogReader==null?Collections.emptyMap():fogReader.read(x,y);}
    Map<String,Object> pathAt(float x,float y,String movement){return pathReader==null?Collections.emptyMap():pathReader.read(x,y,movement);}
}
