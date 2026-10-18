package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.*;
import com.google.gson.*;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningContentCodec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Deterministic request deduplication; never edits owner messages or the durable operation ledger. */
final class TaskContextProjection {
    private static final Set<String> SCAN_ENVELOPE = Set.of("game_tick", "completed_tick", "scan_tick", "observation_id", "result_ref", "observation_key");
    private static final Set<String> MENU_GEOMETRY = Set.of("slots", "backpack_slots", "controls", "layout", "frame_id", "compatibility", "data");
    private TaskContextProjection() { }

    static void reduce(List<LLMMessage> messages, Set<Integer> closedResults, TaskResultStore store) {
        Map<String,String> tools=new HashMap<>();
        Set<String> closedIds=new HashSet<>();for(int index:closedResults)closedIds.add(messages.get(index).toolCallId());
        Map<String,String> explanations=new HashMap<>();
        for(int i=messages.size()-1;i>=0;i--) {
            var m=messages.get(i);
            if(m.role()!=Role.ASSISTANT || m.toolCalls()==null || m.toolCalls().isEmpty())continue;
            for(var call:m.toolCalls())tools.put(call.getId(),call.getFunction().getName());
            if(!m.toolCalls().stream().allMatch(call->closedIds.contains(call.getId())))continue;
            var decoded=ReasoningContentCodec.decode(m.message());
            if(decoded.content().length()<512)continue;
            String latest=explanations.putIfAbsent(decoded.content(),m.toolCalls().get(0).getId());
            if(latest!=null) {
                String reference=store.put(m.message());
                // Only exact repeated public explanations are elided. Provider-required thinking,
                // every call/argument and the newest complete explanation are left intact.
                String text=ReasoningContentCodec.encode("Repeated explanation retained at call "+latest+"; original: "+reference,decoded.reasoningContent());
                messages.set(i,withText(m,text));
            }
        }
        Map<String,Integer> fullMenus=new HashMap<>();
        Map<JsonElement,Integer> scans=new HashMap<>();
        for(int i=messages.size()-1;i>=0;i--) {
            if(!closedResults.contains(i))continue;
            LLMMessage message=messages.get(i);
            try {
                JsonObject value=JsonParser.parseString(message.message()).getAsJsonObject();
                if(value.has("snapshot_kind") && value.has("session_id")) {
                    String session=value.get("session_id").getAsString();
                    Integer newer=fullMenus.get(session);
                    if(newer!=null && Set.of("full","delta").contains(value.get("snapshot_kind").getAsString())) {
                        // A later complete observation covers earlier geometry, including old deltas.
                        // Keep ALL nongeometry fields and nested action receipts, not a lossy whitelist.
                        JsonObject history=value.deepCopy();reference(history,message,store);
                        MENU_GEOMETRY.forEach(history::remove);
                        history.addProperty("snapshot_kind","historical");
                        history.addProperty("superseded_by_call",messages.get(newer).toolCallId());
                        history.addProperty("snapshot_details","Historical geometry omitted; receipts retained. Details: result_ref.");
                        replaceIfSmaller(messages,i,history);
                    }
                    if("full".equals(value.get("snapshot_kind").getAsString()) && !session.isBlank())fullMenus.putIfAbsent(session,i);
                }
                if(!"scan_surroundings".equals(tools.get(message.toolCallId())) || value.has("error")
                        || !value.has("status") || !"ok".equals(value.get("status").getAsString())
                        || !value.has("dimension") || !value.has("maid_position_at_scan_start")
                        || !(value.has("important_blocks") || value.has("observed_blocks")))continue;
                JsonObject semantic=value.deepCopy();SCAN_ENVELOPE.forEach(semantic::remove);
                JsonElement key=canonical(semantic);
                Integer latest=scans.putIfAbsent(key,i);
                if(latest==null)continue;
                String observationKey="scan_"+digest(key.toString());
                JsonObject retained=JsonParser.parseString(messages.get(latest).message()).getAsJsonObject();
                retained.addProperty("observation_key",observationKey);
                messages.set(latest,withText(messages.get(latest),retained.toString()));
                JsonObject duplicate=value.deepCopy();
                // Drop only known observation arrays, never unknown fields or action receipts.
                for(String field:List.of("important_blocks","important_targets","observed_blocks","surface_summary","entities","entity_targets","sign_texts"))duplicate.remove(field);
                reference(duplicate,message,store);
                duplicate.addProperty("same_observation_as",observationKey);
                duplicate.addProperty("notice","Identical captured data retained at observation_key; historical data is not current action authorization.");
                replaceIfSmaller(messages,i,duplicate);
            } catch(RuntimeException ignored) { /* Unknown/malformed results stay verbatim. */ }
        }
    }
    static JsonArray recentEvidence(List<String> evidence,List<LLMMessage> messages) {
        Set<String> visible=new HashSet<>();
        for(var message:messages) if(message.role()==Role.TOOL) {
            try {
                var value=JsonParser.parseString(message.message()).getAsJsonObject();
                if(value.has("result_ref"))visible.add(value.get("result_ref").getAsString());
            } catch(RuntimeException ignored) { }
        }
        JsonArray out=new JsonArray();
        for(String text:evidence) {
            try {
                var value=JsonParser.parseString(text).getAsJsonObject();
                if(value.has("result_ref") && visible.contains(value.get("result_ref").getAsString())) {
                    var reference=new JsonObject();reference.add("result_ref",value.get("result_ref"));out.add(reference);continue;
                }
            } catch(RuntimeException ignored) { }
            out.add(text);
        }
        return out;
    }
    private static void reference(JsonObject out,LLMMessage original,TaskResultStore store) {
        if(!out.has("result_ref"))out.addProperty("result_ref",store.put(original.message()));
    }
    private static void replaceIfSmaller(List<LLMMessage> messages,int index,JsonObject value) {
        String text=value.toString();
        if(text.length()<messages.get(index).message().length())messages.set(index,withText(messages.get(index),text));
    }
    private static LLMMessage withText(LLMMessage original,String text) {
        return new LLMMessage(original.role(),text,original.gameTime(),original.toolCalls(),original.toolCallId());
    }
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()) {
            JsonObject out=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))out.add(key,canonical(value.getAsJsonObject().get(key)));return out;
        }
        if(value.isJsonArray()) {JsonArray out=new JsonArray();for(var item:value.getAsJsonArray())out.add(canonical(item));return out;}
        return value;
    }
    private static String digest(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)),0,12);}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
