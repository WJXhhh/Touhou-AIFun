package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTSettingsService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Typed GUI operations, independent of chat commands. */
public record AIFunChatGPTActionMessage(UUID screenId, Action action, int port, boolean enabled, String clientId,
                                       boolean reasoningSummary, String reasoningEffort, boolean webSearch, boolean fastMode) {
    public enum Action { STATUS, LOGIN, NEW_ACCOUNT, CANCEL, MODELS, SAVE, LOGOUT, SELECT, RELOAD }

    public static void encode(AIFunChatGPTActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.screenId()); buffer.writeEnum(message.action());
        buffer.writeVarInt(message.port()); buffer.writeBoolean(message.enabled());
        buffer.writeUtf(message.clientId(), 256);
        buffer.writeBoolean(message.reasoningSummary()); buffer.writeUtf(message.reasoningEffort(), 32);
        buffer.writeBoolean(message.webSearch());
        buffer.writeBoolean(message.fastMode());
    }
    public static AIFunChatGPTActionMessage decode(FriendlyByteBuf buffer) {
        return new AIFunChatGPTActionMessage(buffer.readUUID(), buffer.readEnum(Action.class),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readUtf(256), buffer.readBoolean(), buffer.readUtf(32),
                buffer.readBoolean(), buffer.readBoolean());
    }
    public static void handle(AIFunChatGPTActionMessage message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getSender() != null) context.enqueueWork(() -> ChatGPTSettingsService.handle(context.getSender(), message));
        context.setPacketHandled(true);
    }
}
