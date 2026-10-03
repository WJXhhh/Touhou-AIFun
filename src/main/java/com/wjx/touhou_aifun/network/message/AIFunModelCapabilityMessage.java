package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.vision.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Updates only image metadata; cannot change model connection parameters. */
public record AIFunModelCapabilityMessage(String modelRef, VisionCapabilityMode capability) {
    public static void encode(AIFunModelCapabilityMessage value, FriendlyByteBuf buffer) {
        buffer.writeUtf(value.modelRef, 1024);
        buffer.writeEnum(value.capability);
    }
    public static AIFunModelCapabilityMessage decode(FriendlyByteBuf buffer) {
        return new AIFunModelCapabilityMessage(buffer.readUtf(1024), buffer.readEnum(VisionCapabilityMode.class));
    }
    public static void handle(AIFunModelCapabilityMessage value, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        var sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> {
            String status = "permission_denied";
            if (com.github.tartaricacid.touhoulittlemaid.util.GameModeUtil.canEditSite(sender)) {
                ModelRef ref = ModelRef.decode(value.modelRef);
                status = ref == null || UnifiedModelCatalog.site(ref) == null ? "site_not_found"
                        : UnifiedModelCatalog.setCapability(ref, value.capability) ? "settings_saved" : "settings_save_failed";
            }
            AIFunNetwork.sendVisionSitesToPlayer(sender, status);
        });
        context.setPacketHandled(true);
    }
}
