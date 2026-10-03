package com.wjx.aifun_gui_test;

import com.wjx.touhou_aifun.maid.gui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.*;
import java.util.*;

/** Run with -PguiQa. Includes a standard menu button and an adapter-only custom protocol. */
@Mod("aifun_gui_test")
public final class GuiFixtureMod {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation("aifun_gui_test", "mode");
    public static final net.minecraftforge.network.simple.SimpleChannel CHANNEL = net.minecraftforge.network.NetworkRegistry.newSimpleChannel(CHANNEL_ID, () -> "1", "1"::equals, "1"::equals);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "aifun_gui_test");
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "aifun_gui_test");
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, "aifun_gui_test");
    public static final RegistryObject<MenuType<TestMenu>> MENU = MENUS.register("machine", () -> net.minecraftforge.common.extensions.IForgeMenuType.create(TestMenu::new));
    public static final RegistryObject<Block> MACHINE = BLOCKS.register("machine", () -> new Block(BlockBehaviour.Properties.of().strength(1)) {
        @Override public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
            return new SimpleMenuProvider((id, inventory, player) -> new TestMenu(id, inventory, pos, storage(level, pos)), Component.literal("GUI Compatibility Machine"));
        }
        @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) NetworkHooks.openScreen(serverPlayer, getMenuProvider(state, level, pos), pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    });
    static { ITEMS.register("machine", () -> new BlockItem(MACHINE.get(), new Item.Properties())); }
    public static final RegistryObject<Item> TERMINAL = ITEMS.register("terminal", () -> new Item(new Item.Properties().stacksTo(1)) {
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                BlockPos pos = player.blockPosition();
                NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider((id, inventory, actor) -> new TestMenu(id, inventory, pos, storage(level, pos)), Component.literal("Held terminal")), pos);
            }
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
    });
    private static final Map<String, Storage> STORAGE = new HashMap<>();
    private static Storage storage(Level level, BlockPos pos) { return STORAGE.computeIfAbsent(level.dimension().location() + ":" + pos, ignored -> new Storage()); }
    public GuiFixtureMod() {
        CHANNEL.registerMessage(0, Mode.class, (m, b) -> b.writeByte(m.value), b -> new Mode(b.readUnsignedByte()), (m, ctx) -> {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender(); if (m.value <= 1 && player != null && player.containerMenu instanceof TestMenu menu) menu.storage.mode = m.value;
            }); ctx.get().setPacketHandled(true);
        }, Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        var bus = FMLJavaModLoadingContext.get().getModEventBus(); BLOCKS.register(bus); ITEMS.register(bus); MENUS.register(bus);
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            MaidGuiAdapters.register(new MaidGuiAdapter() {
                @Override public boolean supports(AbstractContainerMenu menu) { return menu instanceof TestMenu; }
                @Override public boolean supportsChannel(ResourceLocation channel) { return CHANNEL_ID.equals(channel); }
                @Override public boolean customPacket(MaidGuiSession session, ResourceLocation channel, FriendlyByteBuf bytes) {
                    if (!channel.equals(CHANNEL_ID)) return false;
                    if (bytes.readableBytes() != 2 || bytes.readUnsignedByte() != 0) throw new IllegalArgumentException("invalid_fixture_mode");
                    int mode = bytes.readUnsignedByte(); if (mode > 1) throw new IllegalArgumentException("invalid_fixture_mode");
                    ((TestMenu) session.menu()).storage.mode = mode; return true;
                }
            });
            MaidGuiAdapters.registerProcess(new MaidGuiProcessAdapter() {
                @Override public boolean supports(AbstractContainerMenu menu) { return menu instanceof TestMenu; }
                @Override public GuiProcessState observe(MaidGuiSession session, String item, int remaining) {
                    Storage storage = ((TestMenu) session.menu()).storage;
                    return new GuiProcessState(storage.items.getItem(0).isEmpty() ? GuiProcessState.Status.BLOCKED : GuiProcessState.Status.RUNNING,
                            "missing_materials", storage.produced * 200 + storage.progress, 200, storage.produced, Math.max(0, remaining * 200L - storage.progress), 20, true);
                }
            });
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) return;
            for (Storage state : STORAGE.values()) if (!state.items.getItem(0).isEmpty() && ++state.progress >= 200) {
                state.progress = 0; state.produced++; state.items.getItem(0).shrink(1); state.items.setItem(1, new ItemStack(Items.PAPER, state.items.getItem(1).getCount() + 1));
            }
        });
    }
    public static final class Storage {
        final SimpleContainer items = new SimpleContainer(2);
        int mode, progress, produced;
    }
    public record Mode(int value) { }
    public static final class TestMenu extends AbstractContainerMenu {
        final Storage storage;
        final BlockPos pos;
        public TestMenu(int id, Inventory inventory, FriendlyByteBuf data) { this(id, inventory, data.readBlockPos(), new Storage()); }
        TestMenu(int id, Inventory inventory, BlockPos pos, Storage storage) {
            super(MENU.get(), id); this.pos = pos; this.storage = storage;
            addSlot(new Slot(storage.items, 0, 44, 30)); addSlot(new Slot(storage.items, 1, 116, 30) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            });
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
            addDataSlot(new DataSlot() { @Override public int get() { return storage.mode; } @Override public void set(int value) { storage.mode = value; } });
            addDataSlot(new DataSlot() { @Override public int get() { return storage.progress; } @Override public void set(int value) { storage.progress = value; } });
        }
        @Override public boolean clickMenuButton(Player player, int button) { if (button != 0) return false; storage.mode = 1 - storage.mode; return true; }
        @Override public boolean stillValid(Player player) { return player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) < 64; }
        @Override public ItemStack quickMoveStack(Player player, int index) {
            Slot slot = slots.get(index); ItemStack source = slot.getItem(), copy = source.copy();
            if (source.isEmpty() || !moveItemStackTo(source, index < 2 ? 2 : 0, index < 2 ? slots.size() : 1, false)) return ItemStack.EMPTY;
            if (source.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged(); slot.onTake(player, source); return copy;
        }
    }
}
