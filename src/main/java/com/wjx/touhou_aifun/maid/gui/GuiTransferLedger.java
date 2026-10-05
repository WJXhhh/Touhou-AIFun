package com.wjx.touhou_aifun.maid.gui;

import java.util.*;

/** Cursor moves are provisional until an item reaches the other inventory. Never persisted as authority. */
public final class GuiTransferLedger {
    public record Transfer(String item, int count, boolean toMaid) { }
    private final Map<String,Integer> incoming = new HashMap<>(), outgoing = new HashMap<>();
    public List<Transfer> observe(Map<String,Integer> externalBefore, Map<String,Integer> externalAfter,
                                  Map<String,Integer> maidBefore, Map<String,Integer> maidAfter) {
        Set<String> items = new HashSet<>(externalBefore.keySet());items.addAll(externalAfter.keySet());
        items.addAll(maidBefore.keySet());items.addAll(maidAfter.keySet());
        List<Transfer> transfers = new ArrayList<>();
        for (String item : items) {
            int externalDelta = externalAfter.getOrDefault(item,0)-externalBefore.getOrDefault(item,0);
            int maidDelta = maidAfter.getOrDefault(item,0)-maidBefore.getOrDefault(item,0);
            if(externalDelta<0) incoming.merge(item,-externalDelta,Integer::sum);
            if(maidDelta<0) outgoing.merge(item,-maidDelta,Integer::sum);
            if(maidDelta>0) {
                int remaining=maidDelta-consume(outgoing,item,maidDelta);
                int received=consume(incoming,item,remaining);
                if(received>0) transfers.add(new Transfer(item,received,true));
            }
            if(externalDelta>0) {
                int remaining=externalDelta-consume(incoming,item,externalDelta);
                int returned=consume(outgoing,item,remaining);
                if(returned>0) transfers.add(new Transfer(item,returned,false));
            }
        }
        return transfers;
    }
    private static int consume(Map<String,Integer> pending,String item,int amount) {
        int available=pending.getOrDefault(item,0), consumed=Math.min(available,amount);
        if(available==consumed) pending.remove(item); else pending.put(item,available-consumed);
        return consumed;
    }
}
