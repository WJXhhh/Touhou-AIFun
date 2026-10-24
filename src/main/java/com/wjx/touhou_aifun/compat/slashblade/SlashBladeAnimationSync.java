package com.wjx.touhou_aifun.compat.slashblade;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Sends native blade state for YSM's existing per-combo animation controller. */
public final class SlashBladeAnimationSync {
    private Frame lastSent;

    /**
     * YSM 2.6.5 reads both values from the held blade capability. A repeated combo can
     * have the same ID with a new start time, so comparing just the ID loses actions.
     * No YSM classes are referenced: the ordinary equipment packet uses the blade's
     * share tag to restore its capability on the client, including for late trackers.
     */
    public boolean publish(EntityMaid maid, ItemStack blade) {
        // A cancelled session may still own the OLD stack after the maid swaps tools.
        // Never equip that stack back onto an observer's copy of the maid.
        if (!(maid.level() instanceof ServerLevel level) || maid.getMainHandItem() != blade) return false;
        var state = blade.getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null) return false;
        Frame frame = new Frame(state.getComboSeq(), state.getLastActionTime());
        if (frame.equals(lastSent)) return false;
        level.getChunkSource().broadcast(maid, packet(maid, blade));
        lastSent = frame;
        return true;
    }

    /** Take a detached capability snapshot before later server ticks change the blade. */
    public static ClientboundSetEquipmentPacket packet(EntityMaid maid, ItemStack blade) {
        return new ClientboundSetEquipmentPacket(maid.getId(),
                java.util.List.of(Pair.of(EquipmentSlot.MAINHAND, blade.copy())));
    }

    private record Frame(ResourceLocation combo, long startedAt) {}
}
