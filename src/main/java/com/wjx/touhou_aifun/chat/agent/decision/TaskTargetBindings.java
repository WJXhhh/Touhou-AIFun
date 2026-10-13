package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import java.util.*;

/** Freshness proof is private server data. Only already-observed names/positions enter a decision. */
public final class TaskTargetBindings {
    public record Capture(List<ObservedTargetCatalog.Target> targets,JsonObject fingerprint) {
        public JsonArray modelTargets() {var out=new JsonArray();for(var target:targets){var row=target.data().deepCopy();row.addProperty("id",target.id());row.addProperty("kind",target.kind());out.add(row);}return out;}
    }
    private TaskTargetBindings() { }
    public static Capture capture(TaskCallback callback) {return capture(callback,true);}
    public static Capture capture(TaskCallback callback,boolean includeInventory) {
        var maid=callback.getMaid();var scan=callback.decisionObservation("scan_surroundings");
        var out=new ArrayList<ObservedTargetCatalog.Target>();var proof=new JsonObject();var targets=new JsonArray();proof.add("observed_targets",targets);
        if(scan==null || !scan.has("dimension") || !maid.level().dimension().location().toString().equals(scan.get("dimension").getAsString()))return new Capture(List.of(),proof);
        proof.add("scan",scan.deepCopy());
        for(var observed:ObservedTargetCatalog.fromScan(scan)) {
            var data=observed.data().deepCopy();var binding=observed.binding().deepCopy();var identity=new JsonObject();identity.addProperty("id",observed.id());if(data.has("dimension"))identity.add("dimension",data.get("dimension"));String kind=observed.kind();
            if(kind.equals("block")) {
                var xyz=data.getAsJsonArray("position");var pos=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());
                identity.add("position",xyz.deepCopy());
                if(!maid.level().hasChunkAt(pos) || !maid.isWithinRestriction(pos))continue;
                var state=maid.level().getBlockState(pos);if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(data.get("registry_id").getAsString()))continue;
                var entity=maid.level().getBlockEntity(pos);if(entity instanceof Container && state.getMenuProvider(maid.level(),pos)!=null)kind="container";
                identity.addProperty("state",state.toString());if(includeInventory && entity!=null)identity.addProperty("block_entity",entity.saveWithoutMetadata().toString());
                var container=state.getBlock() instanceof ChestBlock?ChestBlock.getContainer((ChestBlock)state.getBlock(),state,maid.level(),pos,true):entity instanceof Container c?c:null;
                if(includeInventory && container!=null) {var stacks=new JsonArray();for(int n=0;n<container.getContainerSize();n++)stacks.add(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.stack(container.getItem(n)));identity.add("container_inventory",stacks);}
                if(data.has("labels")) {
                    var signs=new JsonArray();
                    // Compare the actual sign text to the scan, rather than reusing a stale label.
                    if(scan.has("sign_texts")) for(var value:scan.getAsJsonArray("sign_texts")) {
                        var row=value.getAsJsonObject();if(!row.has("attached_block") || !row.getAsJsonObject("attached_block").get("position").equals(xyz) || !row.has("relative_position"))continue;
                        var origin=scan.getAsJsonArray("maid_position_at_scan_start");var delta=row.getAsJsonArray("relative_position");
                        var p=new BlockPos(origin.get(0).getAsInt()+delta.get(0).getAsInt(),origin.get(1).getAsInt()+delta.get(1).getAsInt(),origin.get(2).getAsInt()+delta.get(2).getAsInt());
                        if(!(maid.level().getBlockEntity(p) instanceof SignBlockEntity sign)) {signs.add("changed");continue;}
                        var current=new JsonObject();for(boolean front:List.of(true,false)) {var lines=new JsonArray();for(var text:(front?sign.getFrontText():sign.getBackText()).getMessages(false))lines.add(text.getString());current.add(front?"front_lines":"back_lines",lines);}
                        signs.add(current);
                    }
                    identity.add("labels",signs);
                    if(!signs.equals(data.get("labels")))continue;
                }
            } else {
                var entity=maid.level().getEntity(data.get("entity_id").getAsInt());
                if(entity==null || !entity.isAlive() || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(data.get("registry_id").getAsString()))continue;
                binding.addProperty("target_entity_uuid",entity.getUUID().toString());identity.addProperty("uuid",entity.getUUID().toString());identity.addProperty("alive",entity.isAlive());
                identity.add("position",new Gson().toJsonTree(new double[]{entity.getX(),entity.getY(),entity.getZ()}));
                identity.addProperty("name",entity.getName().getString());
            }
            targets.add(identity);out.add(new ObservedTargetCatalog.Target(observed.id(),kind,data,binding));
        }
        return new Capture(List.copyOf(out),proof);
    }
    public static Map<String,Long> containerCounts(TaskCallback callback,ObservedTargetCatalog.Target target) {
        var xyz=target.data().getAsJsonArray("position");var pos=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());var level=callback.getMaid().level();
        if(!level.hasChunkAt(pos))return Map.of();var state=level.getBlockState(pos);var entity=level.getBlockEntity(pos);
        var container=state.getBlock() instanceof ChestBlock?ChestBlock.getContainer((ChestBlock)state.getBlock(),state,level,pos,true):entity instanceof Container c?c:null;
        var counts=new TreeMap<String,Long>();if(container!=null)for(int i=0;i<container.getContainerSize();i++){var stack=container.getItem(i);if(!stack.isEmpty())counts.merge(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.itemId(stack),(long)stack.getCount(),Long::sum);}return counts;
    }
    public static void containerStackSizes(TaskCallback callback,ObservedTargetCatalog.Target target,Map<String,Integer> sizes) {
        var xyz=target.data().getAsJsonArray("position");var pos=new BlockPos(xyz.get(0).getAsInt(),xyz.get(1).getAsInt(),xyz.get(2).getAsInt());var level=callback.getMaid().level();
        if(!level.hasChunkAt(pos))return;var state=level.getBlockState(pos);var entity=level.getBlockEntity(pos);
        var container=state.getBlock() instanceof ChestBlock?ChestBlock.getContainer((ChestBlock)state.getBlock(),state,level,pos,true):entity instanceof Container c?c:null;
        if(container!=null)for(int i=0;i<container.getContainerSize();i++)recordStackSize(sizes,container.getItem(i));
    }
    public static void recordStackSize(Map<String,Integer> sizes,net.minecraft.world.item.ItemStack stack) {
        if(!stack.isEmpty())sizes.merge(com.wjx.touhou_aifun.maid.gui.MaidGuiSession.itemId(stack),stack.getMaxStackSize(),(first,next)->first.equals(next)?first:0);
    }
}
