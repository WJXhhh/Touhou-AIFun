package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.network.message.AIFunChatGPTActionMessage;
import com.wjx.touhou_aifun.network.message.AIFunChatGPTStateMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Server-side GUI controller. All OAuth/file/network work runs outside the Minecraft server thread. */
public final class ChatGPTSettingsService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AIFun-ChatGPT-Settings"); thread.setDaemon(true); return thread;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Map<java.util.UUID, Long> POLLED = new ConcurrentHashMap<>();
    private static volatile String operationStatus = "";

    private ChatGPTSettingsService() { }

    public static boolean canManage(ServerPlayer player) {
        var server = player.getServer();
        return server != null && (player.hasPermissions(4)
                || (server.isSingleplayer() && server.isSingleplayerOwner(player.getGameProfile())));
    }

    public static void handle(ServerPlayer player, AIFunChatGPTActionMessage action) {
        if (!canManage(player)) {
            AIFunNetwork.sendChatGPTState(player, new AIFunChatGPTStateMessage(action.screenId(), "{}",
                    "只有服主或权限等级 4 的管理员可以管理订阅账号", "", false, false, false));
            return;
        }
        if (action.action() == AIFunChatGPTActionMessage.Action.STATUS) {
            long now = System.currentTimeMillis();
            Long previous = POLLED.put(player.getUUID(), now);
            if (previous == null || now - previous >= 900) WORKER.execute(() -> publish(player, action, "", false));
            return;
        }
        if (!BUSY.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            String url = "";
            boolean saved = false;
            try {
                switch (action.action()) {
                    case LOGIN, NEW_ACCOUNT -> {
                        if (action.port() < 1024 || action.port() > 65535) throw new IllegalStateException("回调端口必须在 1024–65535 之间");
                        url = ChatGPTSession.login(action.port(), action.action() == AIFunChatGPTActionMessage.Action.NEW_ACCOUNT,
                                message -> WORKER.execute(() -> {
                                    operationStatus = message;
                                    try {
                                        if (message.startsWith("已连接") && ChatGPTSession.uiMetadata().get("sharing").getAsBoolean()) {
                                            BUSY.set(true);
                                            refreshModels(player);
                                            operationStatus = "登录成功；" + ChatGPTSession.modelSummary() + "；启用服务后点击保存";
                                        }
                                    } catch (Exception e) { operationStatus = "模型读取失败：" + ChatGPTSession.safeError(e); }
                                    finally { BUSY.set(false); publish(player, action, "", false); }
                                }));
                        operationStatus = "等待浏览器授权，完成后本页会自动更新";
                    }
                    case CANCEL -> { ChatGPTSession.cancelLogin(); operationStatus = "已取消授权"; }
                    case MODELS -> { operationStatus = "正在读取并验证模型…"; refreshModels(player); operationStatus = ChatGPTSession.modelSummary(); }
                    case SAVE -> {
                        if (!ChatGPTReasoningSettings.EFFORTS.contains(action.reasoningEffort()))
                            throw new IllegalStateException("无效的推理强度");
                        player.getServer().submit(() -> {
                            var site = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
                            if (site == null) {
                                site = new ChatGPTLLMSite.Serializer().defaultSite();
                                AvailableSites.LLM_SITES.put(ChatGPTLLMSite.API_TYPE, site);
                            }
                            site.setEnabled(action.enabled());
                            if (site instanceof ChatGPTLLMSite subscription) {
                                subscription.setReasoningSettings(new ChatGPTReasoningSettings(action.reasoningSummary(), action.reasoningEffort()));
                                subscription.setWebSearch(action.webSearch());
                            }
                            AvailableSites.saveSites();
                        }).join();
                        saved = true; operationStatus = "订阅设置已保存";
                    }
                    case LOGOUT -> { operationStatus = ChatGPTSession.logout(); clearModels(player); }
                    case SELECT -> {
                        ChatGPTSession.select(action.clientId()); clearModels(player);
                        if (ChatGPTSession.uiMetadata().get("sharing").getAsBoolean()) refreshModels(player);
                        operationStatus = "已切换账号；" + ChatGPTSession.modelSummary();
                    }
                    case RELOAD -> { ChatGPTSession.reload(); operationStatus = "已重新读取服务器凭据"; }
                    default -> { }
                }
            } catch (Exception e) { operationStatus = ChatGPTSession.safeError(e); }
            finally { BUSY.set(false); publish(player, action, url, saved); }
        });
    }

    private static void refreshModels(ServerPlayer player) throws Exception {
        Map<String, String> catalog = ChatGPTSession.models().get();
        player.getServer().submit(() -> {
            var old = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
            AvailableSites.LLM_SITES.put(ChatGPTLLMSite.API_TYPE, old instanceof ChatGPTLLMSite subscription ? subscription.withModels(catalog)
                    : new ChatGPTLLMSite(ChatGPTLLMSite.API_TYPE, old != null && old.enabled(), catalog));
            AvailableSites.saveSites();
        }).join();
    }

    private static void clearModels(ServerPlayer player) {
        player.getServer().submit(() -> {
            var old = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
            AvailableSites.LLM_SITES.put(ChatGPTLLMSite.API_TYPE, old instanceof ChatGPTLLMSite subscription ? subscription.withModels(Map.of())
                    : new ChatGPTLLMSite(ChatGPTLLMSite.API_TYPE, old != null && old.enabled(), Map.of()));
            AvailableSites.saveSites();
        }).join();
    }

    private static void publish(ServerPlayer player, AIFunChatGPTActionMessage action, String url, boolean saved) {
        JsonObject metadata;
        try { metadata = ChatGPTSession.uiMetadata(); }
        catch (Exception e) { metadata = new JsonObject(); operationStatus = ChatGPTSession.safeError(e); }
        JsonObject result = metadata;
        String status = operationStatus;
        player.getServer().execute(() -> {
            if (player.isRemoved() || !canManage(player)) return;
            var site = AvailableSites.LLM_SITES.get(ChatGPTLLMSite.API_TYPE);
            result.addProperty("enabled", site != null && site.enabled());
            ChatGPTReasoningSettings settings = site instanceof ChatGPTLLMSite subscription
                    ? subscription.reasoningSettings() : ChatGPTReasoningSettings.DEFAULT;
            result.addProperty("reasoning_summary", settings.summary()); result.addProperty("reasoning_effort", settings.effort());
            result.addProperty("web_search", !(site instanceof ChatGPTLLMSite subscription) || subscription.webSearch());
            JsonObject models = new JsonObject();
            if (site instanceof ChatGPTLLMSite subscription) subscription.models().entrySet().stream().limit(256)
                    .forEach(entry -> models.addProperty(entry.getKey(), entry.getValue()));
            result.add("models", models);
            AIFunNetwork.sendChatGPTState(player, new AIFunChatGPTStateMessage(action.screenId(), result.toString(),
                    status, url, BUSY.get(), true, saved));
        });
    }
}
