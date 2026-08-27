package com.wjx.touhou_aifun.maid;

/** Addon-owned, per-maid access settings mixed into {@code EntityMaid}. */
public interface PublicMaidData {
    boolean touhouAIFun$isPublicMaid();

    void touhouAIFun$setPublicMaid(boolean publicMaid);

    boolean touhouAIFun$isFriendlyFireAllowed();

    void touhouAIFun$setFriendlyFireAllowed(boolean allowed);
}
