package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Content-free diagnostics from the actual wire body; hashes/characters are not cache tokens. */
public final class RequestPrefixAudit {
    private record Message(String hash,int chars) { }
    private String tools,system;
    private List<Message> previous=List.of();

    public JsonObject observe(JsonObject body) {
        String nextTools=hash(body.get("tools")),nextSystem=hash(body.get("system"));
        var messages=new ArrayList<Message>();
        if(body.has("messages"))for(var message:body.getAsJsonArray("messages"))
            messages.add(new Message(hash(message),message.toString().length()));
        int shared=0,chars=0;
        while(shared<Math.min(messages.size(),previous.size()) && messages.get(shared).equals(previous.get(shared))) {
            chars+=messages.get(shared).chars();shared++;
        }
        var out=new JsonObject();out.addProperty("tools_sha256",nextTools);out.addProperty("system_sha256",nextSystem);
        if(tools!=null){out.addProperty("same_tools_as_previous",tools.equals(nextTools));out.addProperty("same_system_as_previous",system.equals(nextSystem));}
        out.addProperty("tool_count",body.has("tools")?body.getAsJsonArray("tools").size():0);
        out.addProperty("message_count",messages.size());out.addProperty("shared_complete_messages",shared);out.addProperty("shared_message_chars",chars);
        tools=nextTools;system=nextSystem;previous=List.copyOf(messages);return out;
    }
    private static String hash(JsonElement value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
