package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.config.MaidConfigContainerGui;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.MaidConfigButton;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.PublicMaidData;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaidConfigContainerGui.class, remap = false)
public abstract class MaidConfigContainerGuiMixin extends AbstractMaidContainerGui<MaidConfigContainer> {
    protected MaidConfigContainerGuiMixin(MaidConfigContainer menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "initAdditionWidgets", at = @At("TAIL"))
    private void touhouAIFun$addPublicAccessButtons(CallbackInfo ci) {
        PublicMaidData data = PublicMaidAccess.data(this.maid);
        int buttonLeft = this.leftPos + 86;
        int buttonTop = this.topPos + 156;
        boolean actualOwner = Minecraft.getInstance().player != null
                && PublicMaidAccess.isActualOwner(this.maid, Minecraft.getInstance().player);

        MaidConfigButton publicButton = new MaidConfigButton(buttonLeft, buttonTop,
                Component.translatable("gui.touhou_aifun.maid_config.public_maid"),
                booleanValue(data.touhouAIFun$isPublicMaid()),
                button -> {
                    data.touhouAIFun$setPublicMaid(!data.touhouAIFun$isPublicMaid());
                    button.setValue(booleanValue(data.touhouAIFun$isPublicMaid()));
                    sendAccessSettings(data);
                });
        publicButton.active = actualOwner;
        this.addRenderableWidget(publicButton);

        MaidConfigButton friendlyFireButton = new MaidConfigButton(buttonLeft, buttonTop + 13,
                Component.translatable("gui.touhou_aifun.maid_config.friendly_fire"),
                booleanValue(data.touhouAIFun$isFriendlyFireAllowed()),
                button -> {
                    data.touhouAIFun$setFriendlyFireAllowed(!data.touhouAIFun$isFriendlyFireAllowed());
                    button.setValue(booleanValue(data.touhouAIFun$isFriendlyFireAllowed()));
                    sendAccessSettings(data);
                });
        friendlyFireButton.active = actualOwner;
        this.addRenderableWidget(friendlyFireButton);
    }

    private static Component booleanValue(boolean value) {
        return Component.translatable("gui.touhou_little_maid.maid_config.value." + value);
    }

    private void sendAccessSettings(PublicMaidData data) {
        AIFunNetwork.sendMaidAccessToServer(this.maid.getId(), data.touhouAIFun$isPublicMaid(),
                data.touhouAIFun$isFriendlyFireAllowed());
    }
}
