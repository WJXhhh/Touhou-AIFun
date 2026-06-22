package team.cfpa.touhoustepfun.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import team.cfpa.touhoustepfun.TouhouStepFun;
import team.cfpa.touhoustepfun.network.message.StepFunSettingsMessage;
import team.cfpa.touhoustepfun.network.message.StepFunTTSStreamMessage;

import java.util.Optional;

public final class StepFunNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TouhouStepFun.MOD_ID, "network"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private StepFunNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(0, StepFunTTSStreamMessage.class,
                StepFunTTSStreamMessage::encode,
                StepFunTTSStreamMessage::decode,
                StepFunTTSStreamMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, StepFunSettingsMessage.class,
                StepFunSettingsMessage::encode,
                StepFunSettingsMessage::decode,
                StepFunSettingsMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToPlayer(StepFunTTSStreamMessage message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendSettingsToServer(boolean sentenceStreaming) {
        CHANNEL.sendToServer(new StepFunSettingsMessage(sentenceStreaming));
    }
}
