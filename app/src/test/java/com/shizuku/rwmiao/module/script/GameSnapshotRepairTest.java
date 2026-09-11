package com.shizuku.rwmiao.module.script;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GameSnapshotRepairTest {
    @Test
    public void repairDataGroupIsAcceptedByLuaDefinition() throws Exception {
        LuaProgram program=LuaProgram.compile("rw.script{api=1,id='repair_test',units={'repairbay'},data={'repair'}}\n"
                +"rw.on_tick(15,function(ctx) end)","repair_test.lua");
        assertTrue(program.definition.dataGroups.contains("repair"));
    }

    @Test
    public void nativeAutomaticOrderIsNotClassifiedAsPlayerInput() {
        assertEquals("game",GameAdapter.unmatchedOrderSource(0,"repair",true,false));
        assertEquals("player",GameAdapter.unmatchedOrderSource(0,"repair",false,false));
        assertEquals("game",GameAdapter.unmatchedOrderSource(2,"repair",false,false));
    }

    @Test
    public void repairStatusSeparatesAutomaticAndManualSources() {
        LinkedHashMap<String,Object> extras=new LinkedHashMap<>();
        extras.put("intent_type","repair");extras.put("repair_target_id",2L);
        extras.put("is_repairing",true);extras.put("repair_automatic",true);
        extras.put("order_automatic",true);extras.put("order_source","game");extras.put("order_revision",3L);
        UnitSnapshot bay=unit(1,"repairbay",0,0,0,100,100,230,false,extras);
        UnitSnapshot target=unit(2,"tank",0,100,0,40,100,0,false,Collections.emptyMap());

        Map<String,Object> status=new GameSnapshot(20,false,0,Arrays.asList(bay,target)).repairStatus(bay);

        assertTrue(Boolean.TRUE.equals(status.get("active")));
        assertTrue(Boolean.TRUE.equals(status.get("automatic")));
        assertTrue(Boolean.TRUE.equals(status.get("in_range")));
        assertEquals("game",status.get("source"));
        assertEquals(2L,status.get("target_id"));
    }

    @Test
    public void repairTargetsAreFriendlyDamagedUnattachedAndInsideNativeRange() {
        UnitSnapshot bay=unit(1,"repairbay",0,0,0,100,100,230,false,Collections.emptyMap());
        UnitSnapshot self=unit(2,"tank",0,100,0,60,100,0,false,Collections.emptyMap());
        UnitSnapshot ally=unit(3,"tank",1,200,0,20,100,0,false,Collections.emptyMap());
        UnitSnapshot enemy=unit(4,"tank",2,80,0,10,100,0,false,Collections.emptyMap());
        UnitSnapshot full=unit(5,"tank",0,90,0,100,100,0,false,Collections.emptyMap());
        UnitSnapshot far=unit(6,"tank",0,231,0,10,100,0,false,Collections.emptyMap());
        UnitSnapshot attached=unit(7,"tank",0,70,0,10,100,0,true,Collections.emptyMap());
        GameSnapshot snapshot=new GameSnapshot(20,false,0,Arrays.asList(bay,self,ally,enemy,full,far,attached));

        List<UnitSnapshot> both=snapshot.repairTargets(bay,"both",128);
        List<UnitSnapshot> own=snapshot.repairTargets(bay,"self",128);

        assertEquals(Arrays.asList(self,ally),both);
        assertEquals(Collections.singletonList(self),own);
        assertFalse(both.contains(enemy));
        assertFalse(both.contains(full));
        assertFalse(both.contains(far));
        assertFalse(both.contains(attached));
    }

    private static UnitSnapshot unit(long id,String type,int relation,float x,float y,float health,float maxHealth,
                                     float range,boolean attached,Map<String,Object> extras){
        return new UnitSnapshot(id,type,type,relation==0?0:1,"team",relation,x,y,0,0,12,
                health,maxHealth,0,0,1,false,false,false,attached,true,false,false,"repairbay".equals(type),"LAND",
                range,0,0,0,null,null,null,null,null,null,0,Collections.emptyList(),
                Collections.emptyList(),extras,null);
    }
}
