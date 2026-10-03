package com.wjx.touhou_aifun.maid.gui;

import com.wjx.touhou_aifun.mixin.GuiMenuDataAccessor;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.List;

public final class FurnaceProcessAdapter implements MaidGuiProcessAdapter {
    @Override public boolean supports(AbstractContainerMenu menu) { return menu instanceof AbstractFurnaceMenu; }
    @Override public GuiProcessState observe(MaidGuiSession session, String requestedItem, int remainingCount) {
        AbstractFurnaceMenu menu = (AbstractFurnaceMenu) session.menu();
        List<DataSlot> data = ((GuiMenuDataAccessor) menu).touhouAIFun$dataSlots();
        if (data.size() < 4) return GuiProcessState.unknown();
        int burn = data.get(0).get(), progress = data.get(2).get(), total = data.get(3).get();
        ItemStack input = menu.getSlot(0).getItem(), output = menu.getSlot(2).getItem();
        long produced = session.observeProduction(output);
        if (input.isEmpty()) return new GuiProcessState(output.isEmpty() ? GuiProcessState.Status.BLOCKED
                : GuiProcessState.Status.COMPLETED, output.isEmpty() ? "missing_materials" : "",
                produced * Math.max(1, total), total, produced, output.isEmpty() ? -1 : 0, 20, true);
        RecipeType<? extends AbstractCookingRecipe> type = menu.recipeType;
        AbstractCookingRecipe recipe = session.maid.level().getRecipeManager()
                .getRecipeFor(type, new SimpleContainer(input.copy()), session.maid.level()).orElse(null);
        String reason = "";
        ItemStack result = recipe == null ? ItemStack.EMPTY : recipe.getResultItem(session.maid.level().registryAccess());
        if (recipe == null || (!requestedItem.isBlank() && !MaidGuiSession.itemId(result).equals(requestedItem))) reason = "invalid_recipe";
        else if (!output.isEmpty() && (!ItemStack.isSameItemSameTags(output, result)
                || output.getCount() + result.getCount() > Math.min(output.getMaxStackSize(), menu.getSlot(2).getMaxStackSize(output)))) reason = "output_full";
        else if (burn <= 0 && net.minecraftforge.common.ForgeHooks.getBurnTime(menu.getSlot(1).getItem(), type) <= 0) reason = "missing_fuel";
        // Use synchronized raw ticks, not the menu's 24-pixel progress-bar value.
        if (total <= 0 && recipe != null) total = recipe.getCookingTime();
        int perRecipe = Math.max(1, result.getCount());
        int available = requestedItem.isBlank() || MaidGuiSession.itemId(output).equals(requestedItem) ? output.getCount() : 0;
        long cycles = Math.max(1, (Math.max(0, remainingCount - available) + perRecipe - 1L) / perRecipe);
        long eta = total > 0 ? Math.max(0, total - progress) + (cycles - 1) * total : -1;
        return new GuiProcessState(reason.isBlank() ? (burn > 0 ? GuiProcessState.Status.RUNNING : GuiProcessState.Status.STARTING) : GuiProcessState.Status.BLOCKED,
                reason, produced * Math.max(1, total) + progress, total, produced, eta, 20, true);
    }
}
