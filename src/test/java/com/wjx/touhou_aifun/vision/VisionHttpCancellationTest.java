package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class VisionHttpCancellationTest {
    @AfterEach void reset() { VisionHttpCancellation.cancelAll(); }
    @Test void delayedOldTransportCannotReplaceCurrentRequestAfterInterrupt() {
        UUID maid = UUID.randomUUID();
        long oldTicket = VisionHttpCancellation.ticket(maid);
        VisionHttpCancellation.cancelForMaid(maid);
        long ticket = VisionHttpCancellation.ticket(maid);
        var current = new CompletableFuture<>();
        VisionHttpCancellation.register(maid, current, ticket);
        var late = new CompletableFuture<>();
        VisionHttpCancellation.register(maid, late, oldTicket);
        assertTrue(late.isCancelled());
        assertFalse(current.isCancelled());
        VisionHttpCancellation.cancelForMaid(maid);
        assertTrue(current.isCancelled());
    }
    @Test void shutdownInvalidatesTicketsAcrossServerSessions() {
        UUID maid = UUID.randomUUID();
        long ticket = VisionHttpCancellation.ticket(maid);
        VisionHttpCancellation.cancelAll();
        assertFalse(VisionHttpCancellation.isCurrent(maid, ticket));
        assertNotEquals(ticket, VisionHttpCancellation.ticket(maid));
    }
}
