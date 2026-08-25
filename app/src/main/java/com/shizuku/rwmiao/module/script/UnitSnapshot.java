package com.shizuku.rwmiao.module.script;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Stable, immutable unit data exposed to automation logic. */
public final class UnitSnapshot {
    public final long id;
    public final String typeId;
    public final String typeName;
    public final int teamId;
    public final String teamName;
    /** 0 self, 1 enemy, 2 ally/neutral. */
    public final int relation;
    public final float x, y, height, heading, radius;
    public final float health, maxHealth, shield, maxShield, buildProgress;
    public final boolean dead, deleted, selected, attached, orderable, custom, factory, building;
    public final String movementType;
    public final float attackRange;
    public final float maxMoveSpeed;
    public final int weaponCount, waypointCount, queueSize;
    public final Long targetId, carrierId;
    public final String currentOrder;
    public final Long orderTargetId;
    public final Float orderX, orderY;
    public final List<String> actionIds;
    public final List<String> buildableTypes;
    /** Version-adapter data which can be extended without changing the stable constructor. */
    public final Map<String,Object> extras;
    final Object raw;

    UnitSnapshot(long id, String typeId, String typeName, int teamId, String teamName,
                 int relation, float x, float y, float height, float heading, float radius,
                 float health, float maxHealth, float shield, float maxShield, float buildProgress,
                 boolean dead, boolean deleted, boolean selected, boolean attached,
                 boolean orderable, boolean custom, boolean factory, boolean building, String movementType,
                 float attackRange, int weaponCount, int waypointCount, int queueSize,
                 Long targetId, Long carrierId, String currentOrder, Long orderTargetId,
                 Float orderX, Float orderY, Object raw) {
        this(id,typeId,typeName,teamId,teamName,relation,x,y,height,heading,radius,health,maxHealth,
                shield,maxShield,buildProgress,dead,deleted,selected,attached,orderable,custom,factory,building,
                movementType,attackRange,weaponCount,waypointCount,queueSize,targetId,carrierId,currentOrder,
                orderTargetId,orderX,orderY,0f,Collections.emptyList(),Collections.emptyList(),Collections.emptyMap(),raw);
    }

    UnitSnapshot(long id, String typeId, String typeName, int teamId, String teamName,
                 int relation, float x, float y, float height, float heading, float radius,
                 float health, float maxHealth, float shield, float maxShield, float buildProgress,
                 boolean dead, boolean deleted, boolean selected, boolean attached,
                 boolean orderable, boolean custom, boolean factory, boolean building, String movementType,
                 float attackRange, int weaponCount, int waypointCount, int queueSize,
                 Long targetId, Long carrierId, String currentOrder, Long orderTargetId,
                 Float orderX, Float orderY, float maxMoveSpeed, List<String> actionIds,
                 List<String> buildableTypes, Map<String,Object> extras, Object raw) {
        this.id = id; this.typeId = typeId; this.typeName = typeName;
        this.teamId = teamId; this.teamName = teamName; this.relation = relation;
        this.x = x; this.y = y; this.height = height; this.heading = heading; this.radius = radius;
        this.health = health; this.maxHealth = maxHealth; this.shield = shield;
        this.maxShield = maxShield; this.buildProgress = buildProgress;
        this.dead = dead; this.deleted = deleted; this.selected = selected; this.attached = attached;
        this.orderable = orderable; this.custom = custom; this.factory = factory; this.building=building;
        this.movementType = movementType; this.attackRange = attackRange;
        this.maxMoveSpeed = maxMoveSpeed;
        this.weaponCount = weaponCount; this.waypointCount = waypointCount; this.queueSize = queueSize;
        this.targetId = targetId; this.carrierId = carrierId; this.raw = raw;
        this.currentOrder = currentOrder; this.orderTargetId = orderTargetId;
        this.orderX = orderX; this.orderY = orderY;
        this.actionIds = Collections.unmodifiableList(actionIds);
        this.buildableTypes = Collections.unmodifiableList(buildableTypes);
        this.extras = Collections.unmodifiableMap(extras);
    }

    public float healthRatio() { return maxHealth > 0f ? health / maxHealth : 0f; }
    public float shieldRatio() { return maxShield > 0f ? shield / maxShield : 0f; }
    public float combinedHealthRatio() {
        float maximum = maxHealth + maxShield;
        return maximum > 0f ? (health + shield) / maximum : 0f;
    }
    public float distanceSquared(UnitSnapshot other) {
        float dx = x - other.x, dy = y - other.y;
        return dx * dx + dy * dy;
    }
}
