package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Server-persistent metadata used when the maid entity's chunk is not loaded. */
public record MaidDirectoryRecord(UUID ownerId, UUID maidId, String nameJson, String dimension,
                                  BlockPos position, long lastSeen, String modelId,
                                  float health, float maxHealth, String taskId, boolean homeMode,
                                  boolean publicMaid, boolean friendlyFireAllowed,
                                  boolean configKnown, boolean configPending,
                                  int pendingMask, MaidAIConfigSnapshot config) {
    public static MaidDirectoryRecord fromMaid(EntityMaid maid) {
        UUID ownerId = maid.getOwnerUUID();
        String name = Component.Serializer.toJson(maid.getDisplayName());
        String dimension = maid.level().dimension().location().toString();
        String taskId = maid.getTask().getUid().toString();
        return new MaidDirectoryRecord(ownerId, maid.getUUID(), name, dimension, maid.blockPosition(),
                System.currentTimeMillis(), maid.getModelId(), maid.getHealth(), maid.getMaxHealth(), taskId,
                maid.isHomeModeEnable(), PublicMaidAccess.isPublic(maid),
                PublicMaidAccess.isFriendlyFireAllowed(maid), true, false, 0,
                MaidAIConfigSnapshot.from(maid.getAiChatManager()));
    }

    public MaidDirectoryRecord withPendingConfig(MaidAIConfigSnapshot nextConfig, boolean nextPublic,
                                                 boolean nextFriendlyFire, int nextMask) {
        int mergedMask = pendingMask | nextMask;
        MaidAIConfigSnapshot mergedConfig = config.merge(nextConfig, nextMask);
        return new MaidDirectoryRecord(ownerId, maidId, nameJson, dimension, position, lastSeen, modelId,
                health, maxHealth, taskId, homeMode,
                (nextMask & MaidAIConfigSnapshot.PUBLIC_MAID) != 0 ? nextPublic : publicMaid,
                (nextMask & MaidAIConfigSnapshot.FRIENDLY_FIRE) != 0 ? nextFriendlyFire : friendlyFireAllowed,
                configKnown, true, mergedMask, mergedConfig);
    }

    public Component displayName() {
        Component component = Component.Serializer.fromJson(nameJson);
        return component == null ? Component.literal(maidId.toString()) : component;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Owner", ownerId);
        tag.putUUID("Maid", maidId);
        tag.putString("Name", nameJson);
        tag.putString("Dimension", dimension);
        tag.put("Position", NbtUtils.writeBlockPos(position));
        tag.putLong("LastSeen", lastSeen);
        tag.putString("Model", modelId);
        tag.putFloat("Health", health);
        tag.putFloat("MaxHealth", maxHealth);
        tag.putString("Task", taskId);
        tag.putBoolean("HomeMode", homeMode);
        tag.putBoolean("PublicMaid", publicMaid);
        tag.putBoolean("FriendlyFire", friendlyFireAllowed);
        tag.putBoolean("ConfigKnown", configKnown);
        tag.putBoolean("ConfigPending", configPending);
        tag.putInt("PendingMask", pendingMask);
        if (configKnown || configPending) {
            tag.put("AIConfig", config.toTag());
        }
        return tag;
    }

    public static MaidDirectoryRecord fromTag(CompoundTag tag) {
        MaidAIConfigSnapshot config = tag.contains("AIConfig", Tag.TAG_COMPOUND)
                ? MaidAIConfigSnapshot.fromTag(tag.getCompound("AIConfig")) : MaidAIConfigSnapshot.EMPTY;
        return new MaidDirectoryRecord(tag.getUUID("Owner"), tag.getUUID("Maid"), tag.getString("Name"),
                tag.getString("Dimension"), NbtUtils.readBlockPos(tag.getCompound("Position")),
                tag.getLong("LastSeen"), tag.getString("Model"), tag.getFloat("Health"),
                tag.getFloat("MaxHealth"), tag.getString("Task"), tag.getBoolean("HomeMode"),
                tag.getBoolean("PublicMaid"), !tag.contains("FriendlyFire", Tag.TAG_BYTE)
                        || tag.getBoolean("FriendlyFire"), tag.getBoolean("ConfigKnown"),
                tag.getBoolean("ConfigPending"), tag.getInt("PendingMask"), config);
    }
}
