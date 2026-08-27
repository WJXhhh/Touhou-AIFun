package com.wjx.touhou_aifun.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;

import java.util.Objects;

public final class PublicMaidAccess {
    private PublicMaidAccess() {
    }

    public static PublicMaidData data(EntityMaid maid) {
        return (PublicMaidData) maid;
    }

    public static boolean isPublic(EntityMaid maid) {
        return data(maid).touhouAIFun$isPublicMaid();
    }

    public static boolean isFriendlyFireAllowed(EntityMaid maid) {
        return data(maid).touhouAIFun$isFriendlyFireAllowed();
    }

    /** Public access is deliberately limited to real players, not automation fake players. */
    public static boolean isPublicPlayer(EntityMaid maid, Player player) {
        return !(player instanceof FakePlayer) && isPublic(maid);
    }

    /** This check never uses isOwnedBy because public access intentionally broadens that method. */
    public static boolean isActualOwner(EntityMaid maid, Player player) {
        return Objects.equals(maid.getOwnerUUID(), player.getUUID());
    }
}
