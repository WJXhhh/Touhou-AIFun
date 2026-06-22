package com.wjx.touhou_aifun.client.sound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public final class AIFunStreamSoundManager {
    private static final Map<UUID, AIFunPcmAudioStream> STREAMS = new HashMap<>();

    private AIFunStreamSoundManager() {
    }

    public static void handle(AIFunTTSStreamMessage message) {
        switch (message.action()) {
            case START -> start(message);
            case DATA -> append(message.streamId(), message.data());
            case END -> finish(message.streamId());
        }
    }

    private static void start(AIFunTTSStreamMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(message.maidId());
        if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
            return;
        }

        AIFunPcmAudioStream stream = new AIFunPcmAudioStream(message.sampleRate());
        stream.append(message.data());
        AIFunPcmAudioStream previous = STREAMS.put(message.streamId(), stream);
        if (previous != null) {
            previous.close();
        }
        minecraft.getSoundManager().play(new AIFunStreamingSoundInstance(maid, stream));
    }

    private static void append(UUID streamId, byte[] data) {
        AIFunPcmAudioStream stream = STREAMS.get(streamId);
        if (stream != null) {
            stream.append(data);
        }
    }

    private static void finish(UUID streamId) {
        AIFunPcmAudioStream stream = STREAMS.remove(streamId);
        if (stream != null) {
            stream.finish();
        }
    }
}
