package com.shizuku.rwmiao.module.script;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Metadata registered by executable Lua through rw.script{...}. */
public final class ScriptDefinition {
    private static final Set<String> ALLOWED_DATA = new LinkedHashSet<>();
    static { Collections.addAll(ALLOWED_DATA,"identity","team","position","health","movement","combat","weapons","orders","pathing","transport","build","production","actions","abilities","damage","selection","map","resources","projectiles","environment","catalog","all"); }
    public final int api;
    public final String id;
    public final String name;
    public final List<String> unitTypes;
    public final Set<String> dataGroups;
    public final List<ScriptSetting> settings;
    public final String sourceName;

    ScriptDefinition(int api, String id, String name, List<String> units,
                     List<String> dataGroups, List<ScriptSetting> settings, String sourceName) throws IOException {
        if (api != 1) throw new IOException("仅支持 api=1，当前为 " + api);
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IOException("id 只能包含字母、数字、点、横线和下划线");
        ArrayList<String> normalized = new ArrayList<>();
        if (units != null) for (String unit : units) {
            if (unit != null && !unit.trim().isEmpty()) normalized.add(unit.trim());
        }
        if (normalized.isEmpty()) throw new IOException("脚本必须声明 units（可多选，或写 All）");
        this.api = api;
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? id : name.trim();
        this.unitTypes = Collections.unmodifiableList(normalized);
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (dataGroups != null) for (String group : dataGroups)
            if (group != null && !group.trim().isEmpty()) {
                String normalizedGroup=group.trim().toLowerCase();
                if(!ALLOWED_DATA.contains(normalizedGroup))throw new IOException("未知 data 分组: "+group);
                requested.add(normalizedGroup);
            }
        if (requested.isEmpty()) requested.add("all");
        this.dataGroups = Collections.unmodifiableSet(requested);
        if(settings==null)settings=Collections.emptyList();
        if(settings.size()>32)throw new IOException("设置项不能超过 32 个");
        LinkedHashSet<String> settingKeys=new LinkedHashSet<>();
        for(ScriptSetting setting:settings)if(!settingKeys.add(setting.key))throw new IOException("重复设置 key: "+setting.key);
        this.settings=Collections.unmodifiableList(new ArrayList<>(settings));
        this.sourceName = sourceName == null ? id + ".lua" : sourceName;
    }

    public boolean acceptsUnit(String typeId, String typeName) {
        for (String value : unitTypes) {
            // Unit IDs are identifiers, not display text: exact, case-sensitive matching only.
            if ("*".equals(value) || "All".equals(value) || equalsType(value, typeId)) return true;
        }
        return false;
    }

    private static boolean equalsType(String a, String b) {
        return a != null && b != null && a.equals(b);
    }
}
