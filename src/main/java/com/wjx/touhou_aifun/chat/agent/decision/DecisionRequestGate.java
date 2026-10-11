package com.wjx.touhou_aifun.chat.agent.decision;

import java.util.*;

/** Shared across maids: no waiting queue on a rate-limited decision path. */
public final class DecisionRequestGate {
    private static final Map<String,Deque<Long>> REQUESTS=new HashMap<>();
    private static final Map<String,Long> BLOCKED_UNTIL=new HashMap<>();
    private DecisionRequestGate() { }
    public static synchronized boolean acquire(String provider,long now) {
        if(now<BLOCKED_UNTIL.getOrDefault(provider,0L)) return false;
        var recent=REQUESTS.computeIfAbsent(provider,k->new ArrayDeque<>());
        while(!recent.isEmpty() && now-recent.peekFirst()>=1000000000L) recent.removeFirst();
        // Perplexity's organization-wide ceiling is 10/s; use the same conservative local ceiling for JEV.
        if(recent.size()>=10) return false;
        recent.addLast(now);return true;
    }
    public static synchronized void coolDown(String provider,long now) { BLOCKED_UNTIL.put(provider,now+1000000000L); }
}
