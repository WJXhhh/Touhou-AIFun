package com.wjx.touhou_aifun.client.sound;

import com.github.tartaricacid.touhoulittlemaid.client.sound.data.MaidAISoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.wjx.touhou_aifun.TouhouAIFun;

import java.util.ArrayDeque;
import java.util.Deque;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class QueuedTTSPlaybackManager {
    private static final Deque<SoundInstance> QUEUE = new ArrayDeque<>();
    private static SoundInstance current;
    private static boolean directPlay;
    private static int startupGraceTicks;

    private QueuedTTSPlaybackManager() {
    }

    public static boolean intercept(SoundManager soundManager, SoundInstance sound) {
        if (directPlay || !(sound instanceof MaidAISoundInstance)) {
            return false;
        }
        QUEUE.addLast(sound);
        playNext(soundManager);
        return true;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            QUEUE.clear();
            current = null;
            return;
        }
        if (current == null) {
            playNext(minecraft.getSoundManager());
            return;
        }
        if (startupGraceTicks > 0) {
            startupGraceTicks--;
            return;
        }
        if (!minecraft.getSoundManager().isActive(current)) {
            current = null;
            playNext(minecraft.getSoundManager());
        }
    }

    private static void playNext(SoundManager soundManager) {
        if (current != null || QUEUE.isEmpty()) {
            return;
        }
        current = QUEUE.removeFirst();
        startupGraceTicks = 2;
        directPlay = true;
        try {
            soundManager.play(current);
        } finally {
            directPlay = false;
        }
    }
}
