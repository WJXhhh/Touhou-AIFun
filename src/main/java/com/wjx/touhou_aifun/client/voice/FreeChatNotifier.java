package com.wjx.touhou_aifun.client.voice;

import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client-side free-chat feedback display. It draws a small stack of fading lines centered just
 * above the vanilla action bar instead of writing the action bar itself, so other mods' action bar
 * messages are never overwritten. The exact y offset is measured from the 1.20.1 HUD layout: the
 * action bar text occupies screenHeight-72..-63 (backdrop up to -74..-61), while the band above it
 * is always free. The band between the action bar and the health row is not usable: armor,
 * absorption and air-bubble rows move into it (full armor starts at -59), leaving as little as
 * two pixels.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FreeChatNotifier {
    /** Top of the newest (bottom-most) line, measured upward from the bottom screen edge. */
    private static final int BOTTOM_OFFSET = 86;
    private static final int LINE_GAP = 2;
    private static final int SIDE_MARGIN = 6;
    private static final int TEXT_PADDING = 2;
    private static final int BACKGROUND_ALPHA_NUMERATOR = 3;
    private static final int BACKGROUND_ALPHA_DENOMINATOR = 4;
    private static final int BACKGROUND_RGB = 0x000000;
    private static final int TEXT_RGB = 0xFFFFFF;

    private static final FreeChatNoticeQueue QUEUE = new FreeChatNoticeQueue();

    private FreeChatNotifier() {
    }

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("free_chat_notice", FreeChatNotifier::render);
    }

    /** Routes one free-chat notice according to {@code [stt].freeChatNotice}. */
    public static void show(Component text) {
        if (text == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        switch (mode()) {
            case "off" -> {
            }
            case "chat" -> minecraft.player.displayClientMessage(text, false);
            case "action_bar" -> minecraft.player.displayClientMessage(text, true);
            default -> QUEUE.push(text.getString(), System.currentTimeMillis());
        }
    }

    public static void clear() {
        QUEUE.clear();
    }

    private static String mode() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) {
                return TouhouAIFunConfig.freeChatNotice();
            }
        } catch (RuntimeException ignored) {
            // A missing or failed config must never take the client down; fall back to the HUD.
        }
        return "hud";
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                               int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || minecraft.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        List<FreeChatNoticeQueue.Entry> entries = QUEUE.visible(now);
        if (entries.isEmpty()) {
            return;
        }
        Font font = minecraft.font;
        int maxWidth = Math.max(24, screenWidth - 2 * SIDE_MARGIN - 2 * TEXT_PADDING);
        int lineHeight = font.lineHeight + LINE_GAP;
        int y = screenHeight - BOTTOM_OFFSET;
        // Newest line sits at the bottom, older lines stack upward.
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (y - TEXT_PADDING < 0) {
                break;
            }
            FreeChatNoticeQueue.Entry entry = entries.get(i);
            int alpha = FreeChatNoticeQueue.alpha(entry, now);
            if (alpha <= 0) {
                continue;
            }
            String line = fit(font, entry.text(), maxWidth);
            int width = font.width(line);
            int left = (screenWidth - width) / 2;
            int backgroundAlpha = alpha * BACKGROUND_ALPHA_NUMERATOR / BACKGROUND_ALPHA_DENOMINATOR;
            graphics.fill(left - TEXT_PADDING, y - TEXT_PADDING,
                    left + width + TEXT_PADDING, y + font.lineHeight + TEXT_PADDING,
                    backgroundAlpha << 24 | BACKGROUND_RGB);
            graphics.drawString(font, line, left, y, alpha << 24 | TEXT_RGB, true);
            y -= lineHeight;
        }
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int budget = Math.max(0, maxWidth - font.width("…"));
        return font.plainSubstrByWidth(text, budget) + "…";
    }
}
