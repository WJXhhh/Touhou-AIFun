package com.wjx.touhou_aifun.maid.management;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Client-safe row data for the management screen. */
public record MaidManagementEntry(UUID maidId, Component name, String dimension, BlockPos position,
                                  long lastSeen, State state, String modelId, float health,
                                  float maxHealth, String taskId, boolean homeMode,
                                  boolean publicMaid, boolean friendlyFireAllowed,
                                  boolean ownedByViewer,
                                  boolean configKnown, boolean configPending,
                                  MaidAIConfigSnapshot config) {
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(maidId);
        buffer.writeComponent(name);
        buffer.writeUtf(dimension, 256);
        buffer.writeBlockPos(position);
        buffer.writeLong(lastSeen);
        buffer.writeEnum(state);
        buffer.writeUtf(modelId, 512);
        buffer.writeFloat(health);
        buffer.writeFloat(maxHealth);
        buffer.writeUtf(taskId, 256);
        buffer.writeBoolean(homeMode);
        buffer.writeBoolean(publicMaid);
        buffer.writeBoolean(friendlyFireAllowed);
        buffer.writeBoolean(ownedByViewer);
        buffer.writeBoolean(configKnown);
        buffer.writeBoolean(configPending);
        config.write(buffer);
    }

    public static MaidManagementEntry read(FriendlyByteBuf buffer) {
        return new MaidManagementEntry(buffer.readUUID(), buffer.readComponent(), buffer.readUtf(256),
                buffer.readBlockPos(), buffer.readLong(), buffer.readEnum(State.class), buffer.readUtf(512),
                buffer.readFloat(), buffer.readFloat(), buffer.readUtf(256), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readBoolean(),
                MaidAIConfigSnapshot.read(buffer));
    }

    public enum State {
        LOADED_HERE,
        LOADED_OTHER_DIMENSION,
        UNLOADED
    }
}
