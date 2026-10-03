package com.wjx.touhou_aifun.maid.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;

public interface MaidGuiProcessAdapter {
    boolean supports(AbstractContainerMenu menu);
    GuiProcessState observe(MaidGuiSession session, String outputItem, int remainingCount);
}
