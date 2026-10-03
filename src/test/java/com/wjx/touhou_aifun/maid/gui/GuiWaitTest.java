package com.wjx.touhou_aifun.maid.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiWaitTest {
    @Test void explicitHumanDeadlineCannotBeExtendedByModel() {
        assertEquals(10, GuiWaitIntent.maximumSeconds("做好拿给我，最多等十秒"));
        assertEquals(600, GuiWaitIntent.maximumSeconds("最多等待10分钟"));
        assertEquals(15, GuiWaitIntent.maximumSeconds("wait at most 15 seconds"));
        assertEquals(0, GuiWaitIntent.maximumSeconds("等烧完"));
    }
    private static GuiProcessState running(long progress, long eta) {
        return new GuiProcessState(GuiProcessState.Status.RUNNING, "", progress, 200, progress / 200, eta, 20, true);
    }
    @Test void automaticShortAndLongJobs() {
        GuiWaitBudget shortJob = new GuiWaitBudget(); shortJob.configure(GuiWaitPolicy.AUTO, 200, 18000, 0);
        assertEquals(400, shortJob.remaining());
        GuiWaitBudget longJob = new GuiWaitBudget(); longJob.configure(GuiWaitPolicy.AUTO, 12800, 18000, 0);
        assertTrue(longJob.exhausted());
        GuiWaitBudget boundary = new GuiWaitBudget(); boundary.configure(GuiWaitPolicy.AUTO, 1200, 18000, 0);
        assertEquals(1800, boundary.remaining());
    }
    @Test void noWaitOverridesModelAndUnknownBudgetIsFinite() {
        assertEquals(GuiWaitPolicy.NO_WAIT, GuiWaitIntent.resolve("烧好拿给我，但是不用等", GuiWaitPolicy.UNTIL_GOAL));
        assertEquals(GuiWaitPolicy.NO_WAIT, GuiWaitIntent.resolve("put it in, don't wait", GuiWaitPolicy.AUTO));
        assertEquals(GuiWaitPolicy.AUTO, GuiWaitIntent.resolve("把这些矿烧一下", GuiWaitPolicy.AUTO));
        assertEquals(GuiWaitPolicy.UNTIL_GOAL, GuiWaitIntent.resolve("等烧完", GuiWaitPolicy.AUTO));
        assertEquals(GuiWaitPolicy.UNTIL_GOAL, GuiWaitIntent.resolve("做好拿给我", GuiWaitPolicy.AUTO));
        assertEquals(GuiWaitPolicy.NO_WAIT, GuiWaitIntent.resolve("做好拿给我，但是不用等", GuiWaitPolicy.UNTIL_GOAL));
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.AUTO, -1, 18000, 0);
        assertEquals(600, budget.remaining());
    }
    @Test void repairsAndReopensDoNotRechargeOrShortenKnownBudget() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.AUTO, 1200, 18000, 0); budget.advance(800);
        budget.configure(GuiWaitPolicy.AUTO, 400, 18000, 0); assertEquals(1000, budget.remaining());
        budget.advance(1000); budget.configure(GuiWaitPolicy.AUTO, 1200, 18000, 0); assertTrue(budget.exhausted());
    }
    @Test void unknownEstimateCanBecomeKnownWithoutResettingElapsed() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.AUTO, -1, 18000, 0); budget.advance(100);
        budget.configure(GuiWaitPolicy.AUTO, 1000, 18000, 0); assertEquals(1500, budget.remaining()); assertEquals(100, budget.consumed());
    }
    @Test void explicitLimitAndUntilGoalIgnoreInitialEta() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.UNTIL_GOAL, 20, 18000, 200);
        GuiWaitTracker tracker = new GuiWaitTracker(0);
        assertEquals("waiting", tracker.sample(20, running(20, 20), budget, false));
        assertEquals("waiting", tracker.sample(100, running(100, 400), budget, false));
        assertEquals("still_processing", tracker.sample(200, running(150, 400), budget, false));
    }
    @Test void missingFuelNeedsStartupGraceAndTwoSamples() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.UNTIL_GOAL, -1, 18000, 0);
        GuiWaitTracker tracker = new GuiWaitTracker(0);
        var blocked = new GuiProcessState(GuiProcessState.Status.BLOCKED, "missing_fuel", 0, 200, 0, -1, 20, true);
        assertEquals("waiting", tracker.sample(20, blocked, budget, false));
        assertEquals("waiting", tracker.sample(60, blocked, budget, false));
        assertEquals("blocked_missing_fuel", tracker.sample(80, blocked, budget, false));
    }
    @Test void fuelAnimationAndUnknownStateDoNotCountAsProgress() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.UNTIL_GOAL, -1, 18000, 0);
        GuiWaitTracker tracker = new GuiWaitTracker(0);
        assertEquals("waiting", tracker.sample(20, running(0, 1000), budget, false));
        assertEquals("stalled", tracker.sample(320, running(0, 700), budget, false));
        GuiWaitBudget unknown = new GuiWaitBudget(); unknown.configure(GuiWaitPolicy.AUTO, -1, 18000, 0);
        GuiWaitTracker untracked = new GuiWaitTracker(0);
        assertEquals("waiting", untracked.sample(400, GuiProcessState.unknown(), unknown, false));
        assertEquals("progress_unknown", untracked.sample(600, GuiProcessState.unknown(), unknown, false));
    }
    @Test void slowMachinesAndActualDelivery() {
        GuiWaitBudget budget = new GuiWaitBudget(); budget.configure(GuiWaitPolicy.UNTIL_GOAL, -1, 18000, 0);
        GuiWaitTracker tracker = new GuiWaitTracker(0);
        var slow = new GuiProcessState(GuiProcessState.Status.RUNNING, "", 1, 100, 0, 10000, 1000, true);
        assertEquals("waiting", tracker.sample(20, slow, budget, false));
        assertEquals("waiting", tracker.sample(1000, slow, budget, false));
        assertEquals("completed", tracker.sample(1020, slow, budget, true));
    }
}
