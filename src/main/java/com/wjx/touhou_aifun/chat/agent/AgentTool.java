package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.*;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ChatCompletion;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.serialization.*;

public final class AgentTool implements ITool<JsonObject> {
    private final String name;
    public AgentTool(String name) { this.name = name; }
    @Override public String id() { return name; }
    @Override public boolean trigger(EntityMaid maid, ChatCompletion request) { return name.equals("read_task_result") || AgentRuntime.enabled(maid); }
    @Override public String summary(EntityMaid maid) {
        return name.equals("task_control") ? "Read task status or manage owner tasks. Enqueue new actions; amend current requirements; replace only when explicitly told to switch. Chat does not cancel work. Resume after reload only when asked."
                : "Read original tool evidence by result_ref and Unicode character offset. Use task_trace for the execution record. Expired results retain a summary and cannot be replayed automatically.";
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        if (name.equals("task_control")) {
            root.addProperties("action", StringParameter.create().addEnumValues("status", "enqueue", "amend", "replace", "stop", "pause", "clear", "resume"), true);
            root.addProperties("goal", StringParameter.create().setDescription("Complete user-authorized goal and constraints; retain quantities, targets and stop conditions.").setMaxLength(4096), false);
            root.addProperties("clear_queued",BoolParameter.create().setDescription("Only for replace, default false. Set true ONLY when the owner explicitly asks to clear the old queue AND switch to this new goal. Otherwise retain queued tasks."),false);
            root.addProperties("completion",completionParameter(),false);
        } else {
            root.addProperties("result_ref", StringParameter.create(), true);
            root.addProperties("task_id",StringParameter.create().setDescription("Owner may read an archived task. Required with task_trace outside the active task callback."),false);
            root.addProperties("offset", IntegerParameter.create().setMinimum(0), false);
        }
        return root;
    }
    public static ObjectParameter completionParameter() {
            ObjectParameter completion = ObjectParameter.create();
            completion.addProperties("kind", StringParameter.create().addEnumValues("transfer","position","inventory","all").setDescription("Defaults to transfer. Inventory requires FINAL backpack stock >= count; NEVER use it to describe an initial inventory/precondition. Putting items away uses outbound transfer, not final inventory of the same items. Reserve stock is valid only when enough items remain or an incoming supply is planned. Position verifies final arrival. Use all for final outcomes only."),false);
            completion.addProperties("item", StringParameter.create().setDescription("Exact registry item ID; required for transfer/inventory"), false);
            completion.addProperties("count", IntegerParameter.create().setMinimum(1).setDescription("Required for transfer/inventory"), false);
            completion.addProperties("dimension", StringParameter.create().setDescription("Dimension registry ID; required except for all"), false);
            completion.addProperties("position", ArrayParameter.create().setItems(IntegerParameter.create()).setMinItems(3).setMaxItems(3).setDescription("Required for transfer/position; exact x,y,z. For transfer use the container block, never the attached sign/label position."), false);
            completion.addProperties("radius", IntegerParameter.create().setMinimum(0).setMaximum(8).setDescription("Arrival radius for position, default 2"),false);
            completion.addProperties("to_maid", BoolParameter.create().setDescription("Required for transfer"), false);
            ObjectParameter atomic=ObjectParameter.create();
            atomic.addProperties("kind",StringParameter.create().addEnumValues("transfer","position","inventory").setDescription("Final outcomes only: inventory means leave at least count in the FINAL backpack, not initially possess count before putting items away."),false);
            atomic.addProperties("dimension",StringParameter.create(),true);
            atomic.addProperties("item",StringParameter.create(),false); atomic.addProperties("count",IntegerParameter.create().setMinimum(1),false);
            atomic.addProperties("position",ArrayParameter.create().setItems(IntegerParameter.create()).setMinItems(3).setMaxItems(3).setDescription("Transfer requires the actual container position, not the sign position."),false);
            atomic.addProperties("radius",IntegerParameter.create().setMinimum(0).setMaximum(8),false); atomic.addProperties("to_maid",BoolParameter.create(),false);
            completion.addProperties("conditions",ArrayParameter.create().setItems(atomic).setMinItems(1).setMaxItems(16).setDescription("Required for kind=all. Every condition must pass; nesting all is unsupported."),false);
            return completion.setDescription("Machine-verified completion derived from the complete user goal, never a dummy condition. Transfer position is the source container when to_maid=true, destination otherwise. Use all for every required item/destination. Foreground may omit unknown facts; the executor defines the missing condition from fresh observations before moving items.");
    }
    @Override public Codec<JsonObject> codec() {
        return Codec.PASSTHROUGH.comapFlatMap(d -> {
            JsonElement value = d.convert(JsonOps.INSTANCE).getValue();
            return value.isJsonObject() ? DataResult.success(value.getAsJsonObject()) : DataResult.error(() -> "object_required");
        }, value -> new Dynamic<>(JsonOps.INSTANCE, value));
    }
    @Override public LLMCallback onCall(String callId, JsonObject args, LLMCallback callback) {
        String result;
        if (name.equals("task_control")) result = AgentRuntime.control(callback, args).toString();
        else if (callback instanceof TaskCallback task) result = args.get("result_ref").getAsString().equals("task_trace")
                ? task.readTrace(args.has("offset") ? args.get("offset").getAsInt() : 0)
                : task.results.read(args.get("result_ref").getAsString(), args.has("offset") ? args.get("offset").getAsInt() : 0);
        else {
            String reference=args.get("result_ref").getAsString();int offset=args.has("offset")?args.get("offset").getAsInt():0;
            var store=AgentContext.store(callback);
            result=store.contains(reference)?store.read(reference,offset):AgentRuntime.readArchived(callback,args.has("task_id")?args.get("task_id").getAsString():null,reference,offset);
        }
        return callback.addToolResult(result, callId);
    }
    @Override public String invocationSummary(JsonObject args) { return name; }
}
