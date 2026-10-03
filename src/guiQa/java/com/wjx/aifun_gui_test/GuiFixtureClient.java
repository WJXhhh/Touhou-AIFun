package com.wjx.aifun_gui_test;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "aifun_gui_test", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GuiFixtureClient {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(GuiFixtureMod.MENU.get(), TestScreen::new));
    }
    public static final class TestScreen extends AbstractContainerScreen<GuiFixtureMod.TestMenu> {
        public TestScreen(GuiFixtureMod.TestMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); }
        @Override protected void init() {
            super.init();
            addRenderableWidget(Button.builder(Component.literal("Standard"), b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0))
                    .bounds(leftPos + 6, topPos + 56, 72, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Custom"), b -> GuiFixtureMod.CHANNEL.sendToServer(new GuiFixtureMod.Mode(1 - menu.storage.mode)))
                    .bounds(leftPos + 90, topPos + 56, 72, 20).build());
            EditBox text = new EditBox(font, leftPos + 8, topPos - 24, 140, 18, Component.literal("Filter")); addRenderableWidget(text);
            addRenderableWidget(Button.builder(Component.literal("Unbridged"), b -> {
                var data = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()); data.writeByte(1);
                try { minecraft.player.connection.send(new net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket(
                        new net.minecraft.resources.ResourceLocation("aifun_gui_test", "unknown"), data)); }
                finally { data.release(); }
            })
                    .bounds(leftPos + 8, topPos + 170, 100, 18).build());
        }
        @Override protected void renderBg(GuiGraphics graphics, float partial, int x, int y) {
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff263149);
            graphics.fill(leftPos + 44, topPos + 30, leftPos + 62, topPos + 48, 0xff111111);
            graphics.fill(leftPos + 116, topPos + 30, leftPos + 134, topPos + 48, 0xff111111);
            graphics.fill(leftPos + 70, topPos + 34, leftPos + 70 + menu.storage.progress / 5, topPos + 42, 0xff7ab966);
            graphics.fill(leftPos + 70, topPos + 20, leftPos + 86, topPos + 30, 0xffa85928);
        }
        @Override protected void renderLabels(GuiGraphics graphics, int x, int y) {
            graphics.drawString(font, "Fixture mode: " + menu.storage.mode, 8, 8, 0xffffff, false);
        }
        @Override public void render(GuiGraphics graphics, int x, int y, float partial) { super.render(graphics, x, y, partial); renderTooltip(graphics, x, y); }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (x >= leftPos + 70 && x < leftPos + 86 && y >= topPos + 20 && y < topPos + 30) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0); return true;
            }
            return super.mouseClicked(x, y, button);
        }
    }
}
