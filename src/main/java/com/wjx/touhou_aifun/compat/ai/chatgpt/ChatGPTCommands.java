package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncAISitesMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;

/** Console / operator administration. Ordinary players cannot authorize or change the host account. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class ChatGPTCommands {
    private ChatGPTCommands() { }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aifun").then(Commands.literal("chatgpt")
                .requires(source -> source.hasPermission(4) || (source.getServer().isSingleplayer()
                        && source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                        && source.getServer().isSingleplayerOwner(player.getGameProfile())))
                .then(Commands.literal("status").executes(ctx -> background(ctx, () -> ChatGPTSession.status())))
                .then(Commands.literal("login").executes(ctx -> login(ctx, 1455, false))
                        .then(Commands.argument("port", IntegerArgumentType.integer(1024, 65535))
                                .executes(ctx -> login(ctx, IntegerArgumentType.getInteger(ctx, "port"), false))))
                .then(Commands.literal("new-account").executes(ctx -> login(ctx, 1455, true)))
                .then(Commands.literal("accounts").executes(ctx -> background(ctx, ChatGPTSession::profiles)))
                .then(Commands.literal("select").then(Commands.argument("client-id", StringArgumentType.word())
                        .executes(ctx -> background(ctx, () -> {
                            String status = ChatGPTSession.select(StringArgumentType.getString(ctx, "client-id"));
                            refresh(ctx.getSource());
                            return status;
                        }))))
                .then(Commands.literal("cancel").executes(ctx -> { ChatGPTSession.cancelLogin(); reply(ctx.getSource(), "已取消待完成的登录"); return 1; }))
                .then(Commands.literal("logout").executes(ctx -> background(ctx, ChatGPTSession::logout)))
                .then(Commands.literal("reload").executes(ctx -> background(ctx, () -> { ChatGPTSession.reload(); return ChatGPTSession.status(); })))
                .then(Commands.literal("models").executes(ctx -> { refresh(ctx.getSource()); return 1; }))
                .then(Commands.literal("enable").executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("disable").executes(ctx -> setEnabled(ctx, false)))));
    }

    private static int login(CommandContext<CommandSourceStack> ctx, int port, boolean newAccount) {
        CommandSourceStack source = ctx.getSource();
        try {
            String url = ChatGPTSession.login(port, newAccount, message -> {
                reply(source, message);
                if (message.contains("使用 ChatGPT 订阅")) refresh(source);
            });
            source.sendSuccess(() -> Component.literal("Continue with ChatGPT（5 分钟内完成）：").append(
                    Component.literal("打开授权页面").withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)))), false);
            // Console needs the full URL; it contains no bearer, refresh, or ID token.
            if (source.getEntity() == null) source.sendSuccess(() -> Component.literal(url), false);
            source.sendSuccess(() -> Component.literal("远程服务器：先在本机运行 ssh -N -L " + port
                    + ":127.0.0.1:" + port + " 用户@服务器，再用本机浏览器授权。"), false);
            return 1;
        } catch (Exception e) { reply(source, "无法启动授权：" + ChatGPTSession.safeError(e)); return 0; }
    }

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        var site = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
        if (!(site instanceof ChatGPTLLMSite)) {
            site = new ChatGPTLLMSite.Serializer().defaultSite();
            AvailableSites.LLM_SITES.put(ChatGPTLLMSite.API_TYPE, site);
        }
        site.setEnabled(enabled);
        AvailableSites.saveSites();
        reply(ctx.getSource(), enabled ? "已启用 ChatGPT 订阅，使用服主授权的额度" : "已禁用 ChatGPT 订阅");
        sync(ctx.getSource());
        return 1;
    }

    private static void refresh(CommandSourceStack source) {
        reply(source, "正在读取并验证 ChatGPT 账号可用模型…");
        ChatGPTSession.models().whenComplete((catalog, error) -> source.getServer().execute(() -> {
            if (error != null) { reply(source, "模型列表读取失败：" + ChatGPTSession.safeError(error)); return; }
            var old = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
            AvailableSites.LLM_SITES.put(ChatGPTLLMSite.API_TYPE, old instanceof ChatGPTLLMSite subscription ? subscription.withModels(catalog)
                    : new ChatGPTLLMSite(ChatGPTLLMSite.API_TYPE, old != null && old.enabled(), catalog));
            ChatGPTSession.applyModelImageCapabilities();
            AvailableSites.saveSites();
            reply(source, ChatGPTSession.modelSummary() + "；/aifun chatgpt enable 启用");
            sync(source);
        }));
    }

    private static void sync(CommandSourceStack source) {
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            NetworkHandler.sendToClientPlayer(new SyncAISitesMessage(AvailableSites.LLM_SITES, AvailableSites.TTS_SITES, false), player);
        }
    }

    @FunctionalInterface private interface Work { String run() throws Exception; }
    private static int background(CommandContext<CommandSourceStack> ctx, Work work) {
        CommandSourceStack source = ctx.getSource();
        CompletableFuture.runAsync(() -> {
            try { reply(source, work.run()); }
            catch (Exception e) { reply(source, "ChatGPT：" + ChatGPTSession.safeError(e)); }
        });
        return 1;
    }

    private static void reply(CommandSourceStack source, String text) {
        source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(text), false));
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        ChatGPTSession.shutdown();
    }
}
