package com.wjx.touhou_aifun.client.sound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.wjx.touhou_aifun.network.message.AIFunTTSStreamMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class AIFunStreamSoundManager {
    private static final Map<UUID, Playback> PLAYBACKS = new HashMap<>();

    private AIFunStreamSoundManager() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            interrupt();
            return;
        }
        // Prune playbacks whose stream and sound instance have both finished.
        isSpeaking();
    }

    public static void handle(AIFunTTSStreamMessage message) {
        switch (message.action()) {
            case START -> start(message);
            case DATA -> append(message.streamId(), message.data());
            case END -> finish(message.streamId());
        }
    }

    private static void start(AIFunTTSStreamMessage message) {
        if (SpeechMute.muted()) {
            return;
        }
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
        AIFunStreamingSoundInstance instance = new AIFunStreamingSoundInstance(maid, stream);
        Playback previous = PLAYBACKS.put(message.streamId(), new Playback(stream, instance));
        if (previous != null) {
            previous.stream.close();
        }
        minecraft.getSoundManager().play(instance);
    }

    private static void append(UUID streamId, byte[] data) {
        Playback playback = PLAYBACKS.get(streamId);
        if (playback != null) {
            playback.stream.append(data);
        }
    }

    private static void finish(UUID streamId) {
        Playback playback = PLAYBACKS.get(streamId);
        if (playback != null) {
            playback.finished = true;
            playback.stream.finish();
        }
    }

    /**
     * True while a streamed TTS reply is still being received or its sound instance is still
     * playing. Free chat raises its onset gate while this is true, but never stops capturing.
     */
    public static boolean isSpeaking() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            interrupt();
            return false;
        }
        boolean active = false;
        Iterator<Map.Entry<UUID, Playback>> iterator = PLAYBACKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Playback playback = iterator.next().getValue();
            if (playback.finished && !minecraft.getSoundManager().isActive(playback.instance)) {
                playback.stream.close();
                iterator.remove();
            } else {
                active = true;
            }
        }
        return active;
    }

    /** Abandon all active streamed TTS so a newer reply can take over. */
    public static void interrupt() {
        for (Playback playback : PLAYBACKS.values()) {
            playback.stream.close();
        }
        PLAYBACKS.clear();
    }

    private static final class Playback {
        private final AIFunPcmAudioStream stream;
        private final AIFunStreamingSoundInstance instance;
        private boolean finished;

        private Playback(AIFunPcmAudioStream stream, AIFunStreamingSoundInstance instance) {
            this.stream = stream;
            this.instance = instance;
        }
    }
}
