package com.wjx.aifun_gui_test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.wjx.touhou_aifun.compat.slashblade.SlashBladeAnimationSync;
import io.netty.buffer.Unpooled;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

@GameTestHolder("aifun_slashblade_test")
@PrefixGameTestTemplate(false)
public final class SlashBladeAnimationGameTests {
    /** Exercise the actual Forge share-tag codec, rather than only comparing server fields. */
    @GameTest(batch = "bladeAnimation", template = "empty", timeoutTicks = 100)
    public static void everyNativeComboSurvivesEquipmentPacket(GameTestHelper h) {
        EntityMaid maid = h.spawn(InitEntities.MAID.get(), 2, 2, 2);
        maid.setNoAi(true);
        ItemStack blade = blade();
        maid.setItemInHand(InteractionHand.MAIN_HAND, blade);
        long startedAt = h.getLevel().getGameTime();
        int count = 0;
        try {
            for (ResourceLocation combo : ComboStateRegistry.REGISTRY.get().getKeys()) {
                ISlashBladeState state = state(blade);
                state.setComboSeq(combo);
                state.setLastActionTime(++startedAt);
                ClientboundSetEquipmentPacket packet = SlashBladeAnimationSync.packet(maid, blade);
                // Packet must keep its own copy while the AI proceeds to the next node.
                state.setComboSeq(ComboStateRegistry.NONE.getId());
                state.setLastActionTime(startedAt + 100);
                ItemStack received = receive(packet);
                h.assertTrue(state(received).getComboSeq().equals(combo), "lost native combo: " + combo);
                h.assertTrue(state(received).getLastActionTime() == startedAt,
                        "lost action time or mutable packet snapshot: " + combo);
                count++;
            }
            h.assertTrue(count > 10, "must cover registered combos and sword arts, not only NONE");
            h.succeed();
        } finally {
            maid.discard();
        }
    }

    @GameTest(batch = "bladeAnimation", template = "empty", timeoutTicks = 100)
    public static void repeatedActionsStopAndWeaponSwapAreSynchronized(GameTestHelper h) {
        EntityMaid maid = h.spawn(InitEntities.MAID.get(), 2, 2, 2);
        maid.setNoAi(true);
        ItemStack blade = blade();
        maid.setItemInHand(InteractionHand.MAIN_HAND, blade);
        ISlashBladeState state = state(blade);
        state.setComboSeq(new ResourceLocation("slashblade", "combo_a1"));
        state.setLastActionTime(h.getLevel().getGameTime());
        SlashBladeAnimationSync sync = new SlashBladeAnimationSync();
        try {
            h.assertTrue(sync.publish(maid, blade), "initial action must be published");
            h.assertTrue(!sync.publish(maid, blade), "unchanged actions must not send every tick");
            state.setLastActionTime(state.getLastActionTime() + 1);
            h.assertTrue(sync.publish(maid, blade), "same combo with a new action time must be published");
            state.setComboSeq(ComboStateRegistry.NONE.getId());
            h.assertTrue(sync.publish(maid, blade), "stop must replace the old client combo immediately");
            h.assertTrue(state(receive(SlashBladeAnimationSync.packet(maid, blade)))
                    .getComboSeq().equals(ComboStateRegistry.NONE.getId()), "stop must reach client capability");
            ItemStack replacement = blade();
            maid.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            state.setLastActionTime(state.getLastActionTime() + 1);
            h.assertTrue(!sync.publish(maid, blade), "cancelled old session must not re-equip its blade on clients");
            h.assertTrue(maid.getMainHandItem() == replacement, "replacement must remain in hand");
            h.succeed();
        } finally {
            maid.discard();
        }
    }

    private static ItemStack receive(ClientboundSetEquipmentPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            return new ClientboundSetEquipmentPacket(buf).getSlots().stream()
                    .filter(slot -> slot.getFirst() == EquipmentSlot.MAINHAND)
                    .findFirst().orElseThrow().getSecond();
        } finally {
            buf.release();
        }
    }

    private static ItemStack blade() {
        return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("slashblade", "slashblade")));
    }

    private static ISlashBladeState state(ItemStack stack) {
        return stack.getCapability(ItemSlashBlade.BLADESTATE).orElseThrow(IllegalStateException::new);
    }
}
