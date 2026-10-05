package com.wjx.touhou_aifun.chat.agent;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Detects recurring domain states and alternating observations at complete batch boundaries. */
public final class AgentProgressGuard {
    private final Set<String> states = new LinkedHashSet<>();
    private final Set<String> observations = new LinkedHashSet<>();
    private long verifiedCount;
    private int goalVersion;
    private int stalled;
    public int observe(String domainState, Collection<String> results, long count, int version) {
        if (version != goalVersion) { states.clear(); observations.clear(); stalled = 0; goalVersion = version; verifiedCount = count; }
        boolean recurringState = !remember(states, digest(domainState));
        boolean recurringResults = !results.isEmpty();
        for (String result : results) recurringResults &= !remember(observations, digest(ToolResultProjection.progress(result)));
        stalled = count > verifiedCount || !recurringState || !recurringResults ? 0 : stalled + 1;
        verifiedCount = count;
        return stalled;
    }
    private static boolean remember(Set<String> values, String value) {
        boolean added = values.add(value);
        while (values.size() > 128) values.remove(values.iterator().next());
        return added;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
