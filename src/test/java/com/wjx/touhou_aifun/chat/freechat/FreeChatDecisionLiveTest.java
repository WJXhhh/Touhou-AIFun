package com.wjx.touhou_aifun.chat.freechat;

import com.wjx.touhou_aifun.chat.agent.decision.NativeDecisionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in calibration; never prints credentials, transcripts, scenes or provider response bodies. */
@EnabledIfEnvironmentVariable(named = "AIFUN_FREE_CHAT_DECISION_CONFIG", matches = ".+")
class FreeChatDecisionLiveTest {
    private static final FreeChatDecision.Thresholds THRESHOLDS =
            new FreeChatDecision.Thresholds(0.7D, 0.85D, 0.7D, 0.5D, 1.15D, 0.5D, 0.35D);

    @Test
    void repeatedMaterialAnswerIsAConversationContinuation() throws Exception {
        assertTrue(evaluate("repeated_material", "暗物质、暗物质", true,
                "主人，您这话好像没说完呀，是想让我看看能不能做什么？是MK2接着做，还是让我去附近哪儿瞧瞧？",
                List.of("你看看能不能做暗物质")).accepted());
    }

    @Test
    void offscreenSelfTalkStillDoesNotStartATurn() throws Exception {
        assertFalse(evaluate("ambient_control", "这苦力怕真烦人", false,
                "我在这里等你。", List.of()).accepted());
    }

    private static FreeChatDecision.Verdict evaluate(String label, String transcript, boolean seen,
                                                     String reply, List<String> recent) throws Exception {
        String toml = Files.readString(Path.of(System.getenv("AIFUN_FREE_CHAT_DECISION_CONFIG")));
        var profile = Pattern.compile("(?ms)^\\h*\\[agentRuntimeV1\\.decisionService\\.perplexity]\\h*\\R"
                + "(.*?)(?=^\\h*\\[|\\z)").matcher(toml);
        assertTrue(profile.find(), "Perplexity profile is missing");
        String section = profile.group(1);
        var client = new NativeDecisionProvider("perplexity", field(section, "endpoint"),
                field(section, "model"), field(section, "apiKey"), 15000);
        var maid = new FreeChatDecision.Candidate(0, 4, "Wine Fox（酒狐）", 3.0D,
                seen ? "front" : "back", seen, seen, false, reply);
        var candidates = List.of(maid);
        var state = FreeChatDecision.state(transcript, 1020, "owner",
                "One nearby owned maid; no other player is visible.", recent, candidates);
        var result = client.evaluate(state, FreeChatDecision.questions(candidates), ignored -> {})
                .get(20, TimeUnit.SECONDS);
        var verdict = FreeChatDecision.accept(result.answers(), candidates, THRESHOLDS, false);
        System.out.println("Free chat calibration " + label + ": accepted=" + verdict.accepted()
                + " reason=" + verdict.reason() + " " + FreeChatDecision.summary(result.answers()));
        return verdict;
    }

    private static String field(String section, String name) {
        var value = Pattern.compile("(?m)^\\h*" + Pattern.quote(name) + "\\h*=\\h*\"([^\"\\r\\n]*)\"")
                .matcher(section);
        assertTrue(value.find(), "Missing profile field: " + name);
        assertFalse(value.group(1).isBlank(), "Empty profile field: " + name);
        return value.group(1);
    }
}
