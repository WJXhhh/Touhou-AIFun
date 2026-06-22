package team.cfpa.touhoustepfun.client.sound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import team.cfpa.touhoustepfun.network.message.StepFunTTSStreamMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public final class StepFunStreamSoundManager {
    private static final Map<UUID, StepFunPcmAudioStream> STREAMS = new HashMap<>();

    private StepFunStreamSoundManager() {
    }

    public static void handle(StepFunTTSStreamMessage message) {
        switch (message.action()) {
            case START -> start(message);
            case DATA -> append(message.streamId(), message.data());
            case END -> finish(message.streamId());
        }
    }

    private static void start(StepFunTTSStreamMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(message.maidId());
        if (!(entity instanceof EntityMaid maid) || !maid.isAlive()) {
            return;
        }

        StepFunPcmAudioStream stream = new StepFunPcmAudioStream(message.sampleRate());
        stream.append(message.data());
        StepFunPcmAudioStream previous = STREAMS.put(message.streamId(), stream);
        if (previous != null) {
            previous.close();
        }
        minecraft.getSoundManager().play(new StepFunStreamingSoundInstance(maid, stream));
    }

    private static void append(UUID streamId, byte[] data) {
        StepFunPcmAudioStream stream = STREAMS.get(streamId);
        if (stream != null) {
            stream.append(data);
        }
    }

    private static void finish(UUID streamId) {
        StepFunPcmAudioStream stream = STREAMS.remove(streamId);
        if (stream != null) {
            stream.finish();
        }
    }
}
