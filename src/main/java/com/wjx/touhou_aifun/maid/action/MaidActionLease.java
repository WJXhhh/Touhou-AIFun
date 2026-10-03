package com.wjx.touhou_aifun.maid.action;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread arbitration between physical tools. */
public final class MaidActionLease {
    private static final Map<UUID, Object> OWNERS = new HashMap<>();
    private MaidActionLease() { }
    public static boolean acquire(UUID maid, Object owner) {
        Object previous = OWNERS.putIfAbsent(maid, owner);
        return previous == null || previous == owner;
    }
    public static void release(UUID maid, Object owner) { OWNERS.remove(maid, owner); }
}
