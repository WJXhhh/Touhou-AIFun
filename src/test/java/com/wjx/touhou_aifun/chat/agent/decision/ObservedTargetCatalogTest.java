package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservedTargetCatalogTest {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    @Test void recordedOriginAndPhysicalSignSupportDefineRealCoordinates(){
        var scan=json("{\"dimension\":\"minecraft:overworld\",\"maid_position_at_scan_start\":[100,64,200],\"important_blocks\":[{\"registry_id\":\"minecraft:chest\",\"relative_position\":[2,0,-3]}],\"sign_texts\":[{\"front_lines\":[\"Source\",\"\",\"\",\"\"],\"back_lines\":[\"\",\"\",\"\",\"\"],\"attached_block\":{\"registry_id\":\"minecraft:chest\",\"position\":[102,64,197]}}]}");
        var targets=ObservedTargetCatalog.fromScan(scan);assertEquals(1,targets.size());assertEquals(json("{\"p\":[102,64,197]}").get("p"),targets.get(0).data().get("position"));assertTrue(targets.get(0).data().has("labels"));assertEquals(102,targets.get(0).binding().get("target_x").getAsInt());
    }
    @Test void absentOriginAndFractionalBlockOffsetsCannotAuthorizeTargets(){assertTrue(ObservedTargetCatalog.fromScan(json("{\"dimension\":\"d\",\"important_blocks\":[]}")).isEmpty());assertTrue(ObservedTargetCatalog.fromScan(json("{\"dimension\":\"d\",\"maid_position_at_scan_start\":[0,0,0],\"important_blocks\":[{\"registry_id\":\"minecraft:chest\",\"relative_position\":[0.5,0,0]}]}")).isEmpty());}
    @Test void expansionBindsOnlyKnownTargetsAndNeverLetsTheModelInventCoordinates(){
        var query=json("{\"kind\":\"container\",\"description\":\"Only source labelled materials\"}");var step=new ActionPlan.Step("Open source",List.of(new ActionPlan.Action("open_gui",json("{\"x\":\"$target.x\",\"y\":\"$target.y\",\"z\":\"$target.z\",\"target\":\"$target.registry_id\"}"),"Open owner-authorized source")),null,null,query);
        var targets=List.of(new ObservedTargetCatalog.Target("b0","container",json("{\"position\":[2,64,3]}"),json("{\"target_x\":2,\"target_y\":64,\"target_z\":3,\"target_registry_id\":\"minecraft:chest\"}")));
        var expanded=ObservedTargetCatalog.expand(step,targets);assertEquals(2,expanded.alternatives().get(0).arguments().get("x").getAsInt());assertNotNull(expanded.targetBinding());assertThrows(IllegalArgumentException.class,()->ObservedTargetCatalog.expand(step,List.of()));
        var schema=json("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"target\":{\"type\":\"string\"}}}");
        assertNull(DecisionQuestionCompiler.compile(expanded,Map.of("open_gui",new DecisionQuestionCompiler.Tool("open_gui","Open",schema)),new JsonObject(),new DecisionProvider.Capabilities(128,255,10,false)).deterministic());
    }
    @Test void entityIdsRemainFiniteAndMissingUuidCannotBeGuessed(){var step=new ActionPlan.Step("Open entity",List.of(new ActionPlan.Action("open_gui",json("{\"entity_uuid\":\"$target.entity_uuid\"}"),"Target")),null,null,json("{\"kind\":\"entity\",\"description\":\"Named cart\"}"));assertThrows(IllegalArgumentException.class,()->ObservedTargetCatalog.expand(step,List.of(new ObservedTargetCatalog.Target("e5","entity",new JsonObject(),json("{\"target_entity_id\":5}")))));}
}
