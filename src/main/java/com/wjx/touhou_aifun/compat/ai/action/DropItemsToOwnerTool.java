package com.wjx.touhou_aifun.compat.ai.action;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.serialization.*;
import com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction;
import java.util.concurrent.CompletableFuture;

/** Explicit owner-directed world action, independent of GUI outside-click emulation. */
public final class DropItemsToOwnerTool implements ITool<JsonObject> {
    public static final String ID = "drop_items_to_owner";
    @Override public String id() { return ID; }
    @Override public String summary(EntityMaid maid) {
        return "When the owner explicitly requests returning/throwing items, walk within 3 blocks and throw exact NEW item counts from the unlocked maid backpack toward the live owner. No GUI required; close any menu first. Preserves NBT. Register drop_to_owner(dimension,item,count) completion for EVERY requested item first; inventory=0 alone does not prove delivery. Subtract committed dropped_count before retries. Success proves spawned item entities directed at the owner, not player pickup. Never use this to clear space without owner authorization.";
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        var item = ObjectParameter.create();
        item.addProperties("item", StringParameter.create().setDescription("Exact currently held item registry id."), true);
        item.addProperties("count", IntegerParameter.create().setMinimum(1).setMaximum(4096).setDescription("NEW quantity to throw; subtract existing drop receipts."), true);
        root.addProperties("items", ArrayParameter.create().setItems(item).setRange(1,16), true);
        root.addProperties("complete_task", BoolParameter.create().setDescription("True only when this action satisfies ALL requirements. Runtime verifies actual receipts after settlement."), false);
        return root;
    }
    @Override public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(d -> {
            var value=d.convert(JsonOps.INSTANCE).getValue();
            return value.isJsonObject()?DataResult.success(value.getAsJsonObject()):DataResult.error(()->"object_required");
        }, value->new Dynamic<>(JsonOps.INSTANCE,value));
    }
    @Override public LLMCallback onCall(String id, JsonObject request, LLMCallback callback) {
        return callback.addToolResult("{\"status\":\"failed\",\"error\":\"async_dispatch_required\"}",id);
    }
    @Override public CompletableFuture<LLMCallback> onCallAsync(String id, JsonObject request, LLMCallback callback, LLMClient client) {
        var result=new CompletableFuture<LLMCallback>();
        callback.runOnServerThread(()->MaidOwnerDropAction.start(callback,request).whenComplete((value,error)->
                callback.runOnServerThread(()->result.complete(callback.addToolResult(error==null?value.toString():"{\"status\":\"failed\",\"error\":\"owner_drop_failed\"}",id)))));
        return result;
    }
    @Override public String invocationSummary(JsonObject request) { return ID; }
}
