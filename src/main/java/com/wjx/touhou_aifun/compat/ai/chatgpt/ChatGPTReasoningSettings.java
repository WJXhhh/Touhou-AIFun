package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/** Preferences shared by the server's subscription site and its GUI. */
public record ChatGPTReasoningSettings(boolean summary, String effort) {
    public static final List<String> EFFORTS = List.of("default", "none", "low", "medium", "high", "xhigh", "max");
    public static final ChatGPTReasoningSettings DEFAULT = new ChatGPTReasoningSettings(true, "default");

    public ChatGPTReasoningSettings { effort = normalize(effort); }

    public static String normalize(String value) { return EFFORTS.contains(value) ? value : "default"; }

    public JsonObject requestOptions(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        JsonObject options = new JsonObject();
        if (!(name.startsWith("gpt-5") || name.startsWith("gpt-6") || name.startsWith("o1")
                || name.startsWith("o3") || name.startsWith("o4"))) return options;
        String selected = effort;
        if ("none".equals(selected) && (name.startsWith("gpt-6-astra") || name.startsWith("gpt-6.1-sol")
                || name.equals("gpt-5") || name.startsWith("gpt-5-") || name.startsWith("o"))) selected = "low";
        if ("max".equals(selected) && !(name.startsWith("gpt-6") || name.startsWith("gpt-5.6"))) selected = "xhigh";
        if ("xhigh".equals(selected) && !(name.startsWith("gpt-6") || name.startsWith("gpt-5.6")
                || name.startsWith("gpt-5.5") || name.startsWith("gpt-5.4") || name.startsWith("gpt-5.2"))) selected = "high";
        if (!"default".equals(selected)) options.addProperty("effort", selected);
        if (summary && !"none".equals(selected)) options.addProperty("summary", "auto");
        return options;
    }
}
