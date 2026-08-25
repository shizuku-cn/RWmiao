package com.shizuku.rwmiao.payload;

import com.corrodinggames.rts.game.units.a.s;
import com.corrodinggames.rts.game.units.a.t;
import com.corrodinggames.rts.game.units.a.u;
import com.corrodinggames.rts.game.units.ce;
import com.corrodinggames.rts.game.units.el;

/** Local action; the module coordinates native commands for the selection. */
public final class RWMiaoSegmentAction extends s {
    public static RWMiaoSegmentAction INST = new RWMiaoSegmentAction();
    public RWMiaoSegmentAction() { super("c__cut_rwmiao_segment"); }
    public String a() { return "Segment native commands"; }
    public int b(ce unit, boolean preview) { return -1; }
    public String b() { return RWMiaoBridge.segmentTitle(); }
    public int c() { return 0; }
    public u d() { return enumValue(u.values(), "infoOnly"); }
    public t e() { return enumValue(t.values(), "infoOnly"); }
    public boolean f() { return false; }
    public el h() { return null; }
    public String i() { return b(); }
    public boolean k() { return false; }
    public boolean I() { return false; }
    public boolean q() { return true; }
    public float l() { return 0.5f; }
    public boolean c(ce unit, boolean alternate) {
        RWMiaoBridge.toggleSegment();
        return true;
    }

    private static <E extends Enum<E>> E enumValue(E[] values, String wanted) {
        for (E value : values) {
            if (wanted.equals(value.name())) return value;
        }
        return values.length == 0 ? null : values[0];
    }
}
