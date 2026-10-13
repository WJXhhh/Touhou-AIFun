package com.wjx.touhou_aifun.chat.agent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Server-private slot/count/NBT baseline, captured only through an actually opened single container. */
final class ContainerCompletionSnapshot {
    private ContainerCompletionSnapshot() { }
    static String key(JsonObject condition) { return condition.get("dimension").getAsString()+":"+condition.get("position"); }
    private static BlockPos position(JsonObject condition) {var p=condition.getAsJsonArray("position");return new BlockPos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());}
    static java.util.Map<String,String> capture(EntityMaid maid, JsonObject completion) {
        var baselines=new java.util.LinkedHashMap<String,String>();
        for(var condition:AgentCompletionRules.leaves(completion)) if(AgentTaskState.completionKind(condition).equals("container_unchanged")) {
            var session=MaidGuiSessionManager.session(maid.getUUID());
            if(session==null || !position(condition).equals(session.targetPosition) || !condition.get("dimension").getAsString().equals(maid.level().dimension().location().toString()))
                throw new IllegalArgumentException("container_baseline_requires_current_open_menu");
            String fingerprint=fingerprint(maid,condition);
            if(fingerprint==null)throw new IllegalArgumentException("container_baseline_requires_loaded_single_container");
            baselines.put(key(condition),fingerprint);
        }
        return baselines;
    }
    static boolean verify(EntityMaid maid, AgentTaskState task, JsonObject condition) {
        String expected=task.initialContainerSnapshots.get(key(condition));return expected!=null && expected.equals(fingerprint(maid,condition));
    }
    private static String fingerprint(EntityMaid maid,JsonObject condition) {
        if(!condition.get("dimension").getAsString().equals(maid.level().dimension().location().toString()))return null;
        var pos=position(condition);if(!maid.level().hasChunkAt(pos))return null;
        var state=maid.level().getBlockState(pos);
        if(state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE)return null;
        if(!(maid.level().getBlockEntity(pos) instanceof Container container))return null;
        StringBuilder data=new StringBuilder(state.toString()).append(':').append(container.getContainerSize());
        for(int i=0;i<container.getContainerSize();i++)data.append('|').append(i).append(':').append(container.getItem(i).save(new net.minecraft.nbt.CompoundTag()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
