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
        return name.equals("task_control") ? "Read task status or manage owner tasks. Enqueue new actions; amend a changed requirement; guide adds a hint without changing the goal; replace only when explicitly told to switch. Chat does not cancel work. Resume after reload only when asked, optionally with a guidance patch. For owner material authorization pass material_policy; it is required when answering a pending materials question."
                : "Read original tool evidence by result_ref and Unicode character offset. Use task_trace for the execution record, task_narration for the background executor's own recent written narration (its intermediate assistant text, newest last) plus its current live, not-yet-archived streaming text, and build_queue for the read-only build queue status (design, progress, blocked step, missing items, material policy, pending owner question). Expired results retain a summary and cannot be replayed automatically.";
    }
    @Override public Parameter parameters(ObjectParameter root, EntityMaid maid) {
        if (name.equals("task_control")) {
            root.addProperties("action", StringParameter.create().addEnumValues("status", "enqueue", "amend", "guide", "replace", "stop", "pause", "clear", "resume"), true);
            root.addProperties("goal", StringParameter.create().setDescription("For enqueue/replace/amend/guide: the complete user-authorized text. guide must be a hint/correction that does NOT change requirements. For resume: optional patch explaining what to do differently after the pause.").setMaxLength(4096), false);
            root.addProperties("clear_queued",BoolParameter.create().setDescription("Only for replace, default false. Set true ONLY when the owner explicitly asks to clear the old queue AND switch to this new goal. Otherwise retain queued tasks."),false);
            root.addProperties("material_policy",StringParameter.create()
                    .addEnumValues("ask_owner","self_gather","self_source","denied")
                    .setDescription("Owner authorization for missing build materials. Default ask_owner. Set self_gather/self_source ONLY when the owner explicitly permits finding materials; set denied when the owner refuses. When answering a pending materials question this field is required. Guide/amend can carry the owner's specific instruction in goal."),false);
            // Foreground only hands off the complete owner goal. The executor establishes the
            // machine-verified contract from fresh observations via update_task_plan.
            // Keep accepting explicit completion in control() for existing callers, but do not
            // ask the chat model to plan quantities/coordinates merely to enqueue a task.
        } else {
            root.addProperties("result_ref", StringParameter.create().setDescription("task_trace for the execution record; task_narration for the background executor's intermediate narration and current live text; build_queue for the read-only build queue; otherwise a result_* reference returned by a tool."), true);
            root.addProperties("task_id",StringParameter.create().setDescription("Owner may read an archived task. Required with task_trace outside the active task callback; optional with task_narration (defaults to the task the conversation is talking about)."),false);
            root.addProperties("offset", IntegerParameter.create().setMinimum(0), false);
        }
        return root;
    }
    public static ObjectParameter completionPredicateParameter() { return completionParameter(false); }
    public static ObjectParameter completionParameter() {
        return completionParameter(true);
    }
    private static ObjectParameter completionParameter(boolean sequence) {
        ObjectParameter completion=atomicCompletion(false,sequence
                ?new String[]{"transfer","position","inventory","maid_state","eat_food","drop_to_owner","container_unchanged","backpack_empty","structure","all","sequence"}
                :new String[]{"transfer","position","inventory","maid_state","eat_food","drop_to_owner","container_unchanged","backpack_empty","structure","all"});
        completion.addProperties("conditions",ArrayParameter.create().setItems(atomicCompletion(true,"transfer","position","inventory","maid_state","eat_food","drop_to_owner","container_unchanged","backpack_empty","structure")).setRange(1,16)
                .setDescription("kind=all: every atomic requirement must hold."),false);
        if(sequence)completion.addProperties("phases",ArrayParameter.create().setItems(completionParameter(false)).setRange(2,8)
                .setDescription("kind=sequence: ordered verified phases, excluding eat_food/drop_to_owner. Transfer counts are NET WITHIN EACH PHASE. Use for take-then-return or intermediate states; never encode a round trip as a final outbound net transfer. Final phase remains live-verified."),false);
        return completion.setDescription("Full user requirements, verified from actual server facts. EVERY transfer leaf requires dimension,item,count,to_maid,position=[observed container x,y,z]. For construction register kind=structure with dimension and either structure_id from build_queue(design) or inline blocks. Composite groups require explicit kind=all or sequence; never drop owner requirements to fix validation. No dummy conditions.");
    }
    private static ObjectParameter atomicCompletion(boolean requiredDimension,String... kinds) {
        ObjectParameter root=ObjectParameter.create();
        root.addProperties("kind",StringParameter.create().addEnumValues(kinds).setDescription("Defaults to transfer. inventory is FINAL backpack stock, never a precondition."),false);
        root.addProperties("dimension",StringParameter.create().setDescription("Required on EVERY atomic condition, including backpack_empty. Composite all/sequence groups may omit."),requiredDimension);
        root.addProperties("item",StringParameter.create().setDescription("Exact item id for transfer/inventory/drop_to_owner. drop_to_owner verifies spawned entities aimed at the current owner, not inventory absence or player pickup."),false);
        root.addProperties("count",IntegerParameter.create().setMinimum(0).setDescription("Transfer >=1; inventory >=1, or 0 with match=exact."),false);
        root.addProperties("match",StringParameter.create().addEnumValues("at_least","exact").setDescription("inventory only; default at_least."),false);
        root.addProperties("position",ArrayParameter.create().setItems(IntegerParameter.create()).setRange(3,3).setDescription("Actual container/arrival x,y,z, never the sign position. container_unchanged captures original slot/count/NBT from an open single container before actions."),false);
        root.addProperties("radius",IntegerParameter.create().setMinimum(0).setMaximum(8),false);
        root.addProperties("to_maid",BoolParameter.create().setDescription("transfer: true from container to maid, false from maid to container. Counts are net, not total actions."),false);
        root.addProperties("structure_id",StringParameter.create().setMaxLength(128)
                .setDescription("structure only: id returned by build_queue(design). The runtime verifies every required place/mine step against the live world. Inline {blocks:[...]} is also accepted for small structures."),false);
        addActionCompletion(root);return root;
    }
    private static void addActionCompletion(ObjectParameter root) {
        root.addProperties("state",StringParameter.create().addEnumValues("follow","sit","schedule","work_task").setDescription("Required for maid_state."),false);
        root.addProperties("value",StringParameter.create().setDescription("Required for maid_state: true/false for follow/sit, schedule name or work-task registry id."),false);
        root.addProperties("food",StringParameter.create().setDescription("Required for eat_food: exact edible block registry id. Count means actually consumed servings."),false);
        root.addProperties("finished",BoolParameter.create().setDescription("eat_food only: require the consumed target to have been finished, rather than one bite."),false);
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
            String taskId=args.has("task_id")&&!args.get("task_id").isJsonNull()?args.get("task_id").getAsString():null;
            if (TaskNarration.REF.equals(reference)) result=AgentRuntime.readTaskNarration(callback,taskId,offset);
            else if ("build_queue".equals(reference)) result=AgentRuntime.readBuildQueue(callback,offset);
            else {
                var store=AgentContext.store(callback);
                result=store.contains(reference)?store.read(reference,offset):AgentRuntime.readArchived(callback,taskId,reference,offset);
            }
        }
        return callback.addToolResult(result, callId);
    }
    @Override public String invocationSummary(JsonObject args) { return name; }
}
