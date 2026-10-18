package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestPrefixAuditTest {
    @Test void separatesSchemaChangesFromHistoryRewritesWithoutRetainingOrReportingDialogue() {
        var body=JsonParser.parseString("{\"tools\":[{\"name\":\"gui_action\"}],\"system\":\"private policy\",\"messages\":[{\"role\":\"user\",\"content\":\"private goal\"},{\"role\":\"assistant\",\"content\":\"receipt\"}]}").getAsJsonObject();
        var audit=new RequestPrefixAudit();var first=audit.observe(body);
        assertFalse(first.has("same_tools_as_previous"));assertFalse(first.toString().contains("private"));
        var next=body.deepCopy();next.getAsJsonArray("messages").add(JsonParser.parseString("{\"role\":\"user\",\"content\":\"next step\"}"));
        var append=audit.observe(next);
        assertTrue(append.get("same_tools_as_previous").getAsBoolean());assertEquals(2,append.get("shared_complete_messages").getAsInt());
        next.getAsJsonArray("tools").get(0).getAsJsonObject().addProperty("description","changed definition");
        var schema=audit.observe(next);assertFalse(schema.get("same_tools_as_previous").getAsBoolean());assertTrue(schema.get("same_system_as_previous").getAsBoolean());
        next.getAsJsonArray("messages").get(1).getAsJsonObject().addProperty("content","compacted receipt");
        var rewrite=audit.observe(next);assertTrue(rewrite.get("same_tools_as_previous").getAsBoolean());assertEquals(1,rewrite.get("shared_complete_messages").getAsInt());
    }
}
