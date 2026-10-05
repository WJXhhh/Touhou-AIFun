package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Independent top-k sign list; apply the byte budget only after prioritizing all visible signs. */
final class SignTextCollector {
    private static final int MAX_SIGNS = 16;
    private static final int MAX_JSON_BYTES = 4 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Comparator<ScannedSign> order;
    private final List<ScannedSign> signs = new ArrayList<>();
    private int seen;
    private boolean textTruncated;

    SignTextCollector(String focus) {
        order = Comparator.comparing((ScannedSign sign) -> !VisionFocusMatcher.matches(focus, sign.registryId()))
                .thenComparingDouble(ScannedSign::distance)
                .thenComparingInt(ScannedSign::dx).thenComparingInt(ScannedSign::dy).thenComparingInt(ScannedSign::dz);
    }

    void add(ScannedSign sign) {
        seen++;
        textTruncated |= sign.textTruncated();
        signs.add(sign);
        signs.sort(order);
        if (signs.size() > MAX_SIGNS) signs.remove(signs.size() - 1);
    }

    Snapshot finish() {
        List<ScannedSign> retained = new ArrayList<>(signs);
        List<String> reasons = new ArrayList<>();
        if (seen > MAX_SIGNS) reasons.add("sign_count_limit");
        if (textTruncated) reasons.add("sign_line_limit");
        while (encodedBytes(retained) > MAX_JSON_BYTES) {
            retained.remove(retained.size() - 1);
            if (!reasons.contains("sign_text_byte_limit")) reasons.add("sign_text_byte_limit");
        }
        return new Snapshot(List.copyOf(retained), seen - retained.size(), List.copyOf(reasons));
    }

    private static int encodedBytes(List<ScannedSign> signs) {
        JsonArray array = new JsonArray();
        signs.forEach(sign -> array.add(sign.toJson()));
        return GSON.toJson(array).getBytes(StandardCharsets.UTF_8).length;
    }

    record Snapshot(List<ScannedSign> signs, int omitted, List<String> truncationReasons) { }
}
