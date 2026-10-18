package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.chat.freechat.FreeChatGate;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> server free-chat utterance. The client has already run ASR; the server validates the
 * candidates, filters self-echo and asks the configured JEV/Perplexity decision service before any
 * chat turn starts.
 */
public record AIFunFreeChatUtteranceMessage(long sequence, List<Integer> candidateMaidIds,
                                           String transcript, String playerScene,
                                           List<String> recentUtterances,
                                           String clientLanguage, int speechMillis) {
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_RECENT = 3;

    public AIFunFreeChatUtteranceMessage {
        candidateMaidIds = candidateMaidIds == null ? List.of()
                : List.copyOf(candidateMaidIds.subList(0, Math.min(MAX_CANDIDATES, candidateMaidIds.size())));
        recentUtterances = recentUtterances == null ? List.of()
                : recentUtterances.subList(0, Math.min(MAX_RECENT, recentUtterances.size())).stream()
                .map(value -> limit(value, 200)).toList();
        transcript = limit(transcript, 2000);
        playerScene = limit(playerScene, 2000);
        clientLanguage = limit(clientLanguage, 32);
        if (clientLanguage.isBlank()) {
            clientLanguage = "en_us";
        }
        speechMillis = Math.max(0, Math.min(30000, speechMillis));
    }

    private static String limit(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    public static void encode(AIFunFreeChatUtteranceMessage message, FriendlyByteBuf buffer) {
        buffer.writeLong(message.sequence);
        buffer.writeVarInt(message.candidateMaidIds.size());
        for (int maidId : message.candidateMaidIds) {
            buffer.writeVarInt(maidId);
        }
        buffer.writeUtf(message.transcript, 4096);
        buffer.writeUtf(message.playerScene, 4096);
        buffer.writeVarInt(message.recentUtterances.size());
        for (String recent : message.recentUtterances) {
            buffer.writeUtf(recent == null ? "" : recent, 512);
        }
        buffer.writeUtf(message.clientLanguage, 32);
        buffer.writeVarInt(message.speechMillis);
    }

    public static AIFunFreeChatUtteranceMessage decode(FriendlyByteBuf buffer) {
        long sequence = buffer.readLong();
        int candidateCount = buffer.readVarInt();
        if (candidateCount < 0 || candidateCount > MAX_CANDIDATES) {
            throw new DecoderException("free_chat_candidate_limit");
        }
        List<Integer> maidIds = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            maidIds.add(buffer.readVarInt());
        }
        String transcript = buffer.readUtf(4096);
        String scene = buffer.readUtf(4096);
        int recentCount = buffer.readVarInt();
        if (recentCount < 0 || recentCount > MAX_RECENT) {
            throw new DecoderException("free_chat_recent_limit");
        }
        List<String> recent = new ArrayList<>(recentCount);
        for (int i = 0; i < recentCount; i++) {
            recent.add(buffer.readUtf(512));
        }
        String language = buffer.readUtf(32);
        int speechMillis = buffer.readVarInt();
        return new AIFunFreeChatUtteranceMessage(sequence, maidIds, transcript, scene, recent,
                language, speechMillis);
    }

    public static void handle(AIFunFreeChatUtteranceMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> FreeChatGate.submit(sender, message));
        }
        context.setPacketHandled(true);
    }

    @Override
    public String toString() {
        return "FreeChatUtterance[sequence=" + sequence + ", candidates=" + candidateMaidIds.size()
                + ", transcriptChars=" + transcript.length() + ", sceneChars=" + playerScene.length()
                + ", recent=" + recentUtterances.size() + ", speechMillis=" + speechMillis + "]";
    }
}
