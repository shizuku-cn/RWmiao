package com.shizuku.rwmiao.module.script;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

/**
 * Enforces the Lua instruction quota with sampled traceback bookkeeping.
 * 通过采样调用栈维护来执行 Lua 指令配额限制。
 */
final class LuaInstructionLimiter extends DebugLib {
    private static final int TRACE_SAMPLE_MASK = 63;
    private int remaining = Integer.MAX_VALUE;
    private int traceSample;

    void begin(int instructions) {
        remaining = Math.max(0, instructions);
        traceSample = 0;
    }

    void end() {
        remaining = Integer.MAX_VALUE;
        traceSample = 0;
    }

    @Override
    public void onInstruction(int pc, Varargs v, int top) {
        if (--remaining < 0) throw new LuaError("Lua 指令配额已用完");
        if ((traceSample++ & TRACE_SAMPLE_MASK) == 0) super.onInstruction(pc, v, top);
    }
}
