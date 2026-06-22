package com.wjx.touhou_aifun.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.network.message.AIFunSettingsMessage;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;

import java.util.Optional;

public final class AIFunNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TouhouAIFun.MOD_ID, "network"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private AIFunNetwork() {
    }

    public static void init() {
        CHANNEL.registerMessage(0, AIFunTTSStreamMessage.class,
                AIFunTTSStreamMessage::encode,
                AIFunTTSStreamMessage::decode,
                AIFunTTSStreamMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, AIFunSettingsMessage.class,
                AIFunSettingsMessage::encode,
                AIFunSettingsMessage::decode,
                AIFunSettingsMessage::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void sendToPlayer(AIFunTTSStreamMessage message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendSettingsToServer(boolean sentenceStreaming) {
        CHANNEL.sendToServer(new AIFunSettingsMessage(sentenceStreaming));
    }
}
