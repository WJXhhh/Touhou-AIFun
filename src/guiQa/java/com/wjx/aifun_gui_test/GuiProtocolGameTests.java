package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;

@GameTestHolder("aifun_gui_test")
@PrefixGameTestTemplate(false)
public final class GuiProtocolGameTests {
    @GameTest(template="empty",templateNamespace="aifun_gui_test")
    public static void nativeForgePlayProtocolRunsAsMaidAndCapturesReplyWithoutChannelAdapter(GameTestHelper helper) {
        for(var floor:BlockPos.betweenClosed(new BlockPos(0,0,0),new BlockPos(5,0,5)))helper.setBlock(floor,Blocks.STONE);
        helper.setBlock(new BlockPos(1,1,1),GuiFixtureMod.MACHINE.get());
        var maid=helper.spawn(InitEntities.MAID.get(),new BlockPos(2,1,2));maid.setNoAi(true);
        var callback=new LLMCallback(maid.getAiChatManager(),new ArrayList<>(),true);var pos=helper.absolutePos(new BlockPos(1,1,1));
        var request=new JsonObject();request.addProperty("x",pos.getX());request.addProperty("y",pos.getY());request.addProperty("z",pos.getZ());request.addProperty("wait_policy","AUTO");
        var opened=MaidGuiSessionManager.call(callback,"open_gui","native_open",request).join();helper.assertTrue(!opened.has("error"),opened.toString());
        var session=MaidGuiSessionManager.session(maid.getUUID());var bytes=new FriendlyByteBuf(Unpooled.buffer());
        try {
            GuiFixtureMod.NATIVE_CHANNEL.encodeMessage(new GuiFixtureMod.NativeMode(123),bytes);byte[] payload=new byte[bytes.readableBytes()];bytes.readBytes(payload);
            helper.assertTrue(session.actor.protocol.dispatch(session,new ResourceLocation("aifun_gui_test","native_gui"),payload),"Native registered handler accepted packet");
            helper.assertTrue(((GuiFixtureMod.TestMenu)session.menu()).storage.nativeValue==123,"Handler sees maid sender/menu and applies actual state");
            var mirror=new FriendlyByteBuf(Unpooled.buffer());
            try {session.actor.protocol.write(mirror);helper.assertTrue(mirror.readVarInt()==1,"Native S2C state captured on maid's own connection");}
            finally{mirror.release();}
            helper.assertTrue(!session.actor.protocol.permits(new ResourceLocation("unrelated_mod","main")),"Unrelated channels cannot be dispatched through this menu");
            helper.assertTrue(!session.actor.protocol.permits(new ResourceLocation("touhou_aifun","main")),"Addon control channel is never replayed");
            var inventory = session.actor.getInventory();
            var wrapped = new net.minecraftforge.items.wrapper.CombinedInvWrapper(
                    new net.minecraftforge.items.wrapper.RangedWrapper(new net.minecraftforge.items.wrapper.InvWrapper(inventory), 1, 7));
            var backpack = new net.minecraftforge.items.SlotItemHandler(wrapped, 0, 0, 0);
            helper.assertTrue(MaidGuiSlots.inventorySlot(backpack, inventory) == 1,
                    "Nested Forge wrappers resolve the actual backpack address without relying on dummy Container identity");
            var unavailable = new net.minecraftforge.items.SlotItemHandler(new net.minecraftforge.items.wrapper.InvWrapper(inventory), 35, 0, 0);
            helper.assertTrue(!unavailable.mayPlace(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT))
                    && unavailable.getMaxStackSize() == 0, "Native Forge slots cannot insert into unavailable maid storage");
            var nativeMenu = session.menu();
            var unfamiliar = new net.minecraft.world.inventory.AbstractContainerMenu(GuiFixtureMod.MENU.get(), nativeMenu.containerId) {
                @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
                @Override public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return net.minecraft.world.item.ItemStack.EMPTY; }
            };
            session.actor.containerMenu = unfamiliar; session.visuallyObservedMenu = unfamiliar;
            var failedVision = new JsonObject(); failedVision.addProperty("image_status", "vision_disabled");
            com.wjx.touhou_aifun.vision.GuiVisualObservation.invalidateFailedInterpretation(callback, failedVision);
            helper.assertTrue(session.visuallyObservedMenu == null, "Uninterpreted screenshot cannot authorize blind unknown-machine actions");
            var blind = new JsonObject(); blind.addProperty("session_id", session.id.toString()); blind.addProperty("action", "button"); blind.addProperty("button_id", 0);
            var rejected = MaidGuiSessionManager.call(callback, "gui_action", "native_blind", blind).join();
            helper.assertTrue(rejected.has("error") && rejected.get("error").getAsString().equals("unfamiliar_machine_inspect_gui_visual_first"), "Actual dispatcher keeps unknown-machine mutations blocked after vision failure");
            session.actor.containerMenu = nativeMenu;
            helper.succeed();
        } finally {bytes.release();MaidGuiSessionManager.finish(callback);maid.discard();}
    }
}
