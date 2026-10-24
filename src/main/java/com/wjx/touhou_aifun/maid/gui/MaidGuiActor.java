package com.wjx.touhou_aifun.maid.gui;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

public final class MaidGuiActor extends FakePlayer {
    private final EntityMaid maid;
    private Component title = Component.empty();
    private byte[] openingData = new byte[0];
    private MerchantOffers offers;
    private int guiCounter;
    public int droppedCount;
    public final GuiProtocolBridge protocol;
    public MaidGuiActor(EntityMaid maid) {
        super((ServerLevel) maid.level(), new GameProfile(UUID.nameUUIDFromBytes(
                ("touhou_aifun:gui:" + maid.getUUID()).getBytes(StandardCharsets.UTF_8)), "[AIFunMaid]"));
        this.maid = maid;
        protocol = new GuiProtocolBridge(this);
        align();
    }
    public EntityMaid maid() { return maid; }
    public Component title() { return title; }
    public byte[] openingData() { return openingData.clone(); }
    public MerchantOffers offers() { return offers; }
    public void align() { moveTo(maid.getX(), maid.getY(), maid.getZ(), maid.getYRot(), maid.getXRot()); }

    /**
     * Deterministic view rotation for simulated placement. Vanilla block orientation is derived
     * from the fake player's yaw/pitch, so the maid's wandering Brain yaw must not leak into it.
     */
    public void orient(float yaw, float pitch) {
        float wrapped = net.minecraft.util.Mth.wrapDegrees(yaw);
        float clamped = net.minecraft.util.Mth.clamp(pitch, -90.0F, 90.0F);
        setYRot(wrapped);
        setXRot(clamped);
        yRotO = wrapped;
        xRotO = clamped;
        setYHeadRot(wrapped);
        setYBodyRot(wrapped);
    }
    @Override public OptionalInt openMenu(MenuProvider provider) { return open(provider, buffer -> { }); }
    public OptionalInt open(MenuProvider provider, Consumer<FriendlyByteBuf> writer) {
        if (provider == null) return OptionalInt.empty();
        if (containerMenu != inventoryMenu) closeContainer();
        protocol.beginMenu();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            if (buffer.readableBytes() > 32600) throw new IllegalArgumentException("GUI opening data too large");
            openingData = new byte[buffer.readableBytes()]; buffer.readBytes(openingData);
        } finally { buffer.release(); }
        guiCounter = guiCounter % 100 + 1;
        AbstractContainerMenu menu = provider.createMenu(guiCounter, getInventory(), this);
        if (menu == null) return OptionalInt.empty();
        title = provider.getDisplayName(); offers = null;
        containerMenu = menu;
        initMenu(menu);
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(this, menu));
        return OptionalInt.of(guiCounter);
    }
    @Override public void sendMerchantOffers(int id, MerchantOffers offers, int level, int xp, boolean progress, boolean restock) {
        this.offers = offers;
    }
    @Override public boolean hasDisconnected() { return false; }
    @Override protected int getPermissionLevel() { return 0; }
    @Override public ItemEntity drop(ItemStack stack, boolean random, boolean trace) {
        droppedCount += stack.getCount();
        return stack.isEmpty() ? null : maid.spawnAtLocation(stack);
    }
}
