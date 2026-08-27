package com.wjx.touhou_aifun.chat.context;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;

import java.util.ArrayList;
import java.util.List;

/** Applies the shared discretionary-history budget before any provider serializes messages. */
public final class ContextBudgetPlanner {
    private ContextBudgetPlanner() {
    }

    public record BudgetReport(int targetTokens, int estimatedTokens, int removedMessages,
                               double calibrationFactor, boolean overBudget) {
    }

    public record Plan(List<LLMMessage> messages, BudgetReport report) {
    }

    public static List<LLMMessage> trim(List<LLMMessage> source, int budget, int reserveMessages) {
        return trim(source, budget, reserveMessages, 1.0);
    }

    public static List<LLMMessage> trim(List<LLMMessage> source, int budget, int reserveMessages,
                                        double calibrationFactor) {
        return plan(source, budget, reserveMessages, calibrationFactor).messages();
    }

    public static Plan plan(List<LLMMessage> source, int budget, int reserveMessages,
                            double calibrationFactor) {
        if (source == null || source.isEmpty()) {
            return new Plan(List.of(), new BudgetReport(Math.max(0, budget - Math.max(0, reserveMessages)),
                    0, 0, Math.max(1.0, Math.min(2.0, calibrationFactor)), false));
        }
        // A very large immutable tool schema may consume the whole configured target. Do not
        // manufacture an extra 2K allowance: trim every discretionary unit, preserve fixed
        // system/current/tool content, and report the unavoidable over-budget request explicitly.
        int target = Math.max(0, budget - Math.max(0, reserveMessages));
        double factor = Math.max(1.0, Math.min(2.0, calibrationFactor));
        List<LLMMessage> result = new ArrayList<>(source);
        int originalSize = result.size();
        while (result.size() > 2 && ContextTokenEstimator.estimate(result) * factor > target) {
            int remove = findLowestPriorityRemovable(result);
            if (remove < 0) {
                TouhouLittleMaid.LOGGER.warn("AIFun context fixed content exceeds input budget: estimated={} target={}",
                        (int) Math.ceil(ContextTokenEstimator.estimate(result) * factor), target);
                break;
            }
            removeUnit(result, remove);
        }
        int estimated = (int) Math.ceil(ContextTokenEstimator.estimate(result) * factor);
        return new Plan(List.copyOf(result), new BudgetReport(target, estimated,
                Math.max(0, originalSize - result.size()), factor, estimated > target));
    }

    /** Implements the fixed eviction order from the layered-memory contract. */
    private static int findLowestPriorityRemovable(List<LLMMessage> messages) {
        int protectedPrefix = protectedPrefix(messages);

        // Recalled episodes are already ordered strongest-first by LocalMemoryRetriever, so the
        // last episode is the lowest-score item and is the first thing to evict.
        for (int i = messages.size() - 1; i >= protectedPrefix; i--) {
            if (isEpisode(messages.get(i))) return i;
        }

        // Remove whole old user/assistant turns, preserving the two most recent complete turns.
        int oldTurn = findOldestTurnPair(messages, protectedPrefix);
        if (oldTurn >= 0) return oldTurn;

        // Superseded input is useful for the immediately following request, but it is still
        // discretionary and must not make a constrained request exceed its target. Remove the
        // oldest item first when a rapid A -> B -> C chain contains several cancelled inputs.
        for (int i = protectedPrefix; i < messages.size(); i++) {
            if (isInterruptedInput(messages.get(i))) return i;
        }

        // Facts are emitted importance-descending, so the last fact is the least important one.
        for (int i = messages.size() - 1; i >= protectedPrefix; i--) {
            if (isFact(messages.get(i))) return i;
        }

        // Closed loops are not normally rendered, but keep the rule explicit for migrations or
        // future UI/debug blocks. Open loops are never returned here.
        for (int i = messages.size() - 1; i >= protectedPrefix; i--) {
            if (isClosedLoop(messages.get(i))) return i;
        }

        return -1;
    }

    private static int protectedPrefix(List<LLMMessage> messages) {
        if (messages.isEmpty() || messages.get(0).role() != Role.SYSTEM) return 0;
        // The first system prompt is always protected; the second is AIFun's memory declaration.
        return messages.size() > 1 && messages.get(1).role() == Role.SYSTEM
                && isMemoryDeclaration(messages.get(1)) ? 2 : 1;
    }

    private static int findOldestTurnPair(List<LLMMessage> messages, int protectedPrefix) {
        List<Integer> pairs = new ArrayList<>();
        int lastUser = -1;
        for (int i = protectedPrefix; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.USER) lastUser = i;
        }
        for (int i = protectedPrefix; i + 1 < messages.size(); i++) {
            LLMMessage user = messages.get(i);
            LLMMessage assistant = messages.get(i + 1);
            if (user.role() != Role.USER || i == lastUser || assistant.role() != Role.ASSISTANT
                    || hasToolCalls(assistant)) continue;
            // A system memory unit between the two messages means this is not a normal pair.
            if (isMemoryMessage(user) || isMemoryMessage(assistant)) continue;
            pairs.add(i);
        }
        // At least the two latest complete turns stay verbatim. The latest user (if present) is
        // protected independently, so it cannot be evicted by a generic fallback either.
        return pairs.size() > 2 ? pairs.get(0) : -1;
    }

    private static void removeUnit(List<LLMMessage> messages, int index) {
        if (index < 0 || index >= messages.size()) return;
        LLMMessage message = messages.get(index);
        if (message.role() == Role.USER && index + 1 < messages.size()
                && messages.get(index + 1).role() == Role.ASSISTANT
                && !hasToolCalls(messages.get(index + 1))) {
            messages.remove(index + 1);
            messages.remove(index);
            return;
        }
        // Tool messages and assistant tool-call groups are deliberately never selected, but keep
        // this defensive check so a future selector cannot split a protocol group.
        if (message.role() == Role.TOOL || hasToolCalls(message)) return;
        messages.remove(index);
    }

    private static boolean hasToolCalls(LLMMessage message) {
        return message.toolCalls() != null && !message.toolCalls().isEmpty();
    }

    private static boolean isMemoryDeclaration(LLMMessage message) {
        return message.message() != null && message.message().startsWith("## AIFun Memory");
    }

    private static boolean isEpisode(LLMMessage message) {
        return message.message() != null && message.message().startsWith("### Relevant older episode");
    }

    private static boolean isFact(LLMMessage message) {
        return message.message() != null && (message.message().startsWith("### Stable fact")
                || message.message().startsWith("### Fallible remembered fact"));
    }

    private static boolean isClosedLoop(LLMMessage message) {
        return message.message() != null && message.message().startsWith("### Closed loop");
    }

    private static boolean isMemoryMessage(LLMMessage message) {
        String text = message.message();
        return text != null && (text.startsWith("## AIFun Memory")
                || text.startsWith("### Stable fact")
                || text.startsWith("### Fallible remembered fact")
                || text.startsWith("### Open loop")
                || text.startsWith("### Closed loop")
                || text.startsWith("### Relevant older episode")
                || text.startsWith("### Recent interrupted user message"));
    }

    private static boolean isInterruptedInput(LLMMessage message) {
        return message.message() != null
                && message.message().startsWith("### Recent interrupted user message");
    }
}
