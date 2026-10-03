package com.wjx.touhou_aifun.client.gui.automation;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Register only in client setup. Applies custom S2C state to the detached menu/screen. */
public interface MaidGuiClientAdapter {
    boolean supports(AbstractContainerMenu menu);
    void apply(AbstractContainerMenu menu, Screen screen, JsonObject data);
}
