package com.wjx.touhou_aifun.maid.action;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.google.gson.*;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.agent.*;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Server-thread movement and atomic, receipt-backed owner drops. No unobserved GUI clicks. */
@Mod.EventBusSubscriber(modid= TouhouAIFun.MOD_ID, bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidOwnerDropAction {
    private static final Map<UUID,Action> ACTIVE=new HashMap<>();
    private MaidOwnerDropAction() { }
    public static CompletableFuture<JsonObject> start(LLMCallback callback,JsonObject request) {
        try {
            if(!(callback instanceof TaskCallback task)) return CompletableFuture.completedFuture(error("task_execution_only"));
            if(MaidGuiSessionManager.session(task.getMaid().getUUID())!=null) return CompletableFuture.completedFuture(error("close_gui_before_owner_drop"));
            var items=new LinkedHashMap<String,Integer>();
            var rows=request.getAsJsonArray("items");
            if(rows==null || rows.isEmpty() || rows.size()>16) throw new IllegalArgumentException("invalid_drop_items");
            for(var entry:rows) {
                var row=entry.getAsJsonObject();String item=row.get("item").getAsString();
                int count=row.get("count").getAsInt();
                if(count<1 || count>4096 || row.get("count").getAsDouble()!=count || items.putIfAbsent(item,count)!=null)
                    throw new IllegalArgumentException("invalid_drop_items");
            }
            if(request.has("complete_task") && !request.getAsJsonPrimitive("complete_task").isBoolean()) throw new IllegalArgumentException("complete_task_must_be_boolean");
            if(!task.getMaid().level().getServer().isSameThread())throw new IllegalArgumentException("server_thread_required");
            var action=new Action(task,items,request.has("complete_task") && request.get("complete_task").getAsBoolean());
            action.validate();
            // Preflight the entire request before taking control or removing any item.
            var inv=task.getMaid().getAvailableBackpackInv();
            for(var entry:items.entrySet()) {
                int available=0;
                for(int i=0;i<inv.getSlots();i++) if(entry.getKey().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(inv.getStackInSlot(i).getItem()).toString())) available+=inv.extractItem(i,entry.getValue(),true).getCount();
                if(available<entry.getValue()) throw new IllegalArgumentException("insufficient_backpack_items: "+entry.getKey());
                long held=0;for(int i=0;i<inv.getSlots();i++)if(entry.getKey().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(inv.getStackInSlot(i).getItem()).toString()))held+=inv.getStackInSlot(i).getCount();
                for(var condition:AgentCompletionRules.leaves(task.task.activeCompletion()))
                    if(AgentTaskState.completionKind(condition).equals("inventory") && condition.get("item").getAsString().equals(entry.getKey()) && held-entry.getValue()<condition.get("count").getAsLong())
                        throw new IllegalArgumentException("drop_conflicts_with_required_final_inventory");
            }
            if(!MaidActionLease.acquire(task.getMaid().getUUID(),action)) throw new IllegalArgumentException("action_busy");
            ACTIVE.put(task.getMaid().getUUID(),action);
            try {action.tick();} catch(RuntimeException failure) {action.finish(error(failure.getMessage()==null?"owner_drop_failed":failure.getMessage()));}
            return action.future;
        } catch(RuntimeException rejected) { return CompletableFuture.completedFuture(error(rejected.getMessage()==null?"invalid_drop_request":rejected.getMessage())); }
    }
    private static JsonObject error(String reason) {var out=new JsonObject();out.addProperty("status","failed");out.addProperty("error",reason);return out;}
    public static void cancelForMaid(UUID maid) {
        var action=ACTIVE.get(maid);if(action!=null)action.finish(error("maid_left_level"));
    }
    public static void clearAll() {for(var action:List.copyOf(ACTIVE.values()))action.finish(error("server_stopped"));}
    @SubscribeEvent public static void preventMaidRecollection(com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent.ItemResultPre event) {
        if(event.getEntityItem().getPersistentData().hasUUID("touhou_aifun_owner_handoff")) {
            event.setCanPickup(false);event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        for(var action:List.copyOf(ACTIVE.values())) {
            try {action.tick();} catch(RuntimeException failure) {action.finish(error(failure.getMessage()==null?"owner_drop_failed":failure.getMessage()));}
        }
    }
    private static final class Action {
        final TaskCallback task;
        final LinkedHashMap<String,Integer> items;
        final boolean complete;
        final CompletableFuture<JsonObject> future=new CompletableFuture<>();
        final JsonArray receipts=new JsonArray();
        final long deadline;
        Action(TaskCallback task,LinkedHashMap<String,Integer> items,boolean complete) {
            this.task=task;this.items=items;this.complete=complete;deadline=task.getMaid().level().getGameTime()+400;
        }
        void validate() {
            var maid=task.getMaid();
            if(task.stopped() || !task.task.pendingAmendment.isBlank())throw new IllegalArgumentException("task_changed_before_owner_drop");
            if(!maid.isAlive() || maid.isRemoved() || !(maid.level() instanceof ServerLevel))throw new IllegalArgumentException("maid_unavailable");
            var owner=maid.getOwner();
            if(owner==null || !owner.isAlive() || owner.level()!=maid.level() || maid.getOwnerUUID()==null || !owner.getUUID().equals(maid.getOwnerUUID()))throw new IllegalArgumentException("owner_unavailable");
            if(!maid.isWithinRestriction(owner.blockPosition()))throw new IllegalArgumentException("owner_outside_restriction");
            if(maid.isSleeping() || maid.isPassenger() || maid.isLeashed())throw new IllegalArgumentException("movement_blocked");
            if(maid.level().getGameTime()>deadline)throw new IllegalArgumentException("owner_unreachable_timeout");
            if(task.task.actionFacts.size()+items.size()+maid.getAvailableBackpackInv().getSlots()>256)throw new IllegalArgumentException("task_action_fact_limit");
            for(var entry:items.entrySet()) task.task.checkDropAllowance(maid.level().dimension().location().toString(),maid.getOwnerUUID().toString(),entry.getKey(),entry.getValue());
        }
        void tick() {
            if(future.isDone())return;
            validate();var maid=task.getMaid();var owner=maid.getOwner();
            Vec3 origin=maid.position().add(0,maid.getBbHeight()*.65,0);
            boolean clear=maid.level().clip(new net.minecraft.world.level.ClipContext(origin,owner.position().add(0,.5,0),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,maid)).getType()==net.minecraft.world.phys.HitResult.Type.MISS;
            if(maid.distanceToSqr(owner)>9 || !maid.hasLineOfSight(owner) || !clear) {
                maid.setInSittingPose(false);
                net.minecraft.world.entity.ai.behavior.BehaviorUtils.setWalkAndLookTargetMemories(maid,owner,.65f,2);
                return;
            }
            maid.getLookControl().setLookAt(owner,30,30);
            var inv=maid.getAvailableBackpackInv();
            for(var entry:items.entrySet()) {
                int remaining=entry.getValue();
                for(int slot=0;slot<inv.getSlots() && remaining>0;slot++) {
                    var original=inv.getStackInSlot(slot);
                    if(!entry.getKey().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(original.getItem()).toString()))continue;
                    // Recheck remaining committed quantity immediately before each physical mutation.
                    if(task.stopped() || !task.task.pendingAmendment.isBlank())throw new IllegalArgumentException("task_changed_before_owner_drop");
                    task.task.checkDropAllowance(maid.level().dimension().location().toString(),owner.getUUID().toString(),entry.getKey(),Math.min(remaining,original.getCount()));
                    ItemStack portion=inv.extractItem(slot,remaining,false);
                    if(portion.isEmpty())continue;
                    Vec3 from=maid.position().add(0,maid.getBbHeight()*.65,0);
                    Vec3 direction=owner.position().add(0,.5,0).subtract(from).normalize();
                    var entity=new ItemEntity(maid.level(),from.x,from.y,from.z,portion.copy());
                    entity.setDeltaMovement(direction.scale(.28).add(0,.12,0));
                    entity.getPersistentData().putUUID("touhou_aifun_owner_handoff",owner.getUUID());
                    entity.setTarget(owner.getUUID());entity.setThrower(maid.getUUID());entity.setDefaultPickUpDelay();
                    if(!maid.level().addFreshEntity(entity) || entity.isRemoved() || !ItemStack.matches(entity.getItem(),portion)) {
                        entity.discard();
                        var restored=inv.insertItem(slot,portion,false);
                        if(!restored.isEmpty())throw new IllegalStateException("drop_rollback_failed");
                        throw new IllegalArgumentException("item_spawn_rejected");
                    }
                    int amount=portion.getCount();remaining-=amount;
                    var fact=new JsonObject();fact.addProperty("kind","drop_to_owner");fact.addProperty("success",true);
                    fact.addProperty("dimension",maid.level().dimension().location().toString());fact.addProperty("owner_uuid",owner.getUUID().toString());
                    fact.addProperty("item",entry.getKey());fact.addProperty("dropped_count",amount);fact.addProperty("entity_uuid",entity.getUUID().toString());
                    fact.addProperty("owner_distance_at_drop",Math.sqrt(maid.distanceToSqr(owner)));
                    fact.add("spawn_position",new Gson().toJsonTree(new double[]{entity.getX(),entity.getY(),entity.getZ()}));
                    fact.add("direction",new Gson().toJsonTree(new double[]{direction.x,direction.y,direction.z}));
                    task.recordMutation(fact);receipts.add(fact);
                }
                if(remaining>0)throw new IllegalArgumentException("backpack_changed_during_owner_drop");
            }
            var result=new JsonObject();result.addProperty("status","completed");result.add("drop_receipts",receipts.deepCopy());
            result.addProperty("notice","Verified spawned items thrown toward the owner; owner pickup is not asserted.");
            if(complete) {
                try {task.requestCompletion();result.addProperty("completion_requested",true);}
                catch(IllegalArgumentException unmet) {result.addProperty("completion_error",unmet.getMessage());}
            }
            finish(result);
        }
        void finish(JsonObject value) {
            if(future.isDone())return;
            value.add("drop_receipts",receipts.deepCopy());
            ACTIVE.remove(task.getMaid().getUUID(),this);
            task.getMaid().getNavigation().stop();
            task.getMaid().getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
            MaidActionLease.release(task.getMaid().getUUID(),this);
            future.complete(value);
        }
    }
}
