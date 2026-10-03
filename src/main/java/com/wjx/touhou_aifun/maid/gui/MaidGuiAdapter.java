package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Optional mod integrations register during common setup; packets never run as the owner. */
public interface MaidGuiAdapter {
    boolean supports(AbstractContainerMenu menu);
    default JsonArray controls(MaidGuiSession session) { return new JsonArray(); }
    default byte[] initializationData(MaidGuiSession session) { return session.actor.openingData(); }
    /** Snapshot custom S2C state for the matching client adapter; never replay it on the owner. */
    default JsonObject clientState(MaidGuiSession session) { return new JsonObject(); }
    default boolean action(MaidGuiSession session, JsonObject action) { return false; }
    /** Only menu-owned inputs that removed() returns. Never declare persistent machine storage. */
    default boolean transientInventory(MaidGuiSession session, net.minecraft.world.Container inventory) { return false; }
    default boolean supportsChannel(ResourceLocation channel) { return false; }
    default boolean customPacket(MaidGuiSession session, ResourceLocation channel, FriendlyByteBuf payload) {
        return false;
    }
}
