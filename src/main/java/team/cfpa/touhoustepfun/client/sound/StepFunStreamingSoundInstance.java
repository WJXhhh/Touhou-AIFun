package team.cfpa.touhoustepfun.client.sound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.CompletableFuture;

public final class StepFunStreamingSoundInstance extends EntityBoundSoundInstance {
    private final StepFunPcmAudioStream stream;

    public StepFunStreamingSoundInstance(EntityMaid maid, StepFunPcmAudioStream stream) {
        super(InitSounds.MAID_AI_CHAT.get(), SoundSource.NEUTRAL, 1.0f, 1.0f, maid, maid.getId());
        this.stream = stream;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary library, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(stream);
    }
}
