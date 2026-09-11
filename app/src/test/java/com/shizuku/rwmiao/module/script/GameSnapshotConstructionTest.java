package com.shizuku.rwmiao.module.script;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GameSnapshotConstructionTest {
    @Test
    public void constructionDataGroupIsAcceptedByLuaDefinition() throws Exception {
        LuaProgram program=LuaProgram.compile("rw.script{api=1,id='construction_test',units={'builder'},data={'construction'}}\n"
                +"rw.on_tick(30,function(ctx) end)","construction_test.lua");
        assertTrue(program.definition.dataGroups.contains("construction"));
    }

    @Test
    public void findsReachableShoreApproachWithoutRequiringBuildingCenterToBePassable() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot navalFactory=unit(2,"seaFactory",1,200,0,true,0.35f);
        GameSnapshot snapshot=snapshot(180f,builder,navalFactory);

        Map<String,Object> result=snapshot.constructionCheck(builder,navalFactory,12,240,256,false);

        assertTrue(Boolean.TRUE.equals(result.get("valid")));
        assertTrue(Boolean.TRUE.equals(result.get("reachable")));
        assertTrue(((Number)result.get("approach_x")).floatValue()<180f);
        assertEquals("ok",result.get("reason"));
    }

    @Test
    public void unreachableConstructionSiteIsPermanentForTheCurrentMatch() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot isolated=unit(2,"turret",0,700,0,true,0.2f);
        Map<String,Object> result=snapshot(120f,builder,isolated)
                .constructionCheck(builder,isolated,12,240,128,false);

        assertFalse(Boolean.TRUE.equals(result.get("valid")));
        assertFalse(Boolean.TRUE.equals(result.get("retryable")));
        assertEquals("unreachable",result.get("reason"));
    }

    @Test
    public void completedBuildingIsRejectedBeforePathSearch() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot complete=unit(2,"turret",0,40,0,true,1f);
        Map<String,Object> result=snapshot(1000f,builder,complete)
                .constructionCheck(builder,complete,12,240,256,false);

        assertFalse(Boolean.TRUE.equals(result.get("valid")));
        assertEquals("target_complete",result.get("reason"));
    }

    @Test
    public void progressBelowOneIsStillAnUnfinishedConstructionSite() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot almostComplete=unit(2,"turret",0,40,0,true,0.9995f);

        Map<String,Object> result=snapshot(1000f,builder,almostComplete)
                .constructionCheck(builder,almostComplete,12,240,256,false);

        assertTrue(Boolean.TRUE.equals(result.get("valid")));
        assertEquals("ok",result.get("reason"));
    }

    @Test
    public void allyConstructionIsAcceptedButEnemyConstructionIsRejected() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot ally=unit(2,"turret",1,40,0,true,0.5f);
        UnitSnapshot enemy=unit(3,"turret",2,40,0,true,0.5f);

        Map<String,Object> allyResult=snapshot(1000f,builder,ally)
                .constructionCheck(builder,ally,12,240,256,false);
        Map<String,Object> enemyResult=snapshot(1000f,builder,enemy)
                .constructionCheck(builder,enemy,12,240,256,false);

        assertTrue(Boolean.TRUE.equals(allyResult.get("valid")));
        assertEquals("target_not_friendly",enemyResult.get("reason"));
    }

    @Test
    public void boundedSearchIsRetryableInsteadOfBeingCachedAsPermanentlyUnreachable() {
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f);
        UnitSnapshot target=unit(2,"turret",0,700,0,true,0.2f);
        GameSnapshot.PathReader reader=(x,y,movement)->{
            LinkedHashMap<String,Object> result=new LinkedHashMap<>();boolean passable=x<100||x>110;
            result.put("available",true);result.put("passable",passable);
            result.put("grid_x",Math.max(0,(int)x+500));result.put("grid_y",Math.max(0,(int)y+500));
            result.put("width",2000);result.put("height",2000);result.put("world_to_grid",1f);return result;
        };
        GameSnapshot snapshot=new GameSnapshot(10,false,0,Arrays.asList(builder,target),Collections.emptyMap(),
                Collections.emptyMap(),Collections.emptyMap(),Collections.emptyList(),null,null,reader);

        Map<String,Object> result=snapshot.constructionCheck(builder,target,12,240,64,false,0,1);

        assertFalse(Boolean.TRUE.equals(result.get("valid")));
        assertTrue(Boolean.TRUE.equals(result.get("retryable")));
        assertEquals("search_limit",result.get("reason"));
    }

    @Test
    public void constructionStatusAcknowledgesNativeRepairIntent() {
        LinkedHashMap<String,Object> extras=new LinkedHashMap<>();
        extras.put("intent_type","repair");extras.put("intent_target_id",2L);
        extras.put("order_source","script");extras.put("order_command_id",9L);extras.put("order_issued_tick",4);
        UnitSnapshot builder=unit(1,"builder",0,0,0,false,1f,extras);
        UnitSnapshot target=unit(2,"turret",0,80,0,true,0.4f);
        GameSnapshot snapshot=new GameSnapshot(10,false,0,Arrays.asList(builder,target));

        Map<String,Object> result=snapshot.constructionStatus(builder,target);

        assertTrue(Boolean.TRUE.equals(result.get("active")));
        assertTrue(Boolean.TRUE.equals(result.get("acknowledged")));
        assertEquals("working",result.get("state"));
        assertEquals(6,((Number)result.get("order_age")).intValue());
    }

    private static GameSnapshot snapshot(float landLimit,UnitSnapshot... units){
        GameSnapshot.PathReader reader=(x,y,movement)->{
            LinkedHashMap<String,Object> result=new LinkedHashMap<>();
            result.put("available",true);result.put("passable",x<landLimit);
            result.put("grid_x",Math.max(0,(int)x));result.put("grid_y",Math.max(0,(int)y+1000));
            result.put("width",2400);result.put("height",2400);result.put("world_to_grid",1f);
            return result;
        };
        return new GameSnapshot(10,false,0,Arrays.asList(units),Collections.emptyMap(),
                Collections.emptyMap(),Collections.emptyMap(),Collections.emptyList(),null,null,reader);
    }

    private static UnitSnapshot unit(long id,String type,int relation,float x,float y,boolean building,float progress){
        return unit(id,type,relation,x,y,building,progress,Collections.emptyMap());
    }

    private static UnitSnapshot unit(long id,String type,int relation,float x,float y,boolean building,float progress,
                                     Map<String,Object> extras){
        return new UnitSnapshot(id,type,type,relation==0?0:1,"team",relation,x,y,0,0,building?30:12,
                100,100,0,0,progress,false,false,false,false,true,false,false,building,"LAND",
                0,0,0,0,null,null,null,null,null,null,2,Collections.emptyList(),
                Collections.emptyList(),extras,null);
    }
}
