package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The background completion wording must only be reused for the exact goal that prepared it. */
class TaskAnnouncementPrefetchTest {
    @AfterEach
    void clear() {
        TaskAnnouncementPrefetch.clearForTests();
    }

    @Test
    void preparedWordingIsReusedOnceForTheSameGoal() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("put five iron into the north chest");
        CompletableFuture<String> prepared = new CompletableFuture<>();
        TaskAnnouncementPrefetch.prime(maid, task, () -> prepared);

        assertSame(prepared, TaskAnnouncementPrefetch.take(maid, task));
        assertNull(TaskAnnouncementPrefetch.take(maid, task), "One completion consumes one preparation");
        assertEquals(0, TaskAnnouncementPrefetch.sizeForTests());
    }

    @Test
    void repeatedPrimeForTheSameGoalDoesNotSendAnotherRequest() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("sort the four labelled chests");
        AtomicInteger requests = new AtomicInteger();
        TaskAnnouncementPrefetch.prime(maid, task, () -> {
            requests.incrementAndGet();
            return new CompletableFuture<>();
        });
        TaskAnnouncementPrefetch.prime(maid, task, () -> {
            requests.incrementAndGet();
            return new CompletableFuture<>();
        });
        assertEquals(1, requests.get(), "Re-entering the same task must not duplicate the request");
        assertEquals(1, TaskAnnouncementPrefetch.sizeForTests());
    }

    @Test
    void amendedGoalReplacesTheStalePreparation() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("take the iron out");
        CompletableFuture<String> first = new CompletableFuture<>();
        TaskAnnouncementPrefetch.prime(maid, task, () -> first);

        task.goal = "take the iron out and also bring the coal";
        task.goalVersion = 2;
        assertNull(TaskAnnouncementPrefetch.take(maid, task), "Old-goal wording must never be spoken");
        assertNull(TaskAnnouncementPrefetch.take(maid, task), "The stale entry is discarded on read");

        AtomicInteger requests = new AtomicInteger();
        TaskAnnouncementPrefetch.prime(maid, task, () -> {
            requests.incrementAndGet();
            return new CompletableFuture<>();
        });
        assertEquals(1, requests.get(), "The amended goal gets its own background request");
    }

    @Test
    void ownerConstraintChangesThePreparedTextEvenWithoutEditingTheRawGoal() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("craft the soft-board brush");
        TaskAnnouncementPrefetch.prime(maid, task, CompletableFuture::new);
        task.amend("Actually the vanilla brush: minecraft:brush, feather and copper ingot");
        task.applyAmendment();

        assertNull(TaskAnnouncementPrefetch.take(maid, task),
                "The wording prepared for the old goal must not be spoken after a correction");
        CompletableFuture<String> corrected = new CompletableFuture<>();
        TaskAnnouncementPrefetch.prime(maid, task, () -> corrected);
        assertSame(corrected, TaskAnnouncementPrefetch.take(maid, task));
    }

    @Test
    void goalVersionChangeWithoutTextChangeIsStillStale() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("receive two iron");
        TaskAnnouncementPrefetch.prime(maid, task, CompletableFuture::new);
        task.goalVersion = 3;
        assertNull(TaskAnnouncementPrefetch.take(maid, task));
    }

    @Test
    void failedPreparationIsReturnedSoDeliveryFallsBack() {
        UUID maid = UUID.randomUUID();
        AgentTaskState task = new AgentTaskState("receive two iron");
        TaskAnnouncementPrefetch.prime(maid, task,
                () -> CompletableFuture.failedFuture(new IllegalStateException("offline")));
        CompletableFuture<String> taken = TaskAnnouncementPrefetch.take(maid, task);
        assertNotNull(taken);
        assertTrue(taken.isCompletedExceptionally(),
                "A failed background request still completes the notification through the fixed fallback");
    }

    @Test
    void clearDropsOnlyThisMaidsPreparation() {
        UUID firstMaid = UUID.randomUUID();
        UUID secondMaid = UUID.randomUUID();
        AgentTaskState firstTask = new AgentTaskState("first task");
        AgentTaskState secondTask = new AgentTaskState("second task");
        TaskAnnouncementPrefetch.prime(firstMaid, firstTask, CompletableFuture::new);
        TaskAnnouncementPrefetch.prime(secondMaid, secondTask, CompletableFuture::new);

        TaskAnnouncementPrefetch.clear(firstMaid);
        assertNull(TaskAnnouncementPrefetch.take(firstMaid, firstTask));
        assertNotNull(TaskAnnouncementPrefetch.take(secondMaid, secondTask),
                "Another maid keeps its own preparation");
    }

    @Test
    void unseenTaskHasNoPreparation() {
        assertNull(TaskAnnouncementPrefetch.take(UUID.randomUUID(), new AgentTaskState("never started")));
    }
}
