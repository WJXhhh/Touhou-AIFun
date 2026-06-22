package team.cfpa.touhoustepfun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import team.cfpa.touhoustepfun.config.TouhouStepFunConfig;

import java.util.function.Supplier;

public record StepFunSettingsMessage(boolean sentenceStreaming) {
    public static void encode(StepFunSettingsMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.sentenceStreaming);
    }

    public static StepFunSettingsMessage decode(FriendlyByteBuf buffer) {
        return new StepFunSettingsMessage(buffer.readBoolean());
    }

    public static void handle(StepFunSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null && context.getSender().hasPermissions(2)) {
                TouhouStepFunConfig.setSentenceStreaming(message.sentenceStreaming);
            }
        });
        context.setPacketHandled(true);
    }
}
