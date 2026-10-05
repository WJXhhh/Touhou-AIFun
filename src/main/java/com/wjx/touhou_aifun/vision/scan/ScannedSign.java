package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** Plain sign text only: styles, click events and block entity NBT never enter the tool result. */
public record ScannedSign(String registryId, int dx, int dy, int dz, double distance, String direction,
                          List<String> frontLines, List<String> backLines, boolean textTruncated, AttachedBlock attachedBlock) {
    private static final int MAX_LINE_CODE_POINTS = 256;
    /** Physical support of a wall sign; does not infer what the written label means. */
    public record AttachedBlock(String registryId, int x, int y, int z) { }
    public ScannedSign(String registryId,int dx,int dy,int dz,double distance,String direction,
                       List<String> frontLines,List<String> backLines,boolean textTruncated) {
        this(registryId,dx,dy,dz,distance,direction,frontLines,backLines,textTruncated,null);
    }

    public ScannedSign {
        if (frontLines.size() != 4 || backLines.size() != 4) {
            throw new IllegalArgumentException("Each sign face must contain four lines");
        }
        textTruncated |= frontLines.stream().anyMatch(ScannedSign::tooLong)
                || backLines.stream().anyMatch(ScannedSign::tooLong);
        frontLines = frontLines.stream().map(ScannedSign::boundedLine).toList();
        backLines = backLines.stream().map(ScannedSign::boundedLine).toList();
    }

    private static boolean tooLong(String line) {
        // Inspect at most 257 code points, even for unusually large command-created sign text.
        return line.length() > MAX_LINE_CODE_POINTS
                && line.codePoints().limit(MAX_LINE_CODE_POINTS + 1L).count() > MAX_LINE_CODE_POINTS;
    }

    private static String boundedLine(String line) {
        return tooLong(line) ? line.substring(0, line.offsetByCodePoints(0, MAX_LINE_CODE_POINTS)) : line;
    }

    JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("registry_id", registryId);
        JsonArray position = new JsonArray();
        position.add(dx); position.add(dy); position.add(dz);
        value.add("relative_position", position);
        value.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        value.addProperty("direction", direction);
        JsonArray front = new JsonArray(), back = new JsonArray();
        frontLines.forEach(front::add);
        backLines.forEach(back::add);
        value.add("front_lines", front);
        value.add("back_lines", back);
        value.addProperty("text_truncated", textTruncated);
        if(attachedBlock!=null) {
            var attached=new JsonObject();attached.addProperty("registry_id",attachedBlock.registryId());
            var absolute=new JsonArray();absolute.add(attachedBlock.x());absolute.add(attachedBlock.y());absolute.add(attachedBlock.z());
            attached.add("position",absolute);value.add("attached_block",attached);
        }
        return value;
    }
}
