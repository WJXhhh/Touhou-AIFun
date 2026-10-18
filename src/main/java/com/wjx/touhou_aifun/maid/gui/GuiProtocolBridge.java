package com.wjx.touhou_aifun.maid.gui;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.network.*;

import java.util.*;

/** Native Forge play messages, addressed to a dedicated maid connection, never the owner's connection. */
public final class GuiProtocolBridge {
    private record Message(long sequence, ResourceLocation channel, byte[] bytes) { }
    private final MaidGuiActor actor;
    private final List<Message> pending = new ArrayList<>();
    private final Set<ResourceLocation> channels = new HashSet<>();
    private final EmbeddedChannel attributes = new EmbeddedChannel();
    private long sequence;
    private int size;
    private boolean lost;
    private boolean closed;
    private final Connection connection;
    private static final Set<String> RESERVED = Set.of("minecraft", "forge", "fml", "touhou_aifun");

    public GuiProtocolBridge(MaidGuiActor actor) {
        this.actor = actor;
        connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public io.netty.channel.Channel channel() { return attributes; }
            @Override public void send(Packet<?> packet) { capture(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { capture(packet); }
            @Override public boolean isConnected() { return true; }
        };
        actor.connection = new ServerGamePacketListenerImpl(actor.server, connection, actor);
        connection.setListener(actor.connection);
        if (actor.maid().getOwner() instanceof net.minecraft.server.level.ServerPlayer owner && owner.connection != null)
            copyNegotiation(connection, owner.connection.connection);
    }
    /** Copy only immutable Forge mod/channel metadata; the channel and player context remain separate. */
    public static void copyNegotiation(Connection target, Connection source) {
        if (source == null || source.channel() == null) return; // Standalone mod/test fake players have no transport.
        ConnectionData data = NetworkHooks.getConnectionData(source);
        if (data != null) target.channel().attr(io.netty.util.AttributeKey.<ConnectionData>valueOf("fml:conndata")).set(data);
    }
    private synchronized void capture(Packet<?> packet) {
        if (closed) return;
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || RESERVED.contains(custom.getIdentifier().getNamespace())) return;
        FriendlyByteBuf data = custom.getData(); // Clientbound#getData returns an owned copy.
        try {
            int bytes = data.readableBytes();
            if (bytes > 256 * 1024 || size + bytes > 640 * 1024 || pending.size() >= 2048) { lost = true; return; }
            byte[] copy = new byte[bytes]; data.getBytes(data.readerIndex(), copy);
            channels.add(custom.getIdentifier()); pending.add(new Message(++sequence, custom.getIdentifier(), copy)); size += bytes;
        } finally { data.release(); }
    }
    public synchronized void write(FriendlyByteBuf buffer) {
        if (lost) throw new IllegalArgumentException("gui_protocol_state_expired_reopen");
        buffer.writeVarInt(pending.size());
        for (Message message : pending) { buffer.writeLong(message.sequence); buffer.writeResourceLocation(message.channel); buffer.writeByteArray(message.bytes); }
    }
    public synchronized void beginMenu() { pending.clear(); channels.clear(); size = 0; lost = false; }
    public synchronized void acknowledge(long value) {
        if (value < 0 || value > sequence) throw new IllegalArgumentException("invalid_gui_protocol_ack");
        pending.removeIf(message -> { if (message.sequence <= value) { size -= message.bytes.length; return true; } return false; });
    }
    public synchronized boolean permits(ResourceLocation channel) {
        if (closed || RESERVED.contains(channel.getNamespace()) || actor.containerMenu == actor.inventoryMenu) return false;
        ResourceLocation menu;
        try { menu = BuiltInRegistries.MENU.getKey(actor.containerMenu.getType()); }
        catch (UnsupportedOperationException unknown) { return false; }
        return menu != null && (channel.getNamespace().equals(menu.getNamespace()) || channels.contains(channel));
    }
    public boolean dispatch(MaidGuiSession session, ResourceLocation channel, byte[] payload) {
        if (!permits(channel)) throw new IllegalArgumentException("gui_protocol_channel_outside_menu:" + channel);
        if (payload.length > 4096) throw new IllegalArgumentException("custom_packet_too_large");
        if (!Objects.equals(session.owner, actor.maid().getOwnerUUID()) || actor.maid().isRemoved()
                || !actor.containerMenu.stillValid(actor) || !actor.maid().level().getServer().isSameThread())
            throw new IllegalArgumentException("gui_protocol_session_unavailable");
        for (MaidGuiAdapter adapter : MaidGuiAdapters.gui()) if (adapter.supports(actor.containerMenu) && adapter.semanticOnly())
            throw new IllegalArgumentException("use_adapter_semantic_actions");
        var data = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            return NetworkHooks.onCustomPayload((ICustomPacket<?>) new ServerboundCustomPayloadPacket(channel, data), connection);
        } finally { data.release(); }
    }
    public synchronized void close() { closed = true; pending.clear(); channels.clear(); size = 0; attributes.finishAndReleaseAll(); }
}
