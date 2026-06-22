package com.wjx.touhou_aifun.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.TTSSiteEditorScreen$ModelRow", remap = false)
public interface ModelRowAccessor {
    @Accessor("id")
    String touhouAIFun$getId();

    @Accessor("name")
    String touhouAIFun$getName();

    @Accessor("idBox")
    EditBox touhouAIFun$getIdBox();

    @Accessor("nameBox")
    EditBox touhouAIFun$getNameBox();
}
