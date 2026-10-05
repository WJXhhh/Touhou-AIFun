package com.wjx.touhou_aifun.maid.gui;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuiTransferLedgerTest {
    private static Map<String,Integer> count(int n) { return n==0?Map.of():Map.of("iron",n); }
    @Test void cursorIsProvisionalAndCommitIsCountedOnce() {
        var ledger=new GuiTransferLedger();
        assertTrue(ledger.observe(count(4),count(0),count(0),count(0)).isEmpty());
        assertEquals(List.of(new GuiTransferLedger.Transfer("iron",4,true)),ledger.observe(count(0),count(0),count(0),count(4)));
        assertTrue(ledger.observe(count(0),count(0),count(4),count(4)).isEmpty());
    }
    @Test void internalCursorReturnAndReturnToSourceAreNotExternalTransfers() {
        var ledger=new GuiTransferLedger();
        assertTrue(ledger.observe(count(4),count(4),count(3),count(0)).isEmpty());
        assertTrue(ledger.observe(count(4),count(4),count(0),count(3)).isEmpty());
        assertTrue(ledger.observe(count(4),count(0),count(3),count(3)).isEmpty());
        assertTrue(ledger.observe(count(0),count(4),count(3),count(3)).isEmpty());
    }
    @Test void reverseTransferAndPartialInsertionPreserveNetCounts() {
        var ledger=new GuiTransferLedger();
        ledger.observe(count(4),count(0),count(0),count(0));
        assertEquals(List.of(new GuiTransferLedger.Transfer("iron",2,true)),ledger.observe(count(0),count(0),count(0),count(2)));
        assertEquals(List.of(new GuiTransferLedger.Transfer("iron",2,true)),ledger.observe(count(0),count(0),count(2),count(4)));
        ledger.observe(count(0),count(0),count(4),count(2));
        assertEquals(List.of(new GuiTransferLedger.Transfer("iron",2,false)),ledger.observe(count(0),count(2),count(2),count(2)));
    }
}
