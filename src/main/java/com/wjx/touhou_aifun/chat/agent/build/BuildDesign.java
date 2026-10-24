package com.wjx.touhou_aifun.chat.agent.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One owner-authored structure design. All coordinates in {@link #steps} are absolute world coordinates. */
public class BuildDesign {
    public String id = UUID.randomUUID().toString();
    public String structureName = "";
    public String dimension = "";
    public int originX;
    public int originY;
    public int originZ;
    public List<BuildStep> steps = new ArrayList<>();
    public long createdTick;
    /** Canonical SHA-256 hex over id + dimension + origin + steps. */
    public String fingerprint = "";

    public BuildDesign() {
    }

    public String displayName() {
        return structureName == null || structureName.isBlank() ? "structure" : structureName;
    }

    /** Canonical fingerprint so a repaired/equal design can be recognised without trusting order. */
    public String computeFingerprint() {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append(id).append('|').append(dimension == null ? "" : dimension).append('|')
                .append(originX).append(',').append(originY).append(',').append(originZ).append('|');
        if (steps != null) {
            for (BuildStep step : steps) {
                if (step == null) {
                    canonical.append('-').append(';');
                    continue;
                }
                canonical.append(step.kindOrPlace()).append(',').append(step.x).append(',').append(step.y)
                        .append(',').append(step.z).append(',').append(step.block == null ? "" : step.block)
                        .append(',').append(step.item == null ? "" : step.item)
                        .append(',').append(step.tool == null ? "" : step.tool)
                        .append(',').append(step.face == null ? "" : step.face)
                        .append(',').append(step.strict).append(',').append(step.required)
                        .append(',').append(step.note == null ? "" : step.note).append(';');
            }
        }
        return sha256Hex(canonical.toString());
    }

    public void refreshFingerprint() {
        fingerprint = computeFingerprint();
    }

    public void reindex() {
        if (steps == null) {
            steps = new ArrayList<>();
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            BuildStep step = steps.get(i);
            if (step != null) step.index = i;
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("sha256_unavailable", unavailable);
        }
    }
}
