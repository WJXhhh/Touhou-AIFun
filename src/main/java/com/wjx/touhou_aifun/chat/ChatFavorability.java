package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** Awards favorability for completed AI conversations without making chat spammable. */
public final class ChatFavorability {
    public static final int POINTS = 1;
    public static final int COOLDOWN_TICKS = 5 * 60 * 20;

    private static final Type AI_CHAT = new Type("AIFunChat", POINTS, COOLDOWN_TICKS);

    private ChatFavorability() {
    }

    /**
     * Awards one chat event. The maid's favorability manager owns and persists the cooldown, so
     * reconnecting or restarting cannot be used to bypass it.
     */
    public static void awardCompletedChat(EntityMaid maid) {
        if (maid == null || maid.level().isClientSide) {
            return;
        }
        maid.getFavorabilityManager().apply(AI_CHAT);
    }
}
